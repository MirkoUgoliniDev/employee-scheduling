"""Java: the application's only mandatory dependency."""

from lib.constants import JAVA_MIN_MAJOR, JAVA_PACKAGES
from lib.step_base import Step


#: Fallback hint when the apt error matches no known cause. Named because the
#: caller recognizes it to substitute a more specific suggestion.
GENERIC_APT_HINT = "Check the network connection and the configured repositories."


def _apt_hint(error: str) -> str:
    """Hint tailored to the actual apt error.

    The generic "check the network" message is almost always wrong: the two
    real causes on a Raspberry Pi are a lock held by apt-daily and dpkg
    interrupted by a dropped SSH session. Restarting the wizard does NOT fix
    the latter; without saying so, users enter a loop of failed attempts.
    """
    lowered = error.lower()
    if "dpkg was interrupted" in lowered or "dpkg --configure" in lowered:
        return ("A previous package installation was left halfway through. "
                "Run this first: sudo dpkg --configure -a")
    if "could not get lock" in lowered or "unable to acquire" in lowered:
        return ("Another program is using apt (usually the automatic update). "
                "Wait a few minutes and run again.")
    return GENERIC_APT_HINT


class JavaStep(Step):
    def __init__(self):
        super().__init__("Java", f"Runtime environment {JAVA_MIN_MAJOR} or later")

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()

        if sysinfo.java_major >= JAVA_MIN_MAJOR:
            return self.skip(f"Java {sysinfo.java_major} already present")

        if sysinfo.package_manager == "apt":
            ok, err = runner.run(["apt-get", "update", "-q", "-o", "DPkg::Lock::Timeout=600"])
            if not ok:
                return self.fail(f"Package list update failed: {err}",
                                 _apt_hint(err))

        # Try 21 first and fall back to 17: Debian bookworm — the basis of
        # Raspberry Pi OS — does not provide 21, so the first attempt normally
        # fails and is not an error to report.
        last_error = ""
        for package in JAVA_PACKAGES:
            runner.log(f"    trying {package}")
            if sysinfo.package_manager == "apt":
                ok, err = runner.run(["apt-get", "install", "-y", "-q", "-o", "DPkg::Lock::Timeout=600",
                                      "-o", "Dpkg::Options::=--force-confold", package])
            else:
                name = package.replace("openjdk-", "java-").replace("-jre-headless", "-openjdk-headless")
                ok, err = runner.run(["dnf", "install", "-y", "-q", name])
            if ok:
                # Installation did not occur in simulation: checking the version
                # here would report a false failure on a machine that simply
                # does not have Java yet — the normal reason for simulating.
                if runner.dry_run:
                    return self.done(f"{package} would be installed")
                # Do not trust only the package manager's result: verify that
                # Java actually responds and determine its version.
                sysinfo.java_major = sysinfo.detect_java_major()
                if sysinfo.java_major >= JAVA_MIN_MAJOR:
                    return self.done(f"Java {sysinfo.java_major} installed")
                last_error = (f"{package} installed but java reports version "
                              f"{sysinfo.java_major or 'unknown'}")
            else:
                last_error = err

        hint = _apt_hint(last_error)
        if hint == GENERIC_APT_HINT:
            hint = f"Install a JRE {JAVA_MIN_MAJOR}+ manually and run the wizard again."
        return self.fail(f"Java installation failed. {last_error}", hint)
