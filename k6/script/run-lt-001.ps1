param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "",
  [string]$ScriptPathInContainer = "/scripts/lt_001_baseline.js",
  [string]$TestId = ("LT-001-" + (Get-Date -Format "yyyy-MM-dd_HHmmss")),
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [int]$GateTimeoutSec = 300,
  [int]$DrainTimeoutSec = 300,
  [string]$ResultsRoot = "docs/loadtest/results",
  [string]$BaselinePath = "docs/loadtest/baseline/latest-baseline.json",
  [string]$RulesPath = "k6/loadtest-acceptance-rules.json",
  [string]$Profile = "local-single-node",
  [bool]$AutoUpdateBaseline = $true,
  [switch]$ResetStateBeforeRun,
  [switch]$ResetPurgeAllQueues
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$workflow = Join-Path $PSScriptRoot "invoke-loadtest-workflow.ps1"

& $workflow `
  -Scenario "LT-001" `
  -TestId $TestId `
  -ScriptPathInContainer $ScriptPathInContainer `
  -StdoutLogName "lt_001_baseline.stdout.log" `
  -K6SummaryFilePrefix "lt_001_baseline" `
  -Network $Network `
  -EnvFile $EnvFile `
  -LocalScriptsDir $LocalScriptsDir `
  -PrometheusBaseUrl $PrometheusBaseUrl `
  -ActuatorBaseUrl $ActuatorBaseUrl `
  -GateTimeoutSec $GateTimeoutSec `
  -DrainTimeoutSec $DrainTimeoutSec `
  -ResultsRoot $ResultsRoot `
  -BaselinePath $BaselinePath `
  -RulesPath $RulesPath `
  -Profile $Profile `
  -ResetStateBeforeRun:$ResetStateBeforeRun `
  -ResetPurgeAllQueues:$ResetPurgeAllQueues

$workflowExitCode = $LASTEXITCODE
if ($workflowExitCode -ne 0) {
  exit $workflowExitCode
}

if ($AutoUpdateBaseline) {
  $updateBaseline = Join-Path $PSScriptRoot "update-baseline-from-snapshot.ps1"
  $snapshotPath = Join-Path (Join-Path $ResultsRoot $TestId) "prometheus-snapshot.json"

  if (-not (Test-Path $snapshotPath)) {
    Write-Host "==> Baseline update blocked: snapshot not found: $snapshotPath" -ForegroundColor Yellow
    exit 1
  }

  $snapshot = Get-Content $snapshotPath -Raw | ConvertFrom-Json
  $snapshotVerdict = [string]$snapshot.verdict
  if ($snapshotVerdict -ne "PASS") {
    $coverage = @($snapshot.checks | Where-Object { $_.name -eq "post_run_counter_coverage" } | Select-Object -First 1)
    $coverageDetail = ""
    if ($coverage.Count -gt 0 -and $coverage[0].status -ne "PASS") {
      $coverageDetail = (" post_run_counter_coverage={0}: {1}" -f $coverage[0].status, $coverage[0].detail)
    }
    Write-Host ("==> Baseline update blocked: snapshot verdict={0}.{1}" -f $snapshotVerdict, $coverageDetail) -ForegroundColor Yellow
    exit 1
  }

  Write-Host "==> Update baseline from LT-001 snapshot"
  & $updateBaseline `
    -SnapshotPath $snapshotPath `
    -OutputPath $BaselinePath `
    -Source ("{0}:{1}" -f "LT-001", $TestId)

  if ($LASTEXITCODE -ne 0) {
    Write-Host "==> Baseline update failed (exit=$LASTEXITCODE)" -ForegroundColor Yellow
    exit $LASTEXITCODE
  }
}

exit 0
