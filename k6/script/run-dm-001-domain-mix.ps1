param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "",
  [string]$ScriptPathInContainer = "/scripts/dm_001_domain_mix.js",
  [string]$TestId = ("DM-001-" + (Get-Date -Format "yyyy-MM-dd_HHmmss")),
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [int]$TargetRps = 5,
  [string]$DemoDuration = "5m",
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
  -Scenario "DM-001" `
  -TestId $TestId `
  -ScriptPathInContainer $ScriptPathInContainer `
  -StdoutLogName "dm_001_domain_mix.stdout.log" `
  -K6SummaryFilePrefix "dm_001_domain_mix" `
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
  -ExtraEnv @("TARGET_RPS=$TargetRps", "DEMO_DURATION=$DemoDuration") `
  -ResetStateBeforeRun:$ResetStateBeforeRun `
  -ResetPurgeAllQueues:$ResetPurgeAllQueues

exit $LASTEXITCODE
