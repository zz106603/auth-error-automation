param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "",
  [string]$ScriptPathInContainer = "/scripts/lt_002_rampup.js",
  [string]$TestId = ("LT-002-" + (Get-Date -Format "yyyy-MM-dd_HHmmss")),
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [int]$GateTimeoutSec = 300,
  [int]$DrainTimeoutSec = 300,
  [string]$ResultsRoot = "docs/loadtest/results",
  [string]$BaselinePath = "docs/loadtest/baseline/latest-baseline.json",
  [string]$RulesPath = "k6/loadtest-acceptance-rules.json",
  [string]$Profile = "local-single-node",
  [switch]$ResetStateBeforeRun,
  [switch]$ResetPurgeAllQueues
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$workflow = Join-Path $PSScriptRoot "invoke-loadtest-workflow.ps1"

& $workflow `
  -Scenario "LT-002" `
  -TestId $TestId `
  -ScriptPathInContainer $ScriptPathInContainer `
  -StdoutLogName "lt_002_rampup.stdout.log" `
  -K6SummaryFilePrefix "lt_002_rampup" `
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

exit $LASTEXITCODE
