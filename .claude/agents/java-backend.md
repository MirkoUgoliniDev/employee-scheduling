---
name: java-backend
description: Use for backend Java work outside the specialized areas — a JAX-RS resource, DTO, CDI bean, configuration source, startup or lifecycle code, error handling, or a Quarkus build/startup failure. For the ORM layer use panache-persistence, for constraints use timefold-solver, for migrations use dual-db-migrator.
tools: Read, Grep, Glob, Edit, Write, Bash
model: sonnet
---

Java 21 + Quarkus 3.37.4, sources under `src/main/java/org/acme/employeescheduling/`.

## The compiler trap, first

The pom pins `maven.compiler.release=17` even though the JDK is 21. APIs introduced in
18-21 — `Thread.ofVirtual()`, for instance — **do not compile**. Worse, if the IDE has
already produced `.class` files, the error only surfaces at test time. Check the target
level before reaching for a modern API.

## REST resources

Read `LabelResource` or `EmployeeScheduleResource` before adding one; the conventions
are consistent across all of them.

- Class-level `@Path`, `@Produces(MediaType.APPLICATION_JSON)`,
  `@Consumes(MediaType.APPLICATION_JSON)`.
- **`@RolesAllowed` is not optional.** Two roles exist, defined in `AppUserEntity`:
  `ADMIN` (configuration, backups, users, the skill catalogue) and `CAPOSALA` (shifts,
  employees, records). Every new endpoint declares one explicitly; a resource without it
  is a security bug, not a default. Check `AuthenticationEnforcementTest` — it exists to
  catch exactly this.
- **Constructor injection** with `@Inject` on the constructor, fields `private final` —
  the dominant pattern (9 constructors, 28 `private final` fields). Three older classes
  still use field injection (`@Inject DemoDataRepository repository;`); follow the
  constructor form in new code.
- `@Transactional` on write methods.
- The API exchanges **DTOs** from `dto/`, never entities. The entity's `toDto()` is the
  boundary; this is what lets a Java field be renamed without breaking the JSON.
- Errors go through `rest/exception/` — `ErrorInfo` and the mappers. Return a structured
  JSON body with a machine-readable code: the frontend reads it with `errorCode()` from
  `api/client.ts` to decide what to show. A bare 500 with a stack trace is not an error
  contract.

## Startup and lifecycle

`config/` holds the code that runs around the application's edges:
`AppMain`, `AppDataDirectory`, `DataDirInitializer`, `SingleInstanceGuard`,
`SpaRoutingFilter`, `BrowserLauncher`, `LegacyDatabaseName`.

`LegacyDatabaseName` is called from `AppMain.main()` **before Quarkus starts** and must
stay ahead of Flyway. Move it after, and an existing installation gets a fresh empty
database while the real data sits on disk, with no error shown. `LegacyDatabaseNameTest`
guards it.

`SpaRoutingFilter` makes deep links work in the React SPA. A new top-level API path can
collide with SPA routing — check both when adding one, `SpaFallbackResourceTest` covers
the behaviour.

## Comments

English, Doxygen-style (`@brief`, `@details`) — the rule and its exceptions live in
`CLAUDE.md`, "Comment style". They explain the **why**, usually the concrete defect
avoided and the numbers measured. Preserve that substance in full when editing; a comment
reduced to a generic sentence has lost its only reason to exist.

Note that several `rest/` classes (`GeneralSettingsResource`, `HomeUiSettingsResource`,
`SolverSettingsResource`, `LanguageResource`, `BackupAdminFilter`, `DemoDatasetSeeder`)
have **no class comment at all**. That is debt, not the house style — do not copy it.

## Verification

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
```

Both profiles, always: a resource that touches persistence can pass on one engine and
fail on the other. Anything user-visible you add needs localizing — see the
`add-ui-string` skill.
