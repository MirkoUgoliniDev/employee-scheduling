"""Final verification: does the application actually respond?

Without this step, a broken installation would appear successful because
``systemctl restart`` returns as soon as the process starts — not when the
application is ready, and even if it dies one second later.
"""

import time

from lib.constants import DEFAULT_PORT, SERVICE_NAME, STARTUP_TIMEOUT_SECONDS
from lib.step_base import Step


class VerifyStep(Step):
    def __init__(self):
        super().__init__("Verification", "Waits for the application to respond on its port")

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()

        if runner.dry_run:
            return self.skip("Simulation: no service to query")

        port = int(config.get("port", DEFAULT_PORT))
        deadline = time.time() + STARTUP_TIMEOUT_SECONDS
        runner.log(f"    waiting for port {port} (up to {STARTUP_TIMEOUT_SECONDS}s)")

        while time.time() < deadline:
            # If the service has died, there is no point in waiting: exit at
            # once and show the log, which is the only useful information.
            alive, _ = runner.run(["systemctl", "is-active", "--quiet", SERVICE_NAME])
            if not alive:
                return self._fail_with_journal(runner, "The service stopped during startup.")

            if sysinfo.port_in_use(port):
                elapsed = int(STARTUP_TIMEOUT_SECONDS - (deadline - time.time()))
                return self.done(f"Responds on port {port} after about {elapsed}s")

            time.sleep(2)

        # A timeout while the service is STILL ALIVE is not a failure: on a
        # Raspberry Pi with an SD card, the first startup applies migrations and
        # seeds over four thousand translations, and may simply take longer.
        # Declaring failure printed "fix the problem and restart" for a perfect
        # installation, prompting users to tamper with something that worked.
        runner.log(f"    the service is active but has not responded yet within"
                   f" {STARTUP_TIMEOUT_SECONDS}s")
        runner.log(f"    this is normal on slow hardware: follow startup with"
                   f" 'journalctl -u {SERVICE_NAME} -f'")
        return self.skip(f"Startup still in progress after {STARTUP_TIMEOUT_SECONDS}s "
                         "(the service is active)")

    def _fail_with_journal(self, runner, message: str) -> bool:
        """Attach the journal tail; without it, the error is not actionable."""
        ok, out = runner.run(["journalctl", "-u", SERVICE_NAME, "-n", "30", "--no-pager"],
                             check_output=True)
        if ok and out:
            runner.log("    ── last lines of the service log ──")
            for line in out.splitlines()[-30:]:
                runner.log(f"    {line}")
        return self.fail(message, f"Full log: journalctl -u {SERVICE_NAME} -n 100")
