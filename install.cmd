@echo off
REM Double-clickable entry point for install.ps1.
REM
REM WHY THIS EXISTS: Windows refuses to run a downloaded .ps1 by double-click, because the
REM default execution policy blocks unsigned scripts. -ExecutionPolicy Bypass applies to
REM this one process only and changes nothing on the machine.
REM
REM Any arguments are passed straight through, so these both work:
REM     install.cmd
REM     install.cmd -DryRun
REM     install.cmd -WithCpsam

setlocal
set "HERE=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%HERE%install.ps1" %*
set "RC=%ERRORLEVEL%"

REM Keep the window open when double-clicked, so the report is readable.
echo.
if "%RC%"=="0" (echo Finished.) else (echo Exited with code %RC%.)
echo Press any key to close...
pause >nul
exit /b %RC%
