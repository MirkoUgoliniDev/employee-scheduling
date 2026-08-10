#!/usr/bin/env python3
"""Employee Scheduling installation wizard for Linux.

    sudo python3 setup/wizard.py --web --jar path/to/package.jar
    sudo python3 setup/wizard.py --tui --jar path/to/package.jar
    sudo python3 setup/wizard.py --dry-run --jar ...     (changes nothing)

It uses only Python's standard library: on a freshly prepared Raspberry Pi,
nothing needs to be installed first — precisely when doing so is most awkward.
"""

import argparse
import logging
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from lib.constants import (DATA_DIR, DEFAULT_ENGINE, DEFAULT_PORT, ENV_FILE,
                           LOCK_FILE, LOG_FILE, SERVICE_NAME, SERVICE_USER,
                           WEB_PORT, WIZARD_VERSION)
from lib.lock import SetupLock
from lib.runner import Runner, get_abort_event
from lib.step_base import STEP_ICONS, Status
from lib.sysinfo import SystemInfo
from steps import build_steps


# ── Logging ──────────────────────────────────────────────────────────────────
def setup_file_logging() -> None:
    """File log: when something goes wrong, the terminal may already be closed."""
    try:
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        logging.basicConfig(filename=str(LOG_FILE), level=logging.DEBUG,
                            format="%(asctime)s %(levelname)s %(message)s")
        logging.info("=== Employee Scheduling wizard v%s started ===", WIZARD_VERSION)
    except OSError:
        pass  # proceed without a log: this is not a reason to block installation


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        prog="wizard.py",
        description="Install Employee Scheduling as a service on Linux.")
    parser.add_argument("--web", action="store_true",
                        help=f"browser interface on port {WEB_PORT}")
    parser.add_argument("--tui", action="store_true",
                        help="text interface (default when --web is absent)")
    parser.add_argument("--dry-run", action="store_true",
                        help="show what it would do without changing anything")
    parser.add_argument("--jar", default="", help="package to install")
    # Default to None rather than the constant value to distinguish "not given"
    # from "explicitly set to the default." During an update, omitted values
    # must be recovered from the existing installation rather than reset to
    # factory defaults — see resolve_existing().
    parser.add_argument("--engine", choices=("postgresql", "sqlite"), default=None,
                        help=f"data engine (default: {DEFAULT_ENGINE})")
    parser.add_argument("--port", type=int, default=None,
                        help=f"application port (default: {DEFAULT_PORT})")
    parser.add_argument("--data-dir", default=None,
                        help=f"data, backups, and database (default: {DATA_DIR})")
    parser.add_argument("--web-port", type=int, default=WEB_PORT)
    parser.add_argument("--web-host", default="127.0.0.1",
                        help="wizard address (default: localhost only)")
    parser.add_argument("--smtp-host", default="")
    parser.add_argument("--smtp-port", type=int, default=587)
    parser.add_argument("--smtp-user", default="")
    parser.add_argument("--smtp-pass", default="")
    parser.add_argument("--smtp-from", default="")
    parser.add_argument("--demo-data", action="store_const", const=True, default=None,
                        help="load the sample dataset on first startup")
    parser.add_argument("--yes", "-y", action="store_true",
                        help="do not ask for confirmation (for automation)")
    return parser.parse_args(argv)


def resolve_existing() -> dict:
    """Engine, port, and data directory of an existing installation.

    Rerunning the wizard to update is the documented use case, and users almost
    always provide only the new package. Without this read, the engine and data
    directory would return to factory defaults. An installation using SQLite or
    a custom data directory would appear empty, with the real data still on disk
    but no longer connected.
    """
    found = {}
    try:
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition("=")
            value = value.strip().strip('"')
            if key == "QUARKUS_PROFILE" and value in ("postgresql", "sqlite"):
                found["engine"] = value
            elif key == "APP_DATA_DIR" and value:
                found["data_dir"] = value
            elif key == "QUARKUS_HTTP_PORT" and value.isdigit():
                found["port"] = int(value)
            elif key == "APP_DEMO_DATA":
                found["demo_data"] = value.lower() in ("true", "yes", "1")
    except OSError:
        pass  # no previous installation: use defaults
    return found


def config_from_args(args) -> dict:
    existing = resolve_existing()
    engine = args.engine or existing.get("engine") or DEFAULT_ENGINE
    port = args.port or existing.get("port") or DEFAULT_PORT
    data_dir = args.data_dir or existing.get("data_dir") or str(DATA_DIR)
    return {
        "engine": engine,
        "port": port,
        "data_dir": data_dir,
        "reused_settings": bool(existing) and not (args.engine or args.port or args.data_dir),
        "jar": args.jar,
        "service_user": SERVICE_USER,
        "smtp_host": args.smtp_host,
        "smtp_port": args.smtp_port,
        "smtp_user": args.smtp_user,
        "smtp_pass": args.smtp_pass,
        "smtp_from": args.smtp_from,
        "demo_data": (args.demo_data if args.demo_data is not None
                      else existing.get("demo_data", False)),
    }


# ── Step execution ───────────────────────────────────────────────────────────
def run_steps(steps, runner, sysinfo, config, on_status=None) -> bool:
    """Run steps in order and stop at the first failure.

    Stopping is intentional: steps depend on one another (configuration makes no
    sense without a database, and the systemd unit makes no sense without a
    package). Continuing would leave the machine worse than before, with half an
    installation on disk.
    """
    abort = get_abort_event()
    abort.clear()

    for step in steps:
        if on_status:
            step.set_callback(on_status)
        if abort.is_set():
            step.skip("Interrupted by the user")
            continue

        runner.log("")
        runner.log(f"── {step.name} — {step.description}")
        try:
            ok = step.execute(runner, sysinfo, config)
        except Exception as exc:  # noqa: BLE001
            # An unexpected exception must not appear as a Python traceback: turn
            # it into a failed step, with details in the log.
            logging.exception("Exception in step %s", step.name)
            ok = step.fail(f"Unexpected error: {exc}",
                           f"Full details in {LOG_FILE}")

        if step.status == Status.SKIPPED:
            runner.log(f"   {STEP_ICONS[Status.SKIPPED]} skipped: {step.message}")
        elif ok:
            runner.log(f"   {STEP_ICONS[Status.DONE]} {step.message or 'done'}")
        else:
            runner.log(f"   {STEP_ICONS[Status.FAILED]} {step.message}")
            return False
    return True


def print_summary(config, sysinfo, dry_run: bool) -> None:
    ip = sysinfo.primary_ip() or "localhost"
    print("")
    print("=" * 54)
    print("  Installation completed" if not dry_run else "  Simulation finished (nothing was changed)")
    print("=" * 54)
    print(f"  Data engine     : {config.get('engine')}")
    print(f"  Data and backups: {config.get('data_dir')}")
    print(f"  Address         : http://{ip}:{config.get('port')}")
    print("")
    print(f"  Log             : journalctl -u {SERVICE_NAME} -f")
    print(f"  Status          : systemctl status {SERVICE_NAME}")
    # Absolute path to what was installed on the machine, not a repository-
    # relative path: the repository may be gone when this is needed.
    print(f"  Uninstall       : sudo {config.get('uninstaller', './scripts/uninstall-linux.sh')}")
    print("")
    if not dry_run:
        print("  The first account that registers becomes the administrator.")
        print("")
        print("  Traffic is plain HTTP, not encrypted: fine on a trusted local")
        print("  network. To expose it outside, put it behind a reverse proxy")
        print("  with a certificate.")
    print("")


# ── Text mode ────────────────────────────────────────────────────────────────
def run_tui(steps, runner, sysinfo, config, assume_yes: bool) -> int:
    print("")
    print("=" * 54)
    print(f"  Employee Scheduling — installation wizard v{WIZARD_VERSION}")
    print("=" * 54)
    for key, value in sysinfo.summary().items():
        print(f"  {key:14}: {value}")
    print("")
    print(f"  Data engine   : {config['engine']}")
    print(f"  Port          : {config['port']}")
    print(f"  Data directory: {config['data_dir']}")
    print(f"  Package       : {config['jar'] or '(not specified)'}")
    if config.get("reused_settings"):
        print("")
        print("  An existing installation was found: engine, port, and data directory")
        print("  were reused from it. To change them, specify them explicitly.")
    if runner.dry_run:
        print("")
        print("  SIMULATION: no change will be applied.")
    print("")

    if not assume_yes and not runner.dry_run:
        try:
            answer = input("  Proceed? [y/N]: ").strip().lower()
        except EOFError:
            # No interactive terminal (scripted execution): stop instead of
            # installing without anyone explicitly asking for it.
            print("  No answer is possible: use --yes to proceed without confirmation.")
            return 1
        # The prompt says [y/N], but the Italian synonyms are still accepted: the
        # operators installing this typed "s" until today, and a refusal here is
        # silent — it prints "Cancelled" and returns 0, so a wrapper script would
        # see a successful run that installed nothing.
        if answer not in ("y", "yes", "s", "si", "sì"):
            print("  Cancelled.")
            return 0

    ok = run_steps(steps, runner, sysinfo, config)
    if ok:
        print_summary(config, sysinfo, runner.dry_run)
        return 0
    print("")
    print("  Installation stopped. Fix the problem and run the wizard again:")
    print("  steps already completed detect themselves and are not repeated.")
    return 1


def main(argv=None) -> int:
    args = parse_args(argv)
    setup_file_logging()

    sysinfo = SystemInfo()
    runner = Runner(dry_run=args.dry_run)
    config = config_from_args(args)

    if os.name != "posix":
        print("This wizard installs a Linux service. On Windows use install-windows.ps1.")
        return 1

    # Command-line simulation changes nothing, so it needs no lock: users should
    # be able to inspect what it would do even during a real installation. Web
    # mode is different because it can still start a real installation; without
    # a lock, two open wizards would work on the same machine.
    # Check privileges BEFORE the lock. Without root, os.open on the lock file
    # raises PermissionError — an OSError that acquire() turns into "not
    # acquired." Users were then told another installation was running, given a
    # nonexistent PID, and asked to delete a nonexistent file. The actual problem
    # was only a forgotten sudo.
    if not sysinfo.is_root:
        print("Root privileges are required.")
        print(f"  Run it again with: sudo python3 {' '.join(sys.argv)}")
        return 1

    needs_lock = args.web or not args.dry_run
    lock = SetupLock(LOCK_FILE)
    if needs_lock and not lock.acquire():
        pid = lock.owner_pid()
        print(f"Another installation is already in progress (process {pid}).")
        print(f"If you are sure it is not, remove {LOCK_FILE} and try again.")
        return 1

    try:
        steps = build_steps()
        if args.web:
            from webui import run_webui
            return run_webui(steps, runner, sysinfo, config, port=args.web_port,
                             host=args.web_host)
        return run_tui(steps, runner, sysinfo, config, assume_yes=args.yes)
    except KeyboardInterrupt:
        get_abort_event().set()
        print("\n  Interrupted.")
        return 130
    finally:
        lock.release()


if __name__ == "__main__":
    sys.exit(main())
