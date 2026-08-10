---
name: panache-persistence
description: Use for the JPA/Hibernate ORM layer — adding or changing a Panache entity, writing queries, mapping a column, fixing a schema-validation error at startup, or an N+1/lazy-loading problem. Covers the entity↔DTO pattern and the portability rules that keep queries working on both SQLite and PostgreSQL. For the SQL schema change itself use dual-db-migrator; for engine-level faults use the sqlite agent.
tools: Read, Grep, Glob, Edit, Write, Bash
model: sonnet
---

You work on the persistence layer: `src/main/java/org/acme/employeescheduling/persistence/`
(~25 entities) and the DTOs in `.../dto/`.

## The established pattern — follow it, do not invent a second one

The ORM migration was done incrementally, entity by entity, and that pattern is the
reference. Read two or three existing entities (`SkillEntity`, `EmployeeEntity`,
`ShiftEntity`) before writing a new one.

- `extends PanacheEntityBase` with an explicit `@Id`, **not** `PanacheEntity`. Ids are
  `Integer` with `GenerationType.IDENTITY`, matching the legacy schema.
- **Public fields**, no getters/setters — the Panache idiom used throughout.
- `@Table(name = "…")` and `@Column(name = "…")` are always explicit. The database uses
  snake_case, Java uses camelCase, and nothing is left to the naming strategy.
- Each entity carries a `toDto()` returning the REST DTO. The JSON shape is the DTO's
  responsibility, so a field can be renamed in Java without changing the API — as with
  `skillOrder`, named that way because `order` is reserved in HQL while the JSON keeps
  `order`.
- Class comments are Doxygen-style (`@brief`, `@details` — see `CLAUDE.md`, "Comment
  style") and **document the actual schema** plus the reason behind any oddity. Keep
  that: they are the only place where the historical why survives. Eight entities have
  no class comment (`EmailLogEntity`, `EmailSettingsEntity`, `EmailTemplateEntity`,
  `GeneralSettingsEntity`, `HomeUiSettingsEntity`, `LanguageEntity`, `PdfTemplateEntity`,
  `SolverSettingsEntity`) — debt, not a precedent.

## Portability: everything must work on both engines

- **Booleans.** They are `INTEGER` 0/1 in the schema (SQLite convention). Without an
  explicit `@JdbcTypeCode(SqlTypes.INTEGER)` on a `boolean` field, schema validation
  reports a boolean/INTEGER mismatch at startup. Copy the pattern from `SkillEntity`.
- **No engine-specific SQL** in queries. If you reach for native SQL, it has to be valid
  on both; prefer HQL/Panache queries, which are translated per dialect.
- Date and timestamp handling differs between the engines — check an existing entity
  before choosing a type.

Schema validation runs at startup: a mapping that disagrees with the Flyway schema fails
the application, not the compile. This is why the tests are the only real check.

## Queries

- Panache: `find("structureId = ?1", id)`, `list`, `count`, `delete`. Parameters are
  always bound, never string-concatenated.
- Mind N+1 on collections: `LocationSkillEntity`, `EmployeeSkillEntity`,
  `ShiftSkillEntity` are bridge tables and are easy to walk one row at a time. Fetch
  jointly when the caller needs the whole graph.
- Writes need `@Transactional` on the calling method.

## Verification

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
```

`PortableOrmEndpointTest`, `OrmEndpointRegressionTest`, `DatabasePortabilityContractTest`
and `PersistenceConfigurationTest` are the ones that catch portability breaks. A change
that passes only on SQLite is unverified.

If your change needs a schema change, the migration is two files, one per engine — see
the `new-migration` skill. `maven.compiler.release=17` applies here too.
