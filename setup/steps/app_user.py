"""Service user and data directories."""

from pathlib import Path

from lib.constants import DATA_DIR, SERVICE_USER
from lib.step_base import Step


def _user_exists(name: str) -> bool:
    """Read user existence from the system, not from a command result.

    The ``pwd`` module exists only on Unix: this code always runs there, but
    simulations also run elsewhere to test the wizard, and an ImportError would
    turn a harmless test into a failed step.
    """
    try:
        import pwd
        pwd.getpwnam(name)
        return True
    except (ImportError, KeyError):
        return False


class AppUserStep(Step):
    def __init__(self):
        super().__init__("Utente e cartelle",
                         "Utente di sistema dedicato e cartella dei dati")

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()
        data_dir = Path(config.get("data_dir", DATA_DIR))

        # System user: no shell and no private home. If the service is ever
        # compromised, it will have neither an environment to move through nor
        # a way to log in.
        # Every command "succeeds" in simulation, so the check would always say
        # that the user already exists. Inspect the real system instead, or the
        # simulation would report one thing and installation would do another.
        ok, out = runner.run(["id", SERVICE_USER], check_output=True)
        if runner.dry_run:
            runner.log(f"    [simulazione] l'utente {SERVICE_USER} "
                       f"{'esiste gia' if _user_exists(SERVICE_USER) else 'verrebbe creato'}")
        elif ok:
            runner.log(f"    utente {SERVICE_USER} gia' presente")
        else:
            ok, err = runner.run(["useradd", "--system", "--no-create-home",
                                  "--home-dir", str(data_dir),
                                  "--shell", "/usr/sbin/nologin", SERVICE_USER])
            if not ok:
                return self.fail(f"Creazione dell'utente di servizio non riuscita: {err}")
            runner.log(f"    utente {SERVICE_USER} creato")

        # 750: the service writes, the group reads, and others have no access.
        # The database and backups — personnel data — are stored here.
        for folder in (data_dir, data_dir / "backups"):
            ok, err = runner.mkdir(folder, mode=0o750)
            if not ok:
                return self.fail(err)

        if not runner.dry_run:
            ok, err = runner.run(["chown", "-R", f"{SERVICE_USER}:{SERVICE_USER}", str(data_dir)])
            if not ok:
                return self.fail(f"Assegnazione della proprieta' non riuscita: {err}")

        config["data_dir"] = str(data_dir)
        return self.done(f"Dati in {data_dir}")
