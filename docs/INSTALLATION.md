# Installation — Employee Scheduling

**Detailed** installation guide for Windows 11 and Linux, with automated wizards.

---

## 0. Installation modes

The application supports two database engines and three distribution forms:

| Mode | Database | Where | Ideal for |
|---|---|---|---|
| **Desktop** | SQLite (single file) | Local machine | Single user (Windows or Linux) |
| **Server** | PostgreSQL | Centralized machine | Multi-user, access from several PCs |

Distribution forms:

| Form | Windows | Linux |
|---|---|---|
| **Development** | `mvn quarkus:dev` + `npm run dev` | same |
| **Service** | `install-windows.ps1` (jpackage: native app with bundled JRE + shortcut) | `install-linux.sh` + systemd |
| **Standalone** | `java -jar` with a JRE installed | `java -jar` or systemd |

> **Windows "like a normal app"**: the wizard uses **jpackage** (the official Java tool), which
> produces an `.msi` installer with a bundled JRE, an icon, a Start menu entry, and
> uninstallation. The end user **does not have to install Java**: everything is in the package.

---

## 0.1 Handing the application to someone else

Whoever installs it **needs neither the repository nor any development tool**: no JDK, Maven,
Node, or WiX. They receive a single file, the MSI, and configure whatever differs between
installations in a text file.

### On your side — once per version

```powershell
.\scripts\install-windows.ps1        # choose 2 = MSI installer
```

This produces `dist\EmployeeScheduling-<version>.msi` (~125 MB, JRE included). Publish it
wherever you like: GitHub Releases, a shared folder, a USB stick. **The same file works for
every installation**: port and SMTP are written into the package as `-D` options, but whoever
installs it overrides them from `config.properties`, which wins (ordinal 450). The registration
mode is not baked in at all — it is derived from the engine at runtime.

### On the installer's side

1. Double-click the MSI and choose the directory (`C:\Program Files` is fine).
2. The application starts and opens the browser by itself.
3. The first person who registers becomes the administrator.

### Local configuration, without rebuilding anything

On first startup the application creates:

```
%LOCALAPPDATA%\EmployeeScheduling\config.properties
```

It contains **every adjustable setting, already commented and explained**: port, SMTP server,
registration mode, session key, backup token, log level. Remove the `#` from the line you care
about, save, and restart the application.

That file **wins over the settings chosen at packaging time** (ordinal 450 against the 400 of
system properties): it exists precisely so that whoever installs the application can fix, say,
a port 8080 already taken by another program without depending on you.

The only setting that cannot be changed from there is `app.data.dir`: by the time the file is
read, the data directory has already been resolved.

### Where the data lives

Everything in `%LOCALAPPDATA%\EmployeeScheduling`: `employee_scheduling.db`, `backups\`, `app.log`,
`config.properties`. **Outside the installation directory**, so updates and uninstallation do
not touch it.

To move an installation to another PC, install the MSI there and copy that directory over.

### Uninstallation

Double-click `uninstall.cmd` in the installation's `app\` directory (for example
`C:\Program Files\EmployeeScheduling\app\uninstall.cmd`). It closes the application, removes
the program, and **keeps the data**; with `-RemoveData` it removes the data too, asking for
confirmation first.

> The `.cmd` is a launcher: `.ps1` files do not run on a double-click, Windows opens them in an
> editor. `Settings > Apps > EmployeeScheduling > Uninstall` works too, as long as the
> application is closed.

---

## 1. Prerequisites

### Windows 11

| Component | Version | Download |
|---|---|---|
| **JDK (Temurin)** | 21+ | https://adoptium.net → `.msi` x64 |
| **Maven** | 3.9+ | https://maven.apache.org/download.cgi → `apache-maven-3.9.x-bin.zip` |
| **Node.js** | 20+ LTS | https://nodejs.org |
| **Git** | any | https://git-scm.com/download/win |
| **WiX Toolset** (only for the manual procedure) | 3.14 | https://wixtoolset.org (`wix314.exe`). The wizard downloads it by itself into `C:\tools\wix314` |
| **PostgreSQL** (server mode only) | 14+ | https://www.postgresql.org/download/windows/ |

After installing the JDK and Maven, open a terminal and verify:

```powershell
java -version        # must show 21.x
mvn -version         # must show 3.9.x
git --version
```

If `mvn` is not recognized: add Maven's `bin` directory to the system PATH
(Control Panel → Environment Variables → Path → New).

### Linux (Debian/Ubuntu/Raspberry Pi OS)

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk maven nodejs npm git curl
java -version
mvn -version
```

On distributions without a JDK 21 package (for example older Ubuntu releases):

```bash
# Manual installation of Temurin 21
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo gpg --dearmor -o /etc/apt/trusted.gpg.d/adoptium.gpg
echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt install -y temurin-21-jdk maven
```

PostgreSQL (server mode only):

```bash
sudo apt install -y postgresql postgresql-client
sudo systemctl enable --now postgresql
```

---

## 2. Running the wizards

### Windows 11

```powershell
cd <your-clone-of>\employee-scheduling
powershell -ExecutionPolicy Bypass -File .\scripts\install-windows.ps1
```

The wizard asks, in order:

1. **Database mode** — `1` = SQLite desktop, `2` = PostgreSQL server (with JDBC URL, username,
   and password);
2. **Sample data** — whether to load the demo dataset for testing (default: no);
3. **HTTP port** — default `8080`;
4. **SMTP** — **only in PostgreSQL mode**: whether to use the **mock** (emails written to the
   log, nothing actually sent) or host, port, username, password, and sender. In SQLite mode
   nothing is asked and the mock is implicit, because standalone registration needs no email;
5. **Packaging** — `1` = app-image (directory with a bundled JRE, fast), `2` = MSI installer
   (Start menu + uninstallation; **WiX is downloaded automatically** the first time, ~39 MB
   into `C:\tools\wix314`), `3` = build only. This one is asked **after** the frontend and
   Maven builds, which take several minutes: do not walk away expecting it to be finished.
   It is skipped entirely when `-Package` is passed on the command line.

The session key and the backup token are **generated automatically**; you are not asked for
them.

The **data directory is no longer asked for**: in a package the data always goes to
`%LOCALAPPDATA%\EmployeeScheduling` (see § 0.1), while in development it stays in `databases\`.

At the end it creates `.env` (needed only by `mvn quarkus:dev`), builds, and generates the
package in `dist\`.

> **Windows packaging has a dedicated, more detailed document**:
> [`docs/PACKAGING-WINDOWS-MSI.md`](PACKAGING-WINDOWS-MSI.md) — manual
> procedure, precedence of configuration sources, pitfalls encountered with symptom and remedy,
> pre-release checklist. Where the two contradict each other, that one wins.

### Linux

```bash
git clone https://github.com/MirkoUgoliniDev/employee-scheduling.git
cd employee-scheduling
sudo ./scripts/install-linux.sh --engine postgresql
```

The script automatically downloads the most recent PostgreSQL JAR from GitHub Releases,
installs Java and PostgreSQL, and registers the systemd service. No compilation and no manual
transfer from a PC are needed.

Main options:

- **systemd service** — `employee-scheduling.service` is created and started by default
  (automatic restart at boot, logs in journald); `--no-service` skips it, and it is skipped
  automatically when `systemctl` is absent;
- **Data** — default `/var/lib/employee-scheduling` (writing there requires sudo).

For a Raspberry Pi, the browser-based wizard is documented in
[`setup/INSTALL.md`](../setup/INSTALL.md).

---

## 2.1 Registration modes (differentiated)

The application distinguishes two registration modes, selected by `app.registration.mode` in
`.env` (default `auto`):

| Mode | Typical database | Registration flow | Email/OTP |
|---|---|---|---|
| **standalone** | SQLite (Windows/Linux desktop) | username+password directly | **None** — no email server required |
| **server** | PostgreSQL (multi-user) | email → 6-digit OTP → token → profile | **Mandatory** for verification and notifications |
| **auto** (default) | derived from the database | sqlite → standalone, postgresql → server | — |

In **standalone**:
- the **first user** creates the active ADMIN with only a username and password;
- later users are created as CAPOSALA **awaiting approval** (the ADMIN activates them from
  Users, with no email involved);
- the OTP endpoints answer `OTP_NOT_REQUIRED` (the UI hides them automatically).

In **server**:
- registration with email verification via OTP (as in section 6);
- CAPOSALA users are created as pending, with an **email notification** to the active ADMINs.

The value can be set in `.env` (`APP_REGISTRATION_MODE=standalone|server|auto`) or in the
`application-*.properties` profiles, but **the wizards never write it**: the `auto` default
derives it from the engine at runtime — SQLite → standalone, PostgreSQL → server. Set it by
hand only to override that.

---

## 3. Manual installation (without the wizards)

### 3.1 Clone and build

```bash
git clone https://github.com/MirkoUgoliniDev/employee-scheduling.git
cd employee-scheduling
```

### 3.2 Frontend (required before the first build)

```bash
cd frontend
npm install
npm run build      # produces the static assets served by Quarkus
cd ..
```

### 3.3 Backend — SQLite desktop mode

The build must use the **uber-jar** option (the default fast-jar of Quarkus 3 does not produce
a standalone executable file):

```bash
mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile=sqlite
```

**`-Dquarkus.profile` is not optional.** The data engine (`quarkus.datasource.db-kind`) and the
Flyway migration directories are fixed at **build time**: no environment variable can change
them afterwards. Building without a profile makes Flyway find the same migration in both
`db/migration/sqlite` and `db/migration/postgresql`, and it stops with *"Found more than one
migration with version 1"*. For a PostgreSQL server use `-Dquarkus.profile=postgresql`.

The executable JAR is `target/employee-scheduling-<version>-SNAPSHOT-runner.jar`.

**Windows** — run:

```powershell
$env:AUTH_SESSION_KEY = "a-cryptographic-key-of-at-least-32-characters!!"
java -jar target\employee-scheduling-<version>-SNAPSHOT-runner.jar
```

**Linux** — run:

```bash
export AUTH_SESSION_KEY=a-cryptographic-key-of-at-least-32-characters!!
java -jar target/employee-scheduling-<version>-SNAPSHOT-runner.jar
```

Open the browser at `http://localhost:8080` → the **first registration** creates the ADMIN with
a username and a password only. With the `sqlite` profile the mode resolves to *standalone*, so
there is no email and no OTP — see § 2.1.

### 3.4 Backend — PostgreSQL mode

Create the database (once):

```bash
# local PostgreSQL
sudo -u postgres psql -c "CREATE ROLE employee_scheduling LOGIN PASSWORD 'choose-a-strong-password';"
sudo -u postgres psql -c "CREATE DATABASE employee_scheduling OWNER employee_scheduling;"
```

**Rebuild the jar for this engine first.** The one produced in § 3.3 was built with
`-Dquarkus.profile=sqlite`, and no environment variable can change that afterwards: the engine
and the Flyway locations are fixed at build time. Setting `QUARKUS_PROFILE=postgresql` on a
SQLite jar makes the service start and die with *"Driver does not support the provided URL"*.

```bash
mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile=postgresql
```

Then run it. `QUARKUS_PROFILE` below is belt-and-braces — the value that counts is the one
baked in above:

```bash
export QUARKUS_PROFILE=postgresql
export DATABASE_URL=jdbc:postgresql://localhost:5432/employee_scheduling
export DATABASE_USERNAME=employee_scheduling
export DATABASE_PASSWORD=the-password-you-chose
export AUTH_SESSION_KEY=a-cryptographic-key-of-at-least-32-characters!!
export BACKUP_ADMIN_TOKEN=a-long-random-token
java -jar target/employee-scheduling-<version>-SNAPSHOT-runner.jar
```

The Flyway migrations create the schema automatically on first startup.

### 3.5 Email configuration (OTP)

The app sends OTPs and notifications over SMTP. Which route applies depends on how it was
installed — and the two are not interchangeable:

**A. For an installed package (MSI): `%LOCALAPPDATA%\EmployeeScheduling\config.properties`**,
the only route that overrides what was baked in at packaging time (ordinal 450 against 400).
The `quarkus.mailer.*` keys are runtime configuration, so the override does take effect.

**B. `.env` file** (next to the JAR, in the process working directory) — development and
`java -jar` only. Its ordinal is 295, **below** the packaged `-D` options, so it cannot correct
an MSI installation:

```ini
QUARKUS_MAILER_HOST=smtp.example.com
QUARKUS_MAILER_PORT=587
QUARKUS_MAILER_USERNAME=no-reply@example.com
QUARKUS_MAILER_PASSWORD=smtp-password
QUARKUS_MAILER_FROM=no-reply@example.com
QUARKUS_MAILER_MOCK=false
```

**C. From the interface** — Configuration → Email parameters (takes effect immediately, no
restart). **Careful: this does not cover the OTP.** Those settings are used for reports and
notifications, which build their own mail client from the database row; registration codes go
through the Quarkus `Mailer`, that is `quarkus.mailer.*`. On a package, an SMTP server
configured only from the interface delivers reports and never delivers a single OTP.

**D. Mock (development/testing only)** — `QUARKUS_MAILER_MOCK=true`, or absent in dev mode:
emails end up in the **logs** and are never sent.

> **Careful**: with `QUARKUS_MAILER_MOCK=true` the OTPs are readable in the log. That is fine in
> development; in production it must always be `false` with a real SMTP server.

---

## 4. Native Windows application (jpackage)

> Summary section. The complete version — with the pitfalls encountered, their symptoms, and
> the pre-release checklist — is in
> [`docs/PACKAGING-WINDOWS-MSI.md`](PACKAGING-WINDOWS-MSI.md).

The wizard automates everything; the manual steps, **in this order**, are:

```powershell
# 1. Frontend BEFORE the jar: the build lands in src\main\resources\META-INF\resources
cd frontend; npm install; npm run build; cd ..

# 2. Uber-jar. Two mandatory things:
#    - uber-jar: the default fast-jar does NOT produce *-runner.jar
#    - profile at BUILD time: quarkus.flyway.locations is build-time; without it
#      the package includes the migrations of both engines and does not start
mvn -B -ntp package -DskipTests "-Dquarkus.package.jar.type=uber-jar" "-Dquarkus.profile=sqlite"

# 3. Staging: jpackage copies the WHOLE --input directory into the application
New-Item -ItemType Directory -Force -Path target\jpackage-input | Out-Null
Copy-Item target\employee-scheduling-<version>-SNAPSHOT-runner.jar target\jpackage-input\
# these two live in scripts\, not in the repository root: a wrong path here produces
# a package with NO uninstaller, silently
Copy-Item scripts\uninstall-windows.ps1, scripts\uninstall.cmd target\jpackage-input\

# 4. Random session key: below 16 characters the application answers 500 on every login.
#    The backup token is just as mandatory: without it /backup answers 503 and the
#    Backup page is dead, while scheduled backups keep running — a silent failure.
# RNGCryptoServiceProvider and not [RandomNumberGenerator]::Fill: the static
#    Fill overload is .NET Core only, and powershell.exe runs on .NET Framework,
#    where the line fails and leaves $key empty. Nor Get-Random, which is a
#    time-seeded System.Random: both these values are secrets.
$bytes = New-Object byte[] 32
$rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
$rng.GetBytes($bytes)
$key = -join ($bytes | ForEach-Object { $_.ToString('x2') })
$tokenBytes = New-Object byte[] 32
$rng.GetBytes($tokenBytes)
$rng.Dispose()
$backupToken = -join ($tokenBytes | ForEach-Object { $_.ToString('x2') })

# 5. MSI installer (WiX 3.14 in C:\tools\wix314, downloaded by the wizard the first time)
$env:Path = "C:\tools\wix314;" + $env:Path
jpackage --type msi --name "EmployeeScheduling" --app-version <version> `
  --input target\jpackage-input `
  --main-jar employee-scheduling-<version>-SNAPSHOT-runner.jar `
  --dest dist --win-menu --win-dir-chooser --win-shortcut --win-shortcut-prompt `
  --java-options "-Dapp.data.dir=auto" `
  --java-options "-Dquarkus.http.port=8080" `
  --java-options "-Dquarkus.mailer.mock=true" `
  --java-options "-Dbackup.admin-token=$backupToken" `
  --java-options "-Dquarkus.http.auth.session.encryption-key=$key" `
  --java-options "-Dquarkus.log.file.enable=true" `
  --java-options "-Dquarkus.log.file.level=INFO" `
  --java-options "-Dapp.demo-data.enabled=false" `
  --java-options "-Dapp.open-browser-on-start=true"
```

The version is not typed by hand: `Get-AppVersion` reads it from `pom.xml` and strips
`-SNAPSHOT`.

For the portable directory: identical with `--type app-image`, without **any** `--win-*`
option — they are installer-only and jpackage rejects them on an app-image.

Result:

- `dist\EmployeeScheduling-<version>.msi` (installer) or `dist\EmployeeScheduling\EmployeeScheduling.exe` (portable);
- an "EmployeeScheduling" entry in the Start menu;
- uninstallation via `<install>\app\uninstall.cmd` or from Settings → Apps.

**Configuring the installed app.** Settings are passed as individual `-D` options, which
jpackage writes into `<install>\app\EmployeeScheduling.cfg`. Whoever installs it can then
correct any value in `%LOCALAPPDATA%\EmployeeScheduling\config.properties`, which **takes
precedence** over those `-D` options (see § 0.1).

> **Do not use `-Dquarkus.config.locations=...\.env`**: in Quarkus 3.37 that property does not
> accept `file:///` URIs, and the value is looked up as a class name
> (`ClassNotFoundException`). Earlier versions of this guide suggested it: that was wrong.

**Where the data ends up**: `%LOCALAPPDATA%\EmployeeScheduling` (database, backups, log,
`config.properties`), **never** in the installation directory. That is why the installation can
safely live in `C:\Program Files` and uninstallation takes nothing of yours with it.

---

## 5. Installing as a Linux service (systemd)

The wizard generates the file, but by hand:

```bash
# Data directory, owned by the service user
sudo useradd --system --no-create-home --home-dir /var/lib/employee-scheduling \
     --shell /usr/sbin/nologin employee-scheduling
sudo mkdir -p /var/lib/employee-scheduling
sudo chown -R employee-scheduling:employee-scheduling /var/lib/employee-scheduling
```

`/etc/systemd/system/employee-scheduling.service`:

```ini
[Unit]
Description=Employee Scheduling (staff shifts)
# network-online.target on both lines: pulling it in with Wants while
# ordering After=network.target means never actually waiting for the network.
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=employee-scheduling
Group=employee-scheduling
WorkingDirectory=/var/lib/employee-scheduling
EnvironmentFile=/etc/employee-scheduling.env
# -Dapp.data.dir and the APP_DATA_DIR variable in the environment file are
# equivalent: AppDataDirectory reads the system property first, then the
# variable. The generated units set both, so that the uninstaller can also
# find a non-default directory. What matters is that ONE of the two is
# present: it is what moves database, backups and settings together, instead
# of writing them relative to WorkingDirectory.
ExecStart=/usr/bin/java -Dapp.data.dir=/var/lib/employee-scheduling -jar /opt/employee-scheduling/employee-scheduling-<version>-SNAPSHOT-runner.jar
Restart=on-failure
RestartSec=5
# Hardening. ProtectSystem=strict makes the whole filesystem read-only, so
# ReadWritePaths is mandatory alongside it, or the service cannot write anything.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/var/lib/employee-scheduling

[Install]
WantedBy=multi-user.target
```

`/etc/employee-scheduling.env` (mode 600, root only):

```ini
AUTH_SESSION_KEY=a-cryptographic-key-of-at-least-32-characters!!
QUARKUS_PROFILE=sqlite
APP_DATA_DIR=/var/lib/employee-scheduling
BACKUP_ADMIN_TOKEN=a-long-random-token
QUARKUS_MAILER_HOST=smtp.example.com
QUARKUS_MAILER_PORT=587
QUARKUS_MAILER_USERNAME=no-reply@example.com
QUARKUS_MAILER_PASSWORD=smtp-password
QUARKUS_MAILER_FROM=no-reply@example.com
QUARKUS_MAILER_MOCK=false
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now employee-scheduling
sudo systemctl status employee-scheduling
journalctl -u employee-scheduling -f     # live log
```

> With PostgreSQL it is enough to change the variables to `QUARKUS_PROFILE=postgresql` plus
> `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`.

> Writing this unit by hand is rarely worth it: `scripts/install-linux.sh` and the Raspberry
> wizard generate an **equivalent** unit and stay in sync with the code. Theirs adds further
> confinement (`ProtectHome`, `ProtectKernelTunables`, `ProtectKernelModules`,
> `ProtectControlGroups`, `RestrictSUIDSGID`, `RestrictNamespaces`, `LockPersonality`,
> `TimeoutStopSec`, and `RequiresMountsFor` in the Python wizard) and writes `QUARKUS_HTTP_PORT`
> and `APP_DEMO_DATA` into the environment file. Read the generated file for the authoritative
> version; use this section to understand what it produces, or to adapt it.

---

## 6. First startup and initial configuration

1. Open `http://localhost:8080` (or the server machine's address);
2. Click **Register**;
3. **Server mode only** (PostgreSQL): enter the email → receive the OTP (in mock mode: in the
   server log) → enter the code. In standalone mode (SQLite, the Windows desktop package) these
   two screens never appear;
4. Choose a username and password;
5. **First user = active ADMIN** (the page says so); later users are created as CAPOSALA
   awaiting approval (in server mode, with an email notification to the ADMINs);
6. From **Users**, the ADMIN approves the CAPOSALA users and (in the future) assigns the
   structure.

---

## 7. Backup and updating

### Backup

- **SQLite**: automatic backup in `<data>/backups` (default every 30 minutes, retention 48
  files, configurable from Configuration → Backup). Take a hot copy from the Backup panel,
  which uses `VACUUM INTO` — atomic and consistent. **Do not copy the `.db` file by hand while
  the application runs**: without the `-wal`/`-shm` files the copy is not consistent.
- **PostgreSQL**: automatic `pg_dump` into the same directory; restoring from the panel is
  protected by a token.

### Updating

```bash
git pull
cd frontend && npm install && npm run build && cd ..
# Same profile as the existing installation: sqlite or postgresql.
mvn package -DskipTests -Dquarkus.package.jar.type=uber-jar -Dquarkus.profile=sqlite
# then: restart the service (systemd) or regenerate the jpackage package
```

**The data is never touched**: database, backups, and local configuration live outside the
application directory — on Windows in `%LOCALAPPDATA%\EmployeeScheduling`.

> For a jpackage installation (app-image or MSI) you do not "replace a JAR": you regenerate the
> whole package by running section 4 again.

**Updating an existing Windows installation**:

1. `<install>\app\uninstall.cmd` — closes the application, uninstalls, and keeps the data;
2. install the new MSI;
3. the application finds the database, backups, and `config.properties` where it left them.

Uninstalling first is necessary: `--app-version` comes from `pom.xml`, and installing over an
installation carrying the **same** version number is not reliable.

---

## 8. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Port 8080 already in use` | Another process on the port | **Installed app**: `quarkus.http.port=8081` in `%LOCALAPPDATA%\EmployeeScheduling\config.properties`, then restart. **Development**: `QUARKUS_HTTP_PORT=8081` in `.env` |
| "Server unreachable" at login, 500 on `/auth/me` | Session key shorter than 16 characters | Regenerate the package with a long key — [PACKAGING-WINDOWS-MSI § 7.1](PACKAGING-WINDOWS-MSI.md) |
| Uninstallation blocked (`app.log` in use, or `GetLastError: 5`) | Application still open, or permissions rewritten by an old version | Use `<install>\app\uninstall.cmd` — [§ 7.3 and § 7.4](PACKAGING-WINDOWS-MSI.md) |
| Interface always in Italian, language selector inert | `localStorage` quota exhausted by old caches | In the browser console: remove the `i18n_cache*` keys and reload — [§ 7.11](PACKAGING-WINDOWS-MSI.md) |
| Empty lists with no error at all after a reinstall | Selected structure left in `localStorage` and no longer existing | Fixed since 5 August 2026; on earlier versions, reselect the structure from the top bar |
| `.ps1` opens in Notepad | `.ps1` files do not run on a double-click | Use `uninstall.cmd`, not the script directly |
| The OTP never arrives | SMTP in mock mode or not configured | Check the log; configure SMTP (section 3.5) |
| `Unrecognized configuration key` | Wrong profile | Use an explicit `QUARKUS_PROFILE=sqlite` or `postgresql` |
| Login blocked ("pending") | CAPOSALA not approved | The ADMIN approves them from Users |
| Backup disabled (PostgreSQL) | `pg_dump` not found | Install the PostgreSQL client tools or set `backup.postgresql.bin-dir` |
| The app does not start as a service | `.env` file not readable | Check mode 600 and absolute paths |
