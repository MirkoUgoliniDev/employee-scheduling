"""Optional step: put the application behind a Caddy HTTPS reverse proxy.

The application itself speaks plain HTTP; on a LAN this is fine, on a server
exposed to the internet it is not (credentials in clear, and the backup admin
API refuses remote plain-HTTP by design). This step installs Caddy, writes the
site block, switches the application to listen on loopback only and restarts
both services — the exact shape used on a cloud host.

TLS mode is derived from the hostname: a name ending in ``.local`` gets an
internal Caddy CA (LAN testing; the client must trust the CA), any other name
requests a Let's Encrypt certificate (the machine must be reachable on ports
80/443 from the internet).
"""

from pathlib import Path

from lib.constants import CADDYFILE, ENV_FILE, SERVICE_NAME
from lib.step_base import Step

#: Cloudsmith's Caddy repository (same source used by setup-caddy.sh).
CADDY_KEY = "/usr/share/keyrings/caddy-stable-archive-keyring.gpg"
CADDY_SOURCES = "/etc/apt/sources.list.d/caddy-stable.list"
CADDY_REPO = ("deb [signed-by=" + CADDY_KEY
              + "] https://dl.cloudsmith.io/public/caddy/stable/deb/"
                "debian any-version main")

#: Where Caddy stores its internal CA, printed when one was generated.
INTERNAL_CA = "/var/lib/caddy/.local/caddy/pki/authorities/local/root.crt"

DEFAULT_HOSTNAME = "employee-scheduling.local"


class ProxySetupStep(Step):
    def __init__(self):
        super().__init__("HTTPS proxy", "Caddy reverse proxy in front of the app", optional=True)

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()
        if not config.get("proxy_enabled"):
            return self.skip("HTTPS proxy not requested")

        hostname = str(config.get("proxy_hostname") or DEFAULT_HOSTNAME).strip().strip(".")
        if not hostname:
            return self.fail("Empty proxy hostname.")
        port = int(config.get("port", 8080))
        tls_internal = hostname.endswith(".local")
        if not tls_internal and "." not in hostname:
            return self.fail(f"Invalid proxy hostname: {hostname}",
                             "Use a name ending in .local for the internal CA, "
                             "or a full public domain for Let's Encrypt.")

        if not runner.dry_run:
            if not self._install_caddy(runner, sysinfo):
                return self.fail("Caddy installation failed.",
                                 "Check the network connection and the configured repositories "
                                 "(details in the log).")

        # Site block: the app listens on loopback only, the proxy terminates TLS.
        site = [f"{hostname} {{"]
        if tls_internal:
            site.append("    tls internal")
        site += [f"    reverse_proxy 127.0.0.1:{port}", "}"]
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

        # Application: loopback only, and the TLS requirement back to its
        # default (the proxy makes the connection loopback, which passes the
        # check without relaxing anything).
        if not runner.dry_run:
            self._harden_env(runner)

        runner.run(["systemctl", "enable", "--now", "caddy"])
        ok, err = runner.run(["systemctl", "restart", "caddy"])
        if not ok:
            return self.fail(f"Caddy failed to restart: {err.strip()}")
        ok, err = runner.run(["systemctl", "restart", SERVICE_NAME])
        if not ok:
            return self.fail(f"{SERVICE_NAME} failed to restart: {err.strip()}",
                             "Check the service with: journalctl -u " + SERVICE_NAME)

        message = f"https://{hostname} → 127.0.0.1:{port}"
        if tls_internal:
            message += f"; trust the Caddy CA on clients: {INTERNAL_CA}"
        return self.done(message)

    # ── Internals ────────────────────────────────────────────────────────────

    def _install_caddy(self, runner, sysinfo) -> bool:
        if sysinfo.package_manager != "apt":
            # Only the Debian/Ubuntu repository is automated; on other systems
            # the user installs Caddy and this step reuses it.
            ok, err = runner.run(["sh", "-c", "command -v caddy"])
            return ok
        ok, err = runner.run(["sh", "-c", "command -v caddy"])
        if ok:
            runner.log("    Caddy already installed")
            return True
        # Cloudsmith's repository: the key and the .list file are written
        # directly (no shell pipes), then apt installs the package.
        ok, err = runner.run(["curl", "-1sLf",
                              "https://dl.cloudsmith.io/public/caddy/stable/gpg.key",
                              "-o", CADDY_KEY])
        if not ok:
            runner.log(f"    [error] downloading the Caddy key: {err.strip()}")
            return False
        try:
            Path(CADDY_SOURCES).write_text(CADDY_REPO + "\n", encoding="utf-8")
        except OSError as exc:
            runner.log(f"    [error] cannot write {CADDY_SOURCES}: {exc}")
            return False
        ok, err = runner.run(["apt-get", "update", "-q", "-o", "DPkg::Lock::Timeout=600"])
        if not ok:
            runner.log(f"    [error] apt-get update failed: {err.strip()}")
            return False
        ok, err = runner.run(["apt-get", "install", "-y", "-q",
                              "-o", "Dpkg::Options::=--force-confold", "caddy"])
        if not ok:
            runner.log(f"    [error] apt-get install caddy failed: {err.strip()}")
            return False
        return True

    def _harden_env(self, runner) -> None:
        try:
            lines = Path(ENV_FILE).read_text(encoding="utf-8").splitlines()
        except OSError as exc:
            runner.log(f"    [warning] cannot read {ENV_FILE}: {exc}")
            return
        lines = [line for line in lines
                 if not line.startswith("BACKUP_ADMIN_REQUIRE_TLS_FOR_REMOTE=")]
        host_line = "QUARKUS_HTTP_HOST=127.0.0.1"
        replaced = False
        for index, line in enumerate(lines):
            if line.startswith("QUARKUS_HTTP_HOST="):
                lines[index] = host_line
                replaced = True
                break
        if not replaced:
            lines.append(host_line)
        try:
            Path(ENV_FILE).write_text("\n".join(lines) + "\n", encoding="utf-8")
        except OSError as exc:
            runner.log(f"    [warning] cannot write {ENV_FILE}: {exc}")
