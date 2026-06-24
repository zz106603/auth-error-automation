param(
    [switch] $Quiet
)

$ErrorActionPreference = "Stop"

function Write-Check {
    param([string] $Message)
    if (-not $Quiet) {
        Write-Host "[testcontainers-check] $Message"
    }
}

try {
    $dockerVersion = docker version --format '{{.Server.Version}}' 2>$null
    if ([string]::IsNullOrWhiteSpace($dockerVersion)) {
        throw "Docker daemon is not responding."
    }

    docker info 1>$null 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "docker info failed."
    }

    Write-Check "Docker daemon is available. Server version: $dockerVersion"
    Write-Check "Testcontainers-backed integration tests can be attempted."
    exit 0
} catch {
    Write-Error @"
Docker/Testcontainers preflight failed.

Cause:
$($_.Exception.Message)

Meaning:
- Code-only checks can still run with: .\gradlew.bat quickTest
- Integration tests require Docker: .\gradlew.bat integrationTest
- Start Docker Desktop or provide a reachable Docker daemon, then rerun this script.
"@
    exit 1
}
