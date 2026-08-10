---
name: add-entity
description: Add a new persisted concept end to end — database table, JPA entity, DTO, REST resource, frontend API module and tests. Use when adding something the application must store and manage, not just a column on an existing table. This is the workflow that crosses every layer, and the one where a step gets forgotten.
---

# Adding a new entity, end to end

Seven steps. Skipping one does not fail the build — it fails later, on the other
database engine or in another language.

## 1. Migration, twice

```
src/main/resources/db/migration/postgresql/Vn__add_thing.sql
src/main/resources/db/migration/sqlite/Vn__add_thing.sql
```

Same version, same file name, same resulting schema. Write PostgreSQL first — it is the
strict engine. Details and the dialect table: the `new-migration` skill.

Booleans are `INTEGER` 0/1, following the existing schema convention.

## 2. Entity

`src/main/java/org/acme/employeescheduling/persistence/ThingEntity.java`, copying the
established pattern (read `SkillEntity` first):

- `extends PanacheEntityBase`, explicit `@Id`, `Integer` + `GenerationType.IDENTITY`
- public fields, no getters/setters
- explicit `@Table(name)` and `@Column(name)` — snake_case in the DB, camelCase in Java
- `@JdbcTypeCode(SqlTypes.INTEGER)` on every `boolean`, or schema validation fails at
  startup with a boolean/INTEGER mismatch
- a Doxygen class comment documenting the real schema and the reason for any oddity
- a `toDto()` method

Watch for HQL reserved words in field names — `order` is why `SkillEntity` has
`skillOrder`.

## 3. DTO

`dto/Thing.java`. This is the JSON contract; the entity is not. Keeping them separate is
what allows a Java rename without an API change.

## 4. REST resource

`rest/ThingResource.java`:

- `@Path`, `@Produces`/`@Consumes` JSON
- **`@RolesAllowed`** — `ADMIN` or `CAPOSALA`, chosen deliberately. Not optional.
- constructor injection, `@Transactional` on writes
- structured errors through `rest/exception/`, with a machine-readable code

## 5. Frontend API module

`frontend/src/api/thing.ts`, on top of `api` from `client.ts` — never a bare `fetch`.
Export a TypeScript `interface` for the payload, Doxygen comments as in the other 22
modules.

If the path is a **new top-level route**, add it to the `proxy` block in
`frontend/vite.config.ts` or it will 404 in dev only.

## 6. UI strings

Every label, button, column header, error and confirmation goes through `t()`, with a
row in `ui-translations.tsv` in five languages and a `CACHE_KEY` bump. Full procedure:
the `add-ui-string` skill.

If the entity's own records carry names shown to the user (like skills and locations),
they use dynamic keys — `skill.<id>`, `location.<id>` — not static catalogue rows.

## 7. Tests, on both engines

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
cd frontend && npm run lint -- --max-warnings=0 && npm run build
```

Add a portability test alongside the existing ones (`PortableOrmEndpointTest`,
`DatabasePortabilityContractTest`), and an authorization test if the endpoint is
sensitive — `AuthenticationEnforcementTest` is the model.

## Finally

If the new table holds personal data — names, emails, addresses, phone numbers,
credentials — add it to the anonymization table in `CLAUDE.md`. Otherwise the next
person publishing the demo database will leak it without knowing the column exists.
