"""PostgreSQL: installation, role, database, and a real connection test."""

import os
import secrets
import string

from lib.constants import DB_NAME, DB_USER, ENV_FILE
from lib.step_base import Step


def _existing_password():
    """Password currently in use, read from configuration; None if absent."""
    try:
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            if line.startswith("DATABASE_PASSWORD="):
                return line.split("=", 1)[1].strip().strip('"') or None
    except OSError:
        pass
    return None


def _generate_password(length: int = 32) -> str:
    """Password containing only letters and digits.

    This is not laziness: the password appears in a JDBC URL, an environment file
    read by systemd, and a psql command line. Each location requires different
    escaping for special characters; one mistake prevents the application from
    connecting and produces an incomprehensible error. Thirty-two alphanumeric
    characters still provide more entropy than needed here.
    """
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


class DatabaseStep(Step):
    def __init__(self):
        super().__init__("Database", "PostgreSQL, application role, and database")

    def execute(self, runner, sysinfo, config: dict) -> bool:
        self.start()

        if config.get("engine") != "postgresql":
            return self.skip("SQLite engine: no database service to install")

        # ── Installation ─────────────────────────────────────────────────────
        if sysinfo.command_exists("psql"):
            runner.log("    PostgreSQL already present")
        else:
            runner.log("    installing PostgreSQL")
            if sysinfo.package_manager == "apt":
                runner.run(["apt-get", "update", "-q", "-o", "DPkg::Lock::Timeout=600"])
                ok, err = runner.run(["apt-get", "install", "-y", "-q", "-o", "DPkg::Lock::Timeout=600",
                                      "-o", "Dpkg::Options::=--force-confold",
                                      "postgresql", "postgresql-client"])
            else:
                ok, err = runner.run(["dnf", "install", "-y", "-q",
                                      "postgresql-server", "postgresql"])
                if ok:
                    runner.run(["postgresql-setup", "--initdb"])
            if not ok:
                return self.fail(f"PostgreSQL installation failed: {err}")

        ok, err = runner.run(["systemctl", "enable", "--now", "postgresql"])
        if not ok:
            return self.fail(f"PostgreSQL does not start: {err}",
                             "Diagnostics: journalctl -u postgresql -n 50")

        # On Debian and Raspberry Pi OS, "postgresql.service" is NOT the daemon;
        # it is a Type=oneshot meta-unit with ExecStart=/bin/true. The actual
        # server is postgresql@15-main. Thus the command above succeeds even with
        # no active clusters, and "systemctl status postgresql" is green. Query
        # the server rather than the unit.
        if not runner.dry_run:
            ready, detail = runner.run(["pg_isready", "-q"]) if sysinfo.command_exists("pg_isready") \
                else runner.run(["psql", "-tAc", "SELECT 1"], user="postgres", check_output=True)
            if not ready:
                return self.fail(
                    "PostgreSQL appears started but the server does not respond.",
                    "On Debian the real service is the cluster: check it with "
                    "'pg_lsclusters' and start it with 'pg_ctlcluster <version> main start'. "
                    "If a package installation was left halfway: sudo dpkg --configure -a")

        # PostgreSQL backup uses pg_dump and restore uses pg_restore. Without
        # them the application still starts but disables those functions; report
        # it now rather than discovering it during the first backup.
        for tool in ("pg_dump", "pg_restore"):
            if not sysinfo.command_exists(tool):
                runner.log(f"    [warning] {tool} missing: backup and restore"
                           " will stay disabled.")

        if runner.dry_run:
            # Do NOT put a placeholder password in config. In web mode both
            # buttons drive the same long-lived config dict, so a simulation
            # followed by a real installation would find the sentinel already
            # there, and the line below ("reuse the current password") would
            # accept it: the production role would end up with a hardcoded,
            # publicly documented password, silently and with everything green.
            config["db_url"] = f"jdbc:postgresql://localhost:5432/{DB_NAME}"
            return self.done("Simulation: no change to the database")

        # Reuse the current password instead of generating one on every run.
        # Rotation seemed harmless, but step order is database → application →
        # configuration. If the application step fails (a wrong JAR path is the
        # most common case), PostgreSQL already has the new password while the
        # configuration still has the old one. The running service survives
        # while pooled connections remain open, then fails after restart: a
        # "package not found" error would have broken a healthy installation.
        password = config.get("db_password") or _existing_password() or _generate_password()

        # ── Role ─────────────────────────────────────────────────────────────
        ok, out = runner.run(
            ["psql", "-tAc", f"SELECT 1 FROM pg_roles WHERE rolname='{DB_USER}'"],
            user="postgres", check_output=True)
        if not ok:
            return self.fail(f"Querying PostgreSQL failed: {out}",
                             "Check that the postgresql service is active.")

        # The role may exist from a previous installation: update its password
        # instead of failing so the wizard remains rerunnable. Send the statement
        # through standard input, not an argument: any local user can read a
        # process command line in /proc/<pid>/cmdline, and -c would expose the
        # password in plain text. The project already applies this rule to pg_dump.
        verb = "ALTER" if out.strip() == "1" else "CREATE"
        ok, err = runner.run(
            ["psql", "-q", "-v", "ON_ERROR_STOP=1", "-f", "-"], user="postgres",
            stdin_text=f"{verb} ROLE {DB_USER} WITH LOGIN PASSWORD '{password}';\n",
            secret=True)
        if not ok:
            return self.fail(f"Role configuration failed: {err}")
        runner.log(f"    role {DB_USER}: {'updated' if verb == 'ALTER' else 'created'}")

        # ── Database ─────────────────────────────────────────────────────────
        ok, out = runner.run(
            ["psql", "-tAc", f"SELECT 1 FROM pg_database WHERE datname='{DB_NAME}'"],
            user="postgres", check_output=True)
        if not ok:
            return self.fail(f"Querying the databases failed: {out}")

        if out.strip() == "1":
            runner.log(f"    database {DB_NAME} already present: data is left untouched")
        else:
            ok, err = runner.run(["createdb", "-O", DB_USER, DB_NAME], user="postgres")
            if not ok:
                return self.fail(f"Database creation failed: {err}")
            runner.log(f"    database {DB_NAME} created")

        # Since PostgreSQL 15, roles that do not own the public schema cannot
        # write to it. Without this line, Flyway's first migration fails with a
        # permissions error that looks like a credentials problem but is not.
        ok, err = runner.run(["psql", "-q", "-v", "ON_ERROR_STOP=1", "-d", DB_NAME, "-c",
                              f"ALTER SCHEMA public OWNER TO {DB_USER}"], user="postgres")
        if not ok:
            # Not fatal — if we created the database, the role already owns it —
            # but report it: on a pre-existing postgres-owned database this is the
            # only warning before Flyway fails with "permission denied for schema public".
            runner.log(f"    [warning] ownership of schema public not assigned: {err}")

        # ── Connection test ──────────────────────────────────────────────────
        # Creating the role and database is insufficient: if pg_hba rejects TCP
        # access, the service enters a restart loop with the cause buried in the journal.
        env_probe = os.environ.copy()
        env_probe["PGPASSWORD"] = password
        try:
            import subprocess
            probe = subprocess.run(
                ["psql", "-h", "localhost", "-U", DB_USER, "-d", DB_NAME, "-tAc", "SELECT 1"],
                capture_output=True, text=True, timeout=30, env=env_probe)
            connected = probe.returncode == 0
            detail = (probe.stderr or "").strip()
        except Exception as exc:  # noqa: BLE001
            connected, detail = False, str(exc)

        if not connected:
            return self.fail(f"Connection refused with the credentials just created: {detail}",
                             "pg_hba.conf needs: host all all 127.0.0.1/32 scram-sha-256")

        config["db_password"] = password
        config["db_url"] = f"jdbc:postgresql://localhost:5432/{DB_NAME}"
        return self.done("Database ready and connection verified")
