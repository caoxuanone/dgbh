@echo off
setlocal
cd /d "%~dp0"

set "URL=http://127.0.0.1:8787"

rem --- 1. Node.js installed? ---
where node >nul 2>nul
if errorlevel 1 goto nonode

rem --- 2. Server already running? just open the page ---
call :probe
if not errorlevel 1 goto openpage

rem --- 3. Start the local server in its own window ---
start "gdxs-server (close this to stop)" /min cmd /c "node server.js"

rem --- 4. Wait up to 12s for it to listen ---
for /l %%i in (1,1,12) do (
  call :probe
  if not errorlevel 1 goto openpage
  ping -n 2 127.0.0.1 >nul
)

echo.
echo [WARN] Local server did not start on port 8787.
echo        Opening index.html in direct mode instead.
echo        (no traffic stats in this mode)
echo.
start "" "%~dp0index.html"
ping -n 5 127.0.0.1 >nul
exit /b 1

:openpage
start "" "%URL%"
exit /b 0

:nonode
echo.
echo [WARN] Node.js not found in PATH.
echo        Opening index.html in direct mode instead.
echo.
start "" "%~dp0index.html"
ping -n 5 127.0.0.1 >nul
exit /b 1

rem --- helper: errorlevel 0 if port 8787 answers ---
:probe
if exist "%SystemRoot%\System32\curl.exe" (
  "%SystemRoot%\System32\curl.exe" -s -o nul --max-time 2 "%URL%/api/health"
) else (
  netstat -an | findstr "127.0.0.1:8787" | findstr "LISTENING" >nul
)
if errorlevel 1 exit /b 1
exit /b 0
