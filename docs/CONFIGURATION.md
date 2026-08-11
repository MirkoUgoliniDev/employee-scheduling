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
| `AUTH_SESSION_KEY` | Session cookie encryption. Must not be **shorter than 16 characters** (32+ recommended); below that, sign-in fails with an opaque 500 that never mentions the key | development only |
| `APP_DATA_DIR` | Data directory: database, backups, settings, log. Equivalent to `-Dapp.data.dir`, which wins if both are set. The uninstaller reads it to find a non-default directory | platform-dependent |
| `APP_REGISTRATION_MODE` | `standalone`, `server`, or `auto`. `auto` derives it from the engine — SQLite → standalone, PostgreSQL → server — and is what the installers leave in place. In standalone the OTP endpoints answer `OTP_NOT_REQUIRED` | `auto` |
| `APP_DEMO_DATA` | Loads the portable sample dataset at first startup. Idempotent, creates no users | `false` |
| `QUARKUS_HTTP_PORT` | Application port | `8080` |
| `BACKUP_ADMIN_REQUIRE_TLS_FOR_REMOTE` | When `false`, the backup API answers non-`localhost` callers over plain HTTP instead of 426. Only on a network you trust | `true` |
| `QUARKUS_PROFILE` | `sqlite` or `postgresql`. **Build-time**: it selects the Flyway locations baked into the jar, so it must be passed to `mvn`/`quarkus:dev`. Setting it against an already-built jar does not change engine | desktop default |
| `APP_DATABASE_PATH` | SQLite file location (`sqlite` and `legacy-sqlite` profiles only; the default profile hardcodes the path) | `databases/employee_scheduling.db` |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL connection | — |
| `QUARKUS_MAILER_*` | SMTP for passcodes and notifications | mocked in development |
| `BACKUP_ADMIN_TOKEN` | Required by the backup API | — |

> **One database is committed, on purpose**: `databases/employee_scheduling.db`, the published
> demo dataset — fictional names and addresses, no SMTP credentials, `app_users` empty so the
> first startup can create the administrator. `.gitignore` excludes `databases/*.db` and then
> re-admits that one file. Every other `.db` stays out.
>
> The file is rewritten at runtime, so **before committing it again after working on it**, run
> the anonymisation checklist in `CLAUDE.md`: your own data may have grown into it.

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

### On PostgreSQL the client tools are an operational prerequisite, not an option

`pg_dump` must be installed and its major version must be **greater than or equal to the
server's** — it refuses to read a server newer than itself. `pg_restore` must sit beside it for
restore to be offered at all; without it `getSettings()` reports `restoreSupported=false` and the
UI hides the button.

**This is not only about backups.** Three operations take a safety snapshot before they run,
because each one rewrites shifts in bulk and there would otherwise be nothing to go back to:

- applying a saved template to a window
- applying a template to a window
- **saving shift assignments** — the result of a solve

Without a usable `pg_dump` the snapshot cannot be taken, and those three operations are
**refused**, not silently degraded. An installation missing the client tools therefore cannot
schedule shifts at all. The refusal is `503` with a specific code so the interface can tell the
two situations apart:

| Code | Meaning | What to do |
|---|---|---|
| `SAFETY_BACKUP_CLIENT_TOOLS_MISSING` | `pg_dump` absent, or older than the server | install or upgrade the client tools — retrying changes nothing |
| `SAFETY_BACKUP_BUSY` | another backup holds the lock, usually the scheduled one | retry shortly; nothing was modified |
| `SAFETY_BACKUP_FAILED` | the dump was attempted and failed | check the server log: disk, permissions, database |

On Windows the binaries are **not on the PATH**: they are looked up in
`C:\Program Files\PostgreSQL\<major>\bin`, taking the highest major, or wherever
`backup.postgresql.bin-dir` points. On Debian and Ubuntu they come from `postgresql-client`.

SQLite is unaffected: there the safety snapshot is a `VACUUM INTO`, with no external tool
involved.

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
first. It then reports a typed outcome: `RESTORED`, `REJECTED` (nothing was written),
`ROLLED_BACK` (the previous state was recovered) or — if promotion *and* rollback both fail —
`INCONSISTENT`, which names a recovery file for manual repair. Three of the four are ordinary
answers, not errors.

And the other direction: **do not "back up" by copying the `.db` file by hand while the
application is running**. Without its `-wal` and `-shm` companions the copy is not consistent,
and the transactions still in the WAL are silently missing when you restore it months later.
That is exactly why the panel uses `VACUUM INTO`.

### Backups made before an application update become unrecoverable on purpose

The schema comparison includes the **full Flyway migration history**
(`flyway_schema_history` on PostgreSQL; the equivalent schema fingerprint on SQLite). After
every release that adds a migration, every backup created before the update is refused as
`INCOMPATIBLE_DATABASE` — exactly when a restore is needed most.

This is a deliberate **fail-closed** choice, not a bug and not file corruption: restoring an
old schema under the new application would leave the database structurally inconsistent. The
refusal message says so explicitly ("backup made before a schema migration — update the
application and try again, or use a more recent backup"), so it does not read as a corrupted
file.

Practical consequence: **make a fresh backup right after each update** (or keep at least one
post-update automatic backup). The rotation protects the newest backup per tag, but an
upgrade followed by weeks of operation can rotate it away before it is needed.

---

## Related

| Document | Contents |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Why configuration sources are ordered the way they are |
| [`PACKAGING-WINDOWS-MSI.md`](PACKAGING-WINDOWS-MSI.md) | The full precedence table, with measured examples |
| [`INSTALLATION-LINUX.md`](INSTALLATION-LINUX.md) | The environment file a Linux service reads |
