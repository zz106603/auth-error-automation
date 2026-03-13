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
  "default" = 60
  "lower-narrow" = 50
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

if ([string]::IsNullOrWhiteSpace($LocalScriptsDir)) {
  $LocalScriptsDir = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
}

$resultsDir = Join-Path $LocalScriptsDir "results"
if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Path $resultsDir | Out-Null }
$runDir = Join-Path $resultsDir $TestId
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$logPath = Join-Path $runDir "lt_002_slice_knee.stdout.log"
$k6SummaryPath = Join-Path $runDir ("lt_002_slice_knee-" + $TestId + ".log")
$wrapperLogPath = Join-Path $runDir "wrapper-execution.log"
$wrapperStatePath = Join-Path $runDir "wrapper-state.json"
$wrapperFailurePath = Join-Path $runDir "wrapper.failure.json"
$wrapperFailureMarkerPath = Join-Path $runDir "FAILED-WRAPPER.txt"

if (Test-Path $logPath) { Remove-Item $logPath -Force }
if (Test-Path $wrapperLogPath) { Remove-Item $wrapperLogPath -Force }

$preRunGate = Join-Path $PSScriptRoot "wait-loadtest-clean.ps1"
$postRunDrain = Join-Path $PSScriptRoot "verify-loadtest-drain.ps1"
$captureAndReport = Join-Path $PSScriptRoot "capture-and-report-loadtest.ps1"
$resetStateScript = Join-Path $PSScriptRoot "reset-loadtest-state.ps1"

$dockerArgs = @(
  "run","--rm","-i",
  "--network",$Network,
  "--env-file",$EnvFile,
  "-e","K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write",
  "-e","K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),avg,max",
  "-e","K6_PROMETHEUS_RW_PUSH_INTERVAL=5s",
  "-e","TEST_ID=$TestId",
  "-e","SLICE_PROFILE=$SliceProfile",
  "-e","RESULTS_DIR=/scripts/results/$TestId",
  "-v","${LocalScriptsDir}:/scripts",
  "grafana/k6:latest","run","-o","experimental-prometheus-rw",
  "--tag","testid=$TestId",
  $ScriptPathInContainer
)

$wrapperState = [ordered]@{
  test_id = $TestId
  scenario = "LT-002E"
  slice_profile = $SliceProfile
  started_at = (Get-Date).ToString("o")
  finished_at = $null
  phase = "init"
  final_status = "running"
  exit_code = $null
  error_phase = $null
  error_message = $null
  run_dir = $runDir
  docs_results_root = $ResultsRoot
  docs_result_dir = $null
  k6_summary_path = $k6SummaryPath
  wrapper_log_path = $wrapperLogPath
  k6_started = $false
  k6_completed = $false
  k6_exit_code = $null
  monitor_stage_detected = $false
  reset_state_exit_code = $null
  pre_run_gate_exit_code = $null
  post_run_drain_exit_code = $null
  capture_report_exit_code = $null
}

function Save-WrapperState {
  $wrapperState | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 $wrapperStatePath
}

function Write-WrapperLog {
  param(
    [string]$Message,
    [string]$Level = "INFO"
  )

  $line = "[{0}] [{1}] {2}" -f (Get-Date).ToString("o"), $Level, $Message
  Write-Host $line
  Add-Content -Encoding utf8 -Path $wrapperLogPath -Value $line
}

function Resolve-ResultDirPath {
  $candidate = Join-Path $ResultsRoot $TestId
  if ([System.IO.Path]::IsPathRooted($candidate)) {
    return [System.IO.Path]::GetFullPath($candidate)
  }
  return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $candidate))
}

function Write-FailureArtifacts {
  param(
    [string]$Phase,
    [string]$Message,
    [int]$ExitCode
  )

  $wrapperState.final_status = "failed"
  $wrapperState.error_phase = $Phase
  $wrapperState.error_message = $Message
  $wrapperState.exit_code = $ExitCode
  $wrapperState.finished_at = (Get-Date).ToString("o")
  Save-WrapperState

  $failure = [ordered]@{
    test_id = $TestId
    scenario = "LT-002E"
    phase = $Phase
    message = $Message
    exit_code = $ExitCode
    run_dir = $runDir
    k6_completed = $wrapperState.k6_completed
    k6_exit_code = $wrapperState.k6_exit_code
    pre_run_gate_exit_code = $wrapperState.pre_run_gate_exit_code
    post_run_drain_exit_code = $wrapperState.post_run_drain_exit_code
    capture_report_exit_code = $wrapperState.capture_report_exit_code
    wrapper_log_path = $wrapperLogPath
    occurred_at = (Get-Date).ToString("o")
  }

  $failure | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 $wrapperFailurePath
  ("FAILED: phase={0} exit_code={1}`nmessage={2}" -f $Phase, $ExitCode, $Message) | Out-File -Encoding utf8 $wrapperFailureMarkerPath

  try {
    $resultDir = Resolve-ResultDirPath
    $wrapperState.docs_result_dir = $resultDir
    New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
    $failure | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 (Join-Path $resultDir "wrapper.failure.json")
    ("FAILED WRAPPER`nrun_dir={0}`nphase={1}`nexit_code={2}`nmessage={3}" -f $runDir, $Phase, $ExitCode, $Message) |
      Out-File -Encoding utf8 (Join-Path $resultDir "FAILED-WRAPPER.txt")
  } catch {
    Write-WrapperLog ("Failed to write docs failure marker: " + $_.Exception.Message) "WARN"
  }
}

$phase = "init"
$proc = $null
$writer = $null
$runStartUtc = $null
$runEndUtc = $null
$gateExitCode = -1
$drainExitCode = -1
$captureExitCode = -1
$finalExitCode = 1

try {
  Save-WrapperState

  if ($ResetStateBeforeRun.IsPresent) {
    $phase = "reset_state"
    $wrapperState.phase = $phase
    Save-WrapperState
    Write-WrapperLog "Reset load-test state started"

    $purgeAllQueues = $ResetPurgeAllQueues.IsPresent
    & $resetStateScript -PurgeAllQueues:$purgeAllQueues
    $resetExitCode = $LASTEXITCODE
    $wrapperState.reset_state_exit_code = $resetExitCode
    Save-WrapperState

    if ($resetExitCode -ne 0) {
      throw "Reset load-test state failed with exit code $resetExitCode"
    }
    Write-WrapperLog "Reset load-test state completed"
  }

  $phase = "pre_run_gate"
  $wrapperState.phase = $phase
  Save-WrapperState
  Write-WrapperLog "Pre-run clean gate started"

  & $preRunGate `
    -PrometheusBaseUrl $PrometheusBaseUrl `
    -ActuatorBaseUrl $ActuatorBaseUrl `
    -OutputDir $runDir `
    -TimeoutSec $GateTimeoutSec
  $gateExitCode = $LASTEXITCODE
  $wrapperState.pre_run_gate_exit_code = $gateExitCode
  Save-WrapperState

  if ($gateExitCode -ne 0) {
    throw "Pre-run clean gate failed with exit code $gateExitCode"
  }

  $phase = "k6_launch"
  $wrapperState.phase = $phase
  Save-WrapperState
  Write-WrapperLog ("Starting k6 knee slice: testid={0}" -f $TestId)
  Write-WrapperLog ("Slice profile: {0}" -f $SliceProfile)
  Write-WrapperLog ("k6 stdout log: {0}" -f $logPath)

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

  $wrapperState.k6_started = $true
  Save-WrapperState

  $writer = [System.IO.StreamWriter]::new($logPath, $true)

  $phase = "k6_monitoring"
  $wrapperState.phase = $phase
  Save-WrapperState
  Write-WrapperLog ("Monitoring [SLICE_START] up to {0}s" -f $WaitSeconds)

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
          Write-WrapperLog ("SLICE_START detected: slice_index={0}, target_rps={1}" -f $ExpectedStageIndex, $ExpectedTargetRps)
          $announced = $true
        }
      }
    }

    if (-not $found -and ((Get-Date) - $start).TotalSeconds -ge $WaitSeconds) { break }
    if ($found -and $proc.HasExited -and $proc.StandardOutput.EndOfStream -and $proc.StandardError.EndOfStream) { break }
    Start-Sleep -Milliseconds 200
  }

  $wrapperState.monitor_stage_detected = $found
  Save-WrapperState

  if (-not $found) {
    try {
      if ($null -ne $proc -and -not $proc.HasExited) { $proc.Kill() }
    } catch {
    }
    throw "[SLICE_START] marker was not detected within $WaitSeconds seconds"
  }

  $phase = "k6_wait_exit"
  $wrapperState.phase = $phase
  Save-WrapperState

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
  $writer = $null
  $runEndUtc = [DateTimeOffset]::UtcNow

  $wrapperState.k6_completed = $true
  $wrapperState.k6_exit_code = [int]$proc.ExitCode
  Save-WrapperState

  Write-WrapperLog ("k6 finished with exit code {0}" -f $proc.ExitCode)
  Write-WrapperLog ("k6 summary path: {0}" -f $k6SummaryPath)

  $phase = "post_run_drain"
  $wrapperState.phase = $phase
  Save-WrapperState
  Write-WrapperLog "Post-run drain verification started"

  & $postRunDrain `
    -PrometheusBaseUrl $PrometheusBaseUrl `
    -ActuatorBaseUrl $ActuatorBaseUrl `
    -OutputDir $runDir `
    -TimeoutSec $DrainTimeoutSec
  $drainExitCode = $LASTEXITCODE
  $wrapperState.post_run_drain_exit_code = $drainExitCode
  Save-WrapperState
  Write-WrapperLog ("Post-run drain finished with exit code {0}" -f $drainExitCode)

  if ($proc.ExitCode -eq 0) {
    $phase = "capture_and_report"
    $wrapperState.phase = $phase
    Save-WrapperState
    Write-WrapperLog "Capture/report generation started"

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
      -Profile $Profile

    $captureExitCode = $LASTEXITCODE
    $wrapperState.capture_report_exit_code = $captureExitCode
    Save-WrapperState

    Write-WrapperLog ("Capture/report finished with exit code {0}" -f $captureExitCode)

    if ($captureExitCode -ne 0) {
      throw "Capture/report generation failed with exit code $captureExitCode"
    }
  }

  if ($proc.ExitCode -ne 0) {
    $finalExitCode = [int]$proc.ExitCode
    Write-WrapperLog ("Final status: k6 failed (exit={0})" -f $finalExitCode) "WARN"
  } else {
    $finalExitCode = [int]$drainExitCode
    if ($finalExitCode -eq 0) {
      Write-WrapperLog "Final status: success"
    } else {
      Write-WrapperLog ("Final status: drain failed (exit={0})" -f $finalExitCode) "WARN"
    }
  }

  $wrapperState.final_status = if ($finalExitCode -eq 0) { "success" } else { "failed" }
  $wrapperState.exit_code = $finalExitCode
  $wrapperState.phase = "completed"
  $wrapperState.finished_at = (Get-Date).ToString("o")
  Save-WrapperState
}
catch {
  $msg = $_.Exception.Message
  Write-WrapperLog ("Phase {0} failed: {1}" -f $phase, $msg) "ERROR"

  $errCode = 1
  if ($phase -eq "pre_run_gate" -and $gateExitCode -ge 0) { $errCode = [int]$gateExitCode }
  elseif ($phase -eq "reset_state" -and $null -ne $wrapperState.reset_state_exit_code) { $errCode = [int]$wrapperState.reset_state_exit_code }
  elseif ($phase -eq "capture_and_report" -and $captureExitCode -ge 0) { $errCode = [int]$captureExitCode }
  elseif ($phase -eq "post_run_drain" -and $drainExitCode -ge 0) { $errCode = [int]$drainExitCode }
  elseif ($null -ne $proc -and $proc.HasExited) { $errCode = [int]$proc.ExitCode }

  Write-FailureArtifacts -Phase $phase -Message $msg -ExitCode $errCode
  $finalExitCode = $errCode
}
finally {
  if ($null -ne $writer) {
    try {
      $writer.Flush()
      $writer.Dispose()
    } catch {
    }
  }

  if ($null -ne $proc -and -not $proc.HasExited) {
    try {
      $proc.Kill()
    } catch {
    }
  }

  if ($wrapperState.final_status -eq "running") {
    $wrapperState.final_status = if ($finalExitCode -eq 0) { "success" } else { "failed" }
    $wrapperState.exit_code = $finalExitCode
    $wrapperState.finished_at = (Get-Date).ToString("o")
    Save-WrapperState
  }

  Write-WrapperLog ("Wrapper finished. exit_code={0}, state_file={1}" -f $finalExitCode, $wrapperStatePath)
}

exit $finalExitCode
