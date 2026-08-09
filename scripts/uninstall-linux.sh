#!/usr/bin/env bash
# ============================================================================
#  uninstall-linux.sh — Remove Employee Scheduling (Linux)
#
#  Stops and removes the service, application, and configuration.
#  DATA IS NOT TOUCHED unless explicitly requested.
#
#  Usage:
#      sudo ./scripts/uninstall-linux.sh                 removes app and service, keeps data
#      sudo ./scripts/uninstall-linux.sh --purge         ALSO removes data, backups, and database
#      sudo ./scripts/uninstall-linux.sh --purge --yes   same, without confirmation
# ============================================================================
set -euo pipefail

SERVICE_NAME="employee-scheduling"
SERVICE_USER="employee-scheduling"
INSTALL_DIR="/opt/employee-scheduling"
ENV_FILE="/etc/employee-scheduling.env"
DATA_DIR="/var/lib/employee-scheduling"
DB_NAME="employee_scheduling"
DB_USER="employee_scheduling"
PURGE="no"
ASSUME_YES="no"
# Saved before parsing: after the loop, arguments have been consumed and the
# "rerun with sudo" hint would suggest a command WITHOUT --purge, different from
# what the user requested.
ORIGINAL_ARGS="$*"

die()  { printf '\n[ERROR] %s\n' "$1" >&2; exit 1; }

# As in the installation script: runuser is always present; sudo may not be.
as_postgres() {
    if command -v runuser >/dev/null 2>&1; then
        runuser -u postgres -- "$@"
    elif command -v sudo >/dev/null 2>&1; then
        sudo -u postgres "$@"
    else
        su -s /bin/sh postgres -c "$(printf '%q ' "$@")"
    fi
}
info() { printf '  %s\n' "$1"; }
step() { printf '\n\033[1;36m%s\033[0m\n' "$1"; }
warn() { printf '  \033[1;33m[WARNING]\033[0m %s\n' "$1"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --purge)   PURGE="yes"; shift ;;
        --yes|-y)  ASSUME_YES="yes"; shift ;;
        -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
        *)         die "Unknown option: $1" ;;
    esac
done

[ "$(id -u)" = "0" ] || die "Root privileges are required. Run again with: sudo $0 $ORIGINAL_ARGS"

# Read the data directory from configuration rather than assuming it: users who
# installed with --data-dir keep it elsewhere, and deleting the wrong directory
# would leave real data on disk while giving the impression it was removed.
#
# Order matters. Read the systemd unit first, THEN the environment file, which
# wins: extraction from the unit stops at the first space, so a path such as
# "/srv/es data" would yield "/srv/es" — a different directory that would be
# deleted. The environment file contains the full line and is authoritative.
if [ -f "/etc/systemd/system/${SERVICE_NAME}.service" ]; then
    FOUND_DIR="$(grep -oE '\-Dapp\.data\.dir=[^ ]+' "/etc/systemd/system/${SERVICE_NAME}.service" \
                 | head -n1 | cut -d= -f2- || true)"
    [ -n "$FOUND_DIR" ] && DATA_DIR="$FOUND_DIR"
fi
if [ -f "$ENV_FILE" ]; then
    FOUND_DIR="$(grep -E '^APP_DATA_DIR=' "$ENV_FILE" | cut -d= -f2- | tr -d '"' || true)"
    [ -n "$FOUND_DIR" ] && DATA_DIR="$FOUND_DIR"
fi

# Last barrier before rm -rf. A hand-edited configuration file or misread path
# must not allow deletion of a system directory: GNU rm protects only literal
# "/", not "/var".
#
# The check applies ONLY with --purge, when something will actually be deleted.
# Applying it always prevented service removal even when data was to be kept —
# precisely when the directory is irrelevant. A one-level path such as /data is
# also allowed: it is legitimate, and requiring two levels blocked normal setups.
if [ "$PURGE" = "yes" ]; then
    case "$DATA_DIR" in
        /|/bin|/boot|/dev|/etc|/home|/lib|/lib64|/media|/mnt|/opt|/proc|/root|/run|/sbin|/srv|/sys|/tmp|/usr|/var|/var/lib)
            die "Suspicious data directory; refusing to delete it: $DATA_DIR" ;;
        /*) ;;
        *) die "The data directory is not an absolute path; refusing to delete it: $DATA_DIR" ;;
    esac
fi

echo ""
echo "======================================================"
echo "  Employee Scheduling — uninstallation"
echo "======================================================"
echo "  Service       : $SERVICE_NAME"
echo "  Application   : $INSTALL_DIR"
echo "  Configuration : $ENV_FILE"
if [ "$PURGE" = "yes" ]; then
    echo "  Data          : $DATA_DIR   <-- WILL BE DELETED"
    echo "  Database      : $DB_NAME    <-- WILL BE DROPPED"
else
    echo "  Data          : $DATA_DIR   (preserved)"
fi
echo ""

if [ "$PURGE" = "yes" ] && [ "$ASSUME_YES" != "yes" ]; then
    warn "--purge permanently deletes shifts, employees, backups, and history. This cannot be undone."
    printf '  Type DELETE to proceed: '
    read -r CONFIRM
    [ "$CONFIRM" = "DELETE" ] || { echo "  Cancelled: nothing was changed."; exit 0; }
fi

# ── Service ──────────────────────────────────────────────────────────────────
step "[1/4] Service"
if [ -f "/etc/systemd/system/${SERVICE_NAME}.service" ]; then
    systemctl stop "$SERVICE_NAME" 2>/dev/null || true
    systemctl disable "$SERVICE_NAME" >/dev/null 2>&1 || true
    rm -f "/etc/systemd/system/${SERVICE_NAME}.service"
    systemctl daemon-reload
    systemctl reset-failed "$SERVICE_NAME" 2>/dev/null || true
    info "Service stopped and removed."
else
    info "No registered service found."
fi

# ── Application and configuration ────────────────────────────────────────────
step "[2/4] Application"
[ -d "$INSTALL_DIR" ] && rm -rf "$INSTALL_DIR" && info "Removed $INSTALL_DIR" || info "Nothing to remove from /opt."
if [ -f "$ENV_FILE" ]; then
    if [ "$PURGE" = "yes" ]; then
        rm -f "$ENV_FILE"; info "Configuration removed."
    else
        # Contains the database password, which remains in use without --purge.
        info "Configuration preserved: $ENV_FILE (contains database credentials)."
    fi
fi

# ── Data ─────────────────────────────────────────────────────────────────────
step "[3/4] Data"
if [ "$PURGE" = "yes" ]; then
    if [ -d "$DATA_DIR" ]; then
        rm -rf "$DATA_DIR"; info "Deleted $DATA_DIR"
    fi
    if command -v psql >/dev/null 2>&1; then
        as_postgres dropdb --if-exists "$DB_NAME" 2>/dev/null \
            && info "Database $DB_NAME dropped." \
            || warn "Database $DB_NAME was not dropped (it may not have existed)."
        as_postgres psql -q -c "DROP ROLE IF EXISTS ${DB_USER}" >/dev/null 2>&1 \
            && info "Role $DB_USER dropped." || true
    fi
else
    info "Data and backups were left in $DATA_DIR."
    info "To remove them: sudo $0 --purge"
fi

# ── Service user ─────────────────────────────────────────────────────────────
step "[4/4] Service user"
if id "$SERVICE_USER" >/dev/null 2>&1; then
    if [ "$PURGE" = "yes" ]; then
        userdel "$SERVICE_USER" 2>/dev/null && info "User $SERVICE_USER removed." || true
    else
        # Without the user, retained files would be orphaned with a numeric UID
        # that might later be reassigned to someone else.
        info "User $SERVICE_USER preserved: it owns the remaining data."
    fi
else
    info "No service user to remove."
fi

echo ""
echo "======================================================"
echo "  Uninstallation complete"
echo "======================================================"
if [ "$PURGE" != "yes" ]; then
    echo "  Data is still in $DATA_DIR."
    echo "  Reinstalling the application will reuse it as it was left."
fi
echo ""
