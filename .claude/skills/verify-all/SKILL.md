---
name: verify-all
description: Run the project's complete verification suite — Maven tests on both database profiles, frontend lint and build, and the Python checks for setup/. Use before committing anything substantial, after a refactor or a mass edit, after a dependency bump, and before a release. Explains what each check does and does not cover.
---

# Verifying the project

Four checks. Three of them miss things the fourth catches, which is why all four run.

## 1. Backend — both database profiles

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
```

The PostgreSQL profile needs a reachable server. If there isn't one, say the check was
**not run** — never report it as passed. CI runs it on Ubuntu against a real server.

Maven: `C:\Program Files\Maven\apache-maven-3.9.13\`. JDK 21 Temurin, but the pom sets
`maven.compiler.release=17` — Java 18-21 APIs (`Thread.ofVirtual()`, for instance) do
not compile. Worse, if the IDE has already produced `.class` files the error only
appears at test time.

## 2. Frontend

From `frontend/`:

```
npm run lint -- --max-warnings=0
npm run build
```

CI enforces zero warnings, so a warning is a failure. `npm run build` is
`tsc -b && vite build`; `npx tsc -b` alone type-checks without emitting a bundle.

## 3. `setup/` — the check the others cannot do

```
python3 -m compileall -q setup
python3 setup/wizard.py --help
```

**Neither Maven nor tsc looks at `setup/`.** The wizard is Python — not compiled, not
imported by either build — so a broken f-string there passes `mvn test` and `tsc -b`
without a sound. This is the same pair CI runs in `.github/workflows/release.yml`. On
Windows the interpreter is usually `python`.

## 4. The demo database, if you touched it

`databases/employee_scheduling.db` is committed and public. It is rewritten at runtime,
so running the app can repopulate it with your own data. Before committing it, run the
`anonymize-db` checks.

## After a mass edit

After a bulk translation or a sweeping rename, green tests are the only evidence that
no code was changed by accident. Run all four, and read the diff — a passing suite does
not prove a comment kept its meaning.

## Reporting

Per check: passed / failed / not run. For a failure, quote the actual test name and
error output rather than summarizing it, and say whether it looks pre-existing or
caused by the current work.
