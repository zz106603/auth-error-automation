param(
  [Parameter(Mandatory = $true)]
  [string]$Scenario,
  [Parameter(Mandatory = $true)]
  [string]$TestId,
  [Parameter(Mandatory = $true)]
  [string]$ScriptPathInContainer,
  [Parameter(Mandatory = $true)]
  [string]$StdoutLogName,
  [Parameter(Mandatory = $true)]
  [string]$K6SummaryFilePrefix,
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "",
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [int]$GateTimeoutSec = 300,
  [int]$DrainTimeoutSec = 300,
  [string]$ResultsRoot = "docs/loadtest/results",
  [string]$BaselinePath = "docs/loadtest/baseline/latest-baseline.json",
  [string]$RulesPath = "k6/loadtest-acceptance-rules.json",
  [string]$Profile = "local-single-node",
  [string[]]$ExtraEnv = @(),
  [double]$AllowedDlqDepth = 0.001,
  [switch]$ResetStateBeforeRun,
  [switch]$ResetPurgeAllQueues,
  [string]$MarkerName = "",
  [string]$MarkerIndexKey = "",
  [int]$ExpectedMarkerIndex = 0,
  [string]$MarkerTargetKey = "",
  [int]$ExpectedMarkerTarget = 0,
  [int]$MarkerWaitSeconds = 20
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

$logPath = Join-Path $runDir $StdoutLogName
$k6SummaryPath = Join-Path $runDir ("{0}-{1}.log" -f $K6SummaryFilePrefix, $TestId)
$wrapperLogPath = Join-Path $runDir "wrapper-execution.log"
$wrapperStatePath = Join-Path $runDir "wrapper-state.json"
$wrapperFailurePath = Join-Path $runDir "wrapper.failure.json"
$wrapperFailureMarkerPath = Join-Path $runDir "FAILED-WRAPPER.txt"

foreach ($path in @($logPath, $wrapperLogPath, $wrapperFailurePath, $wrapperFailureMarkerPath)) {
  if (Test-Path $path) { Remove-Item $path -Force }
}

$preRunGate = Join-Path $PSScriptRoot "wait-loadtest-clean.ps1"
$postRunDrain = Join-Path $PSScriptRoot "verify-loadtest-drain.ps1"
$captureAndReport = Join-Path $PSScriptRoot "capture-and-report-loadtest.ps1"
$resetStateScript = Join-Path $PSScriptRoot "reset-loadtest-state.ps1"

function Resolve-ResultDirPath {
  $candidate = Join-Path $ResultsRoot $TestId
  if ([System.IO.Path]::IsPathRooted($candidate)) {
    return [System.IO.Path]::GetFullPath($candidate)
  }
  return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $candidate))
}

$wrapperState = [ordered]@{
  test_id = $TestId
  scenario = $Scenario
  started_at = (Get-Date).ToString("o")
  finished_at = $null
  phase = "init"
  final_status = "running"
  exit_code = $null
  failure_reason = $null
  error_phase = $null
  error_message = $null
  run_dir = $runDir
  docs_results_root = $ResultsRoot
  docs_result_dir = Resolve-ResultDirPath
  k6_summary_path = $k6SummaryPath
  wrapper_log_path = $wrapperLogPath
  k6_started = $false
  k6_completed = $false
  k6_exit_code = $null
  marker_name = $MarkerName
  marker_detected = $false
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

function Convert-PhaseToFailureReason {
  param([string]$Phase)

  if ($Phase -eq "reset_state_failed") { return "reset_state_failed" }
  if ($Phase -eq "clean_start_failed") { return "clean_start_failed" }
  if ($Phase -eq "k6_failed") { return "k6_failed" }
  if ($Phase -eq "drain_failed") { return "drain_failed" }
  if ($Phase -eq "snapshot_failed") { return "snapshot_failed" }
  if ($Phase -eq "report_failed") { return "report_failed" }
  if ($Phase -eq "snapshot_or_report_failed") { return "snapshot_or_report_failed" }
  if ($Phase -eq "reset_state") { return "reset_state_failed" }
  if ($Phase -eq "pre_run_gate") { return "clean_start_failed" }
  if ($Phase -like "k6*") { return "k6_failed" }
  if ($Phase -eq "post_run_drain") { return "drain_failed" }
  if ($Phase -eq "capture_and_report") { return "snapshot_or_report_failed" }
  return "workflow_failed"
}

function Write-FailureArtifacts {
  param(
    [string]$Phase,
    [string]$Message,
    [int]$ExitCode
  )

  $failureReason = Convert-PhaseToFailureReason -Phase $Phase
  $wrapperState.final_status = "failed"
  $wrapperState.failure_reason = $failureReason
  $wrapperState.error_phase = $Phase
  $wrapperState.error_message = $Message
  $wrapperState.exit_code = $ExitCode
  $wrapperState.finished_at = (Get-Date).ToString("o")
  Save-WrapperState

  $failure = [ordered]@{
    test_id = $TestId
    scenario = $Scenario
    phase = $Phase
    failure_reason = $failureReason
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
  ("FAILED: reason={0} phase={1} exit_code={2}`nmessage={3}" -f $failureReason, $Phase, $ExitCode, $Message) |
    Out-File -Encoding utf8 $wrapperFailureMarkerPath

  try {
    $resultDir = Resolve-ResultDirPath
    New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
    $failure | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 (Join-Path $resultDir "wrapper.failure.json")
    ("FAILED WRAPPER`nreason={0}`nrun_dir={1}`nphase={2}`nexit_code={3}`nmessage={4}" -f $failureReason, $runDir, $Phase, $ExitCode, $Message) |
      Out-File -Encoding utf8 (Join-Path $resultDir "FAILED-WRAPPER.txt")
  } catch {
    Write-WrapperLog ("Failed to write docs failure marker: " + $_.Exception.Message) "WARN"
  }
}

function New-DockerArgs {
  $args = @(
    "run","--rm","-i",
    "--network",$Network,
    "--env-file",$EnvFile,
    "-e","K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write",
    "-e","K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),avg,max",
    "-e","K6_PROMETHEUS_RW_PUSH_INTERVAL=5s",
    "-e","TEST_ID=$TestId",
    "-e","RESULTS_DIR=/scripts/results/$TestId"
  )

  foreach ($envEntry in $ExtraEnv) {
    if (-not [string]::IsNullOrWhiteSpace($envEntry)) {
      $args += @("-e", $envEntry)
    }
  }

  $args += @(
    "-v","${LocalScriptsDir}:/scripts",
    "grafana/k6:latest","run","-o","experimental-prometheus-rw",
    "--tag","testid=$TestId",
    $ScriptPathInContainer
  )
  return $args
}

function Test-MarkerDetected {
  param([string]$Raw)

  if ([string]::IsNullOrWhiteSpace($MarkerName)) { return $true }
  if ($Raw -notmatch [regex]::Escape($MarkerName)) { return $false }

  if (-not [string]::IsNullOrWhiteSpace($MarkerIndexKey)) {
    $indexPattern = "{0}={1}" -f [regex]::Escape($MarkerIndexKey), $ExpectedMarkerIndex
    if ($Raw -notmatch $indexPattern) { return $false }
  }

  if (-not [string]::IsNullOrWhiteSpace($MarkerTargetKey)) {
    $targetPattern = "{0}={1}" -f [regex]::Escape($MarkerTargetKey), $ExpectedMarkerTarget
    if ($Raw -notmatch $targetPattern) { return $false }
  }

  return $true
}

$phase = "init"
$proc = $null
$writer = $null
$runStartUtc = $null
$runEndUtc = $null
$gateExitCode = -1
$drainExitCode = -1
$captureExitCode = -1
$resetExitCode = -1
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
  Write-WrapperLog ("Starting k6 scenario={0} testid={1}" -f $Scenario, $TestId)
  Write-WrapperLog ("k6 stdout log: {0}" -f $logPath)

  $dockerArgs = New-DockerArgs
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

  if (-not [string]::IsNullOrWhiteSpace($MarkerName)) {
    $phase = "k6_marker_monitoring"
    $wrapperState.phase = $phase
    Save-WrapperState
    Write-WrapperLog ("Monitoring {0} up to {1}s" -f $MarkerName, $MarkerWaitSeconds)

    $start = Get-Date
    $found = $false

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

      if (Test-MarkerDetected -Raw $raw) {
        $found = $true
        Write-WrapperLog ("Marker detected: {0}" -f $MarkerName)
        break
      }

      if (-not $found -and ((Get-Date) - $start).TotalSeconds -ge $MarkerWaitSeconds) { break }
      if ($proc.HasExited -and $proc.StandardOutput.EndOfStream -and $proc.StandardError.EndOfStream) { break }
      Start-Sleep -Milliseconds 200
    }

    $wrapperState.marker_detected = $found
    Save-WrapperState

    if (-not $found) {
      try {
        if ($null -ne $proc -and -not $proc.HasExited) { $proc.Kill() }
      } catch {
      }
      throw "$MarkerName marker was not detected within $MarkerWaitSeconds seconds"
    }
  }

  $phase = "k6_wait_exit"
  $wrapperState.phase = $phase
  Save-WrapperState

  while (-not $proc.HasExited) {
    while (-not $proc.StandardOutput.EndOfStream) {
      $line = $proc.StandardOutput.ReadLine()
      $writer.WriteLine($line)
    }
    while (-not $proc.StandardError.EndOfStream) {
      $line = $proc.StandardError.ReadLine()
      $writer.WriteLine($line)
    }
    $writer.Flush()
    Start-Sleep -Milliseconds 200
  }

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
    -TimeoutSec $DrainTimeoutSec `
    -AllowedDlqDepth $AllowedDlqDepth
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
      -Scenario $Scenario `
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
    $wrapperState.failure_reason = "k6_failed"
    Write-WrapperLog ("Final status: k6 failed (exit={0})" -f $finalExitCode) "WARN"
  } elseif ($drainExitCode -ne 0) {
    $finalExitCode = [int]$drainExitCode
    $wrapperState.failure_reason = "drain_failed"
    Write-WrapperLog ("Final status: drain failed (exit={0})" -f $finalExitCode) "WARN"
  } else {
    $finalExitCode = 0
    Write-WrapperLog "Final status: success"
  }

  $wrapperState.final_status = if ($finalExitCode -eq 0) { "success" } else { "failed" }
  $wrapperState.exit_code = $finalExitCode
  $wrapperState.phase = "completed"
  $wrapperState.finished_at = (Get-Date).ToString("o")
  Save-WrapperState

  if ($finalExitCode -ne 0) {
    Write-FailureArtifacts -Phase $wrapperState.failure_reason -Message ("Workflow completed with failure reason {0}" -f $wrapperState.failure_reason) -ExitCode $finalExitCode
  }
}
catch {
  $msg = $_.Exception.Message
  Write-WrapperLog ("Phase {0} failed: {1}" -f $phase, $msg) "ERROR"

  $errCode = 1
  if ($phase -eq "pre_run_gate" -and $gateExitCode -ge 0) { $errCode = [int]$gateExitCode }
  elseif ($phase -eq "reset_state" -and $resetExitCode -ge 0) { $errCode = [int]$resetExitCode }
  elseif ($phase -eq "post_run_drain" -and $drainExitCode -ge 0) { $errCode = [int]$drainExitCode }
  elseif ($phase -eq "capture_and_report" -and $captureExitCode -ge 0) { $errCode = [int]$captureExitCode }
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
