---
name: run-app
description: Launch Employee Scheduling locally to see a change working in the real application — the Quarkus backend on :8080, optionally the Vite dev server on :5173 with hot reload. Use when asked to run, start, restart or screenshot the app, or to confirm behaviour that tests cannot show. Covers both database engines, port conflicts and the frontend build step.
---

# Running the application

## Full app on :8080 (backend serves the built frontend)

```
mvn quarkus:dev
```

Quarkus serves the frontend from `src/main/resources/META-INF/resources/`, which is the
**build output** of `frontend/`. If you changed frontend code, build it first or you
will be looking at the previous bundle:

```
cd frontend && npm run build
```

Default profile is SQLite, file `databases/employee_scheduling.db`. Flyway creates and
migrates the schema at startup.

`AppMain.main()` calls `LegacyDatabaseName` **before Quarkus starts**, renaming a
pre-existing `large_data.db` to the current name. It must stay ahead of Flyway —
otherwise an existing installation gets a fresh empty database while the real data sits
on disk, with no error shown.

## Frontend hot reload on :5173

Two processes: leave `mvn quarkus:dev` running, then in `frontend/`:

```
npm run dev
```

Vite proxies the API routes (`/schedules`, `/structures`, `/translations`, `/labels`,
`/employees`, …) to `http://localhost:8080` — see the `proxy` block in
`frontend/vite.config.ts`. If you add a new top-level API path, add it to that proxy or
it will 404 only in dev.

## PostgreSQL instead of SQLite

```
mvn quarkus:dev -Dquarkus.profile=postgresql
```

Needs a reachable server plus the database and role named `employee_scheduling`. Backup
and restore on this profile additionally need `pg_dump`/`pg_restore` installed, with a
major version **greater than or equal** to the server's; without them the feature
disables itself and the UI hides the restore button.

## Port 8080 already in use

A previous run is still holding it. On Windows:

```
scripts\kill-port.ps1 -Port 8080
```

`scripts\restart.bat` does the same and relaunches in one step.

## First boot and login

With `app_users` empty, the first boot lets you create the administrator. If you cannot
log in and suspect a leftover ADMIN row, check that table before debugging the auth code.

## What running proves that tests do not

Solver output, PDF generation, the five-language switch, and anything involving the
demo dataset. If the task was a solver or UI change, running it is part of the work, not
an extra.

Note that running the app **writes to the committed demo database**. Before committing
it afterwards, go through the `anonymize-db` checks.
