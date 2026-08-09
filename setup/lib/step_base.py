"""Common contract for all installation steps.

Each step is a class that does one thing and reports whether it succeeded. The
benefit is not cosmetic: an installation split into steps can resume where it
stopped and can be simulated; when it fails, the failed component is known
instead of being buried in an error at the end of two hundred lines.
"""

from enum import Enum, auto
from typing import Callable, Optional


class Status(Enum):
    PENDING = auto()
    RUNNING = auto()
    DONE = auto()
    FAILED = auto()
    SKIPPED = auto()


#: Icons used by both text and web modes, so the two interfaces report the
#: same information using the same symbols.
STEP_ICONS = {
    Status.PENDING: "○",
    Status.RUNNING: "◎",
    Status.DONE: "✓",
    Status.FAILED: "✗",
    Status.SKIPPED: "⊘",
}


class Step:
    """An installation step.

    Subclasses implement :meth:`execute` and return ``True`` when the step
    succeeds. To mark a step as not applicable (for example PostgreSQL when
    SQLite was selected), call :meth:`skip` and return ``True``: skipped does
    not mean failed.
    """

    def __init__(self, name: str, description: str, optional: bool = False):
        self.name = name
        self.description = description
        self.optional = optional
        self.status = Status.PENDING
        self.message = ""
        self._callback: Optional[Callable[["Step", Status], None]] = None

    # ── Status ───────────────────────────────────────────────────────────────
    def set_callback(self, cb: Callable[["Step", Status], None]) -> None:
        self._callback = cb

    def _set_status(self, status: Status) -> None:
        self.status = status
        if self._callback:
            self._callback(self, status)

    def start(self) -> None:
        self._set_status(Status.RUNNING)

    def done(self, message: str = "") -> bool:
        self.message = message
        self._set_status(Status.DONE)
        return True

    def skip(self, reason: str) -> bool:
        self.message = reason
        self._set_status(Status.SKIPPED)
        return True

    def fail(self, message: str, hint: str = "") -> bool:
        # The hint is part of the message rather than a separate field: wherever
        # the error is shown, the remedy accompanies it. An error that only says
        # "failed" forces the user to guess.
        self.message = message if not hint else f"{message}\n  → {hint}"
        self._set_status(Status.FAILED)
        return False

    # ── To be implemented ────────────────────────────────────────────────────
    def execute(self, runner, sysinfo, config: dict) -> bool:
        raise NotImplementedError

    def __repr__(self) -> str:
        return f"<Step {self.name} {self.status.name}>"
