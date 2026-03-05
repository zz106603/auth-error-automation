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
  [int]$ExpectedStageIndex = 0,
  [int]$ExpectedTargetRps = 60,
  [string]$ResultsRoot = "docs/loadtest/results",
  [string]$BaselinePath = "docs/loadtest/baseline/latest-baseline.json",
  [string]$RulesPath = "k6/loadtest-acceptance-rules.json"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($LocalScriptsDir)) {
  $LocalScriptsDir = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
}

$resultsDir = Join-Path $LocalScriptsDir "results"
if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Path $resultsDir | Out-Null }
$runDir = Join-Path $resultsDir $TestId
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$logPath = Join-Path $runDir "lt_002_slice_knee.stdout.log"
$k6SummaryPath = Join-Path $runDir ("lt_002_slice_knee-" + $TestId + ".log")
if (Test-Path $logPath) { Remove-Item $logPath -Force }
$preRunGate = Join-Path $PSScriptRoot "wait-lt-002e-clean.ps1"
$postRunDrain = Join-Path $PSScriptRoot "verify-lt-002e-drain.ps1"
$captureAndReport = Join-Path $PSScriptRoot "capture-and-report-loadtest.ps1"

Write-Host "==> Pre-run clean gate"
& $preRunGate `
  -PrometheusBaseUrl $PrometheusBaseUrl `
  -ActuatorBaseUrl $ActuatorBaseUrl `
  -OutputDir $runDir `
  -TimeoutSec $GateTimeoutSec
if ($LASTEXITCODE -ne 0) {
  Write-Host "==> Aborting LT-002E run because pre-run clean gate failed." -ForegroundColor Red
  exit $LASTEXITCODE
}

$dockerArgs = @(
  "run","--rm","-i",
  "--network",$Network,
  "--env-file",$EnvFile,
  "-e","K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write",
  "-e","K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),avg,max",
  "-e","K6_PROMETHEUS_RW_PUSH_INTERVAL=5s",
  "-e","TEST_ID=$TestId",
  "-e","RESULTS_DIR=/scripts/results/$TestId",
  "-v","${LocalScriptsDir}:/scripts",
  "grafana/k6:latest","run","-o","experimental-prometheus-rw",
  "--tag","testid=$TestId",
  $ScriptPathInContainer
)

Write-Host "==> Starting k6 knee slice (testid=$TestId)"
Write-Host "==> Log file: $logPath"
Write-Host "==> Artifact directory: $runDir"
Write-Host "==> Waiting up to $WaitSeconds sec for [SLICE_START]..."

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "docker"
$psi.Arguments = ($dockerArgs -join " ")
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$runStartUtc = [DateTimeOffset]::UtcNow
$null = $proc.Start()

$writer = [System.IO.StreamWriter]::new($logPath, $true)

$start = Get-Date
$found = $false
$announced = $false

while ($true) {
  while (-not $proc.StandardOutput.EndOfStream) {
    $line = $proc.StandardOutput.ReadLine()
    $writer.WriteLine($line)
  }
  while (-not $proc.StandardError.EndOfStream) {
    $line = $proc.StandardError.ReadLine()
    $writer.WriteLine($line)
  }
  $writer.Flush()

  $raw = ""
  if (Test-Path $logPath) { $raw = Get-Content $logPath -Raw }

  if ($raw -match "\[SLICE_START\]") {
    if ($raw -match "\[SLICE_START\].*slice_index=$ExpectedStageIndex" -and
        $raw -match "\[SLICE_START\].*target_rps=$ExpectedTargetRps") {
      $found = $true
      if (-not $announced) {
        Write-Host "PASS: SLICE_START detected (slice_index=$ExpectedStageIndex, target_rps=$ExpectedTargetRps)." -ForegroundColor Green
        Write-Host "   testid=$TestId"
        Write-Host "   log=$logPath"
        $announced = $true
      }
    }
  }

  if (-not $found -and ((Get-Date) - $start).TotalSeconds -ge $WaitSeconds) { break }
  if ($found -and $proc.HasExited -and $proc.StandardOutput.EndOfStream -and $proc.StandardError.EndOfStream) { break }
  Start-Sleep -Milliseconds 200
}

if (-not $found) {
  Write-Host "FAIL: [SLICE_START] (slice_index=$ExpectedStageIndex, target_rps=$ExpectedTargetRps) not found within $WaitSeconds sec." -ForegroundColor Red
  Write-Host "   log=$logPath" -ForegroundColor Yellow
  try { $proc.Kill() } catch {}
  $writer.Dispose()
  exit 1
}

$proc.WaitForExit()
while (-not $proc.StandardOutput.EndOfStream) {
  $line = $proc.StandardOutput.ReadLine()
  $writer.WriteLine($line)
}
while (-not $proc.StandardError.EndOfStream) {
  $line = $proc.StandardError.ReadLine()
  $writer.WriteLine($line)
}
$writer.Flush()
$writer.Dispose()
$runEndUtc = [DateTimeOffset]::UtcNow

Write-Host "==> k6 finished with exit code $($proc.ExitCode)"
Write-Host "==> Summary file: $k6SummaryPath"

Write-Host "==> Post-run drain verification"
& $postRunDrain `
  -PrometheusBaseUrl $PrometheusBaseUrl `
  -ActuatorBaseUrl $ActuatorBaseUrl `
  -OutputDir $runDir `
  -TimeoutSec $DrainTimeoutSec
$drainExitCode = $LASTEXITCODE

Write-Host "==> Final artifact directory: $runDir"

if ($proc.ExitCode -eq 0) {
  Write-Host "==> Capture Prometheus snapshot + generate summary"
  & $captureAndReport `
    -TestId $TestId `
    -Scenario "LT-002E" `
    -PrometheusBaseUrl $PrometheusBaseUrl `
    -ResultsRoot $ResultsRoot `
    -K6ResultsRoot (Join-Path $LocalScriptsDir "results") `
    -RunDir $runDir `
    -K6SummaryPath $k6SummaryPath `
    -StartTime $runStartUtc.ToString("o") `
    -EndTime $runEndUtc.ToString("o") `
    -StepSec 5 `
    -BaselinePath $BaselinePath `
    -RulesPath $RulesPath `
    -Profile "local-single-node"

  if ($LASTEXITCODE -ne 0) {
    Write-Host "==> Snapshot/report generation failed (exit=$LASTEXITCODE)" -ForegroundColor Yellow
    exit $LASTEXITCODE
  }
}

if ($proc.ExitCode -ne 0) {
  exit $proc.ExitCode
}

exit $drainExitCode
