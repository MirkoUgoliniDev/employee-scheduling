---
name: upgrade-dependency
description: Bump a backend or frontend dependency — Quarkus, Timefold, a JDBC driver, React, Vite, TypeScript, or a library. Use for any version change in pom.xml or package.json, because several places repeat those version numbers by hand and go stale silently.
---

# Upgrading a dependency

## 1. Change the version in one place

**Backend**: the `<properties>` block of `pom.xml` (`version.io.quarkus`,
`version.ai.timefold.solver`, …), never inside an individual `<dependency>`. The BOMs
manage the rest.

**Frontend**: `frontend/package.json`, then `npm install` to refresh the lockfile. Commit
the lockfile with the change.

## 2. Update the hardcoded fallbacks — the step that gets forgotten

`SystemInfoResource.java` carries literal versions used when the runtime cannot report
one:

```java
static final String FALLBACK_TIMEFOLD = "1.33.0";
static final String FALLBACK_QUARKUS  = "3.37.4";
```

Forget these and the System Info screen shows the user a **version that is not the one
running**. No test fails, nothing logs a warning — the screen simply lies.
`SystemInfoVersionFallbackTest` covers the mechanism, not the values.

Check the rest of the same file for other literals before assuming two are all there is.

## 3. Read the release notes for breaking changes

Not optional for the four that shape this project:

- **Timefold** — the constraint stream API and score types have changed shape between
  versions. We are on 1.33; 2.x was evaluated and rejected because Score Analysis is
  Enterprise. An upgrade that crosses a major needs the migration guide, and the pinned
  version in `CLAUDE.md` must be updated with it.
- **Quarkus** — configuration keys get renamed and deprecated. Check the datasource and
  Flyway keys in `application.properties` still exist.
- **Vite** — Vite 8 runs **Rolldown**; chunking is `rolldownOptions.output`, the old
  `manualChunks` object no longer applies.
- **A JDBC driver** — `sqlite-jdbc` or the PostgreSQL driver can change pragma or type
  handling. This is exactly where the "two pragmas in one `new-connection-sql`, only the
  first executes" behaviour lives; `OrmDatasourcePragmaTest` is the guard.

## 4. Java level

The pom pins `maven.compiler.release=17` while the JDK is 21. A library requiring a newer
class-file version fails here even though the JDK is fine.

## 5. Verify — all four checks, no shortcuts

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
cd frontend && npm run lint -- --max-warnings=0 && npm run build
python3 -m compileall -q setup && python3 setup/wizard.py --help
```

A dependency bump is exactly the case where a green SQLite run means nothing about
PostgreSQL. See the `verify-all` skill.

A frontend bump also needs the application actually opened (`run-app`): a bundler or
React upgrade can build cleanly and break at runtime — the timeline, the PDF and the
rich-text editor are the fragile ones.

## 6. Update what documents the version

- `CLAUDE.md` — the Stack section names Quarkus, Timefold, React, TypeScript and Vite
  versions explicitly.
- `docs/ARCHITECTURE.md` and `docs/DEVELOPMENT.md` if they state versions.
- The installers under `setup/` and `scripts/` if they check for a minimum version.

A version documented in three places and bumped in one is worse than not documenting it.
