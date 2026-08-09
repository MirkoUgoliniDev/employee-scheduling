@echo off
setlocal

if "%~1"=="" (
    echo Usage: kill-port.bat [port]
    echo Example: kill-port.bat 8080
    exit /b 1
)

set PORT=%~1

echo Searching for processes on port %PORT%...

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
    echo Process found with PID: %%a
    echo Terminating the process...
    taskkill /PID %%a /F
    if %errorlevel% equ 0 (
        echo Process %%a terminated successfully.
    ) else (
        echo Error terminating process %%a.
    )
)

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT% " ^| findstr "LISTENING"') do (
    echo WARNING: Port %PORT% is still in use.
    exit /b 1
)

echo Port %PORT% is free.
endlocal
