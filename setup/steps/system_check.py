"""Preflight checks: stop before changing anything, not halfway through."""

import os
import shutil
from pathlib import Path

from lib.constants import DEFAULT_PORT, SERVICE_NAME, SYSTEM_DIRS
from lib.step_base import Step
from steps.install_app import NO_PROFILE, baked_engine


def _service_is_active() -> bool:
    """Is our service running? Read from the system, not simulated."""
    try:
        import subprocess
        return subprocess.run(["systemctl", "is-active", "--quiet", SERVICE_NAME],
                              capture_output=True, timeout=15).returncode == 0
    except Exception:  # noqa: BLE001 - systemctl is absent or unresponsive
        return False


class SystemCheckStep(Step):
    def __init__(self):
        super().__init__("Controllo del sistema",
                         "Privilegi, sistema operativo, spazio e porta libera")

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()

        if not sysinfo.is_root:
            return self.fail("Il wizard non ha i privilegi di root.",
                             "Rilancialo con: sudo python3 setup/wizard.py")

        if not sysinfo.package_manager:
            return self.fail("Nessun gestore pacchetti riconosciuto (servono apt o dnf).",
                             "Installa a mano Java 17+ e PostgreSQL, poi usa install-linux.sh --no-service.")

        if not sysinfo.has_systemd:
            return self.fail("systemd non e' disponibile su questo sistema.",
                             "Il servizio non puo' essere registrato: serve un avvio manuale.")

        # Below 400 MB there is not comfortably enough room for the roughly
        # 80 MB JAR, downloaded packages, and the first backup.
        if sysinfo.disk_free_mb and sysinfo.disk_free_mb < 400:
            return self.fail(f"Spazio libero insufficiente: {sysinfo.disk_free_mb} MB.",
                             "Servono almeno 400 MB su /.")

        if sysinfo.arch in ("armv6l", "armv7l"):
            runner.log("    [attenzione] Sistema a 32 bit: Java 17+ headless per armhf")
            runner.log("                 spesso non e' nei repository, e la JVM rende molto")
            runner.log("                 peggio. Consigliato Raspberry Pi OS a 64 bit.")

        if sysinfo.ram_mb and sysinfo.ram_mb < 900:
            runner.log(f"    [attenzione] Memoria ridotta ({sysinfo.ram_mb} MB): il solver")
            runner.log("                 su pianificazioni grandi potrebbe risultare lento.")

        # Detect an occupied port now. Later, the service would start and die at
        # once with a bind error that does not identify the responsible process.
        #
        # During an update, however, OUR service occupies the port, which is
        # normal. Blocking there would make the wizard usable only once per
        # machine, and "change port" would be bad advice. Identify the owner.
        port = int(config.get("port", DEFAULT_PORT))
        if not 1 <= port <= 65535:
            return self.fail(f"Porta fuori intervallo: {port}", "Valori ammessi: 1-65535.")
        # The service runs unprivileged and without CAP_NET_BIND_SERVICE, so bind
        # below 1024 would fail only after installation, after two minutes in the
        # verification step, with a journal error that does not name the problem.
        # install-linux.sh performs the same check.
        if port < 1024:
            return self.fail(f"La porta {port} e' riservata e il servizio non potrebbe occuparla.",
                             "Usa una porta da 1024 in su, ed eventualmente un reverse proxy davanti.")
        if sysinfo.port_in_use(port):
            # Query directly instead of using runner.run, which always succeeds
            # in simulation. Otherwise a port held by ANOTHER program would be
            # mistaken for our service and simulation would approve an unsuitable
            # system — the classic flaw of a simulation validating itself.
            ours = _service_is_active()
            if ours:
                runner.log(f"    porta {port} occupata dal servizio {SERVICE_NAME}:"
                           " e' un aggiornamento, verra' riavviato")
                config["updating"] = True
            else:
                return self.fail(f"La porta {port} e' gia' occupata da un altro programma.",
                                 "Scegline un'altra con --port, oppure libera quella.")

        # Validate the package HERE before any change. This is the most likely
        # failure (wrong path or file not copied); discovering it at step 5 after
        # reconfiguring the database would leave the machine worse than before.
        jar = config.get("jar")
        if not jar:
            return self.fail("Nessun pacchetto indicato.",
                             "Passa --jar percorso/al/employee-scheduling-runner.jar")
        jar_path = Path(jar).expanduser()
        if not jar_path.is_file():
            return self.fail(f"Pacchetto non trovato: {jar_path}",
                             "Compilalo sul PC e copialo qui con scp.")

        # Also verify the engine baked into the package HERE. The application
        # step already checked it, but only after database creation and
        # reconfiguration. Rejecting a wrong package must not leave the machine
        # halfway through installation.
        engine = config.get("engine", "postgresql")
        rebuild = ("Ricompilalo: mvn package -DskipTests "
                   f"-Dquarkus.package.jar.type=uber-jar -Dquarkus.profile={engine}")
        baked = baked_engine(jar_path)
        if baked is None:
            runner.log("    [attenzione] motore del pacchetto non verificabile:"
                       f" assicurati che sia compilato con -Dquarkus.profile={engine}")
        elif baked == NO_PROFILE:
            return self.fail(
                "Il pacchetto e' stato compilato senza -Dquarkus.profile e non funziona"
                " con nessuno dei due motori.",
                rebuild + " — con PostgreSQL il servizio non parte, con SQLite parte ma"
                          " non crea le tabelle, senza dare errori.")
        elif baked != engine:
            return self.fail(
                f"Il pacchetto e' compilato per '{baked}' ma stai installando con motore '{engine}'.",
                rebuild)
        else:
            runner.log(f"    motore del pacchetto verificato: {baked}")

        # The data directory is embedded in the systemd unit: a relative path or
        # spaces break ExecStart, while a newline would inject arbitrary
        # directives executed as root.
        data_dir = str(config.get("data_dir", ""))
        if not data_dir.startswith("/"):
            return self.fail(f"La cartella dati dev'essere un percorso assoluto: {data_dir}")
        if any(char in data_dir for char in " \t\n\r"):
            return self.fail("La cartella dati non puo' contenere spazi o a capo.",
                             f"Valore ricevuto: {data_dir!r}")
        # chown -R and chmod run on the data directory: specifying a system
        # directory is not a harmless mistake; it destroys the system. With
        # --data-dir /etc, all machine configuration would be assigned to the
        # service user with mode 750.
        if data_dir.rstrip("/") in SYSTEM_DIRS:
            return self.fail(f"'{data_dir}' e' una cartella di sistema, non una cartella dati.",
                             "Indica un percorso dedicato, per esempio /var/lib/employee-scheduling.")

        # A data directory on an unmounted external disk is the worst case because
        # it produces no error. The directory is created on the SD card beneath
        # the empty mountpoint and works for months; when someone mounts the disk,
        # data disappears behind it and Flyway creates a new, empty database.
        if data_dir.startswith(("/mnt/", "/media/")):
            mountpoint = Path(data_dir)
            while not mountpoint.exists() and mountpoint != mountpoint.parent:
                mountpoint = mountpoint.parent
            if not os.path.ismount(str(mountpoint)):
                return self.fail(
                    f"'{data_dir}' e' su un disco che non risulta montato (nessun punto di "
                    f"mount fino a {mountpoint}).",
                    "Monta il disco e rilancia, altrimenti i dati finirebbero sulla scheda "
                    "e sparirebbero dietro il mount al primo riavvio.")

        # Measure space where data actually grows: the database and backups live
        # in the data directory, which may be on another filesystem.
        target = Path(data_dir)
        while not target.exists() and target != target.parent:
            target = target.parent
        try:
            free_mb = shutil.disk_usage(str(target)).free // (1024 * 1024)
            if free_mb < 400:
                return self.fail(f"Spazio insufficiente su {target}: {free_mb} MB.",
                                 "Servono almeno 400 MB per pacchetto, database e primo backup.")
        except OSError:
            pass

        # The package name enters the unit's ExecStart just like the data
        # directory: a space would split the command into arguments and prevent
        # startup, with diagnostics that do not name the file.
        if any(char in jar_path.name for char in " \t\n\r"):
            return self.fail("Il nome del pacchetto non puo' contenere spazi o a capo.",
                             f"Rinominalo: {jar_path.name!r}")
        if jar_path.suffix != ".jar":
            return self.fail(f"Il file indicato non e' un jar: {jar_path.name}")

        for key, value in sysinfo.summary().items():
            runner.log(f"    {key}: {value}")
        return self.done("Sistema idoneo")
