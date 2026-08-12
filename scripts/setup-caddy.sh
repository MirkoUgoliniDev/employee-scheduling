#!/usr/bin/env bash
# ============================================================================
#  setup-caddy.sh — Put Employee Scheduling behind a TLS reverse proxy (Caddy)
#
#  Mirrors the production (cloud) shape on a LAN machine:
#      [client] -> Caddy HTTPS :443 -> app HTTP 127.0.0.1:8080
#
#  Usage:
#      sudo ./scripts/setup-caddy.sh                       hostname employee-scheduling.local, internal CA
#      sudo ./scripts/setup-caddy.sh --domain app.example.com   Let's Encrypt (cloud: public DNS + ports open)
#
#  Options:
#      --domain NAME   Public hostname: Caddy requests a Let's Encrypt certificate
#                      (requires the machine to be reachable on :80/:443 from the internet).
#                      Without it, an internal CA is generated and the client must trust it.
#      --web-port N    Application port Caddy forwards to (default: 8080)
#      --no-firewall   Skip ufw changes (for hosts with an external firewall/security group)
#
#  What it does:
#    1. installs Caddy (Debian/Ubuntu repository)
#    2. writes /etc/caddy/Caddyfile with tls internal|Let's Encrypt and reverse_proxy
#    3. edits /etc/employee-scheduling.env: removes BACKUP_ADMIN_REQUIRE_TLS_FOR_REMOTE,
#       sets QUARKUS_HTTP_HOST=127.0.0.1 (the app is reachable ONLY through the proxy)
#    4. restarts caddy and employee-scheduling
#    5. ufw: keeps OpenSSH, allows 80/443, denies the application port
#    6. prints the internal CA certificate path when one was generated (client trust step)
# ============================================================================
set -euo pipefail

APP_SERVICE="employee-scheduling"
ENV_FILE="/etc/employee-scheduling.env"
CADDYFILE="/etc/caddy/Caddyfile"
APP_PORT="8080"
HOSTNAME="employee-scheduling.local"
DOMAIN=""
NO_FIREWALL="no"

die() { printf '\n[ERROR] %s\n' "$1" >&2; exit 1; }
info() { printf '  %s\n' "$1"; }
step() { printf '\n\033[1;36m%s\033[0m\n' "$1"; }

while [ $# -gt 0 ]; do
    case "$1" in
        --domain)      [ $# -ge 2 ] || die "--domain requires a hostname."
                       DOMAIN="$2"; shift 2 ;;
        --web-port)    [ $# -ge 2 ] || die "--web-port requires a port number."
                       APP_PORT="$2"; shift 2 ;;
        --no-firewall) NO_FIREWALL="yes"; shift ;;
        -h|--help)     sed -n '2,26p' "$0"; exit 0 ;;
        *)             die "Unknown option: $1" ;;
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

step "[2/5] Writing the Caddyfile"
if [ -n "$DOMAIN" ]; then
    HOSTNAME="$DOMAIN"
    TLS_LINE=""
    info "Certificate: Let's Encrypt for $DOMAIN (ports 80/443 must be reachable from the internet)."
else
    TLS_LINE="    tls internal"
    info "Certificate: internal Caddy CA for $HOSTNAME (LAN test; client must trust the CA)."
fi
cat > "$CADDYFILE" <<EOF
$HOSTNAME {
$TLS_LINE
    reverse_proxy 127.0.0.1:$APP_PORT
}
EOF
caddy validate --config "$CADDYFILE" || die "Invalid Caddyfile."

step "[3/5] App: loopback only, TLS requirement back to default"
sed -i '/^BACKUP_ADMIN_REQUIRE_TLS_FOR_REMOTE=/d' "$ENV_FILE"
if grep -q '^QUARKUS_HTTP_HOST=' "$ENV_FILE"; then
    sed -i 's|^QUARKUS_HTTP_HOST=.*|QUARKUS_HTTP_HOST=127.0.0.1|' "$ENV_FILE"
else
    printf 'QUARKUS_HTTP_HOST=127.0.0.1\n' >> "$ENV_FILE"
fi

step "[4/5] Restarting services"
systemctl enable --now caddy >/dev/null 2>&1 || true
systemctl restart caddy
systemctl restart "$APP_SERVICE"

step "[5/5] Firewall"
if [ "$NO_FIREWALL" = "yes" ]; then
    info "Skipped (--no-firewall): manage the firewall on the host/security group."
else
    apt-get install -y -q ufw >/dev/null 2>&1 || true
    ufw allow OpenSSH >/dev/null
    ufw allow 80,443/tcp >/dev/null
    ufw deny "$APP_PORT"/tcp >/dev/null
    ufw --force enable >/dev/null
    info "OpenSSH kept, 80/443 allowed, $APP_PORT denied."
fi

printf '\n\033[1;32mDone.\033[0m\n'
printf '  Open  https://%s\n' "$HOSTNAME"
if [ -z "$DOMAIN" ]; then
    printf '  Trust the Caddy CA on clients: %s\n' \
        '/var/lib/caddy/.local/caddy/pki/authorities/local/root.crt'
fi
printf '  Backup admin page: token from %s (BACKUP_ADMIN_TOKEN)\n' "$ENV_FILE"
