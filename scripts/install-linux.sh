#!/usr/bin/env bash
# ============================================================================
#  install-linux.sh — Employee Scheduling server installation (Linux)
#
#  Designed for an always-on server (Raspberry Pi, mini-PC, VM): installs Java,
#  installs and configures PostgreSQL, creates the service user, registers the
#  systemd service, and verifies that the application actually responds.
#
#  Typical usage after extracting the Raspberry installer release archive:
#      sudo ./scripts/install-linux.sh --engine postgresql
#
#  All options:
#      --engine postgresql|sqlite   data engine (default: postgresql)
#      --jar PATH                   local pre-built JAR (otherwise latest GitHub Release)
#      --from-source                build here instead of using a JAR
#      --port N                     HTTP port (default: 8080)
#      --data-dir PATH              data, backups, and logs (default: /var/lib/employee-scheduling)
#      --db-password SECRET         PostgreSQL password (default: generated)
#      --smtp-host H --smtp-port N --smtp-user U --smtp-pass P --smtp-from F
#      --no-service                 do not create the systemd service
#      --yes                        ask no questions; use defaults
#
#  Rerunnable: updates an existing installation without losing data.
# ============================================================================
set -euo pipefail

SERVICE_NAME="employee-scheduling"
SERVICE_USER="employee-scheduling"
INSTALL_DIR="/opt/employee-scheduling"
ENV_FILE="/etc/employee-scheduling.env"
DATA_DIR="/var/lib/employee-scheduling"
ENGINE="postgresql"
PORT="8080"
JAR_SRC=""
FROM_SOURCE="no"
CREATE_SERVICE="yes"
ASSUME_YES="no"
DB_NAME="employee_scheduling"
DB_USER="employee_scheduling"
DB_PASS=""
SMTP_HOST=""; SMTP_PORT="587"; SMTP_USER=""; SMTP_PASS=""; SMTP_FROM=""
RELEASE_REPOSITORY="MirkoUgoliniDev/employee-scheduling"
DOWNLOAD_DIR=""
# The release archive preserves scripts/ so the same layout works both in a
# source checkout and in the minimal Raspberry installer package.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
UNINSTALLER_SRC="$SCRIPT_DIR/uninstall-linux.sh"
# Preserve arguments before parsing to reconstruct the command in the "rerun
# with sudo" message, where options would otherwise already be lost. Passwords
# are replaced because that message reaches terminal scrollback and session logs.
ORIGINAL_ARGS="$(printf '%s ' "$@" | sed -E 's/(--(db-password|smtp-pass)) [^ ]+/\1 ***/g')"

# ── Informative output ───────────────────────────────────────────────────────
# An installation that fails halfway is worse than one that never starts: every
# error says what went wrong AND what to do, rather than only "error."
die()  { printf '\n[ERROR] %s\n' "$1" >&2; [ $# -gt 1 ] && printf '        %s\n' "$2" >&2; exit 1; }
info() { printf '  %s\n' "$1"; }
step() { printf '\n\033[1;36m%s\033[0m\n' "$1"; }
warn() { printf '  \033[1;33m[WARNING]\033[0m %s\n' "$1"; }

[ -f "$UNINSTALLER_SRC" ] \
    || die "The uninstaller is missing from the Raspberry installer package."

# Commands as the postgres user. Prefer runuser: it is part of util-linux and is
# always present, while sudo may be absent from a minimal image.
as_postgres() {
    if command -v runuser >/dev/null 2>&1; then
        runuser -u postgres -- "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo -u postgres "$@"
    else
        su -s /bin/sh postgres -c "$(printf '%q ' "$@")"
    fi
}

# A value-taking option without its value (the last argument) made "shift 2"
# fail, and with set -e the script exited with code 1 and ZERO output. Someone
# typing "--jar" without a path saw only the prompt return.
need_value() {
    [ $# -ge 2 ] || die "Option $1 requires a value." "Use --help for the full list."
}

while [ $# -gt 0 ]; do
    case "$1" in
        --engine)      need_value "$@"; ENGINE="$2"; shift 2 ;;
        --jar)         need_value "$@"; JAR_SRC="$2"; shift 2 ;;
        --from-source) FROM_SOURCE="yes"; shift ;;
        --port)        need_value "$@"; PORT="$2"; shift 2 ;;
        --data-dir)    need_value "$@"; DATA_DIR="$2"; shift 2 ;;
        --db-password) need_value "$@"; DB_PASS="$2"; shift 2 ;;
        --smtp-host)   need_value "$@"; SMTP_HOST="$2"; shift 2 ;;
        --smtp-port)   need_value "$@"; SMTP_PORT="$2"; shift 2 ;;
        --smtp-user)   need_value "$@"; SMTP_USER="$2"; shift 2 ;;
        --smtp-pass)   need_value "$@"; SMTP_PASS="$2"; shift 2 ;;
        --smtp-from)   need_value "$@"; SMTP_FROM="$2"; shift 2 ;;
        --no-service)  CREATE_SERVICE="no"; shift ;;
        --yes|-y)      ASSUME_YES="yes"; shift ;;
        -h|--help)     sed -n '2,25p' "$0"; exit 0 ;;
        *)             die "Unknown option: $1" "Use --help for the full list." ;;
    esac
done

case "$ENGINE" in
    postgresql|sqlite) ;;
    *) die "Invalid engine: $ENGINE" "Allowed values: postgresql, sqlite." ;;
esac
printf '%s' "$PORT" | grep -Eq '^[0-9]+$' || die "Non-numeric port: $PORT"
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "Port out of range: $PORT" "Allowed values: 1-65535."
# The service runs unprivileged and without CAP_NET_BIND_SERVICE: binding below
# 1024 would fail only after installation, with an error in the journal.
[ "$PORT" -ge 1024 ] || die "Port $PORT is reserved and the service would be unable to bind to it." \
    "Use a port from 1024 upward and, if necessary, place a reverse proxy in front."

# The data directory enters the systemd unit and, during uninstallation, rm -rf.
# A relative path or spaces break ExecStart, while a newline would allow
# injection of directives executed as root.
case "$DATA_DIR" in
    /*) ;;
    *) die "--data-dir must be an absolute path: $DATA_DIR" ;;
esac
case "$DATA_DIR" in
    *[[:space:]]*) die "--data-dir cannot contain spaces: $DATA_DIR" ;;
esac
# install -o and chown run on the data directory: specifying a system directory
# is not a harmless mistake; it destroys the system. With --data-dir /etc, the
# machine's entire configuration would be assigned to the service user with
# mode 750, without confirmation and without --purge.
case "${DATA_DIR%/}" in
    ""|/|/bin|/boot|/dev|/etc|/home|/lib|/lib64|/media|/mnt|/opt|/proc|/root|/run|/sbin|/srv|/sys|/tmp|/usr|/var|/var/lib|/var/log|/var/run)
        die "'$DATA_DIR' is a system directory, not a data directory." \
            "Specify a dedicated path, for example /var/lib/employee-scheduling." ;;
esac

# The database password is interpolated into SQL and written to a file read by
# systemd. An apostrophe would break it, and worse characters could inject SQL
# executed as the superuser.
if [ -n "$DB_PASS" ]; then
    printf '%s' "$DB_PASS" | grep -Eq '^[A-Za-z0-9]+$' \
        || die "The database password may contain letters and digits only." \
               "Omit --db-password to generate a secure one."
fi

echo ""
echo "======================================================"
echo "  Employee Scheduling — Linux server installation"
echo "======================================================"

# ── 1. Privileges and system ─────────────────────────────────────────────────
step "[1/8] System"
[ "$(id -u)" = "0" ] || die "Root privileges are required." "Run again with: sudo $0 $ORIGINAL_ARGS"

# Preserve SMTP during updates. On a fresh PostgreSQL installation, make the
# choice explicit: pressing Enter keeps test mode (OTP in journald), while a
# production installation can configure delivery before the first account.
env_value() {
    local value
    value="$(grep -E "^$1=" "$ENV_FILE" 2>/dev/null | tail -n1 | cut -d= -f2- || true)"
    value="${value#\"}"; value="${value%\"}"
    printf '%s' "$value"
}
if [ -z "$SMTP_HOST" ] && [ -f "$ENV_FILE" ]; then
    SMTP_HOST="$(env_value QUARKUS_MAILER_HOST)"
    if [ -n "$SMTP_HOST" ]; then
        SMTP_PORT="$(env_value QUARKUS_MAILER_PORT)"; SMTP_PORT="${SMTP_PORT:-587}"
        SMTP_USER="$(env_value QUARKUS_MAILER_USERNAME)"
        SMTP_PASS="$(env_value QUARKUS_MAILER_PASSWORD)"
        SMTP_FROM="$(env_value QUARKUS_MAILER_FROM)"
        info "Existing SMTP configuration will be preserved."
    fi
fi
if [ "$ENGINE" = "postgresql" ] && [ -z "$SMTP_HOST" ] \
        && [ "$ASSUME_YES" != "yes" ] && [ -t 0 ]; then
    echo ""
    printf '  Configure an SMTP server now? [y/N]: '
    read -r CONFIGURE_SMTP
    case "$CONFIGURE_SMTP" in
        y|Y|yes|YES)
            printf '  SMTP host: '; read -r SMTP_HOST
            [ -n "$SMTP_HOST" ] || die "SMTP host cannot be empty after choosing SMTP setup."
            printf '  SMTP port [587]: '; read -r SMTP_PORT_INPUT
            SMTP_PORT="${SMTP_PORT_INPUT:-587}"
            printf '  SMTP username: '; read -r SMTP_USER
            printf '  SMTP password: '; read -r -s SMTP_PASS; echo ""
            printf '  Sender [%s]: ' "$SMTP_USER"; read -r SMTP_FROM
            SMTP_FROM="${SMTP_FROM:-$SMTP_USER}"
            ;;
        *)
            info "SMTP test mode selected: registration OTP will be shown in the service log."
            ;;
    esac
fi
printf '%s' "$SMTP_PORT" | grep -Eq '^[0-9]+$' \
    || die "Non-numeric SMTP port: $SMTP_PORT"
[ "$SMTP_PORT" -ge 1 ] && [ "$SMTP_PORT" -le 65535 ] \
    || die "SMTP port out of range: $SMTP_PORT"

# A port already occupied by OTHERS must be detected now. Later, the service
# would fail to bind and final verification would connect to the other program,
# declaring success while our service is in a restart loop. If our own service
# holds it, this is a normal update.
if (exec 3<>"/dev/tcp/127.0.0.1/${PORT}") 2>/dev/null; then
    exec 3>&- 2>/dev/null || true
    if systemctl is-active --quiet "$SERVICE_NAME" 2>/dev/null; then
        info "Port $PORT is occupied by the $SERVICE_NAME service: this is an update."
    else
        die "Port $PORT is already occupied by another program." \
            "Choose another one with --port, or free this port."
    fi
fi

command -v systemctl >/dev/null 2>&1 || CREATE_SERVICE="no"
if command -v apt-get >/dev/null 2>&1; then
    PKG="apt"
elif command -v dnf >/dev/null 2>&1; then
    PKG="dnf"
else
    die "Unsupported package manager (apt or dnf is required)." \
        "Install Java 17+ and PostgreSQL manually, then run again with --no-service."
fi

# apt must work unattended. Without these variables, a debconf prompt or
# Ubuntu's needrestart prompt asking which services to restart would wait
# forever for an answer that will never arrive.
export DEBIAN_FRONTEND=noninteractive
export NEEDRESTART_MODE=a

ARCH="$(uname -m)"
info "Architecture: $ARCH | package manager: $PKG"
if [ "$ARCH" = "armv7l" ] || [ "$ARCH" = "armv6l" ]; then
    warn "32-bit system. Headless Java 17+ for armhf is often unavailable"
    warn "in repositories, and the JVM performs much worse under these workloads."
    warn "Recommended: 64-bit Raspberry Pi OS (arm64)."
fi

# ── 2. Java ──────────────────────────────────────────────────────────────────
# The application is compiled for Java 17 (maven.compiler.release=17): 17 is
# enough to RUN it, and Debian bookworm — the basis of Raspberry Pi OS — provides
# 17 without additional repositories. Java 21 works as well.
step "[2/8] Java"
java_major() {
    command -v java >/dev/null 2>&1 || return 1
    java -version 2>&1 | head -n1 | sed -E 's/.*"([0-9]+)(\.[0-9]+)*.*/\1/'
}
CURRENT_JAVA="$(java_major || echo 0)"
if [ "${CURRENT_JAVA:-0}" -ge 17 ] 2>/dev/null; then
    info "Java $CURRENT_JAVA is already installed."
else
    info "Java is missing or too old: installing..."
    if [ "$PKG" = "apt" ]; then
        apt-get update -q -o DPkg::Lock::Timeout=600
        apt-get install -y -q -o DPkg::Lock::Timeout=600 -o Dpkg::Options::=--force-confold openjdk-21-jre-headless 2>/dev/null \
            || apt-get install -y -q -o DPkg::Lock::Timeout=600 -o Dpkg::Options::=--force-confold openjdk-17-jre-headless \
            || die "Java installation failed." \
                   "Install a JRE version 17 or later manually and run again."
    else
        dnf install -y -q java-21-openjdk-headless 2>/dev/null \
            || dnf install -y -q java-17-openjdk-headless \
            || die "Java installation failed."
    fi
    CURRENT_JAVA="$(java_major || echo 0)"
    [ "${CURRENT_JAVA:-0}" -ge 17 ] 2>/dev/null \
        || die "Java was installed, but its version is still lower than 17."
    info "Java $CURRENT_JAVA installed."
fi
JAVA_BIN="$(command -v java)"

# ── 3. PostgreSQL ────────────────────────────────────────────────────────────
step "[3/8] Database"
if [ "$ENGINE" = "postgresql" ]; then
    if command -v psql >/dev/null 2>&1 && (systemctl is-active --quiet postgresql 2>/dev/null); then
        info "PostgreSQL is already installed and active."
    else
        info "Installing PostgreSQL..."
        if [ "$PKG" = "apt" ]; then
            apt-get update -q -o DPkg::Lock::Timeout=600
            apt-get install -y -q -o DPkg::Lock::Timeout=600 -o Dpkg::Options::=--force-confold postgresql postgresql-client \
                || die "PostgreSQL installation failed."
        else
            dnf install -y -q postgresql-server postgresql \
                || die "PostgreSQL installation failed."
            [ -d /var/lib/pgsql/data/base ] || postgresql-setup --initdb >/dev/null 2>&1 || true
        fi
        systemctl enable --now postgresql \
            || die "PostgreSQL was installed but does not start." "Check: journalctl -u postgresql -n 50"
    fi

    # PostgreSQL backup uses pg_dump and restore uses pg_restore. Without them,
    # the application starts but disables those functions.
    for tool in pg_dump pg_restore; do
        command -v "$tool" >/dev/null 2>&1 \
            || warn "$tool not found: backup/restore will remain disabled."
    done

    # Generated password: letters and digits only because it enters a JDBC URL
    # and an environment file, where special characters require different
    # escaping at each location and one will eventually be mishandled.
    if [ -z "$DB_PASS" ]; then
        DB_PASS="$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-32)"
        [ "${#DB_PASS}" -ge 24 ] || die "Database password generation failed."
    fi

    # Create the role and database only if absent: the script must be rerunnable
    # on an existing working installation without breaking it.
    ROLE_EXISTS="$(as_postgres psql -tAc \
        "SELECT 1 FROM pg_roles WHERE rolname='${DB_USER}'" 2>/dev/null || true)"
    # Send the statement through standard input, not as a psql argument: any local
    # user can read a process command line in /proc/<pid>/cmdline, and -c would
    # expose the password in plain text. The project already applies this rule
    # to pg_dump.
    if [ "$ROLE_EXISTS" = "1" ]; then
        info "Role '$DB_USER' already exists: updating its password."
        printf "ALTER ROLE %s WITH LOGIN PASSWORD '%s';\n" "$DB_USER" "$DB_PASS" \
            | as_postgres psql -q -v ON_ERROR_STOP=1 -f - >/dev/null \
            || die "Unable to update the role password."
    else
        printf "CREATE ROLE %s WITH LOGIN PASSWORD '%s';\n" "$DB_USER" "$DB_PASS" \
            | as_postgres psql -q -v ON_ERROR_STOP=1 -f - >/dev/null \
            || die "PostgreSQL role creation failed."
        info "Role '$DB_USER' created."
    fi

    DB_EXISTS="$(as_postgres psql -tAc \
        "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" 2>/dev/null || true)"
    if [ "$DB_EXISTS" = "1" ]; then
        info "Database '$DB_NAME' already exists: existing data is preserved."
    else
        as_postgres createdb -O "$DB_USER" "$DB_NAME" \
            || die "Database creation failed."
        info "Database '$DB_NAME' created."
    fi
    # Since PostgreSQL 15, non-owner roles cannot write to the public schema.
    # Without this, Flyway fails on the first migration.
    as_postgres psql -q -d "$DB_NAME" -c \
        "ALTER SCHEMA public OWNER TO ${DB_USER}" >/dev/null 2>&1 || true

    DB_URL="jdbc:postgresql://localhost:5432/${DB_NAME}"

    # Real connection test: if pg_hba rejects TCP access, discover it now rather
    # than later with the service in a restart loop.
    if ! PGPASSWORD="$DB_PASS" psql -h localhost -U "$DB_USER" -d "$DB_NAME" \
            -tAc "SELECT 1" >/dev/null 2>&1; then
        die "Database connection rejected with the newly created credentials." \
            "Check pg_hba.conf: it requires a 'host all all 127.0.0.1/32 scram-sha-256' line."
    fi
    info "Database connection verified."
else
    info "SQLite engine: no database service to install."
    DB_URL=""
fi

# ── 4. Application ───────────────────────────────────────────────────────────
step "[4/8] Application"
if [ "$FROM_SOURCE" = "yes" ]; then
    warn "Building locally: this may take several minutes on a Raspberry Pi."
    command -v mvn >/dev/null 2>&1 || die "Maven is not installed." "sudo apt install maven"
    command -v npm >/dev/null 2>&1 || die "Node.js is not installed." "sudo apt install nodejs npm"
    NODE_MAJOR="$(node -v 2>/dev/null | sed -E 's/^v([0-9]+).*/\1/' || echo 0)"
    [ "${NODE_MAJOR:-0}" -ge 20 ] 2>/dev/null \
        || die "Node.js $NODE_MAJOR is too old for this frontend (20+ is required)." \
               "On Debian/Raspberry Pi OS use the NodeSource packages, or pass --jar."
    ( cd "$ROOT/frontend" && npm install --silent && npm run build --silent ) \
        || die "Frontend build failed."
    # -Dquarkus.profile is MANDATORY here, not a detail: the data engine and
    # Flyway migration directories are fixed at BUILD time, not runtime. Without
    # it, quarkus.flyway.locations keeps its default and Flyway finds V1 in both
    # sqlite/ and postgresql/, stopping with "Found more than one migration with
    # version 1." The JAR starts with neither engine.
    ( cd "$ROOT" && mvn -B -q package -DskipTests \
        -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile="$ENGINE" ) \
        || die "Backend build failed."
    JAR_SRC="$(ls "$ROOT"/target/*runner.jar 2>/dev/null | head -n1 || true)"
fi

if [ -z "$JAR_SRC" ]; then
    JAR_SRC="$(ls "$ROOT"/target/*runner.jar 2>/dev/null | head -n1 || true)"
fi

# A clean clone intentionally contains no 80 MB binary. Releases publish one
# package per engine with a stable asset name, so a Raspberry can install the
# application directly from GitHub without Maven, Node, scp, or a Windows PC.
if [ -z "$JAR_SRC" ]; then
    ASSET="employee-scheduling-${ENGINE}-runner.jar"
    RELEASE_URL="https://github.com/${RELEASE_REPOSITORY}/releases/latest/download/${ASSET}"
    DOWNLOAD_DIR="$(mktemp -d -t employee-scheduling.XXXXXXXX)" \
        || die "Unable to create a temporary download directory."
    JAR_SRC="${DOWNLOAD_DIR}/${ASSET}"
    info "Downloading the latest $ENGINE release from GitHub..."
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --silent --show-error --output "$JAR_SRC" "$RELEASE_URL" \
            || die "Unable to download the application package." \
                   "Check the Internet connection and that a GitHub Release exists: $RELEASE_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget --quiet --output-document="$JAR_SRC" "$RELEASE_URL" \
            || die "Unable to download the application package." \
                   "Check the Internet connection and that a GitHub Release exists: $RELEASE_URL"
    else
        die "Neither curl nor wget is installed." \
            "Install one of them, or pass a local package with --jar."
    fi
fi
[ -n "$JAR_SRC" ] && [ -f "$JAR_SRC" ] \
    || die "No JAR to install." \
           "The automatic GitHub download failed; pass --jar or use --from-source."

JAR_NAME="$(basename "$JAR_SRC")"
info "Package: $JAR_NAME"

# ── The engine baked into the package must match the selected engine ─────────
# Quarkus fixes quarkus.datasource.db-kind and quarkus.flyway.locations when the
# JAR is BUILT. A SQLite JAR installed with --engine postgresql starts a service
# that then dies with "Driver does not support the provided URL," an error that
# never names the build profile and sends users searching pg_hba.conf. Detect it now.
jar_engine() {
    local jar="$1"
    local cls="io/quarkus/runtime/generated/BuildTimeRunTimeFixedConfigSourceBuilder.class"
    local dump=""
    if command -v unzip >/dev/null 2>&1; then
        dump="$(unzip -p "$jar" "$cls" 2>/dev/null || true)"
    elif command -v python3 >/dev/null 2>&1; then
        dump="$(python3 -c "
import sys, zipfile
try:
    print(zipfile.ZipFile(sys.argv[1]).read(sys.argv[2]).decode('latin-1'))
except Exception:
    pass" "$jar" "$cls" 2>/dev/null || true)"
    fi
    [ -z "$dump" ] && return 0   # indeterminate: continue without blocking
    # Order matters: try the two suffixes BEFORE the generic path, which appears
    # in both cases.
    case "$dump" in
        *db/migration/postgresql*) printf 'postgresql' ;;
        *db/migration/sqlite*)     printf 'sqlite' ;;
        # Only "db/migration" without a suffix: built without -Dquarkus.profile.
        # It works with neither engine — and with SQLite the failure is silent
        # because the default profile has quarkus.flyway.active=false.
        *db/migration*)            printf 'no-profile' ;;
    esac
}

REBUILD="Rebuild it: mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile=$ENGINE"
BAKED="$(jar_engine "$JAR_SRC")"
if [ -z "$BAKED" ]; then
    warn "Package contents cannot be inspected: engine not verified."
    warn "Make sure it was built with -Dquarkus.profile=$ENGINE."
elif [ "$BAKED" = "no-profile" ]; then
    die "The package was built without -Dquarkus.profile and does not work with either engine." \
        "$REBUILD  (with PostgreSQL the service does not start; with SQLite it starts but silently creates no tables)"
elif [ "$BAKED" != "$ENGINE" ]; then
    die "The package was built for '$BAKED', but you are installing with --engine $ENGINE." \
        "$REBUILD"
else
    info "Package engine verified: $BAKED"
fi

# Service user without a shell or private home: it must access nothing beyond
# its own data.
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
    useradd --system --no-create-home --home-dir "$DATA_DIR" \
            --shell /usr/sbin/nologin "$SERVICE_USER" \
        || die "Service user creation failed."
    info "Service user '$SERVICE_USER' created."
fi

install -d -m 755 -o root -g root "$INSTALL_DIR"
install -d -m 750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$DATA_DIR"
install -d -m 750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$DATA_DIR/backups"

install -m 755 -o root -g root "$UNINSTALLER_SRC" "$INSTALL_DIR/uninstall-linux.sh"

# The JAR remains root-owned and read-only for the service: a compromised process
# must not be able to rewrite its own executable.
rm -f "$INSTALL_DIR"/*runner.jar
install -m 644 -o root -g root "$JAR_SRC" "$INSTALL_DIR/$JAR_NAME"
if [ -n "$DOWNLOAD_DIR" ]; then
    rm -f -- "$JAR_SRC"
    rmdir -- "$DOWNLOAD_DIR" 2>/dev/null || true
fi

# ── 5. Secrets and configuration ─────────────────────────────────────────────
step "[5/8] Configuration"
# Regenerating the session key on every run would log out all connected users;
# reuse the existing key if present.
SESSION_KEY=""; BACKUP_TOKEN=""
if [ -f "$ENV_FILE" ]; then
    SESSION_KEY="$(grep -E '^AUTH_SESSION_KEY=' "$ENV_FILE" | cut -d= -f2- || true)"
    BACKUP_TOKEN="$(grep -E '^BACKUP_ADMIN_TOKEN=' "$ENV_FILE" | cut -d= -f2- || true)"
    [ -n "$SESSION_KEY" ] && info "Existing session key reused (no one will be logged out)."
fi
[ -n "$SESSION_KEY" ]  || SESSION_KEY="$(head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-48)"
[ -n "$BACKUP_TOKEN" ] || BACKUP_TOKEN="$(head -c 64 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | cut -c1-48)"
# The key encrypts the session cookie: below 16 characters, Quarkus rejects every
# request with an unexplained 500 error.
[ "${#SESSION_KEY}"  -ge 32 ] || die "Session key is too short (${#SESSION_KEY})."
[ "${#BACKUP_TOKEN}" -ge 32 ] || die "Backup token is too short (${#BACKUP_TOKEN})."

umask 077
{
    echo "# Employee Scheduling — generated by install-linux.sh on $(date -Is)"
    echo "# Contains credentials: do not copy it or commit it to version control."
    echo "QUARKUS_PROFILE=${ENGINE}"
    echo "QUARKUS_HTTP_PORT=${PORT}"
    # Redundant with -Dapp.data.dir in the unit, but it also lets uninstallation
    # find the correct directory when it is not the default.
    echo "APP_DATA_DIR=${DATA_DIR}"
    echo "AUTH_SESSION_KEY=${SESSION_KEY}"
    echo "BACKUP_ADMIN_TOKEN=${BACKUP_TOKEN}"
    if [ "$ENGINE" = "postgresql" ]; then
        echo "DATABASE_URL=${DB_URL}"
        echo "DATABASE_USERNAME=${DB_USER}"
        echo "DATABASE_PASSWORD=${DB_PASS}"
    fi
    if [ -n "$SMTP_HOST" ]; then
        # Quotes are mandatory: a sender such as "Shifts <shifts@example.com>"
        # contains spaces, and systemd would truncate an unquoted value.
        echo "QUARKUS_MAILER_MOCK=false"
        echo "QUARKUS_MAILER_HOST=\"${SMTP_HOST}\""
        echo "QUARKUS_MAILER_PORT=${SMTP_PORT}"
        echo "QUARKUS_MAILER_USERNAME=\"${SMTP_USER}\""
        echo "QUARKUS_MAILER_PASSWORD=\"${SMTP_PASS}\""
        echo "QUARKUS_MAILER_FROM=\"${SMTP_FROM:-$SMTP_USER}\""
        echo "QUARKUS_MAILER_START_TLS=REQUIRED"
    else
        echo "QUARKUS_MAILER_MOCK=true"
    fi
} > "$ENV_FILE"
chown root:"$SERVICE_USER" "$ENV_FILE"
chmod 640 "$ENV_FILE"
info "Configuration written to $ENV_FILE (readable only by root and the service)."

if [ "$ENGINE" = "postgresql" ] && [ -z "$SMTP_HOST" ]; then
    warn "No SMTP configured: OTP registration will display the code"
    warn "only in the log (journalctl -u $SERVICE_NAME). For production use,"
    warn "the --smtp-host / --smtp-user / --smtp-pass options are required."
fi

# ── 6. systemd service ───────────────────────────────────────────────────────
step "[6/8] Service"
if [ "$CREATE_SERVICE" != "yes" ]; then
    info "Service not created (--no-service)."
else
    # ProtectHome hides /home and /root from the service. Disable it if data was
    # placed there, or the service would not see its own directory and would fail
    # with an error that does not explain why.
    PROTECT_HOME="true"
    case "$DATA_DIR" in
        /home/*|/root/*) PROTECT_HOME="false" ;;
    esac

    cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<EOF
[Unit]
Description=Employee Scheduling — employee shift planning
Documentation=https://github.com/MirkoUgoliniDev/employee-scheduling
After=network-online.target${DB_URL:+ postgresql.service}
Wants=network-online.target${DB_URL:+ postgresql.service}

[Service]
Type=simple
User=${SERVICE_USER}
Group=${SERVICE_USER}
WorkingDirectory=${DATA_DIR}
EnvironmentFile=${ENV_FILE}
# app.data.dir keeps the database, backups, settings, and logs in the data
# directory. Without it, relative paths would end up beside the JAR in a
# directory the service cannot write to.
ExecStart=${JAVA_BIN} -Dapp.data.dir=${DATA_DIR} -jar ${INSTALL_DIR}/${JAR_NAME}
Restart=on-failure
RestartSec=10
TimeoutStopSec=30

# Confinement: the service writes only to its data directory.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=${PROTECT_HOME}
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
RestrictNamespaces=true
LockPersonality=true
ReadWritePaths=${DATA_DIR}

[Install]
WantedBy=multi-user.target
EOF
    # umask 077 was set above for secrets; the unit must remain normally readable,
    # or systemd-analyze and third-party logs encounter unexpected permissions.
    chmod 644 "/etc/systemd/system/${SERVICE_NAME}.service"
    systemctl daemon-reload
    systemctl enable "$SERVICE_NAME" >/dev/null 2>&1 || true
    systemctl restart "$SERVICE_NAME" \
        || die "The service does not start." "Diagnostics: journalctl -u $SERVICE_NAME -n 60 --no-pager"
    info "Service registered and started."
fi

# ── 7. Verify that it actually responds ──────────────────────────────────────
# "systemctl start" returns immediately: without this wait, a broken installation
# would appear successful.
step "[7/8] Verification"
if [ "$CREATE_SERVICE" = "yes" ]; then
    READY="no"
    for _ in $(seq 1 60); do
        if ! systemctl is-active --quiet "$SERVICE_NAME"; then
            echo ""
            journalctl -u "$SERVICE_NAME" -n 30 --no-pager >&2 || true
            die "The service stopped during startup." \
                "The latest log lines are shown above."
        fi
        if (exec 3<>"/dev/tcp/127.0.0.1/${PORT}") 2>/dev/null; then
            exec 3>&- 2>/dev/null || true
            READY="yes"; break
        fi
        sleep 2
    done
    if [ "$READY" = "yes" ]; then
        info "The application is responding on port $PORT."
    else
        echo ""
        journalctl -u "$SERVICE_NAME" -n 30 --no-pager >&2 || true
        die "Started, but not responding on port $PORT within two minutes." \
            "The latest log lines are shown above."
    fi
fi

# ── 8. Summary ───────────────────────────────────────────────────────────────
step "[8/8] Done"
IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
echo ""
echo "======================================================"
echo "  Installation completed"
echo "======================================================"
echo "  Data engine   : $ENGINE"
[ "$ENGINE" = "postgresql" ] && echo "  Database      : $DB_NAME (user $DB_USER, localhost only)"
echo "  Data & backups: $DATA_DIR"
echo "  Application   : $INSTALL_DIR/$JAR_NAME"
echo "  Configuration : $ENV_FILE"
echo "  Address       : http://${IP:-localhost}:${PORT}"
echo ""
echo "  Live log      : journalctl -u $SERVICE_NAME -f"
echo "  Status        : systemctl status $SERVICE_NAME"
echo "  Restart       : systemctl restart $SERVICE_NAME"
echo "  Uninstall     : sudo $INSTALL_DIR/uninstall-linux.sh"
echo ""
echo "  The first registered account becomes the administrator."
echo ""
warn "Traffic uses unencrypted HTTP: this is acceptable on a trusted local network."
warn "If the application must be reachable externally, place it behind a"
warn "reverse proxy with a certificate (for example, nginx or Caddy)."
echo ""
