param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "",
  [string]$ScriptPathInContainer = "/scripts/lt_002_slice_knee.js",
  [string]$TestId = ("LT-002E-" + (Get-Date -Format "yyyy-MM-dd_HHmmss")),
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [int]$GateTimeoutSec = 300,
  [int]$DrainTimeoutSec = 300,
  [int]$WaitSeconds = 20,
  [string]$SliceProfile = "default",
  [int]$ExpectedStageIndex = 0,
  [int]$ExpectedTargetRps = -1,
  [string]$ResultsRoot = "docs/loadtest/results",
  [string]$BaselinePath = "docs/loadtest/baseline/latest-baseline.json",
  [string]$RulesPath = "k6/loadtest-acceptance-rules.json",
  [string]$Profile = "local-single-node",
  [switch]$ResetStateBeforeRun,
  [switch]$ResetPurgeAllQueues
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$sliceProfileFirstTargetMap = @{
  "default" = 30
  "lower-narrow" = 20
}

if ([string]::IsNullOrWhiteSpace($SliceProfile)) {
  $SliceProfile = "default"
}

if (-not $sliceProfileFirstTargetMap.ContainsKey($SliceProfile)) {
  throw "Unsupported SliceProfile '$SliceProfile'. Supported values: default, lower-narrow"
}

if ($ExpectedTargetRps -lt 0) {
  $ExpectedTargetRps = [int]$sliceProfileFirstTargetMap[$SliceProfile]
}

$workflow = Join-Path $PSScriptRoot "invoke-loadtest-workflow.ps1"

& $workflow `
  -Scenario "LT-002E" `
  -TestId $TestId `
  -ScriptPathInContainer $ScriptPathInContainer `
  -StdoutLogName "lt_002_slice_knee.stdout.log" `
  -K6SummaryFilePrefix "lt_002_slice_knee" `
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
  -ExtraEnv @("SLICE_PROFILE=$SliceProfile") `
  -ResetStateBeforeRun:$ResetStateBeforeRun `
  -ResetPurgeAllQueues:$ResetPurgeAllQueues `
  -MarkerName "[SLICE_START]" `
  -MarkerIndexKey "slice_index" `
  -ExpectedMarkerIndex $ExpectedStageIndex `
  -MarkerTargetKey "target_rps" `
  -ExpectedMarkerTarget $ExpectedTargetRps `
  -MarkerWaitSeconds $WaitSeconds

exit $LASTEXITCODE
