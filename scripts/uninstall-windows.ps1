# ============================================================================
#  uninstall-windows.ps1 - Clean Employee Scheduling uninstallation
#  Run: double-click uninstall.cmd (.ps1 files do not start by themselves).
#
#  Handles the four issues that made manual uninstallation fail:
#   1. moves outside the installation directory (a directory used as a live
#      process CWD cannot be removed, nor can its parent);
#   2. requests elevation, without which neither icacls nor msiexec can operate
#      on a per-machine product;
#   3. closes the application (two processes: jpackage launcher + JVM);
#   4. repairs permissions from old installations, where the application had
#      rewritten the ACL without SYSTEM, and runs msiexec.
#
#  Data (%LOCALAPPDATA%\EmployeeScheduling) is NOT touched. Use -RemoveData,
#  which asks for confirmation.
#
#  ENCODING NOTE: ASCII characters only. Windows PowerShell 5.1 reads files
#  without a BOM using the system code page (1252 on most machines), which would
#  turn accents and long dashes into unreadable characters.
# ============================================================================
[CmdletBinding()]
param(
    # Also remove the database, backups, and logs after uninstalling (asks first).
    [switch]$RemoveData,
    # Do not prompt: for automated scenarios.
    [switch]$Silent,
    # Data directory propagated across elevation: after UAC with another account,
    # %LOCALAPPDATA% would refer to the administrator and the script would report
    # that data does not exist while it remains in place.
    [string]$DataRoot
)

# Use 'Continue', not 'Stop': a secondary error (an unstoppable process or failed
# copy) must not prevent the actual uninstallation.
$ErrorActionPreference = 'Continue'
$AppName  = 'EmployeeScheduling'
$failed   = $false

# Resolve BEFORE any relaunch, while the environment still belongs to the user.
if ($DataRoot) { $dataRoot = $DataRoot }
elseif ($env:LOCALAPPDATA) { $dataRoot = Join-Path $env:LOCALAPPDATA $AppName }
else { $dataRoot = Join-Path $HOME '.employee-scheduling' }

# -- 1. Relaunch from %TEMP% ---------------------------------------------------
# The script is distributed INSIDE the installation. The file is not the problem
# (a running .ps1 can be deleted), but the CWD is: msiexec cannot remove a
# directory that is the current directory of a live process.
if (-not $env:ES_UNINSTALL_RELOCATED) {
    $copy = Join-Path $env:TEMP ("es-uninstall-" + [guid]::NewGuid().ToString('N') + ".ps1")
    Copy-Item $PSCommandPath $copy -Force -ErrorAction SilentlyContinue
    if (-not (Test-Path $copy)) { $copy = $PSCommandPath }

    $env:ES_UNINSTALL_RELOCATED = '1'
    $relaunch = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $copy, '-DataRoot', $dataRoot)
    if ($RemoveData) { $relaunch += '-RemoveData' }
    if ($Silent)     { $relaunch += '-Silent' }

    # -WorkingDirectory: without it the child inherits its parent's CWD, which is
    # precisely the directory to remove.
    $child = Start-Process -FilePath 'powershell.exe' -ArgumentList $relaunch `
                           -WorkingDirectory $env:TEMP -Wait -PassThru
    if ($copy -ne $PSCommandPath) { Remove-Item $copy -Force -ErrorAction SilentlyContinue }
    exit $child.ExitCode
}
Remove-Item Env:ES_UNINSTALL_RELOCATED -ErrorAction SilentlyContinue

# Set-Location changes only the PowerShell provider location. The process Win32
# CWD remains the original one and keeps blocking the directory. Both lines are
# required.
Set-Location $env:TEMP
[System.IO.Directory]::SetCurrentDirectory($env:TEMP)

Write-Host ""
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host "  Employee Scheduling - Uninstallation"                -ForegroundColor Cyan
Write-Host "======================================================" -ForegroundColor Cyan
Write-Host ""

# -- 2. Elevation --------------------------------------------------------------
# Double-clicking produces a NON-elevated token even for an administrator (a
# UAC-filtered token). Without elevation, icacls is denied and msiexec /x on a
# per-machine product fails with 1925. IsInRole resolves by SID, so it works on
# non-English Windows as well.
$isAdmin = ([Security.Principal.WindowsPrincipal] `
            [Security.Principal.WindowsIdentity]::GetCurrent()
           ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if (-not $isAdmin) {
    Write-Host "Administrator privileges are required; requesting elevation..." -ForegroundColor Yellow
    # Not $args: it is a PowerShell automatic variable.
    $elevArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $PSCommandPath, '-DataRoot', $dataRoot)
    if ($RemoveData) { $elevArgs += '-RemoveData' }
    if ($Silent)     { $elevArgs += '-Silent' }
    $env:ES_UNINSTALL_RELOCATED = '1'   # the elevated copy already runs from %TEMP%
    try {
        $elevated = Start-Process -FilePath 'powershell.exe' -ArgumentList $elevArgs -Verb RunAs `
                                  -WorkingDirectory $env:TEMP -Wait -PassThru
        exit $elevated.ExitCode
    } catch {
        Write-Host "  Elevation denied: uninstall from Settings > Apps." -ForegroundColor Red
        exit 1602
    }
}

# -- 3. Close the application -------------------------------------------------
Write-Host "Closing the application..." -ForegroundColor Yellow
$running = @(Get-Process -Name $AppName -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    $running | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    $left = @(Get-Process -Name $AppName -ErrorAction SilentlyContinue)
    if ($left.Count -eq 0) {
        Write-Host "  Closed $($running.Count) processes." -ForegroundColor Green
    } else {
        Write-Host "  $($left.Count) processes did not close; removal may fail." -ForegroundColor Yellow
    }
} else {
    Write-Host "  The application was not running."
}

# -- 4. Find the product -------------------------------------------------------
Write-Host "Searching for the installation..." -ForegroundColor Yellow
$roots = @(
    'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*',
    'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*'
)
$found = @()
foreach ($root in $roots) {
    # Exact name and MSI product: wildcard matching would also select optional
    # satellite components or similarly named products.
    $found += Get-ItemProperty $root -ErrorAction SilentlyContinue |
              Where-Object { $_.DisplayName -eq $AppName -and $_.WindowsInstaller -eq 1 -and -not $_.SystemComponent }
}

if ($found.Count -eq 0) {
    Write-Host "  No installation found in the registry." -ForegroundColor Yellow
} else {
    foreach ($product in $found) {
        Write-Host "  Found: $($product.DisplayName) $($product.DisplayVersion)" -ForegroundColor Green

        # -- 5. Permissions from old installations -----------------------------
        # Needed only for versions before August 5, 2026, which stored data in
        # <install>\app\data and rewrote its ACL without SYSTEM.
        $installDir = $product.InstallLocation
        if ($installDir -and (Test-Path $installDir)) {
            $dataDir = Join-Path $installDir 'app\data'
            if (Test-Path $dataDir) {
                Write-Host "  Restoring permissions in $dataDir ..." -ForegroundColor Yellow
                # Explicit SIDs: group names are localized (Administrators,
                # Administrateurs, Administratoren), so name matching would fail.
                # Take ownership first: if the ACL denies DACL rewriting, /grant
                # alone is insufficient.
                cmd /c "icacls `"$dataDir`" /setowner *S-1-5-32-544 /T /C" 2>&1 | Out-Null
                cmd /c "icacls `"$dataDir`" /reset /T /C /Q" 2>&1 | Out-Null
                cmd /c "icacls `"$dataDir`" /inheritance:e /grant *S-1-5-18:(OI)(CI)F /grant *S-1-5-32-544:(OI)(CI)F /T /C" 2>&1 | Out-Null
                if ($LASTEXITCODE -eq 0) {
                    Write-Host "  Permissions restored." -ForegroundColor Green
                } else {
                    Write-Host "  Permissions were only partially restored (icacls $LASTEXITCODE); removal may fail." -ForegroundColor Yellow
                }
            }
        }

        # -- 6. Uninstallation --------------------------------------------------
        $code = $product.PSChildName
        if ($code -notmatch '^\{[0-9A-Fa-f]{8}(-[0-9A-Fa-f]{4}){3}-[0-9A-Fa-f]{12}\}$') {
            Write-Host "  Unrecognized product code ($code): uninstall from Settings > Apps." -ForegroundColor Yellow
            $failed = $true
            continue
        }
        Write-Host "  Uninstalling (msiexec)..." -ForegroundColor Yellow
        $msiArgs = @('/x', $code, '/norestart')
        if ($Silent) { $msiArgs += '/qn' } else { $msiArgs += '/passive' }
        $proc = Start-Process -FilePath 'msiexec.exe' -ArgumentList $msiArgs -Wait -PassThru

        switch ($proc.ExitCode) {
            0     { Write-Host "  Uninstalled." -ForegroundColor Green }
            3010  { Write-Host "  Uninstalled: restart the computer to complete the process." -ForegroundColor Yellow }
            1605  { Write-Host "  The product was already uninstalled." -ForegroundColor Yellow }
            1602  { Write-Host "  Cancelled: nothing was removed." -ForegroundColor Yellow }
            1618  { Write-Host "  Another installation is in progress; wait for it to finish and try again." -ForegroundColor Red
                    $failed = $true }
            1925  { Write-Host "  Insufficient privileges: run as administrator." -ForegroundColor Red
                    $failed = $true }
            1603  { Write-Host "  Removal error (files in use or permissions): close the application and try again." -ForegroundColor Red
                    $failed = $true }
            default { Write-Host "  msiexec returned $($proc.ExitCode): see Settings > Apps." -ForegroundColor Red
                      $failed = $true }
        }
    }
}

# -- 7. User data --------------------------------------------------------------
Write-Host ""
if (Test-Path $dataRoot) {
    $size = (Get-ChildItem $dataRoot -Recurse -File -ErrorAction SilentlyContinue |
             Measure-Object -Property Length -Sum).Sum
    if (-not $size) { $size = 0 }
    Write-Host "User data: $dataRoot ($([math]::Round($size/1MB,1)) MB)" -ForegroundColor Cyan

    if ($RemoveData) {
        $confirmed = $Silent
        if (-not $confirmed) {
            Write-Host "  This contains the database, backups, and logs. This operation CANNOT be undone." -ForegroundColor Yellow
            $answer = Read-Host "  Remove the data as well? (y/N)"
            $confirmed = ($answer -eq 'y' -or $answer -eq 'Y')
        }
        if ($confirmed) {
            Remove-Item $dataRoot -Recurse -Force -ErrorAction SilentlyContinue
            if (Test-Path $dataRoot) {
                Write-Host "  Partial removal: some files were in use." -ForegroundColor Yellow
                $failed = $true
            } else {
                Write-Host "  Data removed." -ForegroundColor Green
            }
        } else {
            Write-Host "  Data preserved." -ForegroundColor Green
        }
    } else {
        Write-Host "  Preserved (use -RemoveData to remove it)." -ForegroundColor Green
        Write-Host "  Reinstalling the application will reuse it as it was left."
    }
} else {
    Write-Host "No data directory found at $dataRoot." -ForegroundColor Cyan
}

Write-Host ""
if ($failed) {
    Write-Host "Completed with errors: see the messages above." -ForegroundColor Red
    Write-Host ""
    exit 1
}
Write-Host "Done." -ForegroundColor Green
Write-Host ""
exit 0
