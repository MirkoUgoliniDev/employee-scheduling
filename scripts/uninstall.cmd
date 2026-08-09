@echo off
REM ===========================================================================
REM  Employee Scheduling uninstallation - double-click to start.
REM
REM  .ps1 files cannot be run by double-clicking (Windows opens them in an
REM  editor): this launcher invokes PowerShell with the actual script.
REM
REM  cd /d "%TEMP%" is mandatory: if the current directory remains the
REM  installation directory, msiexec cannot remove it. A directory used as the
REM  CWD of a live process cannot be deleted, nor can its parent.
REM
REM  The full powershell.exe path avoids depending on PATH.
REM
REM  Data (database, backups, logs) remains in %LOCALAPPDATA%\EmployeeScheduling
REM  and is NOT removed. To remove it as well: uninstall.cmd -RemoveData
REM ===========================================================================
setlocal
set "SCRIPT=%~dp0uninstall-windows.ps1"
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

echo.
echo   Employee Scheduling - Uninstallation
echo.

if not exist "%SCRIPT%" (
  echo   Script not found: "%SCRIPT%"
  echo   Uninstall from Settings ^> Apps.
  echo.
  echo   Press any key to close this window.
  pause >nul
  endlocal
  exit /b 1
)

cd /d "%TEMP%"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%" %*
set EXITCODE=%ERRORLEVEL%

echo.
if not "%EXITCODE%"=="0" (
  echo   Finished with exit code %EXITCODE%: uninstallation may be incomplete.
)
echo   Press any key to close this window.
pause >nul
endlocal
exit /b %EXITCODE%
