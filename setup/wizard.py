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
        logging.info("=== Wizard Employee Scheduling v%s avviato ===", WIZARD_VERSION)
    except OSError:
        pass  # proceed without a log: this is not a reason to block installation


def parse_args(argv=None):
    parser = argparse.ArgumentParser(
        prog="wizard.py",
        description="Installa Employee Scheduling come servizio su Linux.")
    parser.add_argument("--web", action="store_true",
                        help=f"interfaccia da browser sulla porta {WEB_PORT}")
    parser.add_argument("--tui", action="store_true",
                        help="interfaccia testuale (predefinita senza --web)")
    parser.add_argument("--dry-run", action="store_true",
                        help="mostra cosa farebbe senza modificare nulla")
    parser.add_argument("--jar", default="", help="pacchetto da installare")
    # Default to None rather than the constant value to distinguish "not given"
    # from "explicitly set to the default." During an update, omitted values
    # must be recovered from the existing installation rather than reset to
    # factory defaults — see resolve_existing().
    parser.add_argument("--engine", choices=("postgresql", "sqlite"), default=None,
                        help=f"motore dati (predefinito: {DEFAULT_ENGINE})")
    parser.add_argument("--port", type=int, default=None,
                        help=f"porta dell'applicazione (predefinito: {DEFAULT_PORT})")
    parser.add_argument("--data-dir", default=None,
                        help=f"dati, backup e database (predefinito: {DATA_DIR})")
    parser.add_argument("--web-port", type=int, default=WEB_PORT)
    parser.add_argument("--smtp-host", default="")
    parser.add_argument("--smtp-port", type=int, default=587)
    parser.add_argument("--smtp-user", default="")
    parser.add_argument("--smtp-pass", default="")
    parser.add_argument("--smtp-from", default="")
    parser.add_argument("--yes", "-y", action="store_true",
                        help="non chiedere conferma (per l'automazione)")
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
            step.skip("Interrotto dall'utente")
            continue

        runner.log("")
        runner.log(f"── {step.name} — {step.description}")
        try:
            ok = step.execute(runner, sysinfo, config)
        except Exception as exc:  # noqa: BLE001
            # An unexpected exception must not appear as a Python traceback: turn
            # it into a failed step, with details in the log.
            logging.exception("Eccezione nel passo %s", step.name)
            ok = step.fail(f"Errore imprevisto: {exc}",
                           f"Dettagli completi in {LOG_FILE}")

        if step.status == Status.SKIPPED:
            runner.log(f"   {STEP_ICONS[Status.SKIPPED]} saltato: {step.message}")
        elif ok:
            runner.log(f"   {STEP_ICONS[Status.DONE]} {step.message or 'fatto'}")
        else:
            runner.log(f"   {STEP_ICONS[Status.FAILED]} {step.message}")
            return False
    return True


def print_summary(config, sysinfo, dry_run: bool) -> None:
    ip = sysinfo.primary_ip() or "localhost"
    print("")
    print("=" * 54)
    print("  Installazione completata" if not dry_run else "  Simulazione conclusa (nulla e' stato modificato)")
    print("=" * 54)
    print(f"  Motore dati   : {config.get('engine')}")
    print(f"  Dati e backup : {config.get('data_dir')}")
    print(f"  Indirizzo     : http://{ip}:{config.get('port')}")
    print("")
    print(f"  Registro      : journalctl -u {SERVICE_NAME} -f")
    print(f"  Stato         : systemctl status {SERVICE_NAME}")
    # Absolute path to what was installed on the machine, not a repository-
    # relative path: the repository may be gone when this is needed.
    print(f"  Disinstalla   : sudo {config.get('uninstaller', './scripts/uninstall-linux.sh')}")
    print("")
    if not dry_run:
        print("  Il primo account che si registra diventa amministratore.")
        print("")
        print("  Il traffico e' in HTTP, non cifrato: va bene su una rete locale")
        print("  fidata. Per esporlo fuori, mettilo dietro un reverse proxy con")
        print("  certificato.")
    print("")


# ── Text mode ────────────────────────────────────────────────────────────────
def run_tui(steps, runner, sysinfo, config, assume_yes: bool) -> int:
    print("")
    print("=" * 54)
    print(f"  Employee Scheduling — wizard di installazione v{WIZARD_VERSION}")
    print("=" * 54)
    for key, value in sysinfo.summary().items():
        print(f"  {key:14}: {value}")
    print("")
    print(f"  Motore dati   : {config['engine']}")
    print(f"  Porta         : {config['port']}")
    print(f"  Cartella dati : {config['data_dir']}")
    print(f"  Pacchetto     : {config['jar'] or '(non indicato)'}")
    if config.get("reused_settings"):
        print("")
        print("  Trovata un'installazione esistente: motore, porta e cartella dati")
        print("  sono stati ripresi da quella. Per cambiarli indicali esplicitamente.")
    if runner.dry_run:
        print("")
        print("  SIMULAZIONE: nessuna modifica verra' applicata.")
    print("")

    if not assume_yes and not runner.dry_run:
        try:
            answer = input("  Procedere? [s/N]: ").strip().lower()
        except EOFError:
            # No interactive terminal (scripted execution): stop instead of
            # installing without anyone explicitly asking for it.
            print("  Nessuna risposta possibile: usa --yes per procedere senza conferma.")
            return 1
        if answer not in ("s", "si", "sì", "y", "yes"):
            print("  Annullato.")
            return 0

    ok = run_steps(steps, runner, sysinfo, config)
    if ok:
        print_summary(config, sysinfo, runner.dry_run)
        return 0
    print("")
    print("  Installazione interrotta. Correggi il problema e rilancia il wizard:")
    print("  i passi gia' completati si riconoscono da soli e non vengono rifatti.")
    return 1


def main(argv=None) -> int:
    args = parse_args(argv)
    setup_file_logging()

    sysinfo = SystemInfo()
    runner = Runner(dry_run=args.dry_run)
    config = config_from_args(args)

    if os.name != "posix":
        print("Questo wizard installa un servizio Linux. Su Windows usa install-windows.ps1.")
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
        print("Servono i privilegi di root.")
        print(f"  Rilancialo con: sudo python3 {' '.join(sys.argv)}")
        return 1

    needs_lock = args.web or not args.dry_run
    lock = SetupLock(LOCK_FILE)
    if needs_lock and not lock.acquire():
        pid = lock.owner_pid()
        print(f"Un'altra installazione e' gia' in corso (processo {pid}).")
        print(f"Se sei sicuro che non lo sia, rimuovi {LOCK_FILE} e riprova.")
        return 1

    try:
        steps = build_steps()
        if args.web:
            from webui import run_webui
            return run_webui(steps, runner, sysinfo, config, port=args.web_port)
        return run_tui(steps, runner, sysinfo, config, assume_yes=args.yes)
    except KeyboardInterrupt:
        get_abort_event().set()
        print("\n  Interrotto.")
        return 130
    finally:
        lock.release()


if __name__ == "__main__":
    sys.exit(main())
