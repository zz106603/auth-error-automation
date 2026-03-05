param(
  [string]$BaseUrl = "http://localhost:8080",
  [string]$OutDir = "docs/loadtest/baseline-captures",
  [int]$WarmupSec = 60,
  [int]$IntervalSec = 10,
  [int]$Samples = 12
)

$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Capture-Prom {
  param([string]$Label)
  $ts = Get-Date -Format "yyyyMMdd-HHmmss"
  $path = Join-Path $OutDir "prom-$Label-$ts.txt"
  (Invoke-WebRequest -UseBasicParsing -Uri "$BaseUrl/actuator/prometheus").Content |
    Out-File -Encoding utf8 $path
  Write-Host "Captured: $path"
  return $path
}

Write-Host "== Baseline capture =="
Write-Host "BaseUrl     : $BaseUrl"
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
