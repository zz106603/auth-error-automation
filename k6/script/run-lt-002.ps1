param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "",
  [string]$ScriptPathInContainer = "/scripts/lt_002_rampup.js",
  [string]$TestId = ("LT-002-" + (Get-Date -Format "yyyy-MM-dd_HHmmss")),
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [int]$DrainTimeoutSec = 300,
  [string]$ResultsRoot = "docs/loadtest/results",
  [string]$BaselinePath = "docs/loadtest/baseline/latest-baseline.json",
  [string]$RulesPath = "k6/loadtest-acceptance-rules.json",
  [int]$WaitSeconds = 20,
  [int]$ExpectedStageIndex = 0,
  [int]$ExpectedTargetRps = 5
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
$logPath = Join-Path $runDir "lt_002_rampup.stdout.log"
$k6SummaryPath = Join-Path $runDir ("lt_002_rampup-" + $TestId + ".log")
if (Test-Path $logPath) { Remove-Item $logPath -Force }
$postRunDrain = Join-Path $PSScriptRoot "verify-lt-002e-drain.ps1"
$captureAndReport = Join-Path $PSScriptRoot "capture-and-report-loadtest.ps1"

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

Write-Host "==> Starting k6 (testid=$TestId)"
Write-Host "==> Log file: $logPath"
Write-Host "==> Waiting up to $WaitSeconds sec for [STAGE_START]..."

# PowerShell 파이프(Tee-Object) 없이 docker stdout/stderr를 직접 파일로 누적 (NativeCommandError 회피)
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

  if ($raw -match "\[STAGE_START\]") {
    # 엄격 검증: stage_index, target_rps까지 확인
    if ($raw -match "\[STAGE_START\].*stage_index=$ExpectedStageIndex" -and
        $raw -match "\[STAGE_START\].*target_rps=$ExpectedTargetRps") {
      $found = $true
      if (-not $announced) {
        Write-Host "✅ PASS: STAGE_START detected (stage_index=$ExpectedStageIndex, target_rps=$ExpectedTargetRps)." -ForegroundColor Green
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
  Write-Host "❌ FAIL: [STAGE_START] (stage_index=$ExpectedStageIndex, target_rps=$ExpectedTargetRps) not found within $WaitSeconds sec." -ForegroundColor Red
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
Write-Host "==> Final artifact directory: $runDir"

Write-Host "==> Post-run drain verification"
& $postRunDrain `
  -PrometheusBaseUrl $PrometheusBaseUrl `
  -ActuatorBaseUrl $ActuatorBaseUrl `
  -OutputDir $runDir `
  -TimeoutSec $DrainTimeoutSec
$drainExitCode = $LASTEXITCODE

if ($proc.ExitCode -eq 0) {
  Write-Host "==> Capture Prometheus snapshot + generate summary"
  & $captureAndReport `
    -TestId $TestId `
    -Scenario "LT-002" `
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
