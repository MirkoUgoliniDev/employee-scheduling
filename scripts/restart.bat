@echo off
setlocal
title Restart Employee Scheduling

echo ============================================
echo  Employee Scheduling - Restart Application
echo ============================================
echo.

:: ── 1. Stop the process on port 8080 ─────────────────────────────────────────
echo [1/3] Stopping the process on port 8080...

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080 " ^| findstr "LISTENING" 2^>nul') do (
    echo      PID found: %%a - terminating it...
    taskkill /PID %%a /F >nul 2>&1
)

:: Wait for the port to become free
timeout /t 2 /nobreak >nul

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080 " ^| findstr "LISTENING" 2^>nul') do (
    echo      WARNING: port 8080 is still in use by PID %%a
    echo      Attempting forced termination...
    taskkill /PID %%a /F /T >nul 2>&1
)

echo      Port 8080 is free.
echo.

:: ── 2. Build the project ─────────────────────────────────────────────────────
echo [2/3] Building the project (mvn package)...
echo.

:: The script lives in scripts/: the project root is its parent directory.
cd /d "%~dp0.."
call mvn package -DskipTests -q

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Build failed. Check the Maven errors above.
    pause
    exit /b 1
)

echo      Build completed.
echo.

:: ── 3. Start the server ──────────────────────────────────────────────────────
echo [3/3] Starting the server at http://localhost:8080 ...
echo.
echo      (The browser will open automatically in a few seconds)
echo      (Close this window to stop the server)
echo.

:: Open the browser after 5 seconds in the background
start "" cmd /c "timeout /t 5 /nobreak >nul && start http://localhost:8080"

:: Start Quarkus (in the foreground; Ctrl+C to stop)
java -jar "%~dp0..\target\quarkus-app\quarkus-run.jar"

echo.
echo Server stopped.
pause
endlocal
