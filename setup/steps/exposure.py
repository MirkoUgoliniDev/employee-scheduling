"""Optional step: choose how the application is reached (exposure scenario).

Runs AFTER the HTTPS proxy step, which installs Caddy and binds the application
to loopback only. This step writes the Caddy site block for one of three
scenarios and reloads Caddy:

- ``local``   — plain HTTP on the LAN, NO certificate. Limitations, stated on
  purpose: traffic in the clear, and the backup admin API refuses remote plain
  HTTP by design (426) — backup administration works only from the machine
  itself or through an SSH tunnel.
- ``ddns``    — free Dynamic DNS (duckdns.org) + Let's Encrypt, for internet
  testing behind a home router. The step installs the automatic IP updater
  (token kept in a root-only file) and reminds the operator to open ports
  80/443 on the router.
- ``domain``  — a personal domain + Let's Encrypt. The DNS record must already
  point to the public IP and ports 80/443 must be forwarded.
"""

from pathlib import Path

from lib.constants import CADDYFILE
from lib.step_base import Step

#: duckdns updater: one root-only config file, one small script, one cron entry.
DUCKDNS_CONF = Path("/etc/duckdns.conf")
DUCKDNS_UPDATER = Path("/usr/local/sbin/duckdns-update.sh")
DUCKDNS_CRON = Path("/etc/cron.d/duckdns")

DUCKDNS_UPDATER_BODY = """#!/bin/sh
# Automatic duckdns.org IP update, installed by the Employee Scheduling wizard.
. /etc/duckdns.conf
curl -fsS "https://www.duckdns.org/update?domains=${DUCKDNS_DOMAIN}&token=${DUCKDNS_TOKEN}&ip=" \\
    -o /dev/null || true
"""

DEFAULT_LAN_HOSTNAME = "employee-scheduling.local"


class ExposureStep(Step):
    def __init__(self):
        super().__init__("Exposure", "LAN without certificate, free DDNS, or a personal domain",
                         optional=True)

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()
        if not config.get("proxy_enabled"):
            return self.skip("HTTPS proxy step not requested")

        mode = str(config.get("exposure_mode") or "local").strip().lower()
        if mode not in ("local", "ddns", "domain"):
            return self.fail(f"Unknown exposure mode: {mode}",
                             "Allowed values: local, ddns, domain.")

        port = int(config.get("port", 8080))
        if mode == "local":
            hostname = str(config.get("proxy_hostname") or DEFAULT_LAN_HOSTNAME).strip().strip(".")
            if not hostname:
                return self.fail("Empty LAN hostname.")
            site = [f"http://{hostname} {{",
                    f"    reverse_proxy 127.0.0.1:{port}", "}"]
            message = (f"http://{hostname} (LAN, no certificate). Limitations: traffic in the "
                       "clear, and the backup admin page answers 426 from other machines by "
                       "design — administer backups on the server or via an SSH tunnel.")
        elif mode == "ddns":
            subdomain = str(config.get("ddns_subdomain") or "").strip().strip(".")
            token = str(config.get("ddns_token") or "").strip()
            if not subdomain or not token:
                return self.fail("duckdns subdomain and token are required in DDNS mode.",
                                 "Create them free at duckdns.org and pass them to the step.")
            if any(ch not in "abcdefghijklmnopqrstuvwxyz0123456789-" for ch in subdomain):
                return self.fail(f"Invalid duckdns subdomain: {subdomain}",
                                 "Lowercase letters, digits and dashes only.")
            if not token.isalnum() or len(token) < 16:
                return self.fail("The duckdns token looks invalid.",
                                 "It is the long alphanumeric string from duckdns.org.")
            hostname = f"{subdomain}.duckdns.org"
            site = [f"{hostname} {{",
                    f"    reverse_proxy 127.0.0.1:{port}", "}"]
            if not self._install_duckdns_updater(runner, subdomain, token):
                return self.fail("Could not install the duckdns IP updater.",
                                 "Check the log; the site block is not written either.")
            message = (f"https://{hostname} (Let's Encrypt, automatic). Open ports 80/443 on the "
                       "router towards this host and test from outside the LAN.")
        else:  # domain
            hostname = str(config.get("proxy_hostname") or "").strip().strip(".")
            if not hostname or "." not in hostname:
                return self.fail(f"Invalid domain: {hostname}",
                                 "Use a full public domain, for example app.example.com.")
            site = [f"{hostname} {{",
                    f"    reverse_proxy 127.0.0.1:{port}", "}"]
            message = (f"https://{hostname} (Let's Encrypt, automatic). The DNS record must point "
                       "to the public IP and ports 80/443 must be forwarded on the router.")

        if runner.dry_run:
            runner.log(f"    [dry-run] would write {CADDYFILE}:")
            for line in site:
                runner.log("      " + line)
        else:
            try:
                Path(CADDYFILE).write_text("\n".join(site) + "\n", encoding="utf-8")
            except OSError as exc:
                return self.fail(f"Cannot write {CADDYFILE}: {exc}")

        ok, err = runner.run(["caddy", "validate", "--config", str(CADDYFILE)])
        if not ok:
            return self.fail(f"Caddy rejected the configuration: {err.strip()}")
        ok, err = runner.run(["systemctl", "restart", "caddy"])
        if not ok:
            return self.fail(f"Caddy failed to restart: {err.strip()}")

        return self.done(message)

    # ── Internals ────────────────────────────────────────────────────────────

    def _install_duckdns_updater(self, runner, subdomain: str, token: str) -> bool:
        """IP updater: root-only config + cron every 5 minutes + immediate run."""
        conf_lines = [
            f"DUCKDNS_DOMAIN={subdomain}",
            f"DUCKDNS_TOKEN={token}",
        ]
        if runner.dry_run:
            runner.log("    [dry-run] would install the duckdns updater "
                       f"({DUCKDNS_CONF}, {DUCKDNS_UPDATER}, {DUCKDNS_CRON})")
            return True
        try:
            Path(DUCKDNS_CONF).write_text("\n".join(conf_lines) + "\n", encoding="utf-8")
            Path(DUCKDNS_CONF).chmod(0o600)
            Path(DUCKDNS_UPDATER).write_text(DUCKDNS_UPDATER_BODY, encoding="utf-8")
            Path(DUCKDNS_UPDATER).chmod(0o755)
            Path(DUCKDNS_CRON).write_text(
                f"*/5 * * * * root {DUCKDNS_UPDATER} >/dev/null 2>&1\n", encoding="utf-8")
        except OSError as exc:
            runner.log(f"    [error] duckdns updater: {exc}")
            return False
        ok, err = runner.run([str(DUCKDNS_UPDATER)])
        if not ok:
            runner.log(f"    [warning] first duckdns update failed: {err.strip()} "
                       "(the updater retries every 5 minutes)")
        return True
