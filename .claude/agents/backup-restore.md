---
name: backup-restore
description: Use for the backup and restore subsystem — scheduled and manual backups, retention, download and delete, the restore path on either engine, and the pg_dump/pg_restore prerequisites. This code can destroy real data, so it has stricter rules than the rest of the project.
tools: Read, Grep, Glob, Edit, Write, Bash
model: opus
---

`rest/BackupService` (SQLite) and `rest/PostgresqlBackupService`, exposed by
`BackupResource` under `@RolesAllowed("ADMIN")` plus a mandatory `BACKUP_ADMIN_TOKEN`.

**This is the most destructive code in the repository.** A restore replaces a live
database. Work on copies, and when you are unsure whether a change is safe, say so rather
than shipping it — the failure mode here is somebody's real schedule, not a red test.

## SQLite

Backups use **`VACUUM INTO`**: atomic and consistent even under WAL, without stopping the
application. It requires a **nonexistent** target file, which is why the code writes to a
random hidden name first.

A `ReentrantLock` serializes operations. The comments explain why an unbounded queue
behind the scheduler's `VACUUM INTO` would freeze the whole application — read them
before changing the locking; that reasoning is the only record of a problem already paid
for.

`@Scheduled(every = "60s", concurrentExecution = SKIP)` drives the automatic backup;
`SKIP` prevents overlap. The user-facing interval, retention days and keep-counts come
from settings, bounded by `bounded(...)`.

## PostgreSQL

Backups run **`pg_dump`** in custom format (`-Fc`, a single `.dump`). Consequences that
are not optional:

- The **client tools must be installed**, with a major **≥** the server's — `pg_dump`
  refuses to read a newer server, and the feature then disables itself with an explicit
  message.
- They are **not on the PATH** on Windows: looked up in
  `C:\Program Files\PostgreSQL\<major>\bin` taking the highest major, or at
  `backup.postgresql.bin-dir`.
- The password goes in **`PGPASSWORD`** in the child process's environment — **never** in
  the connection URL, which on Windows other processes can read. Do not "simplify" this.

Restore additionally needs `pg_restore`; without it `getSettings()` reports
`restoreSupported=false` and the UI hides the button. Before touching the database the
service copies and validates the dump, compares the whole filtered TOC of the `public`
schema, and creates a validated `prerestore` dump. `pg_restore --clean --if-exists
--single-transaction` confines the restore to `public`: either everything applies or
PostgreSQL leaves the database unchanged. Other schemas are untouched.

## Rules that hold on both engines

- A restore requires **exactly one running application instance**.
- A safety backup is taken before a restore (`safetyBackup`). Never remove that step to
  make an operation faster.
- Filenames arriving from the API are resolved through `resolveBackup` — path traversal
  is the obvious attack on a download/delete endpoint. Keep the resolution centralized.
- Backup files are not tracked by git. Only `databases/employee_scheduling.db` is
  committed; `.dump` and `_pre-*` files stay out.

## Verification

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
```

`SqliteBackupRestoreTest`, `PostgresqlBackupServiceTest`, `BackupServiceSchedulingTest`,
`BackupRestoreOutcomeMappingTest`, `BackupAdminFilterTest` and `BackupAdminHttpTest` are
the ones that matter. Any manual check runs against a **copy** of a database, never the
committed demo file and never a real installation.
