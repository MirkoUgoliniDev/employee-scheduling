#!/usr/bin/env bash
# ============================================================================
#  setup-caddy.sh — Put Employee Scheduling behind a Caddy reverse proxy
#
#  CLI equivalent of the wizard's "HTTPS proxy" + "Exposure" steps:
#      [client] -> Caddy (HTTP or HTTPS) :80/:443 -> app HTTP 127.0.0.1:8080
#
#  Usage:
#      sudo ./scripts/setup-caddy.sh --mode local                 LAN, plain HTTP, no certificate
#      sudo ./scripts/setup-caddy.sh --mode ddns --ddns-subdomain mioserver --ddns-token TOKEN
#      sudo ./scripts/setup-caddy.sh --mode domain --hostname app.example.com
#
#  Modes:
#      local   LAN without certificate (plain HTTP). Limitations: traffic in
#              the clear, and the backup admin API refuses remote plain HTTP
#              (426) — administer backups from the server or via SSH tunnel.
#      ddns    free duckdns.org subdomain + Let's Encrypt (test behind a home
#              router). Installs the automatic IP updater (token root-only)
#              and reminds to open ports 80/443 on the router.
#      domain  personal domain + Let's Encrypt (DNS must already point to the
#              public IP; ports 80/443 forwarded).
#
#  Options:
#      --mode local|ddns|domain   exposure scenario (default: local)
#      --hostname NAME            hostname for local/domain (default: employee-scheduling.local)
#      --ddns-subdomain NAME      duckdns subdomain (mode ddns)
#      --ddns-token TOKEN         duckdns token (mode ddns; stored root-only)
#      --web-port N               application port Caddy forwards to (default: 8080)
#      --no-firewall              skip ufw changes (external firewall/security group)
# ============================================================================
set -euo pipefail

APP_SERVICE="employee-scheduling"
ENV_FILE="/etc/employee-scheduling.env"
CADDYFILE="/etc/caddy/Caddyfile"
APP_PORT="8080"
MODE="local"
HOSTNAME="employee-scheduling.local"
DDNS_SUBDOMAIN=""
DDNS_TOKEN=""
NO_FIREWALL="no"

DUCKDNS_CONF="/etc/duckdns.conf"
DUCKDNS_UPDATER="/usr/local/sbin/duckdns-update.sh"
DUCKDNS_CRON="/etc/cron.d/duckdns"

die() { printf '\n[ERROR] %s\n' "$1" >&2; exit 1; }
info() { printf '  %s\n' "$1"; }
step() { printf '\n\033[1;36m%s\033[0m\n' "$1"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --mode)          [ $# -ge 2 ] || die "--mode requires local, ddns, or domain."
                         MODE="$2"; shift 2 ;;
        --hostname)      [ $# -ge 2 ] || die "--hostname requires a value."
                         HOSTNAME="$2"; shift 2 ;;
        --ddns-subdomain)[ $# -ge 2 ] || die "--ddns-subdomain requires a value."
                         DDNS_SUBDOMAIN="$2"; shift 2 ;;
        --ddns-token)    [ $# -ge 2 ] || die "--ddns-token requires a value."
                         DDNS_TOKEN="$2"; shift 2 ;;
        --web-port)      [ $# -ge 2 ] || die "--web-port requires a port number."
                         APP_PORT="$2"; shift 2 ;;
        --no-firewall)   NO_FIREWALL="yes"; shift ;;
        -h|--help)       sed -n '2,30p' "$0"; exit 0 ;;
        *)               die "Unknown option: $1" ;;
    esac
done

[ "$(id -u)" = "0" ] || die "Root privileges are required. Run again with: sudo $0 $*"
[ -f "$ENV_FILE" ] || die "Missing $ENV_FILE: install the application first (install-linux.sh or start-web-setup.sh)."
[ -d /etc/caddy ] || mkdir -p /etc/caddy

step "[1/5] Installing Caddy"
if command -v caddy >/dev/null 2>&1; then
    info "Caddy already installed: $(caddy version)"
else
    apt-get update -qq || true
    apt-get install -y -q curl gpg apt-transport-https debian-keyring debian-archive-keyring
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' \
        | gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
    curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' \
        | tee /etc/apt/sources.list.d/caddy-stable.list > /dev/null
    apt-get update -qq
    apt-get install -y -q caddy
fi

step "[2/5] Exposure scenario: $MODE"
case "$MODE" in
    local)
        HOSTNAME="${HOSTNAME:-employee-scheduling.local}"
        SITE_HEADER="http://$HOSTNAME"
        ;;
    ddns)
        [ -n "$DDNS_SUBDOMAIN" ] || die "DDNS mode requires --ddns-subdomain."
        [ -n "$DDNS_TOKEN" ] || die "DDNS mode requires --ddns-token."
        printf '%s' "$DDNS_SUBDOMAIN" | grep -Eq '^[a-z0-9-]+$' \
            || die "The duckdns subdomain may contain lowercase letters, digits, and dashes only."
        printf '%s' "$DDNS_TOKEN" | grep -Eq '^[A-Za-z0-9]{16,}$' \
            || die "The duckdns token looks invalid (long alphanumeric string)."
        HOSTNAME="${DDNS_SUBDOMAIN}.duckdns.org"
        SITE_HEADER="$HOSTNAME"
        ;;
    domain)
        [ -n "$HOSTNAME" ] || die "Domain mode requires --hostname."
        printf '%s' "$HOSTNAME" | grep -q '\.' || die "Use a full public domain, e.g. app.example.com."
        SITE_HEADER="$HOSTNAME"
        ;;
    *) die "Invalid mode: $MODE (local|ddns|domain)" ;;
esac

step "[3/5] Writing the Caddyfile"
cat > "$CADDYFILE" <<EOF
$SITE_HEADER {
    reverse_proxy 127.0.0.1:$APP_PORT
}
EOF
caddy validate --config "$CADDYFILE" || die "Invalid Caddyfile."

if [ "$MODE" = "ddns" ]; then
    step "[3b/5] Installing the duckdns IP updater"
    cat > "$DUCKDNS_CONF" <<EOF
DUCKDNS_DOMAIN=$DDNS_SUBDOMAIN
DUCKDNS_TOKEN=$DDNS_TOKEN
EOF
    chmod 600 "$DUCKDNS_CONF"
    cat > "$DUCKDNS_UPDATER" <<'EOF'
#!/bin/sh
. /etc/duckdns.conf
curl -fsS "https://www.duckdns.org/update?domains=${DUCKDNS_DOMAIN}&token=${DUCKDNS_TOKEN}&ip=" -o /dev/null || true
EOF
    chmod 755 "$DUCKDNS_UPDATER"
    printf '*/5 * * * * root %s >/dev/null 2>&1\n' "$DUCKDNS_UPDATER" > "$DUCKDNS_CRON"
    "$DUCKDNS_UPDATER" && info "First duckdns update done." \
        || info "First update failed; the cron retries every 5 minutes."
fi

step "[4/5] App: loopback only, TLS requirement back to default"
sed -i '/^BACKUP_ADMIN_REQUIRE_TLS_FOR_REMOTE=/d' "$ENV_FILE"
if grep -q '^QUARKUS_HTTP_HOST=' "$ENV_FILE"; then
    sed -i 's|^QUARKUS_HTTP_HOST=.*|QUARKUS_HTTP_HOST=127.0.0.1|' "$ENV_FILE"
else
    printf 'QUARKUS_HTTP_HOST=127.0.0.1\n' >> "$ENV_FILE"
fi

step "[5/5] Restarting services"
systemctl enable --now caddy >/dev/null 2>&1 || true
systemctl restart caddy
systemctl restart "$APP_SERVICE"

if [ "$NO_FIREWALL" != "yes" ]; then
    apt-get install -y -q ufw >/dev/null 2>&1 || true
    ufw allow OpenSSH >/dev/null
    ufw allow 80,443/tcp >/dev/null
    ufw deny "$APP_PORT"/tcp >/dev/null
    ufw --force enable >/dev/null
    info "OpenSSH kept, 80/443 allowed, $APP_PORT denied."
fi

printf '\n\033[1;32mDone.\033[0m\n'
case "$MODE" in
    local)
        printf '  Open  http://%s  (LAN, no certificate)\n' "$HOSTNAME"
        printf '  Backup admin: only from the server itself or via an SSH tunnel (426 otherwise).\n'
        ;;
    ddns)
        printf '  Open  https://%s  (Let'\''s Encrypt)\n' "$HOSTNAME"
        printf '  Router: forward ports 80 and 443 to this host.\n'
        ;;
    domain)
        printf '  Open  https://%s  (Let'\''s Encrypt)\n' "$HOSTNAME"
        printf '  DNS must point to the public IP; forward ports 80 and 443 to this host.\n'
        ;;
esac
printf '  Backup admin page: token from %s (BACKUP_ADMIN_TOKEN)\n' "$ENV_FILE"
