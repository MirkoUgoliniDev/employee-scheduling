# Windows package (MSI) — consolidated document

**Status: current as of 6 August 2026.** This file is the source of truth on Windows packaging:
where it contradicts other documents, this one wins. Whoever reads it from start to finish can
produce, verify, and ship a working installer without repeating the mistakes already made —
each of them documented in section 7 with the **symptom observed**, the real cause, and the
remedy applied.

---

## 1. What is produced, and for whom

A single file, `dist\EmployeeScheduling-<version>.msi` (~125 MB), that serves **every**
installation. Whoever receives it needs nothing else: no Java, no repository, no development
tools. The JRE is inside the package.

What differs between installations — port, SMTP, registration mode — **never requires a
rebuild**. Port and SMTP are written into the package as `-D` defaults, but the text file next
to the user's data overrides them (section 5); the registration mode is not in the package at
all, it is derived from the engine at runtime.

| Artifact | What it is | When it is needed |
|---|---|---|
| `dist\EmployeeScheduling-<version>.msi` | Installer with Start menu entry and uninstallation | This is **the** thing you ship |
| `dist\EmployeeScheduling-<version>-windows-x64.zip` | The same app, portable, to unpack | For those who cannot or will not install |
| `dist\EmployeeScheduling\` | The unpacked app-image (contents of the zip) | Quick trials without installing |

---

## 2. Prerequisites on the building machine

Only on the machine that **produces** the package, never on the one that installs it.

| Tool | Version | Notes |
|---|---|---|
| Temurin JDK | **21** | `jpackage` is needed, and it ships with the JDK. Typical path: `C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot\bin\jpackage.exe` |
| Maven | 3.9.x | Typical: `C:\Program Files\Maven\apache-maven-3.9.13\bin\mvn.cmd`. It may be absent from the PATH of a `powershell -File` process: the script falls back to `MAVEN_HOME` |
| Node.js | 24 | For the frontend build |
| WiX Toolset | **3.14** | Needed **only** for `--type msi`. `install-windows.ps1` downloads it by itself into `C:\tools\wix314` (~39 MB). Check: `C:\tools\wix314\candle.exe` must exist |

> `jpackage --type msi` requires WiX **3.x**: with WiX 4/5 it fails. Do not upgrade it "for
> hygiene".

---

## 3. Quick procedure — the wizard

```powershell
cd <your-clone-of>\employee-scheduling
powershell -ExecutionPolicy Bypass -File .\scripts\install-windows.ps1
```

At the final packaging question, answer **`2` = MSI installer**. The wizard runs the chain of
section 4 by itself and writes `dist\EmployeeScheduling-<version>.msi`.

### 3.1 Non-interactive mode (task/button)

For automation or VS Code buttons:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\install-windows.ps1 -Package msi
```

In this mode the script **asks nothing**: it uses SQLite, port 8080, and mock SMTP, rebuilds
frontend and backend, and generates the MSI. `-Package` also accepts `app-image` and `none`,
and the `-DemoData` switch loads the sample dataset (off by default).

In VS Code there is a **Packaging: MSI (rebuild)** task; if the status bar button is configured
(Status Bar Commands extension), it appears as **"MSI"**.

The wizard is **interactive** (`Read-Host`) only without `-Package`. With `-Package` it is
suitable for automation or tasks.

---

## 4. Manual procedure, step by step

The four steps must run **in this order**. Skipping one produces a package that looks fine and
is not.

### 4.1 Frontend

```powershell
cd frontend
npm install
npm run build      # tsc -b && vite build
cd ..
```

The output lands in `src\main\resources\META-INF\resources\` (see `vite.config.ts`,
`build.outDir`), so it **must run before** step 4.2: the jar embeds that directory exactly as it
is on disk. A jar built before the frontend build contains the **previous** interface, and the
defect you think you fixed is still there.

### 4.2 Uber-jar

```powershell
mvn -B -ntp package -DskipTests "-Dquarkus.package.jar.type=uber-jar" "-Dquarkus.profile=sqlite"
```

Two non-negotiable details:

- **`uber-jar`**: the default format (fast-jar) does **not** produce `*-runner.jar`, and
  jpackage needs a single self-contained jar.
- **`-Dquarkus.profile=sqlite` at build time**: `quarkus.flyway.locations` is a **build-time**
  property. Without this, the package carries the migrations of **both** engines and fails at
  startup with duplicate migrations. For a PostgreSQL package:
  `-Dquarkus.profile=postgresql`.

Result: `target\employee-scheduling-<version>-SNAPSHOT-runner.jar` (~77 MB).

### 4.3 Staging directory

```powershell
$staging = "target\jpackage-input"
Remove-Item $staging -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $staging | Out-Null
Copy-Item target\employee-scheduling-<version>-SNAPSHOT-runner.jar $staging
Copy-Item scripts\uninstall-windows.ps1 $staging
Copy-Item scripts\uninstall.cmd $staging
```

jpackage copies the **entire `--input` directory** into the application: only what you want to
distribute may be in it. Pointing `--input` at `target\` means shipping the whole build
directory. The two uninstallation files travel here, and end up in `<install>\app\`. **They live in
`scripts\`, not in the repository root**: `install-windows.ps1` carries an explicit warning
about this, because a wrong path makes `Test-Path` fail silently and produces a package with
no uninstaller and no warning.

### 4.4 jpackage

This procedure uses two helpers defined in `scripts\install-windows.ps1`: `ConvertTo-WixBitmap`
(bitmap resizing) and `Get-AppVersion`. Dot-source the script to get them
(`. .\scripts\install-windows.ps1` does define the functions, but it also runs the wizard — in
practice, copy the two function bodies, or resize the bitmaps by hand to 370x44 and 370x234).

```powershell
$env:Path = "C:\tools\wix314;" + $env:Path
# jpackage wants a numeric three-component version: read it from pom.xml and
# strip -SNAPSHOT, which is what Get-AppVersion does.
$appVersion = "1.2.9"

# random 64-hex-character key: see 7.1 — below 16 characters the app does not start
$bytes = New-Object byte[] 32
$rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
$rng.GetBytes($bytes)
$rng.Dispose()
$key = -join ($bytes | ForEach-Object { $_.ToString('x2') })

# backup token: never empty (see 7.2 and 7.9-ter), otherwise the app does not
# start or the Backup section answers 503 silently. Generated with the same
# CSPRNG as the session key: this token gates the whole /backup API, restore
# included, and Get-Random is a time-seeded System.Random, not a CSPRNG.
$tokenBytes = New-Object byte[] 32
$rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
$rng.GetBytes($tokenBytes)
$rng.Dispose()
$backupToken = -join ($tokenBytes | ForEach-Object { $_.ToString('x2') })

# MSI images (WiX UI): when present, they override the standard UI.
# Real control sizes (Control table of the MSI produced by jpackage):
# banner 370x44 px, dialog background 370x234 px (dialogs are 370x270; the bottom
# band holds the buttons). The text is drawn ON TOP of the images at fixed positions:
#   - dialog: title+description at x=135..355, y=20..140 → keep that area
#     dark/clean; put the logo or artwork on the left (x<135) or below (y>140);
#   - banner: title x=15..215 y=6..21, description x=25..305 y=23..38 → only
#     ~65px on the right (x>305) and the 6px bands above/below are free.
$resDir = "target\jpackage-resources"
Remove-Item $resDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $resDir | Out-Null
if (Test-Path "assets\app\installer\dialog.bmp") {
  ConvertTo-WixBitmap -SourcePath "assets\app\installer\dialog.bmp" `
    -DestPath "$resDir\WixUIDialog.bmp" -Width 370 -Height 234
}
if (Test-Path "assets\app\installer\banner.bmp") {
  ConvertTo-WixBitmap -SourcePath "assets\app\installer\banner.bmp" `
    -DestPath "$resDir\WixUIBanner.bmp" -Width 370 -Height 44
}
# main.wxs override: the images must be declared as <WixVariable> (not as
# <?define ... ?>: main.wxs reads only JpProductLanguage and JpInstallerVersion
# from overrides.wxi). Mind the WiX v3 XSD (verified with wix314):
#   - under <Wix> they are not allowed (CNDL0005);
#   - in a trailing <Fragment> they compile (after Product, otherwise CNDL0107) but light
#     DISCARDS sections unreachable from Product: the default bitmaps stay;
#   - inside <Product> they are valid (position fixed by the Product sequence:
#     right after <Package>, before <Media>) and they work.
$javaHome = $env:JAVA_HOME
if (-not $javaHome) { $javaHome = (Get-Command java.exe -ErrorAction SilentlyContinue).Source | Split-Path -Parent | Split-Path -Parent }
$jmod = Join-Path $javaHome "jmods\jdk.jpackage.jmod"
$jmodExe = Join-Path $javaHome "bin\jmod.exe"
if ((Test-Path $jmod) -and (Test-Path $jmodExe)) {   # each Test-Path in its own parentheses
  $extractDir = Join-Path $env:TEMP "jpackage-mainwxs-extract"
  Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue
  & $jmodExe extract --dir $extractDir $jmod | Out-Null
  $src = Join-Path $extractDir "classes\jdk\jpackage\internal\resources\main.wxs"
  $content = Get-Content $src -Raw
  $resDirAbs = (Resolve-Path $resDir).Path
  # Doubled quotes, not backslashes: in PowerShell the escape character is the
  # backtick, so \" is a literal backslash plus a quote. With \" the injected
  # XML is invalid AND the .Replace() marker never matches the real
  #     <Media Id="1"  — the patch silently does nothing and the default
  # bitmaps survive, which is exactly the failure of 7.13.
  $wixVars = @(
    "    <WixVariable Id=""WixUIDialogBmp"" Value=""$resDirAbs\WixUIDialog.bmp"" />",
    "    <WixVariable Id=""WixUIBannerBmp"" Value=""$resDirAbs\WixUIBanner.bmp"" />"
  )
  $patched = $content.Replace("    <Media Id=""1""", ($wixVars -join "`r`n") + "`r`n    <Media Id=""1""")
  Set-Content "$resDir\main.wxs" -Value $patched -Encoding UTF8
}

$jopts = @(
  '--java-options','-Dapp.data.dir=auto',
  '--java-options','-Dquarkus.http.port=8080',
  '--java-options','-Dquarkus.mailer.mock=true',
  '--java-options',"-Dbackup.admin-token=$backupToken",
  '--java-options',"-Dquarkus.http.auth.session.encryption-key=$key",
  '--java-options','-Dquarkus.log.file.enable=true',
  '--java-options','-Dquarkus.log.file.level=INFO',
  '--java-options','-Dapp.open-browser-on-start=true',
  # demo data: off unless the wizard was invoked with -DemoData
  '--java-options','-Dapp.demo-data.enabled=false',
  '--resource-dir', $resDir
)
# optional application icon, when present
if (Test-Path "assets\app\app-icon.ico") { $jopts += '--icon', "assets\app\app-icon.ico" }

jpackage --type msi --name "EmployeeScheduling" --app-version $appVersion `
  --input target\jpackage-input `
  --main-jar employee-scheduling-<version>-SNAPSHOT-runner.jar `
  --dest dist --win-menu --win-dir-chooser --win-shortcut --win-shortcut-prompt @jopts
```

> **Welcome text on the left**: the wizard does this automatically
> (`Invoke-MsiTextPositionPatch`, after jpackage), and **the build fails if the patch fails**.
> Mind that the unpatched `.msi` is still left in `dist\`: delete it before retrying, or you
> risk shipping the bad one. The same function also rewrites the WelcomeDlg title to include
> `[ProductVersion]`. In this manual procedure, apply §7.14 by hand.

> **The version comes from `pom.xml`**, it is not typed by hand: `Get-AppVersion` reads it,
> strips `-SNAPSHOT`, and brings it to three components (`1.2-SNAPSHOT` → `1.2.0`), because
> jpackage wants numeric versions. It is the same version the installed application compares
> with the latest published one (section 9.1): two disconnected numbers would make the notice
> wrong in one direction or the other.

For the app-image (portable directory) it is identical with `--type app-image` and **without any
of the `--win-*` options**: `--win-menu`, `--win-dir-chooser`, `--win-shortcut` and
`--win-shortcut-prompt` are installer-only, and jpackage rejects them on an app-image.

---

## 5. Configuration: who wins over whom

This table explains 90% of the surprising behaviour. Higher ordinal = wins.

| Ordinal | Source | Where | Who writes it |
|---:|---|---|---|
| 450 | `AppUserConfigSource` | `%LOCALAPPDATA%\EmployeeScheduling\config.properties` | **Whoever installs**, by hand |
| 400 | System properties | `--java-options` in the jpackage `.cfg` | Whoever packages |
| 320 | `AppDataDirConfigSource` | computed at startup | The code |
| 300 | Environment variables | process environment | — |
| 295 | `.env` file | working directory | The wizard (dev mode only) |
| 250 | `application.properties` | inside the jar | The repository |

**The user's file sits above the system properties on purpose.** The options baked into the
package are *defaults*: whoever installs must be able to fix a port 8080 already taken by
another program without depending on whoever produced the MSI. Verified live: package with
`-Dquarkus.http.port=8099`, file with `quarkus.http.port=8123` → the application answers on
**8123**.

One exception: **`app.data.dir` is ignored** when written in the user's file. By the time that
file is read, the data directory has already been resolved; honouring it would produce a
configuration that says one thing and an application that does another.

The `config.properties` file is **created on first startup, already commented and explained**
(`AppUserConfigSource.writeTemplate`): port, SMTP, registration mode, session key, backup
token, log level. Whoever installs removes the `#`, saves, and restarts.

### Why not `quarkus.config.locations`

It is the obvious-looking route and it is **a dead end**: in Quarkus 3.37 that property does not
accept `file:///` URIs, and the value is looked up as a class name (`ClassNotFoundException`).
Configuration must be passed as individual `-D` options, which jpackage writes into the `.cfg`.
Any document suggesting `-Dquarkus.config.locations=...\.env` is obsolete.

---

## 6. Where the data lives

```
%LOCALAPPDATA%\EmployeeScheduling\
├── employee_scheduling.db            SQLite database
├── employee_scheduling.db-shm/-wal   SQLite side files (WAL)
├── backups\                 automatic backups
├── app.log (+ app.log.N)    rotated log
├── config.properties        editable configuration (section 5)
└── app.lock                 single-instance lock
```

**Outside the installation directory**, and that is not a cosmetic detail: it is the fix for
three real failures (7.3, 7.4, 7.5). Useful consequences:

- updates and uninstallation do not touch the database;
- the installation can live in `C:\Program Files` without permission problems;
- to move an installation to another PC, install the MSI and copy that directory.

The path cannot be written into the `.cfg`: **jpackage expands `$APPDIR` but not environment
variables**. That is why the package passes `-Dapp.data.dir=auto` and resolution happens at
runtime in `AppDataDirectory`: `%LOCALAPPDATA%\EmployeeScheduling` on Windows,
`~/.employee-scheduling` elsewhere.

> **`$APPDIR` resolves to `<install>\app`, not to the installation root.** That cost half an
> hour of hunting for the "vanished" database every single time. Nobody needs it today, but
> anyone reintroducing `$APPDIR`-based paths must know it.

---

## 7. Pitfalls encountered, and how they were closed

Each one was observed on a real machine, not deduced.

### 7.1 Short session key → the application answers 500 on every login

**Symptom**: the interface shows *"Server non raggiungibile. Riprova fra qualche istante."*
(the application's own Italian string, quoted verbatim so it stays greppable); `GET /auth/me`
and `POST /j_security_check` answer **500**; in the log:
`RuntimeException: Shared keys for persistent logins must be more than 16 characters long`.

**Cause**: `PersistentLoginManager` rejects keys shorter than 16 characters. Since
`FormAuthenticationMechanism` is a synthetic bean, the exception in the constructor becomes a
`CreationException` and **every** authenticated request fails.

**Remedy**: pass `-Dquarkus.http.auth.session.encryption-key=<64 hex chars>` directly. We no
longer rely on `-DAUTH_SESSION_KEY`, which is only the default value of the expression in
`application.properties` and had a more fragile resolution path.

**Always check** (section 8): one installation ended up with the key
`0123456789abcdef0123456789abcdef`. Predictably: anyone who knows it can **forge a valid
session cookie for any user**, because the cookie encrypts only the expiry and the username.

### 7.2 Empty `backup.admin-token` → Quarkus does not start at all

**Symptom**: at startup (and in `mvn test` from the working copy)
`ConfigurationException: Failed to load config value of type class java.lang.String for:
backup.admin-token`, with `SRCFG00040: ... defined as the empty String ("") which the following
Converter considered to be null`.

**Cause**: `application.properties` declares `backup.admin-token=${BACKUP_ADMIN_TOKEN:}` and the
wizard wrote that variable into `.env` **only** in the PostgreSQL branch.

**Remedy**: the wizard writes it for both engines. If you run the suite by hand from a working
copy with an old `.env`:

```powershell
$env:BACKUP_ADMIN_TOKEN='any-non-empty-value'
mvn -B -ntp test "-Dquarkus.test.profile=test-sqlite"
```

### 7.3 Uninstallation cannot remove `app.log`

**Symptom**: a window saying *"Another application has exclusive access to the file
C:\...\app\data\app.log. Please shut down all other applications, then click Retry."*

**Cause**: the log lived inside the installation and the application was still running.

**Remedy**: data outside the installation (section 6). In addition, `uninstall-windows.ps1`
closes the application **before** calling `msiexec`.

### 7.4 Uninstallation fails with `GetLastError: 5`

**Symptom**: *"Error getting file security: C:\...\app\data\backups\ GetLastError: 5"*.

**Cause** — the most insidious of the lot: `BackupService.restrictPermissions` replaced the
**entire ACL** of the backup directory with a single entry (the owner), disabling inheritance.
That removed **SYSTEM and Administrators**. The MSI uninstaller runs precisely as SYSTEM: it
could not even read the permissions. Note that the hardening *worked*: it was its success that
broke uninstallation.

**Remedy**: `restrictPermissions` no longer rewrites the DACL inside directories that are
already private to the user (`%LOCALAPPDATA%`, home) — where it would be pointless — and no
longer propagates errors (it used to make the scheduled automatic backup fail on every run,
silently except for the log).

**Repairing an already broken installation** (works without administrator privileges if you own
the directory; group names are localized, so SIDs are used):

```powershell
cmd /c "icacls `"C:\path\to\app\data`" /inheritance:e /grant *S-1-5-18:(OI)(CI)F /grant *S-1-5-32-544:(OI)(CI)F /T /C"
```

### 7.5 Uninstallation takes the database away

**Cause**: the data lived in `<install>\app\data`.

**Remedy**: section 6. Before uninstalling an **old** version (earlier than 5 August 2026), save
`<install>\app\data\employee_scheduling.db` by hand.

### 7.6 A `.ps1` file does not run on a double-click

**Symptom**: Windows opens *"Selezionare un'app per aprire questo file .ps1"* ("Choose an app
to open this .ps1 file") and suggests Notepad.

**Cause**: standard Windows behaviour, `.ps1` files are not executable by double-click.

**Remedy**: `uninstall.cmd`, shipped next to the script, which calls PowerShell with
`-ExecutionPolicy Bypass` and forwards the arguments.

### 7.7 Two `EmployeeScheduling.exe` processes

**Not a defect**: one is the native jpackage launcher, the other the JVM. Closing the
application for real requires both:

```powershell
Get-Process -Name EmployeeScheduling -ErrorAction SilentlyContinue | Stop-Process -Force
```

UI alternative (recommended): user menu → **Close application**.

### 7.8 A second instance does not start, even on a different port

**Symptom**: while testing, the application does not answer and the log says
*"Employee Scheduling e' gia' in esecuzione"* — the log line is Italian and
ASCII-transliterated (`SingleInstanceGuard`).

**Cause**: `SingleInstanceGuard` uses an `app.lock` file **inside the resolved data directory**
(`%LOCALAPPDATA%\EmployeeScheduling` is only the default base). It is independent of the
**port**, so two instances on different ports do collide; a different `-Dapp.data.dir`, on the
other hand, gets its own lock. Correct for a desktop app, annoying for testing.

**Remedy while testing**: `-Dapp.single-instance-lock=false`.

Note that the guard is inert outside a package: with no `app.data.dir`/`APP_DATA_DIR` set —
development, `mvn quarkus:dev`, the tests — the base directory is `null`, `enforce()` returns
immediately, and no `app.lock` is created at all. Do not go looking for one from a checkout.

**Current behaviour**: the second instance opens the browser on the first one and exits
immediately.

### 7.9 Regenerating the app-image leaves the exe locked

**Symptom**: `Access to the path 'EmployeeScheduling.exe' is denied`, immediately followed by
`Error: Application destination directory ... already exists`. If the zip gets created anyway,
it comes out **corrupt** (a few hundred KB instead of 125 MB).

**Cause**: an Explorer window open on that directory, or the antivirus scanning the
freshly created executable.

**Remedy**: build the app-image in `%TEMP%` and create the zip from there, then replace the
directory in `dist` once it is free. **Always check the zip size** before shipping it.

### 7.9-bis `jpackage --type app-image` refuses to overwrite

**Symptom**: `Error: Application destination directory ...\dist\EmployeeScheduling already
exists`, exit 1, on the **second** generation.

**Cause**: it is jpackage's behaviour, not a filesystem lock. Verified that `--type msi` does
**not** have this problem: it rewrites both the `.msi` and the leftover app-image directory
without complaining.

**Remedy**: remove `dist\EmployeeScheduling` before generating the app-image (the wizard does
this). If removal fails, see 7.9.

### 7.9-ter Backup token missing from the package → the Backup section is dead

**Symptom**: in the interface the Backup page does not work; the `/backup` API answers **503
`BACKUP_ADMIN_TOKEN_NOT_CONFIGURED`**. Automatic backups keep appearing in the directory,
though: the failure is silent until somebody opens that page.

**Cause**: `-Dbackup.admin-token` was passed to jpackage **only** in the PostgreSQL branch. The
token is injected as `Optional<String>`, so the application starts anyway — unlike the tests,
which inject it as `String` and fail at startup (7.2).

**Remedy**: the token is passed for both engines.

### 7.9-quater SMTP collected and then thrown away

**Symptom**: PostgreSQL package, registration with email verification: the OTP code never
arrives, it stays written in `app.log`.

**Cause**: the wizard asked for the SMTP parameters but the package was built with
`-Dquarkus.mailer.mock=true` regardless.

**Remedy**: when a real SMTP server has been configured, the five `quarkus.mailer.*` options go
into the package with `mock=false`.

### 7.10 Reinstalling the same version over itself

`--app-version` now comes from `pom.xml`, but until the version changes it stays the same on
every rebuild. Installing the MSI over an installation with the same number is not reliable:
**uninstall first**. It is painless now, since the data does not live there.

### 7.11 A fresh installation looks broken (empty or untranslated interface)

Two causes **on the browser side**, not in the package. They show up for anyone who has used
`localhost:8080` for development for a long time — that is, precisely for whoever tests:

- **Ghost structure**: `localStorage` keeps the selected structure; if its id does not exist in
  the new database, every list comes up empty **with no error at all** (the backend answers 200
  with empty lists). Fixed since 5 August 2026: the Navbar validates the selection against the
  real list.
- **Interface always in Italian, language selector inert**: the translation cache saturates the
  `localStorage` quota and the write fails. Fixed. Immediate unblock on an old installation,
  from the browser console:
   ```js
   Object.keys(localStorage).filter(k => k.startsWith('i18n_cache')).forEach(k => localStorage.removeItem(k)); location.reload()
   ```

### 7.12 `jpackage --type msi` fails with exit 1 ("is WiX installed?")

jpackage **hides the candle/light error** (the wizard wraps it with `Invoke-Native`): rerun
jpackage by hand, without suppressing the output, to see the real error.

Actual cause encountered (6 August 2026): a `<WixVariable>` placed wrongly in `main.wxs`.
WiX v3 XSD rules (wix314, verified empirically with candle):

| Position of `<WixVariable>` | Outcome |
|---|---|
| Directly under `<Wix>` (before `<Product>`) | **CNDL0005** "unexpected child element WixVariable" |
| In a `<Fragment>` before `<Product>` | **CNDL0107** (the `<Wix>` sequence wants Product before the Fragments) |
| In a trailing `<Fragment>`, after `</Product>` | Compiles, but light **discards** the unreachable section: the WixVariables vanish |
| **Inside `<Product>`**, right after `<Package>`, before `<Media>` | **Works** (WixVariable is a valid child of Product; the Product section is light's entry point and is always linked) |

The wizard already takes the fourth route: `New-WixImageOverrideResource` extracts `main.wxs`
from `jdk.jpackage.jmod` (`jmod extract`) and injects the `<WixVariable>` elements anchored on
`    <Media Id="1"` (literal `.Replace()`, not a regex).

PowerShell 5.1 quirk: candle defines such as `-dJpAppVersion=1.2.0` get **split** (the value
becomes `.2.0` and candle looks for a file with that name) → always quote them:
`"-dJpAppVersion=1.2.0"`.

### 7.13 The custom images do not make it into the MSI (the defaults stay)

Symptom: the MSI contains the default WixUIExtension bitmaps, not ours.
Check with dark (the default sizes are **2746** for the banner and **68468** for the dialog):

```powershell
dark.exe -x dist\EmployeeScheduling-<version>.msi
Get-ChildItem dist\*.msi | ForEach-Object { dark.exe -x "$env:TEMP\chk" $_ }  # Binary\
# WixUI_Bmp_Banner / WixUI_Bmp_Dialog must have the size of our bitmaps
# (e.g. 48982 / 260262 for 370x44 / 370x234)
```

Causes and remedies: see 7.12 (position of the WixVariables).

**Real Bitmap control sizes** (from the Control table of the MSI produced by jpackage, NOT the
493x58/493x312 believed in the past): banner **370x44**, dialog background **370x234** (dialogs
are 370x270; the bottom band holds the navigation buttons).

### 7.14 The installer text overlaps the image / is unreadable

The title and the description are drawn **on top of** the bitmaps, at **fixed coordinates**
(defined in the WixUIExtension dialogs, which jpackage uses via `UIRef JpUI`):

| Dialog | Control | Position (X,Y,W,H) |
|---|---|---|
| WelcomeDlg | Title | 135, 20, 220, 60 |
| WelcomeDlg | Description | 135, 80, 220, 60 |
| WelcomeDlg | PatchDescription | 135, 80, 220, 60 |
| Banner (all dialogs) | Title | 15, 6, 200, 15 |
| Banner (all dialogs) | Description | 25, 23, 280, 15 |

- The text is **white** (style `{\WixUI_Font_Bigger}`): on light images it is unreadable
  everywhere. The current masters have an average brightness of 232/255.
- **The dialog cannot be overridden in WiX v3**: defining a custom `<Dialog Id="WelcomeDlg">`
  alongside `UIRef JpUI` yields `LGHT0091 Duplicate symbol 'Dialog:WelcomeDlg'`. The
  fully-custom-UI alternative (replicating JpUI = WixUI_InstallDir + ShortcutPromptDlg +
  InstallDirNotEmptyDlg + 5 Publish events, which jpackage generates at runtime in `ui.wxf` via
  `WixUiFragmentBuilder`) is possible but expensive and risky.
- **Solution adopted (verified)**: a **post-build patch of the MSI's Control table**. The text
  goes into the empty block of the image: X=20, W=175 → it occupies x 20..195, inside the empty
  area x 0..200 measured on the current image (pixel analysis: per-column variance, empty block
  = columns with std < 12 in the band y=10..150). The wizard applies it by itself on every MSI
  build (`Invoke-MsiTextPositionPatch`); if the image changes, recompute the empty block and
  adjust X/W in the function.

Manual fallback script, for when you work by hand — run it **after every jpackage build**.
The wizard itself no longer uses it: it drives the same COM object from PowerShell with late
binding (see 7.15). **Verify the result with `dark.exe` afterwards**: this route has been seen
to report success without persisting anything. `view.Close()`/`db.Close()` do not exist,
**`db.Commit()` is what saves**:

```js
// msi-move-text.js  —  cscript //nologo msi-move-text.js dist\EmployeeScheduling-<version>.msi
var path = WScript.Arguments(0);
var msi = new ActiveXObject("WindowsInstaller.Installer");
var db = msi.OpenDatabase(path, 2); // 2 = transact (write)
var view = db.OpenView("SELECT * FROM Control WHERE Dialog_ = 'WelcomeDlg' AND (Control = 'Title' OR Control = 'Description' OR Control = 'PatchDescription')");
view.Execute();
var rec = view.Fetch();
while (rec) {
  rec.StringData(4) = "20";   // X
  rec.StringData(6) = "175";  // Width
  // The wizard also rewrites the title so it carries the version, and its own
  // verification rejects an MSI without it. Field 10 is the control text.
  if (rec.StringData(2) == "Title") {
    rec.StringData(10) = "{\\WixUI_Font_Bigger}Welcome to [ProductName] [ProductVersion] Setup Wizard";
  }
  view.Modify(3, rec);        // 3 = msiViewModifyUpdate
  rec = view.Fetch();
}
db.Commit();
```

Status: the wizard applies the equivalent patch by itself on every build
(`Invoke-MsiTextPositionPatch`, verified on 8 August 2026 with `install-windows.ps1 -Package
msi`), verifies the result, and **aborts the build if the verification fails**. If the image
changes, recompute the empty block and adjust X/W.

### 7.15 MSI verification quirks (so you do not lose your mind)

- **Early-bound** calls on the `WindowsInstaller` COM object from PowerShell fail
  (`OpenView`/`Fetch` with DISP_E_TYPEMISMATCH). Two ways out, both valid: **late binding** via
  `GetType().InvokeMember('OpenDatabase'/'OpenView'/'Execute'/'Fetch'/'Commit', ...)`, which is
  what the wizard does today, or **cscript + JScript**
  (`ActiveXObject("WindowsInstaller.Installer")`), kept as the manual fallback of 7.14. The
  cscript route was abandoned because on some CI runners it reported success without
  persisting its changes.
- JScript: the SQL clause `IN ('a','b')` fails with "OpenView,Sql" → use explicit `OR`s.
- `SELECT *` is required in order to Modify; the control's **text** is in **field 10** (field 9
  is empty: 1=Dialog_, 2=Control, 3=Type, 4=X, 5=Y, 6=Width, 7=Height, 8=Attributes, 10=Text).
- `db.Commit()` is mandatory (mode 2 = transact); without Commit the changes are lost.
- `dark.exe` emits **DARK1059** warnings (foreign row Control "not found") on jpackage MSIs:
  false alarms, the tables do exist (verified with `SELECT * FROM _Tables`).
- The file `dist\EmployeeScheduling-<version>.msi` **can disappear** between one command and the
  next (user activity, or a process holding it open): rebuild before verifying, with the direct
  jpackage command of §4.4 (the `target\jpackage-input` staging and the
  `target\jpackage-resources` resources stay ready between builds).

---

## 8. Pre-release checklist

To be run **on every package**, before giving it to anyone.

1. **Is the jar newer than the frontend build?**
   ```powershell
   Get-Item target\*runner.jar | Select-Object LastWriteTime
   Get-Item src\main\resources\META-INF\resources\index.html | Select-Object LastWriteTime
   ```
2. **Does the `.cfg` contain what it must** — build the app-image and read it:
   ```powershell
   Get-Content dist\EmployeeScheduling\app\EmployeeScheduling.cfg
   ```
   It must contain `-Dapp.data.dir=auto` and a **random `encryption-key` of at least 32
   characters**. If you read `0123456789abcdef...` or a short key, rebuild the package.
3. **The uninstallation files are there**: `dist\EmployeeScheduling\app\` must contain
   `uninstall-windows.ps1` and `uninstall.cmd`.
4. **The zip has the right size** (~125 MB, not a few hundred KB — see 7.9).
5. **First-start trial**: install, check that the browser opens, register the initial
   administrator, and **land straight inside the application**.
6. **Data trial**: `%LOCALAPPDATA%\EmployeeScheduling` must contain `employee_scheduling.db`,
   `config.properties` and, after a couple of minutes, a file in `backups\`.
7. **Permissions trial** (closes 7.4):
   ```powershell
   (Get-Acl "$env:LOCALAPPDATA\EmployeeScheduling\backups").AreAccessRulesProtected   # must be False
   ```
8. **Uninstallation trial**: double-click `uninstall.cmd`; afterwards
   `%LOCALAPPDATA%\EmployeeScheduling` must **still exist**.

---

## 9. Shipping and updating

**Shipping**: publish the `.msi` alone (GitHub Releases, shared folder, USB stick). Whoever
installs it double-clicks, chooses the directory, and registers on first startup: the first user
becomes the active administrator.

### 9.1 The application announces a new version by itself

People who installed the app do not watch the repository: the application has to tell them. At
login, **administrators only** — the only ones who can update — see a modal if the published
version is greater than the installed one, with the download link and the instructions
(uninstall, reinstall, the data stays). It closes once per version and does not come back until
another one is released.

Three things are needed for this to work:

| What | Where |
|---|---|
| The installed version | Declared by jpackage in the `.cfg` (`-Djpackage.app-version`), and it comes from `pom.xml` |
| The published version | Read from `updates.app.releases-api`, by default `releases/latest` of the repository on GitHub |
| The comparison | `SystemInfoResource.isNewerVersion`, numeric per component, covered by `AppVersionComparisonTest` |

**Release procedure**, to be respected or the notice never fires:

1. raise the version in `pom.xml` (that is the source, not the script);
2. generate the MSI;
3. publish a GitHub **release tagged with the same version** (`v1.2.0` or `1.2.0`, the "v" is
   ignored) and **attach the MSI**;
4. existing installations notice at the next login, with at most an hour of delay due to the
   cache.

The check **fails silently**: with no network, behind a proxy, or with the GitHub API quota
exhausted, nothing appears — never an error message for something the user did not ask for.

To turn it off entirely (isolated network, or simply not wanted), point the property at
something unreachable in `config.properties`:

```properties
updates.app.releases-api=http://127.0.0.1:9/disabled
```

**An empty value does not work**: `AppUserConfigSource.load()` skips blank values, so
`updates.app.releases-api=` is discarded and the packaged default stays in force. The same
applies to any other property you may want to blank out from that file.

**Updating an existing installation**:

1. `uninstall.cmd` (the data stays);
2. install the new MSI;
3. the application finds the database, backups, and `config.properties` where it left them.

**Bringing in an already populated database** (the development one, for example): copy it to
`%LOCALAPPDATA%\EmployeeScheduling\employee_scheduling.db` **with the application closed**. Two
conditions, both verified in the field:

- the database **must contain the `flyway_schema_history` table**. The SQLite profile has
  `baseline-on-migrate=false`, so Flyway refuses to start on a populated schema without a
  history. The repository's development database **does not have it**: it must be copied from a
  database created by the application;
- the users of the source database have **unknown bcrypt passwords**: either you know the
  credentials, or you empty `app_users` (the application will offer to create the first
  administrator again), or you transplant your own user row from a database created by the app.

---

## 10. Uninstallation

```
Double-click  <install>\app\uninstall.cmd
```

It closes the application, restores the permissions of old installations (7.4), calls `msiexec`,
and **keeps the data**. With `-RemoveData` it also removes
`%LOCALAPPDATA%\EmployeeScheduling`, asking for confirmation.

The script copies itself to `%TEMP%` and restarts from there: a script running inside the
directory to be removed would prevent its deletion.

*Settings → Apps → EmployeeScheduling → Uninstall* works too, as long as the application is
closed.

---

## 11. Open points

- **`app.sqlite.legacy-bootstrap`** is `true` by default in `application.properties` and `false`
  in every explicit profile except `legacy-sqlite`, which re-enables it deliberately. In the package it must be checked which profile is active at
  runtime: if the historical bootstrap runs on a user installation, it can seed test data. On a
  database created by the MSI on 5 August 2026 **no** test data was observed (a single
  structure, "Default"), but the chain was not traced all the way through.
- **No digital signature**: SmartScreen will show the "unknown publisher" warning. A code
  signing certificate is needed.
- **In-place update**: not supported, you uninstall and reinstall (7.10).
- **Registrations after the first**: in standalone mode the comment in `application.properties`
  says they are closed, while the code accepts them and creates inactive users. A divergence to
  be resolved one way or the other.
- **If the dialog image changes**: recompute the empty block (pixel analysis, §7.14) and update
  X/W in `Invoke-MsiTextPositionPatch`.
