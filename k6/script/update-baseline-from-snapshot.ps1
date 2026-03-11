param(
  [Parameter(Mandatory = $true)]
  [string]$SnapshotPath,
  [string]$OutputPath = "docs/loadtest/baseline/latest-baseline.json",
  [string]$Source = "LT-001 snapshot"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-PathFromRepoRoot {
  param([string]$InputPath)

  if ([string]::IsNullOrWhiteSpace($InputPath)) {
    throw "Path is empty."
  }
  if ([System.IO.Path]::IsPathRooted($InputPath)) {
    return [System.IO.Path]::GetFullPath($InputPath)
  }
  $repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
  return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $InputPath))
}

function Get-PathValue {
  param(
    [object]$Root,
    [string[]]$Path
  )

  $cur = $Root
  foreach ($seg in $Path) {
    if ($null -eq $cur) { return $null }
    if ($cur -is [hashtable]) {
      if (-not $cur.ContainsKey($seg)) { return $null }
      $cur = $cur[$seg]
      continue
    }
    $p = $cur.PSObject.Properties[$seg]
    if ($null -eq $p) { return $null }
    $cur = $p.Value
  }
  return $cur
}

function To-DoubleOrNull {
  param([object]$Value)
  if ($null -eq $Value) { return $null }
  try { return [double]$Value } catch { return $null }
}

$resolvedSnapshot = Resolve-PathFromRepoRoot -InputPath $SnapshotPath
if (-not (Test-Path $resolvedSnapshot)) {
  throw "Snapshot file not found: $resolvedSnapshot"
}

$resolvedOutput = Resolve-PathFromRepoRoot -InputPath $OutputPath
$outputDir = Split-Path -Parent $resolvedOutput
if (-not (Test-Path $outputDir)) {
  New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}

$snapshot = Get-Content $resolvedSnapshot -Raw | ConvertFrom-Json

$pipelineP95Sec = To-DoubleOrNull (Get-PathValue -Root $snapshot -Path @("kpis", "ingest_to_consume_p95_seconds", "stats", "p95"))
$pipelineP99Sec = To-DoubleOrNull (Get-PathValue -Root $snapshot -Path @("kpis", "ingest_to_consume_p99_seconds", "stats", "p99"))
$outboxP95Ms = To-DoubleOrNull (Get-PathValue -Root $snapshot -Path @("kpis", "outbox_age_p95_ms", "stats", "avg"))
$outboxP99Ms = To-DoubleOrNull (Get-PathValue -Root $snapshot -Path @("kpis", "outbox_age_p99_ms", "stats", "avg"))
$ingestRps = To-DoubleOrNull (Get-PathValue -Root $snapshot -Path @("kpis", "ingest_rps", "stats", "avg"))
$publishRps = To-DoubleOrNull (Get-PathValue -Root $snapshot -Path @("kpis", "publish_rps", "stats", "avg"))
$consumeRps = To-DoubleOrNull (Get-PathValue -Root $snapshot -Path @("kpis", "consume_rps", "stats", "avg"))

if ($null -eq $pipelineP95Sec -or $null -eq $pipelineP99Sec) {
  throw "Snapshot does not contain usable ingest_to_consume baseline stats: $resolvedSnapshot"
}

$capturedAt = [string](Get-PathValue -Root $snapshot -Path @("metadata", "run_window", "t_end"))
if ([string]::IsNullOrWhiteSpace($capturedAt)) {
  $capturedAt = [DateTimeOffset]::UtcNow.ToString("o")
}

$baseline = [ordered]@{
  source = $Source
  captured_at = $capturedAt
  baseline_ingest_to_consume_p95_ms = [Math]::Round($pipelineP95Sec * 1000.0, 3)
  baseline_ingest_to_consume_p99_ms = [Math]::Round($pipelineP99Sec * 1000.0, 3)
  baseline_outbox_age_p95_ms = if ($null -eq $outboxP95Ms) { 0.0 } else { [Math]::Round($outboxP95Ms, 3) }
  baseline_outbox_age_p99_ms = if ($null -eq $outboxP99Ms) { 0.0 } else { [Math]::Round($outboxP99Ms, 3) }
  baseline_ingest_rate_rps = if ($null -eq $ingestRps) { 0.0 } else { [Math]::Round($ingestRps, 3) }
  baseline_publish_rate_rps = if ($null -eq $publishRps) { 0.0 } else { [Math]::Round($publishRps, 3) }
  baseline_consume_rate_rps = if ($null -eq $consumeRps) { 0.0 } else { [Math]::Round($consumeRps, 3) }
}

$baseline | ConvertTo-Json -Depth 5 | Out-File -Encoding utf8 $resolvedOutput

Write-Host "Baseline updated."
Write-Host " - snapshot: $resolvedSnapshot"
Write-Host " - output:   $resolvedOutput"
exit 0
