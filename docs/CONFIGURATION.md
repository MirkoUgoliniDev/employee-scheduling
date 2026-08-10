# Configuration, data and backups

The reference for what can be set, where the data lives, and what happens to it. Written to be
looked things up in rather than read start to finish.

---

## Engines and environment

| Engine | Profile | Migrations |
|---|---|---|
| **SQLite** | default / `sqlite` | Flyway (`db/migration/sqlite/`) plus a legacy bootstrap |
| **PostgreSQL** | `postgresql` | Flyway (`db/migration/postgresql/`) |

Key environment variables:

| Variable | Purpose | Default |
|---|---|---|
| `AUTH_SESSION_KEY` | Session cookie encryption. Must be **more** than 16 characters (32+ recommended); at or below that, sign-in fails with an opaque 500 | development only |
| `QUARKUS_PROFILE` | `sqlite` or `postgresql`. **Build-time**: it selects the Flyway locations baked into the jar, so it must be passed to `mvn`/`quarkus:dev`. Setting it against an already-built jar does not change engine | desktop default |
| `APP_DATABASE_PATH` | SQLite file location (`sqlite` and `legacy-sqlite` profiles only; the default profile hardcodes the path) | `databases/employee_scheduling.db` |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL connection | — |
| `QUARKUS_MAILER_*` | SMTP for passcodes and notifications | mocked in development |
| `BACKUP_ADMIN_TOKEN` | Required by the backup API | — |

> **No database is committed**: `databases/*.db` is git-ignored and created at first startup.
> Only `databases/schema.sql` is tracked. Any database that does get shared must be
> **anonymised** first — fictional names and addresses, no SMTP credentials.

**Where the data lives.** During development, in `databases/`. In the installed Windows
application, in `%LOCALAPPDATA%\EmployeeScheduling`, resolved at startup from
`-Dapp.data.dir=auto` — the `jpackage` configuration file expands `$APPDIR` but not environment
variables, so the path has to be computed at runtime. Alongside it the application creates
`config.properties`, which **takes precedence over the options chosen at packaging time**: whoever
installs the application can correct an occupied port without depending on whoever built it.

> **An applied migration must never be edited.** Flyway records a checksum for every file and
> refuses to start if one changes. A change requires a new version, not an edit to the previous
> one — otherwise every existing installation fails on startup.

---

## Backups and restore

| Engine | Mechanism | Format |
|---|---|---|
| **SQLite** | `VACUUM INTO` and the online backup API | `.db` |
| **PostgreSQL** | `pg_dump -Fc` and `pg_restore --single-transaction` | `.dump` |

Safeguards:

- **Origin validation** — the backup's base name must match the configured database
- **Schema validation** — the DDL is compared against the live schema and an incompatible
  restore is refused rather than attempted
- **Pre-restore snapshot** with automatic rollback
- **PostgreSQL advisory lock** (`pg_try_advisory_lock`) so two instances cannot restore at once
- **Automatic rotation** per tag, with configurable retention
- **Typed outcomes** — `RESTORED`, `REJECTED`, `ROLLED_BACK`, `INCONSISTENT`
- **Restrictive permissions** — owner-only ACL on Windows, mode `0600` on Unix

The detail worth internalising: **a restore is not a file copy**. The backup is staged and
validated, its schema is compared with the live one, and a pre-restore snapshot is taken
first. It then either applies completely or leaves the database untouched — and reports which
of the two happened, as a typed outcome rather than as a message to interpret.

---

## Related

| Document | Contents |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Why configuration sources are ordered the way they are |
| [`PACKAGING-WINDOWS-MSI.md`](PACKAGING-WINDOWS-MSI.md) | The full precedence table, with measured examples |
| [`INSTALLATION-LINUX.md`](INSTALLATION-LINUX.md) | The environment file a Linux service reads |
