"""Run system commands with simulation and logging.

Everything that touches the machine passes through here. Centralizing it serves
two concrete purposes: simulation mode truly works (no step can forget to honor
it because no step executes anything directly), and every command and its
result are written to the log.
"""

import logging
import os
import shutil
import subprocess
import tempfile
import threading
from pathlib import Path
from typing import Callable, Optional, Sequence, Tuple

_abort_event = threading.Event()


def get_abort_event() -> threading.Event:
    """Shared event: when set, subsequent steps do not start."""
    return _abort_event


class Runner:
    def __init__(self, dry_run: bool = False):
        self.dry_run = dry_run
        self._log: Optional[Callable[[str], None]] = None

    def set_log_callback(self, cb: Callable[[str], None]) -> None:
        self._log = cb

    def log(self, line: str) -> None:
        # Every line also goes to the file log: INSTALL.md directs diagnostics
        # there, and previously the file contained only two lines.
        logging.info(line)
        if self._log:
            self._log(line)
        else:
            print(line)

    @staticmethod
    def _env() -> dict:
        """Non-interactive environment for child commands.

        Without these values, apt can open a debconf dialog or, on Ubuntu, the
        needrestart prompt asking which services to restart. Neither is visible
        because output is captured, and the command stalls until timeout.
        """
        env = os.environ.copy()
        env.setdefault("DEBIAN_FRONTEND", "noninteractive")
        env.setdefault("NEEDRESTART_MODE", "a")
        return env

    # ── Commands ─────────────────────────────────────────────────────────────
    def run(self, cmd: Sequence[str], user: Optional[str] = None,
            timeout: int = 1800, check_output: bool = False,
            stdin_text: Optional[str] = None, secret: bool = False) -> Tuple[bool, str]:
        """Run a command. Return (succeeded, output-or-error).

        The caller is assumed to be root already; the wizard verifies this in
        the first step. ``user`` drops privileges rather than elevating them —
        typically to communicate with PostgreSQL as the ``postgres`` user.
        """
        argv = list(cmd)
        if user:
            # runuser is part of util-linux and is always present; sudo may be
            # absent from a minimal image.
            if shutil.which("runuser"):
                argv = ["runuser", "-u", user, "--"] + argv
            elif shutil.which("sudo"):
                argv = ["sudo", "-n", "-u", user] + argv
            else:
                return False, "runuser or sudo is required to switch user."

        # Commands marked sensitive do not show arguments: this line reaches the
        # terminal, scrollback, and the browser of anyone following installation.
        printable = " ".join(argv) if not secret else f"{argv[0]} (arguments hidden)"
        if self.dry_run:
            self.log(f"    [simulation] {printable}")
            return True, ""

        self.log(f"    $ {printable}")
        try:
            result = subprocess.run(
                argv, capture_output=True, text=True, timeout=timeout,
                input=stdin_text,
                # Close stdin when nothing is supplied. Otherwise a command that
                # asks for confirmation (for example an apt debconf prompt) hangs
                # until timeout while the user sees only a frozen progress bar.
                stdin=None if stdin_text is not None else subprocess.DEVNULL,
                env=self._env())
        except FileNotFoundError:
            return False, f"Command not found: {argv[0]}"
        except subprocess.TimeoutExpired:
            return False, f"Timed out ({timeout}s): {printable}"

        out = (result.stdout or "").strip()
        err = (result.stderr or "").strip()
        if result.returncode != 0:
            # The actual error is almost always on stderr; if empty, fall back to
            # stdout because some tools (including psql) write there.
            detail = err or out or f"exit code {result.returncode}"
            if secret:
                # Hiding only arguments is not enough: when a statement fails,
                # PostgreSQL REPEATS it in the error, including the password. That
                # message is shown to the user and sent to installation browsers.
                return False, "command failed (details omitted: they contain a secret)"
            return False, detail

        if out and not check_output and not secret:
            for line in out.splitlines()[:10]:
                self.log(f"      {line}")
        return True, out

    def shell(self, command: str, timeout: int = 1800) -> Tuple[bool, str]:
        """Like :meth:`run`, but for a shell line (pipes and redirections)."""
        if self.dry_run:
            self.log(f"    [simulation] {command}")
            return True, ""
        self.log(f"    $ {command}")
        try:
            result = subprocess.run(["bash", "-c", command], capture_output=True,
                                    text=True, timeout=timeout)
        except subprocess.TimeoutExpired:
            return False, f"Timed out ({timeout}s)"
        if result.returncode != 0:
            return False, (result.stderr or result.stdout or "").strip()
        return True, (result.stdout or "").strip()

    # ── Files ────────────────────────────────────────────────────────────────
    def write(self, path: Path, content: str, mode: int = 0o644,
              owner: Optional[str] = None) -> Tuple[bool, str]:
        """Write a file atomically.

        Write to a temporary file in the same directory, then rename it. If the
        machine shuts down midway, the destination remains unchanged instead of
        becoming an unreadable fragment. Set permissions BEFORE moving it, or a
        credentials file is briefly readable by everyone.
        """
        if self.dry_run:
            self.log(f"    [simulation] write {path} ({len(content)} bytes, {oct(mode)})")
            return True, ""
        try:
            path.parent.mkdir(parents=True, exist_ok=True)
            fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".tmp-setup-")
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    handle.write(content)
                os.chmod(tmp, mode)
                if owner:
                    shutil.chown(tmp, user=owner[0], group=owner[1])
                os.replace(tmp, str(path))
            except BaseException:
                if os.path.exists(tmp):
                    os.unlink(tmp)
                raise
        except Exception as exc:  # noqa: BLE001 - the message is shown to the user
            return False, f"Failed to write {path}: {exc}"
        self.log(f"    wrote {path} ({oct(mode)})")
        return True, ""

    def copy(self, src: Path, dst: Path, mode: int = 0o644) -> Tuple[bool, str]:
        """Atomic copy with size verification.

        An 80 MB JAR takes tens of seconds on an SD card. Copying directly to the
        destination means Ctrl-C or a dropped SSH session would leave a TRUNCATED
        file in /opt. The systemd unit is already installed and enabled, so after
        reboot the service would die with "Invalid or corrupt jarfile" and loop
        forever with Restart=on-failure. Write to a temporary file in the same
        directory and rename it only after a complete, verified copy.
        """
        if self.dry_run:
            self.log(f"    [simulation] copy {src} → {dst}")
            return True, ""
        staging = dst.with_name(dst.name + ".tmp-setup")
        try:
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(str(src), str(staging))
            # fsync before rename: otherwise the rename may reach disk before
            # the data, and an abrupt shutdown would leave a correctly named but
            # incomplete file. Open read-write because fsync needs a writable handle.
            with open(staging, "rb+") as handle:
                os.fsync(handle.fileno())
            expected = src.stat().st_size
            actual = staging.stat().st_size
            if actual != expected:
                staging.unlink()
                return False, (f"Incomplete copy: {actual} bytes instead of {expected}. "
                               "Out of disk space?")
            os.chmod(str(staging), mode)
            os.replace(str(staging), str(dst))
        except Exception as exc:  # noqa: BLE001
            if staging.exists():
                try:
                    staging.unlink()
                except OSError:
                    pass
            return False, f"Failed to copy {src}: {exc}"
        self.log(f"    copied {src.name} → {dst}")
        return True, ""

    def mkdir(self, path: Path, mode: int = 0o755,
              owner: Optional[Tuple[str, str]] = None) -> Tuple[bool, str]:
        if self.dry_run:
            self.log(f"    [simulation] directory {path} ({oct(mode)})")
            return True, ""
        try:
            path.mkdir(parents=True, exist_ok=True)
            os.chmod(str(path), mode)
            if owner:
                shutil.chown(str(path), user=owner[0], group=owner[1])
        except Exception as exc:  # noqa: BLE001
            return False, f"Failed to create {path}: {exc}"
        return True, ""
