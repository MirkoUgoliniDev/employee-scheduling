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
        super().__init__("Verifica", "Attende che l'applicazione risponda sulla porta")

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()

        if runner.dry_run:
            return self.skip("Simulazione: nessun servizio da interrogare")

        port = int(config.get("port", DEFAULT_PORT))
        deadline = time.time() + STARTUP_TIMEOUT_SECONDS
        runner.log(f"    attesa della porta {port} (fino a {STARTUP_TIMEOUT_SECONDS}s)")

        while time.time() < deadline:
            # If the service has died, there is no point in waiting: exit at
            # once and show the log, which is the only useful information.
            alive, _ = runner.run(["systemctl", "is-active", "--quiet", SERVICE_NAME])
            if not alive:
                return self._fail_with_journal(runner, "Il servizio si e' fermato durante l'avvio.")

            if sysinfo.port_in_use(port):
                elapsed = int(STARTUP_TIMEOUT_SECONDS - (deadline - time.time()))
                return self.done(f"Risponde sulla porta {port} dopo circa {elapsed}s")

            time.sleep(2)

        # A timeout while the service is STILL ALIVE is not a failure: on a
        # Raspberry Pi with an SD card, the first startup applies migrations and
        # seeds over four thousand translations, and may simply take longer.
        # Declaring failure printed "fix the problem and restart" for a perfect
        # installation, prompting users to tamper with something that worked.
        runner.log(f"    il servizio e' attivo ma non ha ancora risposto entro"
                   f" {STARTUP_TIMEOUT_SECONDS}s")
        runner.log(f"    su hardware lento e' normale: segui l'avvio con"
                   f" 'journalctl -u {SERVICE_NAME} -f'")
        return self.skip(f"Avvio ancora in corso dopo {STARTUP_TIMEOUT_SECONDS}s "
                         "(il servizio e' attivo)")

    def _fail_with_journal(self, runner, message: str) -> bool:
        """Attach the journal tail; without it, the error is not actionable."""
        ok, out = runner.run(["journalctl", "-u", SERVICE_NAME, "-n", "30", "--no-pager"],
                             check_output=True)
        if ok and out:
            runner.log("    ── ultime righe del registro del servizio ──")
            for line in out.splitlines()[-30:]:
                runner.log(f"    {line}")
        return self.fail(message, f"Registro completo: journalctl -u {SERVICE_NAME} -n 100")
