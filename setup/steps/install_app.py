"""Install the application package in /opt."""

import zipfile
from pathlib import Path

from lib.constants import INSTALL_DIR
from lib.step_base import Step

#: Quarkus-generated class listing properties fixed at build time.
_BUILD_TIME_CLASS = "io/quarkus/runtime/generated/BuildTimeRunTimeFixedConfigSourceBuilder.class"


#: The JAR was built without -Dquarkus.profile: it works with neither engine and
#: is the most likely case because it is produced by a careless "mvn package".
NO_PROFILE = "senza-profilo"


def baked_engine(jar: Path):
    """Data engine baked into the package.

    Return ``"postgresql"``, ``"sqlite"``, :data:`NO_PROFILE`, or ``None`` if
    the package cannot be inspected.

    Quarkus fixes ``quarkus.datasource.db-kind`` and
    ``quarkus.flyway.locations`` when the JAR is BUILT; an environment variable
    cannot change them.

    The no-profile case must be recognized separately and is the most subtle of
    the three. With PostgreSQL, the service starts and dies with "Driver does not
    support the provided URL." With SQLite it reports no error: the default
    profile has ``quarkus.flyway.active=false``, so a fresh installation does
    not run migrations or create tables. The failure appears much later as
    inexplicable application behavior. It is identified by the generic
    ``db/migration`` path in the class without either engine suffix.
    """
    try:
        with zipfile.ZipFile(jar) as archive:
            blob = archive.read(_BUILD_TIME_CLASS)
    except (KeyError, OSError, zipfile.BadZipFile):
        return None
    if b"db/migration/postgresql" in blob:
        return "postgresql"
    if b"db/migration/sqlite" in blob:
        return "sqlite"
    if b"db/migration" in blob:
        return NO_PROFILE
    return None


class InstallAppStep(Step):
    def __init__(self):
        super().__init__("Applicazione", "Copia del pacchetto in /opt")

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()

        jar = config.get("jar")
        if not jar:
            return self.fail("Nessun pacchetto da installare.",
                             "Indica il jar con --jar percorso/al/employee-scheduling-runner.jar")

        source = Path(jar).expanduser()
        if not source.is_file():
            return self.fail(f"Pacchetto non trovato: {source}",
                             "Compilalo sul PC e copialo qui con scp.")
        if source.suffix != ".jar":
            return self.fail(f"Il file indicato non e' un jar: {source.name}")

        engine = config.get("engine", "postgresql")
        baked = baked_engine(source)
        if baked is None:
            runner.log("    [attenzione] motore del pacchetto non verificabile:"
                       f" assicurati che sia compilato con -Dquarkus.profile={engine}")
        elif baked != engine:
            return self.fail(
                f"Il pacchetto e' compilato per '{baked}' ma stai installando con motore '{engine}'.",
                "Ricompilalo: mvn package -DskipTests "
                f"-Dquarkus.package.jar.type=uber-jar -Dquarkus.profile={engine}")
        else:
            runner.log(f"    motore del pacchetto verificato: {baked}")

        ok, err = runner.mkdir(INSTALL_DIR, mode=0o755)
        if not ok:
            return self.fail(err)

        # Copy FIRST, THEN remove the previous package. The reverse order left
        # the machine without the application if copying failed — full disk or
        # source on an unmounted flash drive — and a failed update is precisely
        # when rollback must remain possible.
        # The JAR remains owned by root and read-only: the service runs as a
        # different user and must not be able to rewrite its own executable.
        ok, err = runner.copy(source, INSTALL_DIR / source.name, mode=0o644)
        if not ok:
            return self.fail(err)

        # Now that the new package exists, remove older differently named ones:
        # two packages in the directory are confusing, and the unit names one.
        if not runner.dry_run:
            for old in INSTALL_DIR.glob("*runner.jar"):
                if old.name != source.name:
                    try:
                        old.unlink()
                        runner.log(f"    rimosso il pacchetto precedente {old.name}")
                    except OSError as exc:
                        runner.log(f"    [attenzione] {old.name} non rimosso: {exc}")

        # Ship the uninstaller with the application. The summary used to show
        # "./scripts/uninstall-linux.sh," a repository-relative path that the
        # wizard installed nowhere. Anyone cleaning their home directory was
        # left with the service, system user, database, and configuration but no
        # tool to remove them.
        uninstaller = Path(__file__).resolve().parents[2] / "scripts" / "uninstall-linux.sh"
        if uninstaller.is_file():
            ok, err = runner.copy(uninstaller, INSTALL_DIR / uninstaller.name, mode=0o755)
            if ok:
                config["uninstaller"] = str(INSTALL_DIR / uninstaller.name)
            else:
                runner.log(f"    [attenzione] disinstallatore non copiato: {err}")
        else:
            runner.log("    [attenzione] scripts/uninstall-linux.sh non trovato:"
                       " la disinstallazione restera' possibile solo dal repository")

        config["jar_name"] = source.name
        size_mb = source.stat().st_size // (1024 * 1024) if source.exists() else 0
        return self.done(f"{source.name} ({size_mb} MB)")
