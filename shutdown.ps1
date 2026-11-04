<#
.SYNOPSIS
    Stops a Fabric Studio server started with launch.ps1, launch.bat or launch.sh, on Windows.

.DESCRIPTION
    Finds the process listening on the Studio port, or every Studio server with --all, and stops it only when its
    command line runs the Studio server jar, so an unrelated process that happens to use the port is never touched.
    Nothing running is not an error. The launch script that started the server exits by itself once Java exits.

    A console Java process on Windows cannot be asked to exit, so the server is stopped forcefully. That is safe:
    Studio writes its state file atomically on every change (docs/RELIABILITY.md), so nothing is lost.

    Usage:
      .\shutdown.ps1                     Stop the Studio server on UNFURL_STUDIO_PORT, else port 7878
      .\shutdown.ps1 --port <port>       Stop the Studio server on that port
      .\shutdown.ps1 --all               Stop every Fabric Studio server on this machine
      .\shutdown.ps1 [...] --timeout <s> Seconds to wait for it to exit (default 10)
      .\shutdown.ps1 --help

    FABRIC_STUDIO_JAR, when set, names the jar to look for instead of unfurl-fabric-studio-server.jar.
    shutdown.bat delegates to this script, and so does shutdown.sh under Git Bash, MSYS or Cygwin.
#>
$ErrorActionPreference = 'Stop'

$JarName = 'unfurl-fabric-studio-server.jar'
if ($env:FABRIC_STUDIO_JAR) { $JarName = Split-Path -Leaf $env:FABRIC_STUDIO_JAR }

<#
    Prints the usage summary, for --help and for usage errors.
#>
function Show-Usage {
    @'
Usage (shutdown.ps1, or shutdown.bat from cmd.exe):
  shutdown                      Stop the Studio server on UNFURL_STUDIO_PORT, else port 7878
  shutdown --port <port>        Stop the Studio server on that port
  shutdown --all                Stop every Fabric Studio server on this machine
  shutdown [...] --timeout <s>  Seconds to wait for it to exit (default 10)
  shutdown --help
'@ | Write-Host
}

<#
    Specification: true when a command line runs the Studio server, that is `java ... -jar <path>\<jar>`. The Fabric
    CLI runs the same jar with -cp and the FabricCli class, so CLI commands in progress are never stopped.
#>
function Test-StudioCommandLine([string]$CommandLine) {
    if (-not $CommandLine) { return $false }
    $pattern = '(^|\s)-jar\s+"?[^"]*' + [regex]::Escape($JarName) + '"?(\s|$)'
    return [regex]::IsMatch($CommandLine, $pattern)
}

<#
    Returns the Win32 process records of every running Studio server.
#>
function Get-StudioProcesses {
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" |
        Where-Object { Test-StudioCommandLine $_.CommandLine }
}

<#
    Returns the process listening on a local TCP port, or $null when nothing is.
#>
function Get-PortListener([int]$Port) {
    $connection = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $connection) { return $null }
    return Get-CimInstance Win32_Process -Filter "ProcessId = $($connection.OwningProcess)"
}

<#
    Stops one Studio server process and waits until it has exited, failing if it is still running after the timeout.
#>
function Stop-StudioProcess($Process, [int]$TimeoutSeconds) {
    Stop-Process -Id $Process.ProcessId -Force -ErrorAction SilentlyContinue
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while (Get-Process -Id $Process.ProcessId -ErrorAction SilentlyContinue) {
        if ((Get-Date) -gt $deadline) {
            throw "Fabric Studio (pid $($Process.ProcessId)) is still running after $TimeoutSeconds seconds"
        }
        Start-Sleep -Milliseconds 200
    }
    Write-Host "Stopped Fabric Studio (pid $($Process.ProcessId))."
}

try {
    if ($PSVersionTable.PSEdition -eq 'Core' -and -not $IsWindows) {
        throw 'shutdown.ps1 is for Windows; on Linux or macOS use ./shutdown.sh'
    }

    # Argument handling: every option is the script's own.
    $all = $false
    $port = $null
    $timeout = 10
    for ($i = 0; $i -lt $args.Count; $i++) {
        switch ([string]$args[$i]) {
            '--all' { $all = $true }
            '--port' { $i++; if ($i -ge $args.Count) { throw '--port needs a value' }; $port = [string]$args[$i] }
            '--timeout' { $i++; if ($i -ge $args.Count) { throw '--timeout needs a value' }; $timeout = [string]$args[$i] }
            { $_ -in '--help', '-h' } { Show-Usage; exit 0 }
            default { Show-Usage; throw "unknown option: $($args[$i])" }
        }
    }
    if ($all -and $port) { throw 'use either --all or --port, not both' }
    if ("$timeout" -notmatch '^\d+$') { throw "--timeout must be a number of seconds, got '$timeout'" }

    if ($all) {
        $servers = @(Get-StudioProcesses)
        if ($servers.Count -eq 0) { Write-Host 'No Fabric Studio server is running.'; exit 0 }
        foreach ($server in $servers) { Stop-StudioProcess $server ([int]$timeout) }
        exit 0
    }

    if (-not $port) { $port = $env:UNFURL_STUDIO_PORT }
    if (-not $port) { $port = '7878' }
    if ("$port" -notmatch '^\d+$') { throw "--port must be a number, got '$port'" }
    $listener = Get-PortListener ([int]$port)
    if (-not $listener) { Write-Host "No Fabric Studio server is running on port $port."; exit 0 }
    if (-not (Test-StudioCommandLine $listener.CommandLine)) {
        throw "port $port is used by $($listener.Name) (pid $($listener.ProcessId)), which is not Fabric Studio; nothing was stopped"
    }
    Stop-StudioProcess $listener ([int]$timeout)
    exit 0
} catch {
    Write-Host "shutdown: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
