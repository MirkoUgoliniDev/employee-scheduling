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
        super().__init__("System check",
                         "Privileges, operating system, disk space, and free port")

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()

        if not sysinfo.is_root:
            return self.fail("The wizard does not have root privileges.",
                             "Run it again with: sudo python3 setup/wizard.py")

        if not sysinfo.package_manager:
            return self.fail("No supported package manager found (apt or dnf is required).",
                             "Install Java 17+ and PostgreSQL manually, then use install-linux.sh --no-service.")

        if not sysinfo.has_systemd:
            return self.fail("systemd is not available on this system.",
                             "The service cannot be registered: it must be started manually.")

        # Below 400 MB there is not comfortably enough room for the roughly
        # 80 MB JAR, downloaded packages, and the first backup.
        if sysinfo.disk_free_mb and sysinfo.disk_free_mb < 400:
            return self.fail(f"Not enough free space: {sysinfo.disk_free_mb} MB.",
                             "At least 400 MB on / are required.")

        if sysinfo.arch in ("armv6l", "armv7l"):
            runner.log("    [warning] 32-bit system: headless Java 17+ for armhf is")
            runner.log("              often missing from the repositories, and the JVM")
            runner.log("              performs much worse. 64-bit Raspberry Pi OS is advised.")

        if sysinfo.ram_mb and sysinfo.ram_mb < 900:
            runner.log(f"    [warning] Low memory ({sysinfo.ram_mb} MB): the solver may")
            runner.log("              be slow on large schedules.")

        # Detect an occupied port now. Later, the service would start and die at
        # once with a bind error that does not identify the responsible process.
        #
        # During an update, however, OUR service occupies the port, which is
        # normal. Blocking there would make the wizard usable only once per
        # machine, and "change port" would be bad advice. Identify the owner.
        port = int(config.get("port", DEFAULT_PORT))
        if not 1 <= port <= 65535:
            return self.fail(f"Port out of range: {port}", "Allowed values: 1-65535.")
        # The service runs unprivileged and without CAP_NET_BIND_SERVICE, so bind
        # below 1024 would fail only after installation, after two minutes in the
        # verification step, with a journal error that does not name the problem.
        # install-linux.sh performs the same check.
        if port < 1024:
            return self.fail(f"Port {port} is reserved and the service could not bind to it.",
                             "Use a port from 1024 upwards, with a reverse proxy in front if needed.")
        if sysinfo.port_in_use(port):
            # Query directly instead of using runner.run, which always succeeds
            # in simulation. Otherwise a port held by ANOTHER program would be
            # mistaken for our service and simulation would approve an unsuitable
            # system — the classic flaw of a simulation validating itself.
            ours = _service_is_active()
            if ours:
                runner.log(f"    port {port} is held by the {SERVICE_NAME} service:"
                           " this is an update, it will be restarted")
                config["updating"] = True
            else:
                return self.fail(f"Port {port} is already used by another program.",
                                 "Choose another one with --port, or free that port.")

        # Validate the package HERE before any change. This is the most likely
        # failure (wrong path or file not copied); discovering it at step 5 after
        # reconfiguring the database would leave the machine worse than before.
        jar = config.get("jar")
        if not jar:
            return self.fail("No package specified.",
                             "Pass --jar path/to/employee-scheduling-runner.jar")
        jar_path = Path(jar).expanduser()
        if not jar_path.is_file():
            return self.fail(f"Package not found: {jar_path}",
                             "Build it on your PC and copy it here with scp.")

        # Also verify the engine baked into the package HERE. The application
        # step already checked it, but only after database creation and
        # reconfiguration. Rejecting a wrong package must not leave the machine
        # halfway through installation.
        engine = config.get("engine", "postgresql")
        rebuild = ("Rebuild it: mvn package -DskipTests "
                   f"-Dquarkus.package.jar.type=uber-jar -Dquarkus.profile={engine}")
        baked = baked_engine(jar_path)
        if baked is None:
            runner.log("    [warning] the package engine cannot be verified:"
                       f" make sure it was built with -Dquarkus.profile={engine}")
        elif baked == NO_PROFILE:
            return self.fail(
                "The package was built without -Dquarkus.profile and works with"
                " neither engine.",
                rebuild + " — with PostgreSQL the service does not start; with SQLite it"
                          " starts but creates no tables, without reporting any error.")
        elif baked != engine:
            return self.fail(
                f"The package is built for '{baked}' but you are installing with engine '{engine}'.",
                rebuild)
        else:
            runner.log(f"    package engine verified: {baked}")

        # The data directory is embedded in the systemd unit: a relative path or
        # spaces break ExecStart, while a newline would inject arbitrary
        # directives executed as root.
        data_dir = str(config.get("data_dir", ""))
        if not data_dir.startswith("/"):
            return self.fail(f"The data directory must be an absolute path: {data_dir}")
        if any(char in data_dir for char in " \t\n\r"):
            return self.fail("The data directory cannot contain spaces or line breaks.",
                             f"Value received: {data_dir!r}")
        # chown -R and chmod run on the data directory: specifying a system
        # directory is not a harmless mistake; it destroys the system. With
        # --data-dir /etc, all machine configuration would be assigned to the
        # service user with mode 750.
        if data_dir.rstrip("/") in SYSTEM_DIRS:
            return self.fail(f"'{data_dir}' is a system directory, not a data directory.",
                             "Specify a dedicated path, for example /var/lib/employee-scheduling.")

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
                    f"'{data_dir}' is on a disk that does not appear to be mounted (no "
                    f"mount point up to {mountpoint}).",
                    "Mount the disk and run again, otherwise the data would land on the SD "
                    "card and disappear behind the mount at the first reboot.")

        # Measure space where data actually grows: the database and backups live
        # in the data directory, which may be on another filesystem.
        target = Path(data_dir)
        while not target.exists() and target != target.parent:
            target = target.parent
        try:
            free_mb = shutil.disk_usage(str(target)).free // (1024 * 1024)
            if free_mb < 400:
                return self.fail(f"Not enough space on {target}: {free_mb} MB.",
                                 "At least 400 MB are required for package, database, and first backup.")
        except OSError:
            pass

        # The package name enters the unit's ExecStart just like the data
        # directory: a space would split the command into arguments and prevent
        # startup, with diagnostics that do not name the file.
        if any(char in jar_path.name for char in " \t\n\r"):
            return self.fail("The package name cannot contain spaces or line breaks.",
                             f"Rename it: {jar_path.name!r}")
        if jar_path.suffix != ".jar":
            return self.fail(f"The specified file is not a jar: {jar_path.name}")

        for key, value in sysinfo.summary().items():
            runner.log(f"    {key}: {value}")
        return self.done("System suitable")
