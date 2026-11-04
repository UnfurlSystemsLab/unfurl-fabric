@echo off
rem Launcher (Facade) for cmd.exe: runs Unfurl Fabric on its own, the Fabric Studio server or the Fabric CLI.
rem
rem It delegates to launch.ps1 in the same folder, so the build, Java, port and argument handling exist once for
rem Windows. The execution-policy bypass applies to this one PowerShell process only.
rem
rem Usage:
rem   launch.bat [--build] [server options]      Start the Fabric Studio server
rem   launch.bat [--build] cli ^<command^> [args]  Run a Fabric CLI command
rem   launch.bat --help
setlocal
where powershell.exe >nul 2>nul
if errorlevel 1 (
    echo launch: powershell.exe is not on PATH; it is needed to run launch.ps1 1>&2
    exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0launch.ps1" %*
exit /b %ERRORLEVEL%
