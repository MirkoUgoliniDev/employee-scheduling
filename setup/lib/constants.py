"""Values shared by all installation steps.

They live here rather than being scattered across individual steps because
uninstallation, verification, and recovery must use exactly the same paths: a
name duplicated manually in two files will eventually diverge, leaving a
service looking for data where nobody wrote it.
"""

from pathlib import Path

WIZARD_VERSION = "1.0"

# ── Installation identity ───────────────────────────────────────────────────
SERVICE_NAME = "employee-scheduling"
SERVICE_USER = "employee-scheduling"

INSTALL_DIR = Path("/opt/employee-scheduling")
DATA_DIR = Path("/var/lib/employee-scheduling")
ENV_FILE = Path("/etc/employee-scheduling.env")
UNIT_FILE = Path("/etc/systemd/system/employee-scheduling.service")
LOG_FILE = Path("/var/log/employee-scheduling-setup.log")
LOCK_FILE = Path("/var/run/employee-scheduling-setup.lock")

# ── Database ─────────────────────────────────────────────────────────────────
DB_NAME = "employee_scheduling"
DB_USER = "employee_scheduling"

# ── Default values ───────────────────────────────────────────────────────────
DEFAULT_PORT = 8080
DEFAULT_ENGINE = "postgresql"
WEB_PORT = 8899

# The application is compiled for Java 17 (maven.compiler.release=17): Java 17
# is enough to run it and is available on Debian bookworm — the basis of
# Raspberry Pi OS — without adding repositories. Java 21 works as well.
JAVA_MIN_MAJOR = 17
JAVA_PACKAGES = ("openjdk-21-jre-headless", "openjdk-17-jre-headless")

# Quarkus rejects every request with an opaque 500 response if the session key
# is shorter than 16 characters: generate many more and validate the length.
SECRET_LENGTH = 48
SECRET_MIN_LENGTH = 32

# Maximum wait for the application to open the port after the service starts.
# Deliberately generous: on a Raspberry Pi with an SD card, the first startup
# must launch the JVM, apply all migrations, and seed over four thousand
# translations. If the timeout expires while the service is still alive, only
# issue a warning: this is slow hardware, not a failure.
STARTUP_TIMEOUT_SECONDS = 180

#: Directories on which the wizard must never run chown, chmod, or removals.
#: This is not paranoia: --data-dir /etc would assign the machine's entire
#: configuration to the service user with mode 750.
SYSTEM_DIRS = frozenset((
    "", "/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/media",
    "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/tmp",
    "/usr", "/var", "/var/lib", "/var/log", "/var/run",
))
