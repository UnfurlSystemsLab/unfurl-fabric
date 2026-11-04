@echo off
rem Stops a Fabric Studio server started with the launch scripts, from cmd.exe.
rem
rem It delegates to shutdown.ps1 in the same folder, so process discovery and the safety check that only the Studio
rem server is stopped exist once for Windows. The execution-policy bypass applies to this one PowerShell process only.
rem Call it by path (.\shutdown.bat) from other folders: a bare "shutdown" there runs Windows' own shutdown.exe.
rem
rem Usage:
rem   shutdown.bat [--port ^<port^> ^| --all] [--timeout ^<seconds^>]
rem   shutdown.bat --help
setlocal
where powershell.exe >nul 2>nul
if errorlevel 1 (
    echo shutdown: powershell.exe is not on PATH; it is needed to run shutdown.ps1 1>&2
    exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0shutdown.ps1" %*
exit /b %ERRORLEVEL%
