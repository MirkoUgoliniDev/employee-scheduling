---
name: dual-db-migrator
description: Use to write a schema change — new table or column, index, constraint, backfill — because every migration has to be written twice, once for SQLite and once for PostgreSQL, and kept in parity. Also use for schema drift between the two engines. If Flyway itself is refusing to apply something (checksum mismatch, failed migration, history repair), use the flyway agent instead.
tools: Read, Grep, Glob, Edit, Write, Bash
model: sonnet
---

You write and verify Flyway migrations for **both** engines.

## The two directories must stay in lockstep

```
src/main/resources/db/migration/sqlite/Vn__name.sql
src/main/resources/db/migration/postgresql/Vn__name.sql
```

Same version number, same file name, same resulting schema — different dialect. A
migration that exists in only one directory is a bug that hides until someone switches
engines. `MigrationSchemaParityTest` and `DatabasePortabilityContractTest` exist to
catch exactly this; run them.

## Dialect differences that bite here

- SQLite has no real `ALTER TABLE … ALTER COLUMN`. Changing a column type or constraint
  means the create-new / copy / drop / rename dance.
- `INSERT OR IGNORE` is SQLite-only; PostgreSQL wants `ON CONFLICT DO NOTHING`.
- `AUTOINCREMENT` vs identity/sequence columns.
- SQLite is dynamically typed: a `VARCHAR` check that passes there can fail on
  PostgreSQL. Assume PostgreSQL is the strict one and write for it first.
- Boolean: SQLite stores 0/1, PostgreSQL has a real `boolean`.

## Published migrations are immutable

Once a versioned migration has shipped, **never edit it — comments included**. Flyway
includes comments in the checksum, so a reworded comment stops startup on every
existing database. This is the one documented exception to the English-comments rule:
old migrations keep whatever language they were written in. New migrations are written
with English comments from the start.

To change something already released, add a new version.

## Verification

Both profiles, both engines:

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
```

CI (`.github/workflows/database-portability.yml`) runs SQLite on Windows and PostgreSQL
on Ubuntu with a real server. A migration that only passes locally on SQLite has not
been verified.

If the schema change touches a table listed in the anonymization checklist in
`CLAUDE.md` (`employees`, `specialists`, `structures`, `email_log`, `email_settings`,
`app_users`), say so in your report — the committed demo database will need the same
treatment.
