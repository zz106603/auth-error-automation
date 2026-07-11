param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "",
  [string]$ScriptPathInContainer = "/scripts/lt_004_consumer_slow.js",
  [string]$TestId = ("LT-004A-" + (Get-Date -Format "yyyy-MM-dd_HHmmss")),
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [int]$TargetRps = 30,
  [string]$SteadyDuration = "10m",
  [int]$ExpectedConsumerDelayMs = 150,
  [int]$GateTimeoutSec = 300,
  [int]$DrainTimeoutSec = 300,
  [string]$ResultsRoot = "docs/loadtest/results",
  [string]$BaselinePath = "docs/loadtest/baseline/latest-baseline.json",
  [string]$RulesPath = "k6/loadtest-acceptance-rules.json",
  [string]$Profile = "local-single-node",
  [switch]$ResetStateBeforeRun,
  [switch]$ResetPurgeAllQueues,
  [switch]$SkipConsumerDelayCheck
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-ConsumerDelayMetric {
  param(
    [string]$BaseUrl,
    [int]$ExpectedMs
  )

  $metricsUrl = ($BaseUrl.TrimEnd("/") + "/actuator/prometheus")
  try {
    $response = Invoke-WebRequest -Uri $metricsUrl -UseBasicParsing -TimeoutSec 10
  } catch {
    throw "consumer delay preflight failed: cannot read $metricsUrl. Start the app with local profile and management port 18081."
  }

  $metricName = "auth_error_runtime_consumer_delay_recorded_ms"
  $line = ($response.Content -split "`n" | Where-Object {
      $_ -match ("^" + [regex]::Escape($metricName) + "(\s|\{)")
    } | Select-Object -First 1)

  if ([string]::IsNullOrWhiteSpace($line)) {
    throw "consumer delay preflight failed: metric $metricName not found. Rebuild/restart the app after applying #55 changes."
  }

  $valueText = ($line.Trim() -split "\s+")[-1]
  [double]$actual = 0
  if (-not [double]::TryParse($valueText, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$actual)) {
    throw "consumer delay preflight failed: cannot parse $metricName value from '$line'."
  }

  if ([math]::Abs($actual - $ExpectedMs) -gt 0.001) {
    throw "consumer delay preflight failed: expected ${ExpectedMs}ms but app reports ${actual}ms. Restart app with --auth-error.loadtest.consumer-delay.recorded-ms=$ExpectedMs."
  }

  Write-Host ("Consumer delay preflight PASS: {0}={1}ms" -f $metricName, $actual)
}

if (-not $SkipConsumerDelayCheck) {
  Assert-ConsumerDelayMetric -BaseUrl $ActuatorBaseUrl -ExpectedMs $ExpectedConsumerDelayMs
}

$workflow = Join-Path $PSScriptRoot "invoke-loadtest-workflow.ps1"

& $workflow `
  -Scenario "LT-004A" `
  -TestId $TestId `
  -ScriptPathInContainer $ScriptPathInContainer `
  -StdoutLogName "lt_004_consumer_slow.stdout.log" `
  -K6SummaryFilePrefix "lt_004_consumer_slow" `
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
  -ExtraEnv @("TARGET_RPS=$TargetRps", "STEADY_DURATION=$SteadyDuration", "EXPECTED_CONSUMER_DELAY_MS=$ExpectedConsumerDelayMs") `
  -ResetStateBeforeRun:$ResetStateBeforeRun `
  -ResetPurgeAllQueues:$ResetPurgeAllQueues

exit $LASTEXITCODE
