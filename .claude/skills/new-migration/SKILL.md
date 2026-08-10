---
name: new-migration
description: Create a database schema change — new table, column, index, constraint, or a data backfill. Use whenever a JPA entity gains or loses a field, or an entity is added. Every migration must be written twice, once for SQLite and once for PostgreSQL, and this skill covers the parity rules, the dialect differences and the tests that enforce both.
---

# Writing a Flyway migration

Two engines are supported, so **every migration is two files**:

```
src/main/resources/db/migration/sqlite/V9__short_name.sql
src/main/resources/db/migration/postgresql/V9__short_name.sql
```

Same version number, same file name, same resulting schema — different dialect. A
migration present in only one directory is a bug that stays invisible until someone
switches engines.

## 1. Pick the version

Look at what exists and take the next number:

```
ls src/main/resources/db/migration/sqlite/
ls src/main/resources/db/migration/postgresql/
```

Both directories must agree on the highest version before you start.

## 2. Write PostgreSQL first

PostgreSQL is the strict engine. SQLite is dynamically typed and will accept things
PostgreSQL rejects, so a migration authored on SQLite first tends to fail later; the
reverse rarely happens.

Dialect differences that matter here:

| | SQLite | PostgreSQL |
|---|---|---|
| Upsert | `INSERT OR IGNORE` | `INSERT … ON CONFLICT DO NOTHING` |
| Identity | `INTEGER PRIMARY KEY AUTOINCREMENT` | `GENERATED … AS IDENTITY` / `BIGSERIAL` |
| Boolean | 0 / 1 | real `boolean` |
| Alter column | not supported — create new table, copy, drop, rename | `ALTER TABLE … ALTER COLUMN` |
| Timestamps | text | `timestamp` |

Comments in new migrations are written in **English**.

## 3. Update the JPA entity

The entity under `src/main/java/org/acme/employeescheduling/domain/` must match. Flyway
creates the schema; Hibernate does not — a field added to the entity without a
migration fails at runtime, not at compile time.

## 4. Never edit a published migration

Once a versioned migration has shipped, it is immutable — **comments included**. Flyway
puts comments in the checksum, so rewording one stops startup on every existing
database. This is the documented exception to the English-comments rule: already
published migrations keep whatever language they were written in.

To change something released, add a new version.

## 5. Verify on both engines

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
```

`MigrationSchemaParityTest` compares the two schemas.
`DatabasePortabilityContractTest` and `SqliteToPostgresqlPopulationTest` check that data
moves between them. CI runs SQLite on Windows and PostgreSQL on Ubuntu against a real
server (`.github/workflows/database-portability.yml`) — passing only on local SQLite is
not a verification.

## 6. If the change touches personal data

Columns in `employees`, `specialists`, `structures`, `email_log`, `email_settings` or
`app_users` fall under the anonymization rules — the committed demo database will need
the same treatment. See the `anonymize-db` skill.
