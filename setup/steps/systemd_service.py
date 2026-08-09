"""systemd unit: automatic startup and service confinement."""

import shutil
from pathlib import Path

from lib.constants import (ENV_FILE, INSTALL_DIR, SERVICE_NAME, SERVICE_USER,
                           UNIT_FILE)
from lib.step_base import Step

UNIT_TEMPLATE = """\
[Unit]
Description=Employee Scheduling — pianificazione turni del personale
Documentation=https://github.com/MirkoUgoliniDev/employee-scheduling
After=network-online.target{after_db}
Wants=network-online.target{after_db}
# Do not start the service until the data directory's filesystem is mounted.
# USB disk enumeration is slow on a Raspberry Pi: without this line, the service
# would start before the mount after reboot, find an empty directory, and make
# Flyway create a new database, hiding the real one behind the mount.
RequiresMountsFor={data_dir}

[Service]
Type=simple
User={user}
Group={user}
WorkingDirectory={data_dir}
EnvironmentFile={env_file}
# app.data.dir keeps the database, backups, settings, and logs in the data
# directory. Without it, relative paths would end up beside the JAR in a
# directory the service cannot write to.
ExecStart={java} -Dapp.data.dir={data_dir} -jar {install_dir}/{jar}
Restart=on-failure
RestartSec=10
TimeoutStopSec=30

# Confinement: the service writes only to its data directory.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome={protect_home}
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
RestrictNamespaces=true
LockPersonality=true
ReadWritePaths={data_dir}

[Install]
WantedBy=multi-user.target
"""


class SystemdStep(Step):
    def __init__(self):
        super().__init__("Servizio", "Unita' systemd, avvio automatico e riavvio in caso di errore")

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()

        jar_name = config.get("jar_name")
        if not jar_name:
            return self.fail("Nome del pacchetto non noto: il passo precedente non e' riuscito.")

        java = shutil.which("java") or "/usr/bin/java"
        data_dir = str(config.get("data_dir"))

        # ProtectHome hides /home and /root from the service. If data was put
        # there, the service could not see its own directory and would fail with
        # an error that does not explain why.
        protect_home = "false" if data_dir.startswith(("/home/", "/root/")) else "true"
        if protect_home == "false":
            runner.log("    dati sotto /home: ProtectHome disattivato per non nascondere la cartella")

        after_db = " postgresql.service" if config.get("engine") == "postgresql" else ""

        unit = UNIT_TEMPLATE.format(
            after_db=after_db, user=SERVICE_USER, data_dir=data_dir,
            env_file=ENV_FILE, java=java, install_dir=INSTALL_DIR,
            jar=jar_name, protect_home=protect_home)

        ok, err = runner.write(UNIT_FILE, unit, mode=0o644)
        if not ok:
            return self.fail(err)

        ok, err = runner.run(["systemctl", "daemon-reload"])
        if not ok:
            return self.fail(f"Ricarica di systemd non riuscita: {err}")

        runner.run(["systemctl", "enable", SERVICE_NAME])

        # Use restart rather than start: if the service was already active from
        # a previous installation, it must restart with the new package.
        ok, err = runner.run(["systemctl", "restart", SERVICE_NAME])
        if not ok:
            return self.fail(f"Il servizio non si avvia: {err}",
                             f"Diagnosi: journalctl -u {SERVICE_NAME} -n 60 --no-pager")

        return self.done("Servizio registrato e avviato")
