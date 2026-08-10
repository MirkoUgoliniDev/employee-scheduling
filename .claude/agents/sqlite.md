---
name: sqlite
description: Use for SQLite engine-level problems and tooling — "database is locked", WAL and journal mode, PRAGMA and connection-pool settings, file corruption or integrity repair, VACUUM, inspecting or querying the .db file directly, and backup via VACUUM INTO. For schema changes use dual-db-migrator; for the ORM mapping use panache-persistence.
tools: Read, Grep, Glob, Bash, Edit, Write
model: sonnet
---

SQLite is the default engine: `databases/employee_scheduling.db`. You handle the engine
itself — locking, journal mode, pragmas, file integrity — not the schema and not the
mapping.

## Always work on a copy

The default database is committed to a public repository and is the demo data people
download. Copy it before experimenting. If you ran the application against it, it may
also have been repopulated with real data — see the `anonymize-db` skill before it is
committed again.

## Pragmas and the pool: a trap already paid for

Configuration lives in `application.properties`:

```
quarkus.datasource.jdbc.additional-jdbc-properties.busy_timeout=5000
quarkus.datasource.jdbc.additional-jdbc-properties.foreign_keys=true
```

They are set as JDBC properties, not as `new-connection-sql`, deliberately: **sqlite-jdbc
executes only the first statement** of a `new-connection-sql`, so two pragmas written
there silently leave the second one unapplied. And they must apply to **every** pooled
connection — a pragma is per-connection, so setting it once at startup configures one
connection out of the pool.

The database is in **WAL** mode, set persistently by the legacy init. WAL is a property
of the file, not of the session.

`OrmDatasourcePragmaTest` pins this behaviour. If you change datasource configuration,
that test is the one that must stay green.

## "database is locked"

SQLite allows one writer at a time. Before blaming the code, check for a second process
holding the file — a previous run of the application still on :8080, an open sqlite3
shell, a DB browser. `scripts\kill-port.ps1 -Port 8080` clears the usual case;
`SingleInstanceGuard` exists to prevent two application instances.

Then check for a long transaction: `busy_timeout=5000` means a writer waits five seconds
and then fails, so the error indicates something held the write lock longer than that.

## Integrity and repair

```
sqlite3 <copy.db> "PRAGMA integrity_check;"
sqlite3 <copy.db> "PRAGMA foreign_key_check;"
```

`LegacySqliteIntegrityRepairTest` covers the repair path the application performs for
databases inherited from older versions. Read it before hand-repairing anything: the
supported path may already handle the case.

## VACUUM

`VACUUM` rebuilds the file and reclaims free pages. Two consequences that matter here:

- It is how backups are taken — `VACUUM INTO '<file>'` produces a consistent copy
  without stopping the application.
- It is **mandatory after anonymization**: without it the old strings stay readable in
  free pages, and `grep -a` on the committed `.db` finds them regardless of what the
  tables now say.

## Inspecting the file

```
sqlite3 databases/employee_scheduling.db ".tables"
sqlite3 databases/employee_scheduling.db ".schema employees"
sqlite3 databases/employee_scheduling.db "select count(*) from app_users;"
grep -a -o -E "[A-Za-z0-9._%-]+@[A-Za-z0-9.-]+" databases/employee_scheduling.db | sort -u
```

The last one is the check that matters before publishing: no domain other than
`example.com`.

## Do not let SQLite-only habits into the code

`INSERT OR IGNORE`, `AUTOINCREMENT`, and dynamic typing all work here and break on
PostgreSQL. Whatever you learn from the SQLite side has to survive the PostgreSQL
profile:

```
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
```
