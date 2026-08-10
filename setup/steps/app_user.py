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
        super().__init__("User and directories",
                         "Dedicated system user and data directory")

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
            runner.log(f"    [simulation] user {SERVICE_USER} "
                       f"{'already exists' if _user_exists(SERVICE_USER) else 'would be created'}")
        elif ok:
            runner.log(f"    user {SERVICE_USER} already present")
        else:
            ok, err = runner.run(["useradd", "--system", "--no-create-home",
                                  "--home-dir", str(data_dir),
                                  "--shell", "/usr/sbin/nologin", SERVICE_USER])
            if not ok:
                return self.fail(f"Creating the service user failed: {err}")
            runner.log(f"    user {SERVICE_USER} created")

        # 750: the service writes, the group reads, and others have no access.
        # The database and backups — personnel data — are stored here.
        for folder in (data_dir, data_dir / "backups"):
            ok, err = runner.mkdir(folder, mode=0o750)
            if not ok:
                return self.fail(err)

        if not runner.dry_run:
            ok, err = runner.run(["chown", "-R", f"{SERVICE_USER}:{SERVICE_USER}", str(data_dir)])
            if not ok:
                return self.fail(f"Assigning ownership failed: {err}")

        config["data_dir"] = str(data_dir)
        return self.done(f"Data in {data_dir}")
