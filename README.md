<div align="center">

# Employee Scheduling

**Automated staff shift planning with AI-driven optimisation**

![Java](https://img.shields.io/badge/Java-17%2B-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Quarkus](https://img.shields.io/badge/Quarkus-3.37-4695EB?style=flat-square&logo=quarkus&logoColor=white)
![Timefold](https://img.shields.io/badge/Timefold-1.33-6C5CE7?style=flat-square)
![React](https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?style=flat-square&logo=typescript&logoColor=white)
![SQLite](https://img.shields.io/badge/SQLite-3-003B57?style=flat-square&logo=sqlite&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-14+-4169E1?style=flat-square&logo=postgresql&logoColor=white)
![License](https://img.shields.io/badge/License-Apache%202.0-D22128?style=flat-square)

**Backend** Quarkus · **Frontend** React + TypeScript (Vite) · **Solver** Timefold

<sub>Compiled for Java 17, so it runs on the JRE shipped with Debian and Raspberry Pi OS.
Development uses JDK 21.</sub>

</div>

---

## Overview

A production-ready shift management system for healthcare organisations: an interactive
timeline, an AI solver that assigns shifts automatically, multilingual master data, PDF
reporting, e-mail delivery, and backup/restore hardened against partial failure.

The application began as the
[`employee-scheduling` quickstart](https://github.com/TimefoldAI/timefold-quickstarts?tab=readme-ov-file#-employee-scheduling)
from **Timefold Quickstarts** and has since been substantially extended — see
[Relationship to the Timefold quickstart](#relationship-to-the-timefold-quickstart).

| Capability | Details |
|---|---|
| **AI optimisation** | Timefold Solver with hard constraints (skills, availability, minimum rest) and soft constraints (preferences, workload balance) |
| **Dual database** | SQLite for single-machine use, PostgreSQL for multi-user servers, with separate Flyway migrations per engine |
| **Multi-organisation** | Each organisation owns its locations, employees, specialists and **skills**; catalogues never mix and each can be renamed independently |
| **Authentication** | Form-based auth, ADMIN and HEAD NURSE roles, optional e-mail registration with one-time passcode |
| **User approval** | The first account becomes ADMIN; subsequent accounts wait for an administrator's approval |
| **Resilient backup** | Scheduled and manual backups, restore with schema validation and automatic rollback, on both engines |
| **Five languages** | Italian, English, French, Spanish and German, from a single portable catalogue |
| **Cross-platform** | Windows 11 installer (MSI) and Linux service, each with a dedicated setup wizard |

---

## Quick start

```bash
# 1. Clone and build the front end
git clone https://github.com/MirkoUgoliniDev/employee-scheduling.git
cd employee-scheduling/frontend && npm install && npm run build && cd ..

# 2. Start the backend (SQLite, single machine)
mvn quarkus:dev

# 3. Open the application
#    → http://localhost:8080
```

> On first launch no account exists, and the application takes you straight to administrator
> creation: a username and a password are enough. In this mode **no e-mail server and no
> one-time passcode are involved** — those apply only to the `postgresql` profile. See
> [Registration flow](#registration-flow).

---

## Visual user guide

This guide follows the normal administrative workflow: sign in, configure an organisation,
prepare the scheduling data, generate a roster, and publish the result. The screenshots use
demonstration records; names, addresses and e-mail addresses are examples only. Labels may vary
slightly with the selected interface language and the permissions assigned to the signed-in user.

### 1. Sign in and select a workspace

Open the application URL and enter your credentials. If registration is enabled, use
**Register** to request an account. Newly registered server-mode accounts may require approval
from an administrator before they can access the application.

![Employee Scheduling sign-in page](assets/readme/Screenshot22.png)

<p align="center"><em>Figure 1 — The sign-in page provides access to authentication and optional account registration.</em></p>

After authentication, the home page shows the currently selected organisation in the upper-right
corner. Administrators can switch organisations there; all locations, operators, skills,
templates and solver settings are scoped to that selection.

![Employee Scheduling home page](assets/readme/Screenshot1.png)

<p align="center"><em>Figure 2 — The home page introduces the active workspace and can display organisation-specific guidance.</em></p>

### 2. Configure the organisation

Start in **Configuration → Structures**. A structure represents an organisation or independent
scheduling workspace. Use **Add** to create one, the pencil icon to edit it, and the selector in
the navigation bar to make it active.

![Structures configuration page](assets/readme/Screenshot14.png)

<p align="center"><em>Figure 3 — Structures isolate scheduling data and allow administrators to manage multiple organisations.</em></p>

In **General settings**, choose whether Shift Management displays a week or a month at a time.
Automatic template population can pre-fill empty current or future periods, while leaving past
periods untouched.

![General scheduling settings](assets/readme/Screenshot15.png)

<p align="center"><em>Figure 4 — Structure-level settings control the planning window and optional template pre-population.</em></p>

Define the qualifications used by the solver under **Configuration → Skills**. Skills belong to
one structure, can be ordered for display, and can be deactivated without deleting historical
references.

![Skills configuration dialog](assets/readme/Screenshot19.png)

<p align="center"><em>Figure 5 — The skill catalogue supplies the capabilities required by locations and held by operators.</em></p>

The **Specialists** page maintains the professionals who may supervise or be associated with
locations. Active status controls whether a specialist is available for current planning data.

![Specialists management page](assets/readme/Screenshot7.png)

<p align="center"><em>Figure 6 — Specialists are maintained separately from operators and may be linked to locations and compatibility rules.</em></p>

### 3. Create locations and operators

The **Locations** page lists the service points that require coverage. Codes provide stable
identifiers, ordering controls their presentation, and inactive rows remain visible for
administrative purposes without participating in new schedules.

![Locations management page](assets/readme/Screenshot4.png)

<p align="center"><em>Figure 7 — The location register summarises ownership, ordering, skill requirements and active status.</em></p>

When editing a location, assign its specialist and mark skills as **Required** or **Optional**.
Required skills are hard eligibility conditions; optional skills can influence assignment quality
without making coverage impossible.

![Edit location dialog](assets/readme/Screenshot12.png)

<p align="center"><em>Figure 8 — Location details define the capabilities the solver must consider for each assignment.</em></p>

Use **Operators** to maintain the employees who can receive shifts. The table provides an overview
of identity, skills, specialist relationships and active status. Deactivated operators are kept
for historical consistency but are excluded from new solver runs.

![Operators management page](assets/readme/Screenshot5.png)

<p align="center"><em>Figure 9 — The operator register is the central roster of assignable employees.</em></p>

The operator editor combines personal details, qualifications and specialist compatibility.
Choose **Avoid** for a soft preference or **Incompatible** for a hard prohibition. Use the latter
carefully: strict incompatibilities can leave shifts uncovered when no eligible operator remains.

![Edit operator dialog](assets/readme/Screenshot11.png)

<p align="center"><em>Figure 10 — Operator qualifications and compatibility rules directly affect solver eligibility and scoring.</em></p>

### 4. Record date preferences and availability

Open **Operator Date Preferences** to record dates that an operator desires, would prefer to
avoid, or cannot work. **Unavailable** is a hard restriction; **Desired** and **Undesired** dates
are optimisation preferences whose importance is configured in Solver Settings.

![Operator date preferences page](assets/readme/Screenshot6.png)

<p align="center"><em>Figure 11 — Date preferences provide the solver with individual availability and preference information.</em></p>

### 5. Build reusable shift templates

Templates describe a recurring coverage pattern. In **Configuration → Shift templates**, click an
empty timeline area to add a shift and click an existing block to edit it. A template can later be
applied manually from Shift Management or populated automatically according to General settings.

![Shift template editor](assets/readme/Screenshot20.png)

<p align="center"><em>Figure 12 — A reusable weekly template defines the expected shifts for each location.</em></p>

### 6. Prepare and solve a schedule

**Shift Management** is the main planning workspace. In **By operator** view, each row represents
an operator and each block represents an assigned shift. Use the arrows or **Today** to navigate
between periods. The colour legend distinguishes unassigned, assigned, desired, undesired and
unavailable states.

![Shift Management by operator](assets/readme/Screenshot2.png)

<p align="center"><em>Figure 13 — The operator view makes individual workload and weekly assignments easy to review.</em></p>

Switch to **By location** to verify coverage. From here an authorised user can fill the period
from a template, save the current pattern as a template, edit individual shifts, and launch the
solver. Review both views before publishing the schedule.

![Shift Management by location](assets/readme/Screenshot3.png)

<p align="center"><em>Figure 14 — The location view focuses on service coverage and exposes template and solve actions.</em></p>

Solver behaviour is configured per structure. Processing limits control runtime and early
stopping; daily and weekly rules define rest and workload boundaries; optimisation weights tune
the relative importance of preferences and balance. A value of `0` disables the limit wherever
the field help explicitly says so.

![Solver settings dialog](assets/readme/Screenshot17.png)

<p align="center"><em>Figure 15 — Solver Settings centralise processing limits, hard scheduling rules and optimisation weights.</em></p>

### 7. Generate and distribute reports

The **Report** page summarises coverage for the selected period and location. Generate PDFs only
after checking uncovered shifts and total hours. Existing documents can be opened or downloaded
from the results table, while **Send Shifts** supports e-mail distribution when SMTP is configured.

![Coverage report page](assets/readme/Screenshot8.png)

<p align="center"><em>Figure 16 — Coverage reporting highlights shift totals, uncovered work, hours and generated PDF files.</em></p>

Use **Configuration → PDF templates** to apply organisation branding. Each structure can have its
own logo, header, footer and primary colour; the live preview shows the approximate document
appearance before saving.

![PDF template editor](assets/readme/Screenshot21.png)

<p align="center"><em>Figure 17 — PDF templates provide consistent organisation-specific branding for exported reports.</em></p>

### 8. Configure communications and presentation

In **Email Settings**, enter the SMTP host, port, transport security, credentials and sender
identity. Save changes before using **Send test**. Passwords and production server details should
never be included in screenshots, issue reports or repository files.

![Email settings page](assets/readme/Screenshot16.png)

<p align="center"><em>Figure 18 — SMTP configuration enables registration messages, approvals and schedule delivery.</em></p>

The home page content is editable under **General configuration**. Administrators can select a
cover image and maintain a title, main message and hint for every supported language. Saving one
language does not replace the text stored for the others.

![Home content configuration](assets/readme/Screenshot13.png)

<p align="center"><em>Figure 19 — Multilingual home-page content can be tailored to each deployment.</em></p>

The **Localizations** catalogue contains the interface keys used throughout the application.
Search by key or description, then edit the translations for each supported language. Keep the
key stable: application code refers to it as a permanent identifier.

![Localization editor](assets/readme/Screenshot18.png)

<p align="center"><em>Figure 20 — The localisation editor manages portable interface text in five languages.</em></p>

### 9. Monitor and administer the system

**System Info** displays the active database, application versions and principal dependencies.
Use **Check for updates** as an advisory tool; review compatibility and release notes before
changing production components.

![System information page](assets/readme/Screenshot9.png)

<p align="center"><em>Figure 21 — System information provides a concise operational and dependency inventory.</em></p>

Administrators manage application accounts from **Users**. Review the assigned role and active
status before granting access. Deactivation is preferable to deletion when an account must retain
an audit or historical association.

![Users administration page](assets/readme/Screenshot10.png)

<p align="center"><em>Figure 22 — User administration controls roles, approval and access to protected application functions.</em></p>

---

## Installation

| Platform | Setup | Detailed guide |
|---|---|---|
| **Windows 11** | [`scripts/install-windows.ps1`](scripts/install-windows.ps1) — builds and packages a native application with a bundled JRE (`jpackage` → `.msi`) | [`docs/INSTALLATION.md`](docs/INSTALLATION.md) |
| **Linux server** | [`setup/wizard.py`](setup/wizard.py) — guided installer with a browser interface, or [`scripts/install-linux.sh`](scripts/install-linux.sh) for a single-command install | [`setup/INSTALL.md`](setup/INSTALL.md) |
| **Manual** | — | [`docs/INSTALLATION.md`](docs/INSTALLATION.md) |

### Windows

The installer asks for the database mode, the HTTP port and — in server mode only — the SMTP
settings, then generates the secrets, builds the application and produces the native package.

**The data directory is not configurable, by design.** The database, backups, logs and local
configuration live in `%LOCALAPPDATA%\EmployeeScheduling`, outside the installation directory.
Upgrades and uninstallation never touch them, and the application itself can safely sit in
`C:\Program Files`. Anything that differs between deployments — port, SMTP, registration mode —
goes into `config.properties`, created on first launch with every option documented in place:
**one MSI serves every deployment**, with no rebuild needed to change a setting.

To uninstall, double-click `uninstall.cmd` in the installation's `app\` folder. It stops the
application, removes the program and **keeps the data** (`-RemoveData` removes that too, after
confirmation).

> Windows packaging has a dedicated document covering the manual procedure, the configuration
> source precedence, field-tested pitfalls and a pre-release checklist:
> [`docs/Consolidati/PACKAGING-WINDOWS-MSI.md`](docs/Consolidati/PACKAGING-WINDOWS-MSI.md).

### Linux

The Linux wizard installs everything the application needs — Java, PostgreSQL, a dedicated
service account and a systemd unit — and then verifies that the application actually responds
before reporting success. It runs on Debian, Ubuntu and Raspberry Pi OS (`apt`) as well as
Fedora and RHEL derivatives (`dnf`).

```bash
curl -fLO https://github.com/MirkoUgoliniDev/employee-scheduling/releases/latest/download/employee-scheduling-raspberry-installer.tar.gz
mkdir employee-scheduling-installer
tar -xzf employee-scheduling-raspberry-installer.tar.gz -C employee-scheduling-installer
cd employee-scheduling-installer
sudo ./scripts/install-linux.sh --engine postgresql
```

The small archive contains only the installation and uninstallation scripts. The installer then
downloads the latest engine-specific JAR from GitHub Releases; the source repository, Maven,
Node.js, Windows and manual file copies are not required on the server. A local JAR can still be
selected with `--jar`, and `--from-source` remains available for development.

---

## Authentication and registration

### Roles

| Role | Access |
|---|---|
| **ADMIN** | Configuration, backup and restore, labels, organisations, SMTP, solver parameters, **user management and approval** |
| **HEAD NURSE** | Shifts, employees, locations, specialists, affinities, date preferences, reports, shift e-mails |

The **skills** catalogue is administered from Configuration and is therefore reserved to
administrators; head nurses assign skills to employees and locations but cannot create or
rename them.

### Registration flow

The mode follows the deployment type (`app.registration.mode`):

**Standalone — SQLite, no mail server**

```
First launch (no accounts yet)          Subsequent registrations
┌──────────────────────────────┐       ┌──────────────────────────────┐
│  username + password         │       │  username + password         │
│        ▼                     │       │        ▼                     │
│  Becomes ADMIN (active)      │       │  Becomes HEAD NURSE          │
│  signs in immediately        │       │  awaits admin approval       │
└──────────────────────────────┘       └──────────────────────────────┘
```

**Server — PostgreSQL, e-mail verified with a one-time passcode**

```
First launch (no accounts yet)          Subsequent registrations
┌──────────────────────────────┐       ┌──────────────────────────────┐
│  e-mail → OTP → credentials  │       │  e-mail → OTP → credentials  │
│        ▼                     │       │        ▼                     │
│  Becomes ADMIN (active)      │       │  Becomes HEAD NURSE          │
│  signs in immediately        │       │  admins notified by e-mail   │
└──────────────────────────────┘       │  awaits admin approval       │
                                       └──────────────────────────────┘
```

- **Standalone** — no passcode and no mail server required to register
- **Server** — six-digit passcode sent by e-mail, valid for five minutes, compared in constant
  time, five attempts maximum, with rate limiting (5 sends per address and 10 per IP address
  per window)
- **Pending accounts** cannot sign in: the application answers `INACTIVE` and explains why
- **E-mail addresses** are stored on `app_users` in a unique column (migration V3)

### Endpoints

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /auth/register/status` | Public | Whether the next account will be the first (ADMIN) |
| `POST /auth/register/otp` | Public | Sends the one-time passcode (rate limited) |
| `POST /auth/register/verify` | Public | Verifies the passcode, issues a single-use token |
| `POST /auth/register/complete` | Public | Creates the account (ADMIN, or HEAD NURSE pending approval) |
| `GET /auth/me` | Public | Current session, with `reason=INACTIVE` when not yet approved |
| `POST /auth/logout` | Authenticated | Invalidates the session |
| `GET/POST/PUT/DELETE /users/**` | ADMIN | User management and approval |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                          BROWSER                            │
│   React + TypeScript (Vite)                                 │
│   Development: localhost:5173, proxying Quarkus on :8080    │
│   Production:  served by Quarkus on :8080                   │
└───────────────────────┬─────────────────────────────────────┘
                        │ REST (JSON) + form authentication
                        ▼
┌─────────────────────────────────────────────────────────────┐
│                    QUARKUS (port 8080)                      │
│  JAX-RS · form auth · 8-hour session cookie                 │
│  Timefold Solver · Panache ORM · Flyway · SMTP mailer       │
│  Scheduled and manual backups · typed restore outcomes      │
└───────────┬──────────────────────────┬──────────────────────┘
            │                          │
            ▼                          ▼
┌───────────────────────┐   ┌──────────────────────────────┐
│  SQLite / PostgreSQL  │   │       Timefold Solver        │
│                       │   │                              │
│  Employees, shifts,   │   │  Assigns employees to        │
│  locations, skills,   │   │  shifts while satisfying     │
│  labels, structures,  │   │  skill, availability and     │
│  users, affinities    │   │  rest constraints            │
└───────────────────────┘   └──────────────────────────────┘
```

### Front end — `frontend/`

| Directory | Responsibility |
|---|---|
| `src/pages/` | Pages: Shifts, Employees, Locations, Skills, Dates, Report, Config, Users, Login, Register |
| `src/components/` | Reusable components: modals, navbar, timeline, backup, solver |
| `src/api/` | HTTP client for the REST API |
| `src/auth/` | `AuthContext` — session state, roles, sign-in and sign-out |
| `src/store/` | Global state (Zustand) — currently selected organisation |
| `src/i18n/` | i18next across five languages, plus backend error mapping |

### Backend — `src/main/java/`

| Package | Responsibility |
|---|---|
| `domain/` | Solver entities (`Shift` carries the planning variable) |
| `dto/` | REST data transfer objects |
| `rest/` | REST endpoints, backup and restore, authentication, registration, user management |
| `persistence/` | Panache entities |
| `solver/` | Timefold constraints, hard and soft |
| `config/` | Application entry point, data directory resolution, single-instance guard, Jackson |
| `security/` | HTML sanitisation |

---

## Backup and restore

| Engine | Mechanism | Format |
|---|---|---|
| **SQLite** | `VACUUM INTO` and the online backup API | `.db` |
| **PostgreSQL** | `pg_dump -Fc` and `pg_restore --single-transaction` | `.dump` |

Safeguards:

- **Origin validation** — the backup's base name must match the configured database
- **Schema validation** — the DDL is compared against the live schema and an incompatible
  restore is refused rather than attempted
- **Pre-restore snapshot** with automatic rollback
- **PostgreSQL advisory lock** (`pg_try_advisory_lock`) so two instances cannot restore at once
- **Automatic rotation** per tag, with configurable retention
- **Typed outcomes** — `RESTORED`, `REJECTED`, `ROLLED_BACK`, `INCONSISTENT`
- **Restrictive permissions** — owner-only ACL on Windows, mode `0600` on Unix

---

## Database

| Engine | Profile | Migrations |
|---|---|---|
| **SQLite** | default / `sqlite` | Flyway (`db/migration/sqlite/`) plus a legacy bootstrap |
| **PostgreSQL** | `postgresql` | Flyway (`db/migration/postgresql/`) |

Key environment variables:

| Variable | Purpose | Default |
|---|---|---|
| `AUTH_SESSION_KEY` | Session cookie encryption. Must be at least 16 characters: below that, every request fails with an opaque 500 | development only |
| `QUARKUS_PROFILE` | `sqlite` or `postgresql` | desktop default |
| `APP_DATABASE_PATH` | SQLite file location | `databases/large_data.db` |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | PostgreSQL connection | — |
| `QUARKUS_MAILER_*` | SMTP for passcodes and notifications | mocked in development |
| `BACKUP_ADMIN_TOKEN` | Required by the backup API | — |

> The databases committed to this repository are **anonymised**: fictional names and addresses,
> and no SMTP credentials.

**Where the data lives.** During development, in `databases/`. In the installed Windows
application, in `%LOCALAPPDATA%\EmployeeScheduling`, resolved at startup from
`-Dapp.data.dir=auto` — the `jpackage` configuration file expands `$APPDIR` but not environment
variables, so the path has to be computed at runtime. Alongside it the application creates
`config.properties`, which **takes precedence over the options chosen at packaging time**: whoever
installs the application can correct an occupied port without depending on whoever built it.

> **An applied migration must never be edited.** Flyway records a checksum for every file and
> refuses to start if one changes. A change requires a new version, not an edit to the previous
> one — otherwise every existing installation fails on startup.

---

## Development

### Backend

```bash
# SQLite
mvn quarkus:dev

# PostgreSQL
QUARKUS_PROFILE=postgresql mvn quarkus:dev            # bash
$env:QUARKUS_PROFILE="postgresql"; mvn quarkus:dev    # PowerShell

# → http://localhost:8080
```

### Front end with hot reload

```bash
cd frontend && npm run dev
# → http://localhost:5173, proxying :8080
```

### Production build

```bash
cd frontend && npm run build   # emits into src/main/resources/META-INF/resources/
mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile=sqlite
```

> `-Dquarkus.profile` is **required**. The database engine and the Flyway migration locations
> are fixed at build time and cannot be changed by an environment variable afterwards. Note also
> that Quarkus reads the `.env` file during the build, so a project-local `.env` can silently
> supply the profile — always pass it explicitly.

### Tests

```bash
mvn -B test "-Dquarkus.test.profile=test-sqlite"        # 174 tests
mvn -B test "-Dquarkus.test.profile=test-postgresql"    # requires PostgreSQL
```

> `application.properties` declares `backup.admin-token=${BACKUP_ADMIN_TOKEN:}`. If the variable
> is unset the value is an empty string and the suite will not start at all. Set it before
> running the tests.

---

## Security

- `deny-unannotated-endpoints=true` — any endpoint without `@RolesAllowed` is denied by default
- Passwords hashed with **bcrypt** through Quarkus form authentication, never compared by hand
- A **single** sign-in error message, so accounts cannot be enumerated
- **One-time passcodes** hashed with SHA-256, compared in constant time, valid five minutes,
  five attempts maximum
- **Rate limiting** on registration: 5 sends per address and 10 per IP address per window
- **E-mail verification** before an account is created
- XSS sanitisation in PDF and e-mail templates
- An administrative token guards every `/backup/*` endpoint
- PostgreSQL passwords never appear in a process command line — only in `PGPASSWORD`
- Partial backups are removed on any failure
- Multi-request operations are **atomic** across five transactional batch endpoints
- Cross-organisation races are guarded by an ownership check on every solve and save

---

## Relationship to the Timefold quickstart

This project started from the
[`employee-scheduling` quickstart](https://github.com/TimefoldAI/timefold-quickstarts?tab=readme-ov-file#-employee-scheduling)
in **Timefold Quickstarts**, which demonstrates assigning shifts to employees while respecting
availability and skill requirements. The solver domain model and the constraint approach still
follow that example.

Everything around it has been built since: a React front end, a persistence layer supporting
both SQLite and PostgreSQL, authentication and user approval, multi-organisation data isolation,
backup and restore with rollback, PDF and e-mail reporting, five-language localisation, and
installers for Windows and Linux.

Timefold Solver is used in its **Community Edition**, which is Apache-2.0 licensed and carries
no commercial restriction. The Enterprise Edition — not used here — requires a paid licence.

---

## Documentation

| Document | Contents |
|---|---|
| [`docs/Consolidati/`](docs/Consolidati/README.md) | **Maintained documents** describing how things stand today. Where they contradict anything else under `docs/`, these prevail |
| [`docs/Consolidati/PACKAGING-WINDOWS-MSI.md`](docs/Consolidati/PACKAGING-WINDOWS-MSI.md) | Windows packaging: prerequisites, manual procedure, configuration source precedence, pitfalls with symptom and remedy, pre-release checklist, uninstallation |
| [`setup/INSTALL.md`](setup/INSTALL.md) | Linux server installation: wizard, options, what each step does, troubleshooting |
| [`docs/INSTALLATION.md`](docs/INSTALLATION.md) | Detailed installation for Windows and Linux: jpackage, systemd, SMTP, backup |

---

## Troubleshooting

| Symptom | Resolution |
|---|---|
| **Port 8080 already in use** | Development: `.\scripts\kill-port.ps1 8080`. Installed application: set `quarkus.http.port=8081` in `%LOCALAPPDATA%\EmployeeScheduling\config.properties` and restart |
| **Blank page, assets returning 401** | A session cookie issued by another instance, with a different encryption key. Delete `employee_scheduling_session` for `localhost:8080`, or use a private window |
| **Interface stuck in one language, selector inert** | The translation cache is full. From the browser console, remove the `i18n_cache*` keys and reload |
| **Startup blocked by Flyway** (`no schema history table`) | The `sqlite` profile only manages databases created by Flyway; for a legacy file use the `legacy-sqlite` profile |
| **Passcode never arrives** | In development the mailer is **mocked**: the code is printed to the console log. Configure SMTP for real delivery |
| **Sign-in refused, account pending** | An administrator must approve the account under **Users** |
| **PostgreSQL tests failing** | A running PostgreSQL with an `employee_scheduling_test` database is required |
| **PostgreSQL backup disabled** | Install the client tools (`pg_dump`, `pg_restore`) or set `backup.postgresql.bin-dir` |

---

## Licence and attribution

Distributed under the **Apache License 2.0** — full text in [LICENSE](LICENSE).

This project **derives from the `employee-scheduling` quickstart** of
[Timefold Quickstarts](https://github.com/TimefoldAI/timefold-quickstarts), also Apache-2.0, and
has been substantially modified and extended. [NOTICE](NOTICE) records the origin, the changes
and the licences of every third-party component redistributed with the application.

Two obligations apply to anyone redistributing this software:

- **LICENSE and NOTICE travel with the package.** Apache-2.0 requires that the licence and the
  copyright notices are preserved in every copy.
- **The Font Awesome Free icons are CC BY 4.0**, which *requires attribution*. NOTICE already
  satisfies this, provided it is distributed alongside the application.

---

<div align="center">

*Shift management for healthcare teams — [Installation](docs/INSTALLATION.md) · [Linux setup](setup/INSTALL.md)*

</div>
