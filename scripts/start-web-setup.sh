#!/usr/bin/env bash
# Start the temporary browser-based installer on a Raspberry Pi.
#
# Usage:
#   sudo ./scripts/start-web-setup.sh
#   sudo ./scripts/start-web-setup.sh --engine sqlite
#
# Options:
#   --engine postgresql|sqlite  Application data engine (default: postgresql)
#   --web-port N                Temporary setup port (default: 8899)
#   --local-only                Require an SSH tunnel instead of LAN access
set -euo pipefail

REPOSITORY="MirkoUgoliniDev/employee-scheduling"
ENGINE="postgresql"
WEB_PORT="8899"
WEB_HOST="0.0.0.0"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DOWNLOAD_DIR=""

die() { printf '\n[ERROR] %s\n' "$1" >&2; exit 1; }

while [ $# -gt 0 ]; do
    case "$1" in
        --engine)
            [ $# -ge 2 ] || die "--engine requires postgresql or sqlite."
            ENGINE="$2"; shift 2 ;;
        --web-port)
            [ $# -ge 2 ] || die "--web-port requires a port number."
            WEB_PORT="$2"; shift 2 ;;
        --local-only)
            WEB_HOST="127.0.0.1"; shift ;;
        -h|--help)
            sed -n '2,24p' "$0"; exit 0 ;;
        *) die "Unknown option: $1" ;;
    esac
done

[ "$(id -u)" = "0" ] || die "Run this command with sudo."
case "$ENGINE" in postgresql|sqlite) ;; *) die "Invalid engine: $ENGINE" ;; esac
printf '%s' "$WEB_PORT" | grep -Eq '^[0-9]+$' || die "Invalid web port: $WEB_PORT"
[ -f "$ROOT/setup/wizard.py" ] || die "The setup directory is missing from this package."

DOWNLOAD_DIR="$(mktemp -d -t employee-scheduling-web.XXXXXXXX)"
trap 'rm -rf -- "$DOWNLOAD_DIR"' EXIT INT TERM
ASSET="employee-scheduling-${ENGINE}-runner.jar"
JAR="$DOWNLOAD_DIR/$ASSET"
URL="https://github.com/${REPOSITORY}/releases/latest/download/${ASSET}"

printf 'Downloading the latest %s application package...\n' "$ENGINE"
if command -v curl >/dev/null 2>&1; then
    curl --fail --location --silent --show-error --output "$JAR" "$URL"
elif command -v wget >/dev/null 2>&1; then
    wget --quiet --output-document="$JAR" "$URL"
else
    die "Neither curl nor wget is installed."
fi
[ -s "$JAR" ] || die "The downloaded application package is empty."

python3 "$ROOT/setup/wizard.py" --web --web-host "$WEB_HOST" \
    --web-port "$WEB_PORT" --engine "$ENGINE" --jar "$JAR"
