param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "",
  [string]$ScriptPathInContainer = "/scripts/lt_004_retry_dlq_pressure.js",
  [string]$TestId = ("LT-004C-" + (Get-Date -Format "yyyy-MM-dd_HHmmss")),
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [int]$TargetRps = 20,
  [string]$SteadyDuration = "6m",
  [ValidateSet("retry-once", "retry-until-dead")]
  [string]$FailureMode = "retry-once",
  [int]$ExpectedFailurePercent = 20,
  [int]$ExpectedFailUntilRetryCount = 1,
  [double]$AllowedDlqDepth = 0.001,
  [int]$GateTimeoutSec = 300,
  [int]$DrainTimeoutSec = 600,
  [string]$ResultsRoot = "docs/loadtest/results",
  [string]$BaselinePath = "docs/loadtest/baseline/latest-baseline.json",
  [string]$RulesPath = "k6/loadtest-acceptance-rules.json",
  [string]$Profile = "local-single-node",
  [switch]$ResetStateBeforeRun,
  [switch]$ResetPurgeAllQueues,
  [switch]$SkipFailureInjectionCheck
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-PrometheusText {
  param([string]$BaseUrl)

  $metricsUrl = ($BaseUrl.TrimEnd("/") + "/actuator/prometheus")
  try {
    return (Invoke-WebRequest -Uri $metricsUrl -UseBasicParsing -TimeoutSec 10).Content
  } catch {
    throw "consumer failure preflight failed: cannot read $metricsUrl. Start the app with local profile and management port 18081."
  }
}

function Get-MetricScalarFromText {
  param(
    [string]$Text,
    [string]$MetricName
  )

  $line = ($Text -split "`n" | Where-Object {
      $_ -match ("^" + [regex]::Escape($MetricName) + "(\s|\{)")
    } | Select-Object -First 1)

  if ([string]::IsNullOrWhiteSpace($line)) {
    throw "consumer failure preflight failed: metric $MetricName not found. Rebuild/restart the app after applying #57 changes."
  }

  $valueText = ($line.Trim() -split "\s+")[-1]
  [double]$actual = 0
  if (-not [double]::TryParse($valueText, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$actual)) {
    throw "consumer failure preflight failed: cannot parse $MetricName value from '$line'."
  }
  return $actual
}

function Assert-ConsumerFailureInjection {
  param(
    [string]$BaseUrl,
    [string]$ExpectedMode,
    [int]$ExpectedPercent,
    [int]$ExpectedFailUntilRetry
  )

  $text = Read-PrometheusText -BaseUrl $BaseUrl

  $modeMetric = "auth_error_runtime_consumer_failure_recorded_info"
  $modeLine = ($text -split "`n" | Where-Object {
      $_ -match ("^" + [regex]::Escape($modeMetric) + "\{") -and $_ -match ("mode=`"" + [regex]::Escape($ExpectedMode) + "`"")
    } | Select-Object -First 1)
  if ([string]::IsNullOrWhiteSpace($modeLine)) {
    throw "consumer failure preflight failed: expected mode '$ExpectedMode' metric was not found. Restart app with auth-error.loadtest.consumer-failure.recorded-mode=$ExpectedMode."
  }

  $percent = Get-MetricScalarFromText -Text $text -MetricName "auth_error_runtime_consumer_failure_recorded_percent"
  if ([math]::Abs($percent - $ExpectedPercent) -gt 0.001) {
    throw "consumer failure preflight failed: expected percent $ExpectedPercent but app reports $percent."
  }

  $failUntilRetry = Get-MetricScalarFromText -Text $text -MetricName "auth_error_runtime_consumer_failure_recorded_fail_until_retry_count"
  if ([math]::Abs($failUntilRetry - $ExpectedFailUntilRetry) -gt 0.001) {
    throw "consumer failure preflight failed: expected fail-until-retry-count $ExpectedFailUntilRetry but app reports $failUntilRetry."
  }

  Write-Host ("Consumer failure preflight PASS: mode={0}, percent={1}, fail_until_retry_count={2}" -f $ExpectedMode, $percent, $failUntilRetry)
}

if (-not $SkipFailureInjectionCheck) {
  Assert-ConsumerFailureInjection `
    -BaseUrl $ActuatorBaseUrl `
    -ExpectedMode $FailureMode `
    -ExpectedPercent $ExpectedFailurePercent `
    -ExpectedFailUntilRetry $ExpectedFailUntilRetryCount
}

$workflow = Join-Path $PSScriptRoot "invoke-loadtest-workflow.ps1"

& $workflow `
  -Scenario "LT-004C" `
  -TestId $TestId `
  -ScriptPathInContainer $ScriptPathInContainer `
  -StdoutLogName "lt_004_retry_dlq_pressure.stdout.log" `
  -K6SummaryFilePrefix "lt_004_retry_dlq_pressure" `
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
  -AllowedDlqDepth $AllowedDlqDepth `
  -ExtraEnv @("TARGET_RPS=$TargetRps", "STEADY_DURATION=$SteadyDuration", "FAILURE_MODE=$FailureMode", "EXPECTED_FAILURE_PERCENT=$ExpectedFailurePercent", "EXPECTED_FAIL_UNTIL_RETRY_COUNT=$ExpectedFailUntilRetryCount") `
  -ResetStateBeforeRun:$ResetStateBeforeRun `
  -ResetPurgeAllQueues:$ResetPurgeAllQueues

exit $LASTEXITCODE
