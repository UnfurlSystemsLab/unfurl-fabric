<#
.SYNOPSIS
    Session helpers for the Foundry assemblies guide (README.md in this folder).

.DESCRIPTION
    Dot-source this file once per PowerShell session, from the workspace root:

        . "unfurl-fabric\docs\examples\foundry-assemblies\guide-helpers.ps1"

    It defines, in the caller's session:
      $launch          Full path of unfurl-fabric\launch.ps1, used by the guide to start Studio.
      $shutdown        Full path of unfurl-fabric\shutdown.ps1, used by the guide to stop Studio.
      fabric           Runs a Fabric CLI command through the launch script.
      Wait-ForHealth   Waits for an HTTP endpoint to answer and returns its response.
      Assert-PortFree  Stops with a clear message when a local port is already in use.

    Kept in a file so the guide never asks readers to paste multi-line function definitions: running only part
    of such a definition leaves its parameters unset (for example "$url cannot be retrieved").
#>

$launch = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\launch.ps1')).Path
$shutdown = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\shutdown.ps1')).Path

<#
    Facade over the launch script's CLI mode: `fabric <command> [args]` is `launch.ps1 cli <command> [args]`, so the
    guide's commands run with the launcher's Java check and jar build. Sets $LASTEXITCODE to Fabric's exit code.
#>
function fabric {
    & $launch cli @args
}

<#
    Polls an HTTP endpoint until it answers, then returns the parsed response. Tries once a second, each try timing out
    after 2 seconds, and fails after the given number of attempts so a service that never starts doesn't hang the guide.
#>
function Wait-ForHealth {
    param(
        [string]$Url,
        [int]$Attempts = 60
    )
    if (-not $Url) { throw 'Wait-ForHealth needs a URL, for example: Wait-ForHealth http://127.0.0.1:7879/health' }
    foreach ($attempt in 1..$Attempts) {
        try { return Invoke-RestMethod $Url -TimeoutSec 2 } catch { Start-Sleep -Seconds 1 }
    }
    throw "no response from $Url"
}

<#
    Guard: fails with a clear message when something is already listening on a local TCP port, before the guide
    starts a service that would otherwise fail to bind.
#>
function Assert-PortFree {
    param([int]$Port)
    if (-not $Port) { throw 'Assert-PortFree needs a port number, for example: Assert-PortFree 7879' }
    if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
        throw "port $Port is already in use. Stop what is using it, then try again."
    }
}
