# ============================================================================
#  install-windows.ps1 - Employee Scheduling installation wizard (Windows 11)
#  Run:  powershell -ExecutionPolicy Bypass -File .\scripts\install-windows.ps1
# ============================================================================
param(
    [ValidateSet('msi', 'app-image', 'none')]
    [string]$Package
)

$ErrorActionPreference = 'Stop'

Write-Host ""
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "  Employee Scheduling - Installation Wizard"            -ForegroundColor Cyan
Write-Host "  Windows 11 | SQLite desktop or PostgreSQL server"      -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host ""

# The script lives in scripts/, but everything it needs - pom.xml, frontend/,
# target/, assets/ - is in its parent directory. That is the project root, not
# the script directory. Without Split-Path, Maven would run inside scripts/ and
# find nothing.
$Root = Split-Path $PSScriptRoot -Parent

# -- Helpers (defined BEFORE use: PowerShell does not hoist functions) ----------
# Run a native command while suppressing stdout+stderr and return $LASTEXITCODE.
# With $ErrorActionPreference='Stop', PS 5.1 turns native command stderr (for
# example Vite warnings) into NativeCommandError; lower EAP locally here.
function Invoke-Native([scriptblock]$cmd) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $cmd 2>&1 | Out-Null } finally { $ErrorActionPreference = $prev }
    return $LASTEXITCODE
}

function Decode-SecureString([System.Security.SecureString]$s) {
    if ($null -eq $s) { return "" }
    $bstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($s)
    try {
        return [System.Runtime.InteropServices.Marshal]::PtrToStringAuto($bstr)
    } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
    }
}

# Read the package version from pom.xml instead of hard-coding it: the installed
# application compares it with the latest release to report available updates.
# Two disconnected numbers would make that notice wrong in one direction or the
# other. jpackage requires three numeric components: "1.1-SNAPSHOT" becomes "1.1.0".
function Get-AppVersion([string]$root) {
    try {
        [xml]$pom = Get-Content (Join-Path $root "pom.xml")
        $raw = $pom.project.version
        if ([string]::IsNullOrWhiteSpace($raw)) { $raw = $pom.project.parent.version }
        $raw = ($raw -replace '-SNAPSHOT', '').Trim()
        $parts = @($raw.Split('.'))
        while ($parts.Count -lt 3) { $parts += '0' }
        return ($parts[0..2] -join '.')
    } catch {
        Write-Host "  [WARNING] unable to read version from pom.xml; using 1.0.0" -ForegroundColor Yellow
        return "1.0.0"
    }
}

function ConvertTo-WixBitmap {
    # Resize a source image to the EXACT dimensions of jpackage's MSI UI Bitmap
    # controls (verified in the generated MSI Control table): 370x44 px banner
    # and 370x234 px dialog background (dialogs are 370x270; the bottom strip
    # contains navigation buttons). Master images in assets\app\installer may use
    # any resolution: center-crop them here to match aspect ratio without
    # distortion, then resize exactly. WiX does not reject other dimensions but
    # stretches/crops them in the fixed-size Bitmap control. MSI scaling is low
    # quality; resizing here with HighQualityBicubic gives better results.
    param([string]$SourcePath, [string]$DestPath, [int]$Width, [int]$Height)
    Add-Type -AssemblyName System.Drawing
    $src = [System.Drawing.Image]::FromFile($SourcePath)
    try {
        $targetRatio = $Width / $Height
        $srcRatio = $src.Width / $src.Height
        if ($srcRatio -gt $targetRatio) {
            # Source is relatively wider than the target: crop the sides.
            $cropWidth = [int]([Math]::Round($src.Height * $targetRatio))
            $cropRect = New-Object System.Drawing.Rectangle([int](($src.Width - $cropWidth) / 2)), 0, $cropWidth, $src.Height
        } else {
            # Source is relatively taller than the target: crop top and bottom.
            $cropHeight = [int]([Math]::Round($src.Width / $targetRatio))
            $cropRect = New-Object System.Drawing.Rectangle(0, [int](($src.Height - $cropHeight) / 2), $src.Width, $cropHeight)
        }
        $dest = New-Object System.Drawing.Bitmap($Width, $Height, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
        try {
            $g = [System.Drawing.Graphics]::FromImage($dest)
            try {
                $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $destRect = New-Object System.Drawing.Rectangle(0, 0, $Width, $Height)
                $g.DrawImage($src, $destRect, $cropRect, [System.Drawing.GraphicsUnit]::Pixel)
            } finally {
                $g.Dispose()
            }
            $dest.Save($DestPath, [System.Drawing.Imaging.ImageFormat]::Bmp)
        } finally {
            $dest.Dispose()
        }
    } finally {
        $src.Dispose()
    }
}

function Get-JavaHome {
    # JAVA_HOME is not always set (for example a user-only JDK present only in
    # PATH): derive it from java.exe found in PATH when the variable is absent.
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) { return $env:JAVA_HOME }
    $javaCmd = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($javaCmd) {
        $bin = Split-Path $javaCmd.Source -Parent
        return Split-Path $bin -Parent
    }
    return $null
}

# WixUIDialogBmp/WixUIBannerBmp (MSI images) are NOT preprocessor variables read
# by main.wxs. They are <WixVariable> entries resolved by light.exe and used by
# standard WixUIExtension dialogs (WelcomeDlg, etc.) referenced by jpackage via
# UIRef. A `<?define WixUIDialogBmp="..." ?>` in overrides.wxi has no effect:
# nothing reads it. main.wxs reads ONLY JpProductLanguage/JpInstallerVersion/
# JpAllowUpgrades/JpAllowDowngrades from overrides.wxi, as documented by the
# overrides.wxi stub shipped inside jdk.jpackage.jmod.
#
# The reliable way to force WixUIDialogBmp/WixUIBannerBmp is to override the
# jpackage main.wxs resource with a patched copy declaring <WixVariable> INSIDE
# <Product>. jpackage always compiles main.wxs and takes it from --resource-dir
# when present. Beware the WiX v3 XSD (verified with wix314):
#   - <WixVariable> directly under <Wix> fails validation (CNDL0005);
#   - <WixVariable> in a trailing <Fragment> compiles (the fragment must follow
#     Product or CNDL0107 occurs), but light.exe DISCARDS sections unreachable
#     from Product. Nothing references the fragment, so WixVariable entries are
#     discarded and default WixUIExtension bitmaps remain in the MSI;
#   - the XSD permits <WixVariable> inside <Product>, at the position fixed by
#     the <Product> sequence: immediately after <Package>, before <Media>.
function New-WixImageOverrideResource([string]$javaHome, [string]$resDir, [string]$dialogValue, [string]$bannerValue) {
    $jmod = Join-Path $javaHome "jmods\jdk.jpackage.jmod"
    $jmodExe = Join-Path $javaHome "bin\jmod.exe"
    if (-not (Test-Path -LiteralPath $jmod) -or -not (Test-Path -LiteralPath $jmodExe)) {
        Write-Host "  [WARNING] jmod.exe/jdk.jpackage.jmod not found: custom MSI images were not applied (using WiX defaults)." -ForegroundColor Yellow
        return $false
    }
    $extractDir = Join-Path $env:TEMP "jpackage-mainwxs-extract"
    if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force -ErrorAction SilentlyContinue }
    $code = Invoke-Native { & $jmodExe extract --dir $extractDir $jmod }
    $src = Join-Path $extractDir "classes\jdk\jpackage\internal\resources\main.wxs"
    if ($code -ne 0 -or -not (Test-Path -LiteralPath $src)) {
        Write-Host "  [WARNING] failed to extract main.wxs from jdk.jpackage.jmod: custom MSI images were not applied." -ForegroundColor Yellow
        return $false
    }
    $content = Get-Content -LiteralPath $src -Raw
    $wixVars = New-Object System.Collections.Generic.List[string]
    if ($dialogValue) { $wixVars.Add("    <WixVariable Id=""WixUIDialogBmp"" Value=""$dialogValue"" />") }
    if ($bannerValue) { $wixVars.Add("    <WixVariable Id=""WixUIBannerBmp"" Value=""$bannerValue"" />") }
    if ($wixVars.Count -eq 0) { return $false }
    # In WiX v3, <WixVariable> entries belong INSIDE <Product>, immediately after
    # <Package>. Product is light's entry point, so values reach the link's
    # WixVariable table and WixUIExtension reads dialog bitmaps from it.
    $inject = ($wixVars -join "`r`n") + "`r`n    <Media Id=""1"""
    # Literal .Replace() (not regex) avoids escaping issues with $ and backslashes
    # in Windows paths inserted into the replacement string.
    $marker = "    <Media Id=""1"""
    $patched = $content.Replace($marker, $inject)
    if ($patched -eq $content) {
        Write-Host "  [WARNING] <Media> anchor not found in main.wxs: custom MSI images were not applied." -ForegroundColor Yellow
        return $false
    }
    Set-Content -LiteralPath (Join-Path $resDir "main.wxs") -Value $patched -Encoding UTF8
    return $true
}

function Invoke-MsiTextPositionPatch([string]$msiPath) {
    # WelcomeDlg title/description are drawn at FIXED coordinates (x=135) over
    # the custom image; overriding the dialog in WiX v3 is impossible (LGHT0091
    # duplicate symbol). The post-build Control-table patch moves text left
    # (X=20, W=175), into the image's empty block (x 0..200). COM details and
    # quirks are in docs\Consolidati\PACKAGING-WINDOWS-MSI.md (7.14-7.15).
    if (-not (Test-Path -LiteralPath $msiPath)) {
        Write-Host "  [WARNING] MSI not found: installer text patch skipped." -ForegroundColor Yellow
        return $false
    }
    $js = Join-Path $env:TEMP "msi-move-text.js"
    @'
var path = WScript.Arguments(0);
var msi = new ActiveXObject("WindowsInstaller.Installer");
var db = msi.OpenDatabase(path, 2);
var view = db.OpenView("SELECT * FROM Control WHERE Dialog_ = 'WelcomeDlg' AND (Control = 'Title' OR Control = 'Description' OR Control = 'PatchDescription')");
view.Execute();
var rec = view.Fetch();
var n = 0;
while (rec) {
  rec.StringData(4) = "20";
  rec.StringData(6) = "175";
  view.Modify(3, rec);
  n++;
  rec = view.Fetch();
}
db.Commit();
WScript.Echo("Patched controls: " + n);
'@ | Set-Content -LiteralPath $js -Encoding UTF8
    $code = Invoke-Native { cscript //nologo $js $msiPath }
    if ($code -ne 0) {
        Write-Host "  [WARNING] installer text patch failed (exit $code): text remains on the right." -ForegroundColor Yellow
        return $false
    }
    return $true
}

function New-CryptoString([int]$length) {
    $bytes = New-Object byte[] $length
    $rng = New-Object System.Security.Cryptography.RNGCryptoServiceProvider
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    # URL-safe Base64 without padding; it may exceed $length, so truncate it.
    $s = [Convert]::ToBase64String($bytes).Replace('+', '-').Replace('/', '_').TrimEnd('=')
    return $s.Substring(0, [Math]::Min($length, $s.Length))
}

# -- WiX for jpackage --type msi ------------------------------------------------
function Ensure-Wix {
    # 1) Already in PATH?
    if (Get-Command candle.exe -ErrorAction SilentlyContinue) { return $true }
    # 2) Known locations. The official installer puts binaries in bin\, while the
    #    ZIP puts them at its root. Try both or a correctly installed WiX is never
    #    found and gets downloaded unnecessarily.
    foreach ($p in @("C:\tools\wix314", "C:\Program Files (x86)\WiX Toolset v3.14", "C:\Program Files\WiX Toolset v3.14")) {
        if (Test-Path "$p\candle.exe")     { $env:WIX = $p;         return $true }
        if (Test-Path "$p\bin\candle.exe") { $env:WIX = "$p\bin";   return $true }
    }
    # 3) Automatically download official wix314 binaries (39 MB)
    Write-Host "  WiX not found: downloading official binaries (39 MB)..."
    New-Item -ItemType Directory -Force -Path "C:\tools\wix314" | Out-Null
    # Use %TEMP%, not a hard-coded path: that directory would not exist on another
    # machine and the download would fail before starting.
    $zip = Join-Path $env:TEMP "wix314-binaries.zip"
    try {
        # PowerShell 5.1 on hardened hosts may negotiate obsolete TLS; GitHub rejects it.
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        Invoke-WebRequest -Uri "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip" `
            -OutFile $zip -UseBasicParsing
        Expand-Archive -Path $zip -DestinationPath "C:\tools\wix314" -Force
        if (Test-Path "C:\tools\wix314\candle.exe") {
            $env:WIX = "C:\tools\wix314"
            Write-Host "  WiX ready in C:\tools\wix314" -ForegroundColor Green
            return $true
        }
    } catch {
        Write-Host "  WiX download failed: $($_.Exception.Message)" -ForegroundColor Red
    }
    return $false
}

# -- 0. Check prerequisites ----------------------------------------------------
function Test-Tool([string]$name, [string]$pathCheck, [scriptblock]$check) {
    # Fall back to JAVA_HOME/MAVEN_HOME: system PATH may omit java/mvn even when
    # installed, for example when installed for the current user only.
    if ($pathCheck -and (Test-Path $pathCheck)) { return $true }
    # java -version writes to stderr; Invoke-Native avoids NativeCommandError.
    $code = Invoke-Native $check
    if ($code -eq 0) { return $true }
    Write-Host "  [MISSING] $name" -ForegroundColor Red
    return $false
}

Write-Host "Checking prerequisites..." -ForegroundColor Yellow
$hasJava = Test-Tool "Java" "$env:JAVA_HOME\bin\java.exe" { java -version }
$hasMvn  = Test-Tool "Maven" "$env:MAVEN_HOME\bin\mvn.cmd" { mvn -version }
$hasNode = Test-Tool "Node.js/npm" "" { npm --version }
if (-not $hasJava) { Write-Host "  Install JDK 21: https://adoptium.net" -ForegroundColor Red; exit 1 }
if (-not $hasMvn)  { Write-Host "  Install Maven: https://maven.apache.org" -ForegroundColor Red; exit 1 }
if (-not $hasNode) { Write-Host "  Install Node.js LTS: https://nodejs.org" -ForegroundColor Red; exit 1 }
Write-Host "  Prerequisites OK (Java + Maven + Node.js)" -ForegroundColor Green

# -- 1. Database mode ----------------------------------------------------------
Write-Host ""
Write-Host "Database mode" -ForegroundColor Yellow
if ($Package) {
    $mode = "1"
} else {
    Write-Host "  1 = SQLite (desktop, single file)  [recommended]"
    Write-Host "  2 = PostgreSQL (server, multi-user)"
    $mode = Read-Host "  Choice (1/2)"
}
if ($mode -eq "2") {
    $dbKind      = "postgresql"
    $dbUrl       = Read-Host "  JDBC URL"
    if ([string]::IsNullOrWhiteSpace($dbUrl)) { $dbUrl = "jdbc:postgresql://localhost:5432/employee_scheduling" }
    $dbUser      = Read-Host "  Database user"
    if ([string]::IsNullOrWhiteSpace($dbUser)) { $dbUser = "employee_scheduling" }
    $dbPassword  = Read-Host "  Database password" -AsSecureString
    $dbPassPlain = Decode-SecureString $dbPassword
    if ([string]::IsNullOrWhiteSpace($dbPassPlain)) { $dbPassPlain = "" }
} else {
    $dbKind = "sqlite"
    $dbUrl = $dbUser = $dbPassPlain = ""
}

# -- 2. HTTP port --------------------------------------------------------------
# (No question about the data directory: packaged data lives in
#  %LOCALAPPDATA%\EmployeeScheduling; development data lives in databases\.)
Write-Host ""
Write-Host "HTTP port" -ForegroundColor Yellow
if ($Package) {
    $port = "8080"
} else {
    $port = Read-Host "  Port [8080]"
    if ([string]::IsNullOrWhiteSpace($port)) { $port = "8080" }
}

# -- 3. SMTP (server mode only) ------------------------------------------------
# Standalone mode (SQLite) needs neither OTP nor email notifications: the mock is
# implicit and the SMTP question is not asked.
$smtpHost = $smtpPort = $smtpUser = $smtpPass = $smtpFrom = ""
$mock = "y"
if ($Package) {
    $mock = "y"
} elseif ($dbKind -eq "postgresql") {
    Write-Host ""
    Write-Host "Email configuration (SMTP)" -ForegroundColor Yellow
    Write-Host "  Required for registration OTPs and approval notifications."
    $mock = Read-Host "  Use MOCK (emails in logs, nothing sent)? y/n [y]"
    if ([string]::IsNullOrWhiteSpace($mock)) { $mock = "y" }
    if ($mock -ne "y") {
        $smtpHost = Read-Host "  SMTP host"
        $smtpPort = Read-Host "  SMTP port [587]"
        if ([string]::IsNullOrWhiteSpace($smtpPort)) { $smtpPort = "587" }
        $smtpUser = Read-Host "  SMTP username"
        $smtpFrom = Read-Host "  Sender (From)"
        if ([string]::IsNullOrWhiteSpace($smtpFrom)) { $smtpFrom = $smtpUser }
        $sec = Read-Host "  SMTP password" -AsSecureString
        $smtpPass = Decode-SecureString $sec
    }
} else {
    Write-Host ""
    Write-Host "Email configuration (SMTP)" -ForegroundColor Yellow
    Write-Host "  Standalone mode: no email required (no OTPs/notifications)." -ForegroundColor Green
}

# -- 5. Secret keys (cryptographically secure) --------------------------------
Write-Host ""
Write-Host "Secret keys" -ForegroundColor Yellow
$sessionKey = New-CryptoString 48
$backupToken = New-CryptoString 48
Write-Host "  Session key generated (48 chars, RNGCryptoServiceProvider)."
Write-Host "  Backup token generated (48 chars)."

# -- 6. Write .env (dev mode ONLY) --------------------------------------------
# The jpackage package uses baked-in system properties ($APPDIR\data).
# .env is for mvn quarkus:dev, which reads it from the CWD (project root).
$envFile = Join-Path $Root ".env"
Write-Host ""
Write-Host "  Writing configuration (dev mode) to $envFile ..."

# Use forward slashes everywhere: backslashes in .env are properties-parser escapes.
$dataDirFwd = ($Root + "\databases") -replace '\\', '/'

$lines = @()
$lines += "# Employee Scheduling - configuration generated by the wizard"
$lines += "AUTH_SESSION_KEY=$sessionKey"
$lines += "QUARKUS_HTTP_PORT=$port"
$lines += "QUARKUS_PROFILE=$dbKind"
$lines += "BACKUP_DIR=$dataDirFwd/backups"
$lines += "BACKUP_SETTINGS_FILE=$dataDirFwd/backup-settings.properties"
$lines += "QUARKUS_LOG_FILE_ENABLE=true"
$lines += "QUARKUS_LOG_FILE_PATH=$dataDirFwd/app.log"
# Always present for both engines: application.properties declares
# backup.admin-token=${BACKUP_ADMIN_TOKEN:}; the converter treats an empty string
# as null, so Quarkus does not start at all (including mvn test).
$lines += "BACKUP_ADMIN_TOKEN=$backupToken"
if ($dbKind -eq "postgresql") {
    $lines += "DATABASE_URL=$dbUrl"
    $lines += "DATABASE_USERNAME=$dbUser"
    $lines += "DATABASE_PASSWORD=$dbPassPlain"
} else {
    $lines += "APP_DATABASE_PATH=$dataDirFwd/large_data.db"
}
if ($mock -eq "y") {
    $lines += "QUARKUS_MAILER_MOCK=true"
} else {
    $lines += "QUARKUS_MAILER_HOST=$smtpHost"
    $lines += "QUARKUS_MAILER_PORT=$smtpPort"
    $lines += "QUARKUS_MAILER_USERNAME=$smtpUser"
    $lines += "QUARKUS_MAILER_PASSWORD=$smtpPass"
    $lines += "QUARKUS_MAILER_FROM=$smtpFrom"
    $lines += "QUARKUS_MAILER_MOCK=false"
}
# UTF-8 without BOM (PowerShell 5.1): pure ASCII; avoid a BOM in .env files.
# One file at the project root, read by mvn quarkus:dev from the CWD. The package
# does not use .env; it has its own -D values and data-directory config.properties.
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($envFile, $lines, $utf8NoBom)

# .env contains plaintext passwords: owner-only permissions.
# Run icacls via cmd /c: native cmd parsing avoids PS 5.1 argument-passing issues
# with "(R,W)" syntax. Best effort, with a warning.
$icaclsResult = Invoke-Native { cmd /c "icacls `"$envFile`" /inheritance:r /grant:r `"$env:USERNAME`:(R,W)`"" }
if ($icaclsResult -eq 0) {
    Write-Host "  OK (restricted permissions)." -ForegroundColor Green
} else {
    Write-Host "  [WARNING] permissions were not applied (continuing anyway)." -ForegroundColor Yellow
}

# -- 7. Build -----------------------------------------------------------------
Write-Host ""
Write-Host "Building..." -ForegroundColor Yellow
$prev = Get-Location
try {
    Set-Location (Join-Path $Root "frontend")
    Write-Host "  Frontend (npm install + build)..."
    # Vite warnings go to stderr: Invoke-Native suppresses them and returns the
    # actual exit code (with EAP=Stop, PS 5.1 would turn them into errors).
    $code = Invoke-Native { npm install }
    if ($code -ne 0) { throw "npm install failed (exit $code)" }
    $code = Invoke-Native { npm run build }
    if ($code -ne 0) { throw "Frontend build failed (exit $code)" }
    Write-Host "  Frontend OK." -ForegroundColor Green

    Set-Location $Root
    Write-Host "  Backend (mvn package, uber-jar)..."
    # Maven may be absent from the -File process PATH; fall back to MAVEN_HOME.
    $mvnCmd = "mvn"
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue) -and $env:MAVEN_HOME) {
        $mvnCmd = Join-Path $env:MAVEN_HOME "bin\mvn.cmd"
    }
    # The profile is BUILD-TIME for quarkus.flyway.locations: select it HERE, not
    # only in .env, or the package fails with duplicate migrations.
    $buildProfile = if ($dbKind -eq "postgresql") { "postgresql" } else { "sqlite" }
    # uber-jar produces target/employee-scheduling-*-runner.jar (the default
    # fast-jar does NOT create that file).
    $code = Invoke-Native { & $mvnCmd package -DskipTests "-Dquarkus.package.jar.type=uber-jar" "-Dquarkus.profile=$buildProfile" }
    if ($code -ne 0) { throw "Backend build failed (exit $code)" }
    Write-Host "  Backend OK." -ForegroundColor Green
} finally {
    Set-Location $prev
}

# Use the newest, not the first alphabetically: mvn runs without clean, and with
# two runner JARs in target\ the old one would otherwise be packaged.
$jar = Get-ChildItem "$Root\target\*runner.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { Write-Host "  JAR not found!" -ForegroundColor Red; exit 1 }

# -- Packaging: ask whether to build the native app ---------------------------
Write-Host ""
Write-Host "Native application package (jpackage, bundled JRE):"
if ($Package) {
    switch ($Package.ToLowerInvariant()) {
        'msi' { $pkg = "2" }
        'app-image' { $pkg = "1" }
        'none' { $pkg = "3" }
    }
} else {
    Write-Host "  1 = app-image (executable directory, no installation)"
    Write-Host "  2 = MSI installer (Start menu + uninstallation)"
    Write-Host "  3 = none (use the JAR directly)"
    $pkg = Read-Host "  Choice [1]"
    if ([string]::IsNullOrWhiteSpace($pkg)) { $pkg = "1" }
}

if ($pkg -eq "1" -or $pkg -eq "2") {
    $dist = Join-Path $Root "dist"
    $staging = Join-Path $Root "target\jpackage-input"
    $appVersion = Get-AppVersion $Root
    Write-Host "  Package version (from pom.xml): $appVersion"
    # Clean it: jpackage copies the ENTIRE input directory into the application,
    # so leftovers from a previous build (for example a different-version runner
    # JAR) would ship with the correct one.
    if (Test-Path $staging) { Remove-Item $staging -Recurse -Force }
    New-Item -ItemType Directory -Path $staging -Force | Out-Null
    Copy-Item $jar.FullName (Join-Path $staging $jar.Name) -Force

    # Ship the uninstallation script INSIDE the package: jpackage copies input to
    # <install>\app, so users find it beside the application without obtaining the
    # repository. Ship the .cmd launcher too: double-clicking a .ps1 opens it in
    # an editor rather than running it.
    # Use $PSScriptRoot, not $Root: these two files are in scripts/ beside this
    # script, not at the project root. A wrong path would make Test-Path silently
    # fail and produce a package WITHOUT an uninstaller and without warning.
    foreach ($extra in @("uninstall-windows.ps1", "uninstall.cmd")) {
        $path = Join-Path $PSScriptRoot $extra
        if (Test-Path $path) {
            Copy-Item $path $staging -Force
        } else {
            throw "Uninstallation file not found: $path"
        }
    }

    # Configure through SYSTEM PROPERTIES (--java-options), NOT through
    # quarkus.config.locations: in Quarkus 3.37 that property does not accept
    # file:/// URIs and the value is looked up as a class (ClassNotFoundException).
    # -D has highest priority and jpackage passes each option as one argument.
    $jopts = @()
    # Data NO LONGER lives in the installation directory. With app.data.dir=auto,
    # the application places it in %LOCALAPPDATA%\EmployeeScheduling (see
    # AppDataDirConfigSource). jpackage's .cfg expands only $APPDIR, not environment
    # variables, so resolution must happen at runtime. Thus uninstallation does
    # not delete the database, trip over an open app.log, or find directories
    # with rewritten permissions.
    $jopts += "--java-options", "-Dapp.data.dir=auto"
    $jopts += "--java-options", "-Dquarkus.http.port=$port"
    # Email: if the user configured real SMTP, it must go INTO THE PACKAGE.
    # Previously it was requested and then discarded, so packages always used the
    # mock. OTP registration could not work in server mode because the code stayed
    # in the log instead of being sent.
    if ($mock -eq "y") {
        $jopts += "--java-options", "-Dquarkus.mailer.mock=true"
    } else {
        $jopts += "--java-options", "-Dquarkus.mailer.mock=false"
        $jopts += "--java-options", "-Dquarkus.mailer.host=$smtpHost"
        $jopts += "--java-options", "-Dquarkus.mailer.port=$smtpPort"
        $jopts += "--java-options", "-Dquarkus.mailer.username=$smtpUser"
        $jopts += "--java-options", "-Dquarkus.mailer.password=$smtpPass"
        $jopts += "--java-options", "-Dquarkus.mailer.from=$smtpFrom"
    }
    # Backup administration token for BOTH engines. Without it, the /backup API
    # returns 503 BACKUP_ADMIN_TOKEN_NOT_CONFIGURED and the UI Backup section is
    # dead while scheduling keeps running: a silent failure.
    $jopts += "--java-options", "-Dbackup.admin-token=$backupToken"
    # Quarkus form auth requires a key of at least 16 characters. Enforce a safe
    # length even if the generator should truncate it for some reason.
    $sessionKey64 = New-CryptoString 64
    $jopts += "--java-options", "-Dquarkus.http.auth.session.encryption-key=$sessionKey64"
    # File log in the data directory (app.data.dir resolves the path): if the
    # application does not start, app.log explains why.
    $jopts += "--java-options", "-Dquarkus.log.file.enable=true"
    $jopts += "--java-options", "-Dquarkus.log.file.level=INFO"
    # Open the browser automatically at startup (desktop-app experience).
    $jopts += "--java-options", "-Dapp.open-browser-on-start=true"
    # Optional application icon: assets\app\app-icon.ico
    $iconPath = Join-Path $Root "assets\app\app-icon.ico"
    if (Test-Path -LiteralPath $iconPath) {
        $jopts += "--icon", $iconPath
    }

    # Optional MSI images (WiX UI). They apply ONLY to --type msi; app-image never
    # passes through WiX. See New-WixImageOverrideResource comments for why
    # WixUIDialogBmp/WixUIBannerBmp CANNOT be set from overrides.wxi.
    $dialogBmp = Join-Path $Root "assets\app\installer\dialog.bmp"
    $bannerBmp = Join-Path $Root "assets\app\installer\banner.bmp"
    if ($pkg -eq "2" -and ((Test-Path -LiteralPath $dialogBmp) -or (Test-Path -LiteralPath $bannerBmp))) {
        $resDir = Join-Path $Root "target\jpackage-resources"
        if (Test-Path $resDir) { Remove-Item $resDir -Recurse -Force -ErrorAction SilentlyContinue }
        New-Item -ItemType Directory -Path $resDir -Force | Out-Null
        $dialogOut = $null
        $bannerOut = $null
        if (Test-Path -LiteralPath $dialogBmp) {
            # 370x234: actual background Bitmap control size (jpackage dialogs are
            # 370x270, with navigation buttons below). Dialog text (title and
            # description) is drawn OVER the image at x=135..355, y=20..140, so
            # design the image with that area dark and uncluttered.
            $dialogOut = Join-Path $resDir "WixUIDialog.bmp"
            ConvertTo-WixBitmap -SourcePath $dialogBmp -DestPath $dialogOut -Width 370 -Height 234
        }
        if (Test-Path -LiteralPath $bannerBmp) {
            # 370x44: actual banner size. Text (title x=15..215 y=6..21,
            # description x=25..305 y=23..38) is drawn over the image, leaving
            # only about 65 px on the right and 6 px strips above and below.
            $bannerOut = Join-Path $resDir "WixUIBanner.bmp"
            ConvertTo-WixBitmap -SourcePath $bannerBmp -DestPath $bannerOut -Width 370 -Height 44
        }
        $javaHomeForWix = Get-JavaHome
        if (-not $javaHomeForWix) {
            Write-Host "  [WARNING] unable to locate JAVA_HOME: custom MSI images were not applied." -ForegroundColor Yellow
        } else {
            $applied = New-WixImageOverrideResource -javaHome $javaHomeForWix -resDir $resDir -dialogValue $dialogOut -bannerValue $bannerOut
            if ($applied) {
                Write-Host "  Custom MSI images applied (banner/dialog)." -ForegroundColor Green
                $jopts += "--resource-dir", $resDir
            }
        }
    }
    if ($dbKind -eq "postgresql") {
        $jopts += "--java-options", "-Dquarkus.datasource.db-kind=postgresql"
        $jopts += "--java-options", "-Dquarkus.datasource.jdbc.url=$dbUrl"
        $jopts += "--java-options", "-Dquarkus.datasource.username=$dbUser"
        $jopts += "--java-options", "-Dquarkus.datasource.password=$dbPassPlain"
    }

    if ($pkg -eq "1") {
        # jpackage REFUSES to overwrite an existing app-image ("Application
        # destination directory already exists") and stops; remove it first.
        # This does not affect --type msi because the installer is overwritten.
        $appImage = Join-Path $dist "EmployeeScheduling"
        if (Test-Path $appImage) {
            Write-Host "  Removing the previous app-image..."
            Remove-Item $appImage -Recurse -Force -ErrorAction SilentlyContinue
            if (Test-Path $appImage) {
                Write-Host "  Unable to remove $appImage (files in use?): close the application and any windows open on that directory." -ForegroundColor Red
                exit 1
            }
        }
        $code = Invoke-Native { jpackage --type app-image --name "EmployeeScheduling" --app-version $appVersion `
            --input $staging --main-jar $jar.Name --dest $dist `
            @jopts }
        if ($code -ne 0) { Write-Host "  jpackage failed (exit $code)" -ForegroundColor Red; exit 1 }
        Write-Host ""
        Write-Host "  Application created: $dist\EmployeeScheduling\EmployeeScheduling.exe" -ForegroundColor Green
        Write-Host "  Data (DB, backups, logs): %LOCALAPPDATA%\EmployeeScheduling" -ForegroundColor Green
    } else {
        # MSI requires WiX: look in PATH and known locations, or automatically
        # download official wix314 binaries to C:\tools\wix314.
        $wixOk = Ensure-Wix
        if (-not $wixOk) { Write-Host "  WiX unavailable: unable to generate the MSI" -ForegroundColor Red; exit 1 }
        # Use the path found by Ensure-Wix, not a hard-coded one: WiX may be
        # elsewhere (official installer or already in PATH).
        if ($env:WIX) { $env:Path = $env:WIX + ";" + $env:Path }

        # --win-dir-chooser: the user CHOOSES the directory during installation.
        # Program Files is fine: data is in %LOCALAPPDATA%, not the installation,
        # so write permissions are unnecessary there.
        $code = Invoke-Native { jpackage --type msi --name "EmployeeScheduling" --app-version $appVersion `
            --input $staging --main-jar $jar.Name --dest $dist `
            --win-menu --win-dir-chooser --win-shortcut --win-shortcut-prompt `
            @jopts }
        if ($code -ne 0) { Write-Host "  jpackage failed (exit $code, is WiX installed?)" -ForegroundColor Red; exit 1 }
        $msiFile = Join-Path $dist "EmployeeScheduling-$appVersion.msi"
        if (Invoke-MsiTextPositionPatch -msiPath $msiFile) {
            Write-Host "  Welcome text moved to the left (X=20, inside the image's empty block)." -ForegroundColor Green
        }
        Write-Host ""
        Write-Host "  Installer: $dist\EmployeeScheduling-$appVersion.msi" -ForegroundColor Green
        Write-Host "  Choose the directory during installation; Program Files is also suitable." -ForegroundColor Green
        Write-Host "  Data (DB, backups, logs) is stored in %LOCALAPPDATA%\EmployeeScheduling." -ForegroundColor Green
    }
}

# -- Summary ------------------------------------------------------------------
Write-Host ""
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "  Installation complete!" -ForegroundColor Green
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "  Database      : $dbKind"
Write-Host "  Port          : $port"
if ($pkg -eq "1") {
    Write-Host "  App directory : $dist\EmployeeScheduling"
} elseif ($pkg -eq "2") {
    Write-Host "  App directory : chosen during installation"
}
if ($pkg -eq "1" -or $pkg -eq "2") {
    Write-Host "  User data     : %LOCALAPPDATA%\EmployeeScheduling (DB, backups, logs)"
    Write-Host "                  Not affected by updates or uninstallation." -ForegroundColor Green
    Write-Host "  Uninstall     : .\scripts\uninstall-windows.ps1  (or Settings > Apps)"
}
Write-Host "  Configuration : $envFile (dev mode only)"
if ($mock -eq "y") { Write-Host "  SMTP          : MOCK (OTP in the console log)" -ForegroundColor Yellow }
Write-Host ""
Write-Host "  Development run: cd $Root; mvn quarkus:dev"
Write-Host "  URL            : http://localhost:$port"
Write-Host "  First registration = first ADMIN (username+password, no email in standalone mode)."
Write-Host "  Full guide: docs\INSTALLATION.md"
Write-Host ""
