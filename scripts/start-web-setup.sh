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
#   --refresh                   Download the package again even if cached
set -euo pipefail

REPOSITORY="MirkoUgoliniDev/employee-scheduling"
ENGINE="postgresql"
WEB_PORT="8899"
WEB_HOST="0.0.0.0"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="/var/cache/employee-scheduling-installer"
REFRESH="no"

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
        --refresh)
            REFRESH="yes"; shift ;;
        -h|--help)
            sed -n '2,24p' "$0"; exit 0 ;;
        *) die "Unknown option: $1" ;;
    esac
done

[ "$(id -u)" = "0" ] || die "Run this command with sudo."
case "$ENGINE" in postgresql|sqlite) ;; *) die "Invalid engine: $ENGINE" ;; esac
printf '%s' "$WEB_PORT" | grep -Eq '^[0-9]+$' || die "Invalid web port: $WEB_PORT"
[ -f "$ROOT/setup/wizard.py" ] || die "The setup directory is missing from this package."

ASSET="employee-scheduling-${ENGINE}-runner.jar"
URL="https://github.com/${REPOSITORY}/releases/latest/download/${ASSET}"
PACKAGE_VERSION="latest"
if [ -f "$ROOT/release-version.txt" ]; then
    CANDIDATE_VERSION="$(tr -d '\r\n' < "$ROOT/release-version.txt")"
    if printf '%s' "$CANDIDATE_VERSION" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+$'; then
        PACKAGE_VERSION="$CANDIDATE_VERSION"
    fi
fi
install -d -m 755 -o root -g root "$CACHE_DIR"
JAR="$CACHE_DIR/${PACKAGE_VERSION}-${ASSET}"
TEMP_JAR="${JAR}.part.$$"
trap 'rm -f -- "$TEMP_JAR"' EXIT INT TERM

if [ "$REFRESH" != "yes" ] && [ -s "$JAR" ]; then
    printf 'Using cached %s application package (%s).\n' "$ENGINE" "$PACKAGE_VERSION"
else
    printf 'Downloading the %s application package (%s)...\n' "$ENGINE" "$PACKAGE_VERSION"
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --silent --show-error --output "$TEMP_JAR" "$URL"
    elif command -v wget >/dev/null 2>&1; then
        wget --quiet --output-document="$TEMP_JAR" "$URL"
    else
        die "Neither curl nor wget is installed."
    fi
    [ -s "$TEMP_JAR" ] || die "The downloaded application package is empty."
    chmod 644 "$TEMP_JAR"
    mv -f -- "$TEMP_JAR" "$JAR"
fi
[ -s "$JAR" ] || die "The downloaded application package is empty."

python3 "$ROOT/setup/wizard.py" --web --web-host "$WEB_HOST" \
    --web-port "$WEB_PORT" --engine "$ENGINE" --jar "$JAR"
