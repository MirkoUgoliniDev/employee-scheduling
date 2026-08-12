"""Optional step: configure the firewall (ufw).

A server exposed to the internet should accept only SSH and the proxy ports;
the application port must stay closed so nobody reaches the app bypassing the
proxy (and the backup admin API over plain HTTP). This step keeps it simple:
keep SSH, allow 80/443, deny the application port. On hosts with an external
firewall or security group the step can be left disabled.
"""

from lib.constants import SERVICE_NAME
from lib.step_base import Step


class FirewallStep(Step):
    def __init__(self):
        super().__init__("Firewall", "ufw: keep SSH, allow 80/443, close the app port", optional=True)

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()
        if not config.get("firewall_enabled"):
            return self.skip("Firewall not requested")

        port = int(config.get("port", 8080))
        commands = [
            ["apt-get", "install", "-y", "-q", "-o", "DPkg::Lock::Timeout=600", "ufw"],
            # SSH first: enabling ufw over a remote connection must never lock
            # the administrator out of the machine.
            ["ufw", "allow", "OpenSSH"],
            ["ufw", "allow", "80,443/tcp"],
            ["ufw", "deny", f"{port}/tcp"],
            ["ufw", "--force", "enable"],
        ]
        for command in commands:
            ok, err = runner.run(command)
            if not ok:
                return self.fail(f"Firewall command failed: {' '.join(command)}",
                                 f"{err.strip()}")

        message = f"OpenSSH kept, 80/443 allowed, {port} closed"
        if port == 8080:
            message += " (the app is reachable only through the proxy)"
        return self.done(message)
