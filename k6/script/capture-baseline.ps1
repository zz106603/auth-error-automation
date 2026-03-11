param(
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [string]$BaseUrl = "",
  [string]$OutDir = "docs/loadtest/baseline-captures",
  [int]$WarmupSec = 60,
  [int]$IntervalSec = 10,
  [int]$Samples = 12
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-PathFromRepoRoot {
  param([string]$InputPath)

  if ([string]::IsNullOrWhiteSpace($InputPath)) {
    throw "OutDir is empty."
  }

  if ([System.IO.Path]::IsPathRooted($InputPath)) {
    return [System.IO.Path]::GetFullPath($InputPath)
  }

  $repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
  return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $InputPath))
}

$OutDir = Resolve-PathFromRepoRoot -InputPath $OutDir
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Resolve-ActuatorBaseUrl {
  param(
    [string]$InputActuatorBaseUrl,
    [string]$InputBaseUrl
  )

  if (-not [string]::IsNullOrWhiteSpace($InputActuatorBaseUrl)) {
    return $InputActuatorBaseUrl.TrimEnd('/')
  }
  if (-not [string]::IsNullOrWhiteSpace($InputBaseUrl)) {
    return $InputBaseUrl.TrimEnd('/')
  }
  return "http://localhost:18081"
}

$ActuatorBaseUrl = Resolve-ActuatorBaseUrl -InputActuatorBaseUrl $ActuatorBaseUrl -InputBaseUrl $BaseUrl

function Get-PrometheusEndpointUri {
  param([string]$ActuatorRoot)

  return ($ActuatorRoot.TrimEnd('/') + "/actuator/prometheus")
}

function Invoke-PrometheusScrapeOrThrow {
  param([string]$Uri)

  try {
    return (Invoke-WebRequest -UseBasicParsing -Uri $Uri -Method Get).Content
  } catch {
    $msg = $_.Exception.Message
    throw ("Failed to fetch actuator prometheus endpoint: {0}`n" +
      "Reason: {1}`n" +
      "Expected local config in this repository uses management port 18081 (application-local.yml).`n" +
      "If app runs on 8080 only, pass explicit URL:`n" +
      "  .\k6\script\capture-baseline.ps1 -ActuatorBaseUrl 'http://localhost:8080'" -f $Uri, $msg)
  }
}

function Capture-Prom {
  param([string]$Label)
  $ts = Get-Date -Format "yyyyMMdd-HHmmss"
  $path = Join-Path $OutDir "prom-$Label-$ts.txt"
  $uri = Get-PrometheusEndpointUri -ActuatorRoot $ActuatorBaseUrl
  (Invoke-PrometheusScrapeOrThrow -Uri $uri) |
    Out-File -Encoding utf8 $path
  Write-Host "Captured: $path"
  return $path
}

Write-Host "== Baseline capture =="
Write-Host "ActuatorUrl : $ActuatorBaseUrl"
Write-Host "OutDir      : $OutDir"
Write-Host "WarmupSec   : $WarmupSec"
Write-Host "IntervalSec : $IntervalSec"
Write-Host "Samples     : $Samples"
Write-Host ""

if ($WarmupSec -gt 0) {
  Write-Host "Warming up for $WarmupSec seconds..."
  Start-Sleep -Seconds $WarmupSec
}

$paths = @()
for ($i=0; $i -lt $Samples; $i++) {
  $paths += (Capture-Prom -Label "s$i")
  if ($i -lt ($Samples - 1)) {
    Start-Sleep -Seconds $IntervalSec
  }
}

Write-Host ""
Write-Host "Done. Snapshot files:"
$paths | ForEach-Object { Write-Host " - $_" }

# Write a small manifest for downstream summarizer
$manifest = Join-Path $OutDir "capture-manifest.txt"
$paths | Out-File -Encoding utf8 $manifest
Write-Host ""
Write-Host "Manifest written: $manifest"
