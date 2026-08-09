"""One installation at a time.

Two wizards running together on the same machine overwrite each other's
configuration and may leave the service in a state matching neither run. This
is not theoretical: simply start web mode and forget about it, then open an SSH
session and run the wizard again.
"""

import os
from pathlib import Path
from typing import Optional


class SetupLock:
    """PID-based file lock with stale-lock recovery."""

    def __init__(self, path: Path):
        self.path = path
        self._acquired = False

    def acquire(self) -> bool:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            # O_EXCL: creation fails if the file already exists, and checking
            # and creation happen in one kernel operation. With "check whether
            # it exists, then create," two simultaneous wizards would both pass.
            fd = os.open(str(self.path), os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
        except FileExistsError:
            if self._stale():
                # The process that held the lock is gone (abrupt shutdown or a
                # dropped SSH session): the file remains but protects nothing.
                # Without recovery, installation would stay blocked forever.
                try:
                    self.path.unlink()
                except OSError:
                    return False
                return self.acquire()
            return False
        except OSError:
            return False

        with os.fdopen(fd, "w") as handle:
            handle.write(str(os.getpid()))
        self._acquired = True
        return True

    def owner_pid(self) -> Optional[int]:
        try:
            return int(self.path.read_text().strip())
        except (OSError, ValueError):
            return None

    def _stale(self) -> bool:
        pid = self.owner_pid()
        if pid is None:
            return True
        try:
            os.kill(pid, 0)  # no signal is sent: this only checks existence
        except ProcessLookupError:
            return True
        except PermissionError:
            return False  # it exists and belongs to another user
        return False

    def release(self) -> None:
        if not self._acquired:
            return
        try:
            self.path.unlink()
        except OSError:
            pass
        self._acquired = False

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.release()
