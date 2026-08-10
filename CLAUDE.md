# Project instructions — Employee Scheduling

This file is the **project's shared memory** (versioned in git): the rules and references
Claude must follow when working on this repository.

Reusable procedures live next to it as skills and agents — see
[Agents and skills](#agents-and-skills) at the bottom.

## Stack

- **Backend**: Java 21 + Quarkus 3.37.4 + Timefold Solver **1.33.0**, data through Hibernate
  ORM/Panache
- **Database**: two engines supported — **SQLite** (`databases/employee_scheduling.db`, the
  default profile) and **PostgreSQL** (`postgresql` profile). The schema is created by Flyway,
  with per-engine migrations in `src/main/resources/db/migration/{sqlite,postgresql}`.
- **Frontend**: React 19 + TypeScript 5.9 + Vite 8 (`frontend/`), built into
  `src/main/resources/META-INF/resources/` and served by Quarkus on :8080
- **Toolchain**: Maven 3.9 (`C:\Program Files\Maven\apache-maven-3.9.13\`), JDK 21 Temurin,
  Node 24
- **Compiler note**: the pom pins `maven.compiler.release=17` even though we run on JDK 21.
  APIs introduced in 18-21 (`Thread.ofVirtual()`, for example) **do not compile**: check
  before using them, because if the IDE has already produced the `.class` files the error
  only shows up at test time.

## Layout

```
src/main/java/org/acme/employeescheduling/
  config/       AppMain, data directory, single-instance guard, SPA routing, legacy DB rename
  domain/       planning entities and problem facts
  dto/          REST payloads
  persistence/  Panache entities and repositories
  rest/         JAX-RS resources
  security/     HTML sanitization
  solver/       Timefold constraints and solver configuration
src/main/resources/
  db/migration/{sqlite,postgresql}/   Flyway, one file per engine
  i18n/ui-translations.tsv            the UI translation catalogue
  META-INF/resources/                 built frontend (generated — do not edit by hand)
frontend/src/   React app: pages, components, hooks, store, i18n, utils
setup/          Python installation wizard (wizard.py, webui.py, steps/, lib/)
scripts/        install / uninstall / restart / release helpers, Windows and Linux
docs/           published documentation, one document per question
```

`MEMORY.md` in the repository root is a **local working diary** (gitignored) with per-step
state, checks performed and open points. Read it at the start of a session.

## Build, run, verify

```
mvn quarkus:dev                                  # full app on :8080 (SQLite by default)
mvn quarkus:dev -Dquarkus.profile=postgresql     # against PostgreSQL
cd frontend && npm run dev                       # Vite on :5173, proxies the API to :8080
cd frontend && npm run build                     # tsc -b && vite build, output into resources
scripts\kill-port.ps1 -Port 8080                 # port still held by a previous run
```

If you add a new top-level API path, add it to the `proxy` block in
`frontend/vite.config.ts` too, or it will 404 in dev only.

The verification suite is four checks, and each one covers something the others miss:

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
cd frontend && npm run lint -- --max-warnings=0 && npm run build
python3 -m compileall -q setup && python3 setup/wizard.py --help
```

CI enforces zero frontend warnings. The PostgreSQL profile needs a reachable server; if
there isn't one, the check is *not run* — never report it as passed.
`.github/workflows/database-portability.yml` runs SQLite on Windows and PostgreSQL on Ubuntu
against a real server.

**The first three checks do not look at `setup/`.** The wizard is Python: it is neither
compiled nor imported by Maven or tsc, so a broken f-string there passes them all in
silence. The fourth check is the one that catches it, and it is the pair CI runs in
`.github/workflows/release.yml`.

## Authentication and roles

Two roles, defined in `AppUserEntity`:

- **`ADMIN`** — configuration, backups, users, the skill catalogue
- **`CAPOSALA`** — shifts, employees and records

REST resources declare them with `@RolesAllowed`. The `app_users` table must be **empty** in
the published database: first boot creates the administrator, and it skips that step when an
ADMIN already exists — which would lock whoever installs the application out of their own
instance.

## Backup: an operational prerequisite on PostgreSQL

On the `postgresql` profile the backup is taken by the application using **`pg_dump`** (custom
format `-Fc`, a single `.dump` file). That means the **PostgreSQL client tools must be
installed**, with a major version **greater than or equal** to the server's: `pg_dump` refuses
to read a server newer than itself, and in that case the feature disables itself with an
explicit message.

The binaries are **not on the PATH** on Windows: they are looked up in
`C:\Program Files\PostgreSQL\<major>\bin`, taking the highest major, or wherever
`backup.postgresql.bin-dir` points. The password travels in `PGPASSWORD` in the child
process's environment — **never** in the connection URL, which on Windows is readable by
other processes.

**PostgreSQL restore** additionally requires `pg_restore` alongside `pg_dump`; without it
`getSettings()` reports `restoreSupported=false` and the UI hides the button. Before touching
the database the service copies and validates the dump, compares the entire filtered TOC of
the `public` schema, and creates a validated `prerestore` dump. `pg_restore --clean
--if-exists --single-transaction` confines the restore to `public`: either everything applies
or PostgreSQL leaves the database unchanged. Other schemas are neither included nor modified.
The `/backup/*` API always requires `BACKUP_ADMIN_TOKEN`; if exposed outside localhost it must
travel exclusively over TLS. For an operational restore exactly one application instance must
be running, on either engine.

## Design references (Timefold)

For every **design** question about the solver or the domain — modelling planning
entities/variables, constraints and score, solver patterns, tuning — **refer to the official
Timefold documentation** (read it, do not proceed from memory: APIs and patterns change
between versions and we are on **1.33**):

- **Starting point (introduction and index)**: https://docs.timefold.ai/timefold-solver/latest/introduction
- Common patterns (domain modeling): https://docs.timefold.ai/timefold-solver/latest/domain-modeling/common-patterns

Score Analysis is a Timefold **Enterprise** feature and is not available here; this is why the
2.x line was evaluated and rejected.

## Language: what is English, what is Italian

The project derives from Timefold's `employee-scheduling` quickstart and is public: whoever
arrives from there does not read Italian. But not everything gets translated, and the line is
sharp.

**In English:**

- **All code comments** — Java, TypeScript, TSX, Python, SQL, shell, PowerShell,
  `.properties`. A hard rule since 8 August 2026. One exception: versioned Flyway migrations
  that have already been published are immutable, comments included, because Flyway includes
  comments in the checksum and changing them prevents startup on existing databases. Comments
  in new migrations are written in English from the start.
- `README.md`, `LICENSE`, `NOTICE` — the public shopfront.
- **The messages printed by the installers and the wizard** — `echo`, `Write-Host`, `print()`,
  `die`, `info`, `warn`, `runner.log()`, the names and descriptions of the steps, the
  Raspberry wizard's web page, the `argparse` help — **and all distributed documentation**:
  everything under `docs/` and `setup/INSTALL.md`. This rule changed on 9 August 2026: until
  that day they were in Italian, but the installer is the first thing someone arriving from
  the public quickstart sees, and an Italian wizard stops them before they ever reach the
  application. On the same day `docs/` stopped being an internal archive — dated handoffs,
  diaries and reports were deleted and remain in git history — and was reorganized by reader:
  each document answers a single question (`ARCHITECTURE`, `USER-GUIDE`,
  `INSTALLATION-WINDOWS`, `INSTALLATION-LINUX`, `CONFIGURATION`, `AUTHENTICATION`,
  `DEVELOPMENT`, `PACKAGING-WINDOWS-MSI`), all linked from the README index. What gets
  published is written in English.
- **This file**, and the agent and skill definitions under `.claude/` — since 10 August 2026.
  They ship with the public repository and describe how to work on it, so they follow the
  same rule as the rest of what is published.

**In Italian:**

- **Everything the user reads inside the application**: interface text, error messages,
  toasts, and the second argument of `t('key', 'fallback text')`.

Translating a comment does **not** mean shortening it. The comments in this project explain
the *why*, often citing the concrete defect they prevented and the numbers that were measured:
that substance is preserved in full. A comment reduced to a generic sentence has lost its only
reason to exist.

After a mass translation, the only evidence that no code was touched by accident is that
`mvn -B test` and `npx tsc -b` stay green — plus the `setup/` checks above, which they do not
cover.

## Comment style: Doxygen tags

The language is English (above); the **shape** is this.

Java, TypeScript and TSX use **Doxygen-style tags** inside block comments: `@brief` for
the one-line summary, `@details` for the explanation, `@file` at the top of a TypeScript
module. Java also uses standard Javadoc tags (`{@link …}`, `<li>`, `<b>`) inside
`@details`.

```java
/**
 * @brief JPA/Panache entity for the {@code skills} table (incremental ORM migration).
 *
 * @details Actual schema: id INTEGER PK, structure_id INTEGER NOT NULL, …
 *          The Java field is named {@code skillOrder} because "order" is reserved in
 *          HQL; JSON remains "order" through the DTO {@link Skill}.
 */
```

This is the dominant convention, not a suggestion: **80 of 101 Java files** and **60 of
76 TS/TSX files** follow it. Every class, entity, resource and exported module gets at
least a `@brief`; `@details` is added wherever the *why* is not obvious.

The files that do not follow it are not written in some other style — they simply have
**no class comment at all**, and they cluster on the more recent features (general
settings, home UI settings, PDF templates, solver settings, users, backup). The
convention was solid in the ORM-migrated code and drifted afterwards. Treat those as
debt to repay when working nearby, not as a precedent to copy.

## Working rules

- **ALWAYS localize UI text**: every string added goes through `t()` plus a translation in all
  five languages (it/en/fr/es/de). A hard rule.
- **Localizations must stay aligned on BOTH databases** (SQLite and PostgreSQL). A hard rule,
  see the dedicated section below.
- **Terminology**: the application says **"Operatore"** — never "Dipendente" or "Impiegato".
- **Database name, aligned across the two engines** (since 9 August 2026): the SQLite file is
  `databases/employee_scheduling.db`, the same name as the PostgreSQL database and role. It
  used to be called `large_data.db`, a name inherited from the quickstart that said nothing.
  `LegacyDatabaseName`, called from `AppMain.main()` **before Quarkus starts**, renames the old
  file wherever it finds it: without that migration an existing installation would get Flyway
  creating a new, empty database while the real data sits on disk, with no error. If you move
  that code, it must stay ahead of Flyway.
- **`.db` files: only the demo one is committed.** `databases/employee_scheduling.db` is
  tracked deliberately (`.gitignore` excludes it, then re-admits it with `!`): it is the
  published demonstration database, with invented names and emails, an **empty** `app_users`
  so that first boot can create the administrator, and no SMTP credentials. Every other `.db`
  — `_pre-*` snapshots, `standalone-test.db`, backups — stays out. Before committing it after
  working on it, go through the checklist below: the file is rewritten at runtime and may have
  refilled with your own data.

## Anonymization: no personal data in the published database

`databases/employee_scheduling.db` lives in a public repository, and any database that leaves
this machine — attached to a bug report, handed to a colleague — carries the same constraint:
**no real personal data**. This applies to **both engines**: whatever is done on SQLite is
done identically on PostgreSQL.

To be anonymized, in every database:

| Table | Columns |
|---|---|
| `employees`, `specialists` | `first_name`, `last_name`, `email` |
| `structures` | `name`, `address`, `phone` |
| `email_log` | `sent_to`, `filename` (the PDF file name contains first and last name) |
| `email_settings` | `host`, `username`, `password`, `mail_from` — **a working SMTP credential** |
| `app_users` | **empty it completely**: `password_hash` is a credential, and an existing ADMIN prevents first boot from creating the administrator, locking whoever installs the application out of their own instance |

Rules learned in the field:

1. **Filter the invented names against the real ones.** The database already contained common
   Italian names: drawing from the usual pool (Rossi, Bianchi, Ferrari) recreates dozens of
   names identical to the originals. Use a pool of uncommon names and discard, via SQL,
   anything appearing in the pre-anonymization backup — do not trust the eye.
2. **Disjoint pools of first and last names**, so a first name can never equal a last name.
3. **Emails derived from the new name**, domain `example.com` (reserved by RFC 2606: it does
   not exist and cannot be delivered to by mistake).
4. **`VACUUM` at the end**, always. Without it the old strings stay readable in the free pages
   and a `grep` on the committed `.db` finds them anyway.
5. **Verify on the binary**, not only on the tables:
   `grep -a -c -i "<string>" databases/employee_scheduling.db` must return 0 for every known
   real value, and `grep -a -o -E "[A-Za-z0-9._%-]+@[A-Za-z0-9.-]+"` must not return any domain
   other than `example.com` (the placeholders `esempio.it`/`exemple.fr`/`ejemplo.es`/
   `beispiel.de` are UI translation text, not data).
6. **A dated backup outside the repo** before starting: anonymization is not reversible.

## Localizations: one place, two databases

UI labels live in the `labels` + `localizzazioni` tables of **every** database. The historical
seed in `DemoDataRepository.seedLabelTranslations*` uses SQLite-only SQL (`INSERT OR IGNORE`)
and runs **only** with `app.sqlite.legacy-bootstrap=true`: on the `postgresql` profile it never
runs. A key added there appears on SQLite and is missing on PostgreSQL, where the user sees the
Italian fallback in every language.

**Single source of truth**: `src/main/resources/i18n/ui-translations.tsv`.

- A 7-field TSV: `key`, description, `it`, `en`, `fr`, `es`, `de`. All five languages are
  mandatory and non-empty.
- `UiTranslationSyncService` applies it at startup **through JPA**, so it lands identically on
  SQLite and PostgreSQL. It is **additive**: it never overwrites a value already present, so
  edits made from the Labels page survive restarts.
- Being a resource rather than code, it is not subject to the JVM's 64KB per-method limit that
  had forced the historical seed to be split across several methods.

**Procedure for every new UI string**:

1. In the frontend use `t('key', 'Italian fallback')`.
2. Add **one row** to `ui-translations.tsv` with the five languages. **Never** add keys to the
   `seedLabelTranslations*` methods: they are SQLite-only and remain solely for historical
   compatibility.
3. Bump `CACHE_KEY` in `frontend/src/i18n/index.ts` (cache-busts the clients).
4. `mvn test` — `UiTranslationCatalogTest` fails the build if a language is missing, if a key
   is duplicated, or if a key exists in the SQLite seed but not in the portable catalogue.

Check on both engines (`UiTranslationSyncTest` runs under both profiles):

```
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
mvn -B -ntp test "-Dquarkus.test.profile=test-postgresql"
```

## Commits

Conventional commits, lowercase, English, imperative: `feat:`, `fix:`, `docs:`, `chore:`,
`ci:`. The subject says what changed and, where it fits, why — `fix: restrict the skill
catalogue to administrators`, not `fix: bug`.

The built frontend under `src/main/resources/META-INF/resources/` is **tracked**, so a
frontend change and its rebuilt bundle belong in the same commit. Committing source
without rebuilding leaves the published bundle behind the code, with nothing reporting it.

## Releases

`scripts\publish-release.ps1 -Version X.Y.Z` sets the pom version, tags `vX.Y.Z`, pushes, and
returns the pom to `X.Y.Z-SNAPSHOT`. It refuses to run unless the branch is `main`, the working
tree is completely clean (untracked files included), and local `main` is not behind
`origin/main`. Those refusals are the safety net — do not work around them.

The version also appears in `frontend/package.json`, in the installers under `scripts/` and
`setup/`, and in the System Info screen, which carries **hardcoded fallback versions** for its
dependency list — they go stale silently after a dependency bump. Windows MSI packaging:
`docs/PACKAGING-WINDOWS-MSI.md`.

## Agents and skills

Recurring procedures are written down under `.claude/` so they are followed the same way every
time rather than reconstructed from this file.

**Skills** (`.claude/skills/`) — invoked as `/name`, or loaded automatically when the task
matches:

| Skill | Use it for |
|---|---|
| `add-ui-string` | any text the user reads: `t()`, the TSV row, the cache bust, the test |
| `add-entity` | a new persisted concept end to end: table ×2, entity, DTO, resource, client, tests |
| `add-api-endpoint` | one REST operation: method, role, error contract, client function |
| `add-frontend-page` | a new screen: component, lazy route, navbar, data, labels |
| `new-migration` | a schema change — two files, one per engine, kept in parity |
| `installer-change` | anything under `setup/` or `scripts/` — the area no compiler checks |
| `add-constraint` | a solver constraint: score unit, configurable weight, verifier test |
| `upgrade-dependency` | a version bump, including the hardcoded fallbacks it invalidates |
| `verify-all` | the four checks, and what each one fails to cover |
| `anonymize-db` | before a database leaves this machine |
| `run-app` | launching locally, both engines, ports, hot reload |
| `release` | preconditions, cutting the release, notes |

**Agents** (`.claude/agents/`) — spawned for work that deserves its own context. They are
scoped so that a given task has one obvious owner:

| Agent | Use it for |
|---|---|
| `timefold-solver` | solver and domain design; reads the 1.33 docs before proposing |
| `panache-persistence` | entities, mappings, queries, schema-validation failures |
| `java-backend` | JAX-RS resources, DTOs, CDI, startup and lifecycle code |
| `frontend-react` | pages, components, store, API modules, Vite/TS build errors |
| `dual-db-migrator` | **writing** migrations and schema parity between the engines |
| `flyway` | Flyway **failures**: checksums, failed migrations, history, repair |
| `sqlite` | SQLite **engine** issues: locking, WAL, pragmas, integrity, VACUUM |
| `i18n-guardian` | auditing the catalogue, hunting hardcoded strings |
| `installer-wizard` | `setup/` and `scripts/` — wizard, steps, installers, systemd |
| `email-pdf` | SMTP, templates, the email log, jsPDF generation |
| `security-auth` | roles, login, tokens, sanitization, XSS |
| `backup-restore` | backups, retention, restore on either engine |
| `build-verifier` | running the full suite and reporting honestly |
| `release-auditor` | read-only pre-publication audit: secrets, personal data, versions |
| `docs-curator` | `docs/`, README, and checking documented claims against the code |

The three database agents divide by *what went wrong*, not by technology:
`dual-db-migrator` authors the change, `flyway` handles the tool refusing to apply it,
`sqlite` handles the engine itself.
