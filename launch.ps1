<#
.SYNOPSIS
    Launcher (Facade): runs Unfurl Fabric on its own, either the Fabric Studio server or the Fabric CLI.

.DESCRIPTION
    One entry point over the shaded jar target\unfurl-fabric-studio-server.jar, so Fabric can run without the UI,
    Flow or Foundry. It builds the jar when it is missing (or with --build), checks that Java 21 or later is on PATH,
    and, for the server, that the port is free. It then runs Java in the foreground, so Ctrl-C stops it and the
    script exits with Java's exit code.

    Usage:
      .\launch.ps1 [--build] [server options]      Start the Fabric Studio server
      .\launch.ps1 [--build] cli <command> [args]  Run a Fabric CLI command (compile, sign, plan, ...)
      .\launch.ps1 --help

    Server options are passed to the server unchanged: --bind <address>, --port <port>, --state-path <file>,
    --asset-root <dir>, --event-bus <in-memory|redis|kafka>, --redis-url <url>, --kafka-bootstrap-servers <list>,
    --kafka-topic <topic>.

    Environment (the same names the Dockerfile uses):
      FABRIC_STUDIO_JAR         Jar to run instead of target\unfurl-fabric-studio-server.jar
      JAVA_OPTS                 Extra JVM options, split on spaces
      UNFURL_STUDIO_BIND        Default for --bind (server default 127.0.0.1)
      UNFURL_STUDIO_PORT        Default for --port (server default 7878)
      UNFURL_STUDIO_STATE_PATH  Studio state file, read by the server itself
      UNFURL_STUDIO_ASSET_ROOT  Asset root, read by the server itself
      UNFURL_FOUNDRY_DCP_ENDPOINT  Foundry endpoint for authoring, read by the server itself

    Kept behaviourally identical to launch.sh; launch.bat delegates to this script.
#>
$ErrorActionPreference = 'Stop'

$FabricHome = $PSScriptRoot
$DefaultJar = Join-Path $FabricHome 'target\unfurl-fabric-studio-server.jar'
$CliMainClass = 'com.unfurl.fabric.cli.FabricCli'
$MinimumJava = 21

<#
    Prints the usage block from this script's header, for --help and for usage errors.
#>
function Show-Usage {
    @'
Usage (launch.ps1, or launch.bat from cmd.exe):
  launch [--build] [server options]      Start the Fabric Studio server
  launch [--build] cli <command> [args]  Run a Fabric CLI command
  launch --help

Server options: --bind, --port, --state-path, --asset-root, --event-bus, --redis-url,
--kafka-bootstrap-servers, --kafka-topic. Defaults come from UNFURL_STUDIO_BIND and
UNFURL_STUDIO_PORT, then the server's own defaults (127.0.0.1:7878).
'@ | Write-Host
}

<#
    Builds the shaded jar with Maven (tests skipped) when it is missing or a rebuild was asked for. A FABRIC_STUDIO_JAR
    override is never built: it names a jar built elsewhere, so a missing one is an error.
    Returns the jar path to run.
#>
function Resolve-FabricJar([bool]$Rebuild) {
    if ($env:FABRIC_STUDIO_JAR) {
        if (-not (Test-Path -PathType Leaf $env:FABRIC_STUDIO_JAR)) {
            throw "FABRIC_STUDIO_JAR points at '$env:FABRIC_STUDIO_JAR', which does not exist"
        }
        return (Resolve-Path $env:FABRIC_STUDIO_JAR).Path
    }
    if ($Rebuild -or -not (Test-Path -PathType Leaf $DefaultJar)) {
        if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
            throw "the Fabric jar is not built and Maven (mvn) is not on PATH; build it with 'mvn package -DskipTests' in $FabricHome"
        }
        Write-Host "Building Fabric ($DefaultJar)..."
        & mvn -q -f (Join-Path $FabricHome 'pom.xml') package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE"
        }
    }
    return $DefaultJar
}

<#
    Returns the major version of the java on PATH ("1.8" style versions map to 8), reading 'java -version' through a
    Process so its stderr output is not turned into a PowerShell error.
#>
function Get-JavaMajorVersion {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = (Get-Command java).Source
    $info.Arguments = '-version'
    $info.RedirectStandardError = $true
    $info.RedirectStandardOutput = $true
    $info.UseShellExecute = $false
    $process = [System.Diagnostics.Process]::Start($info)
    $text = $process.StandardError.ReadToEnd() + $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    if ($text -notmatch 'version "(\d+)(?:\.(\d+))?') {
        throw "could not read the Java version from: $text"
    }
    $major = [int]$Matches[1]
    if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
    return $major
}

<#
    Guard: Java must be on PATH and at least the version Fabric is built for.
#>
function Assert-Java {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw "java is not on PATH; install Java $MinimumJava or later"
    }
    $major = Get-JavaMajorVersion
    if ($major -lt $MinimumJava) {
        throw "Fabric needs Java $MinimumJava or later, but java on PATH is version $major"
    }
}

<#
    Returns the value following a flag in an argument list, or $null when the flag is absent.
#>
function Get-OptionValue([string[]]$Arguments, [string]$Flag) {
    for ($i = 0; $i -lt $Arguments.Count - 1; $i++) {
        if ($Arguments[$i] -eq $Flag) { return $Arguments[$i + 1] }
    }
    return $null
}

<#
    Guard: fails when something already accepts connections on the server's port, instead of letting the server die
    with a bind error. A wildcard bind address is checked through the loopback interface.
#>
function Assert-PortFree([string]$Address, [int]$Port) {
    $target = $Address
    if ($target -eq '0.0.0.0' -or $target -eq '::') { $target = '127.0.0.1' }
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $attempt = $client.BeginConnect($target, $Port, $null, $null)
        if ($attempt.AsyncWaitHandle.WaitOne(500) -and $client.Connected) {
            throw "port $Port on $Address is already in use; stop what is using it or pass --port <other port>"
        }
    } catch [System.Net.Sockets.SocketException] {
        # Nothing is listening: the port is free.
    } finally {
        $client.Close()
    }
}

# Argument handling: --build and --help are the launcher's own; 'cli' switches mode; everything else is forwarded.
$rebuild = $false
$rest = New-Object System.Collections.Generic.List[string]
foreach ($argument in $args) { $rest.Add([string]$argument) }
while ($rest.Count -gt 0 -and ($rest[0] -eq '--build' -or $rest[0] -eq '--help' -or $rest[0] -eq '-h')) {
    if ($rest[0] -eq '--build') { $rebuild = $true } else { Show-Usage; exit 0 }
    $rest.RemoveAt(0)
}
$cliMode = $rest.Count -gt 0 -and $rest[0] -eq 'cli'
if ($cliMode) { $rest.RemoveAt(0) }

try {
    Assert-Java
    $jar = Resolve-FabricJar $rebuild
    $javaArgs = New-Object System.Collections.Generic.List[string]
    if ($env:JAVA_OPTS) {
        foreach ($option in ($env:JAVA_OPTS -split '\s+' | Where-Object { $_ })) { $javaArgs.Add($option) }
    }
    if ($cliMode) {
        if ($rest.Count -eq 0) { Show-Usage; throw "cli needs a Fabric command, for example: cli plan --catalog <dir> --needs <file>" }
        $javaArgs.AddRange([string[]]@('-cp', $jar, $CliMainClass))
        $javaArgs.AddRange([string[]]$rest)
    } else {
        $serverArgs = New-Object System.Collections.Generic.List[string]
        $serverArgs.AddRange([string[]]$rest)
        if ($env:UNFURL_STUDIO_BIND -and -not $serverArgs.Contains('--bind')) {
            $serverArgs.AddRange([string[]]@('--bind', $env:UNFURL_STUDIO_BIND))
        }
        if ($env:UNFURL_STUDIO_PORT -and -not $serverArgs.Contains('--port')) {
            $serverArgs.AddRange([string[]]@('--port', $env:UNFURL_STUDIO_PORT))
        }
        $bind = Get-OptionValue $serverArgs.ToArray() '--bind'
        if (-not $bind) { $bind = '127.0.0.1' }
        $port = Get-OptionValue $serverArgs.ToArray() '--port'
        if (-not $port) { $port = '7878' }
        if ($port -notmatch '^\d+$') { throw "--port must be a number, got '$port'" }
        Assert-PortFree $bind ([int]$port)
        Write-Host "Starting Fabric Studio on http://${bind}:$port (health: http://${bind}:$port/health). Press Ctrl-C to stop."
        $javaArgs.AddRange([string[]]@('-jar', $jar))
        $javaArgs.AddRange([string[]]$serverArgs)
    }
    & java @javaArgs
    exit $LASTEXITCODE
} catch {
    Write-Host "launch: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
