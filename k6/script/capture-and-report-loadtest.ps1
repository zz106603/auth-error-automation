param(
  [Parameter(Mandatory = $true)]
  [string]$TestId,
  [string]$Scenario = "",
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ResultsRoot = "docs/loadtest/results",
  [string]$K6ResultsRoot = "k6/results",
  [string]$RunDir = "",
  [string]$K6SummaryPath = "",
  [string]$StartTime = "",
  [string]$EndTime = "",
  [int]$StepSec = 5,
  [string]$BaselinePath = "docs/loadtest/baseline/latest-baseline.json",
  [string]$RulesPath = "k6/loadtest-acceptance-rules.json",
  [string]$Profile = "local-single-node"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$captureScript = Join-Path $PSScriptRoot "capture-prometheus-snapshot.ps1"
$reportScript = Join-Path $PSScriptRoot "generate-loadtest-report.ps1"

function Resolve-AbsolutePath {
  param([string]$PathValue)

  if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
  if ([System.IO.Path]::IsPathRooted($PathValue)) {
    return [System.IO.Path]::GetFullPath($PathValue)
  }
  return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $PathValue))
}

$resolvedRunDir = Resolve-AbsolutePath -PathValue $RunDir
$resultDir = Resolve-AbsolutePath -PathValue (Join-Path $ResultsRoot $TestId)
$statusPath = ""
$logPath = ""

if (-not [string]::IsNullOrWhiteSpace($resolvedRunDir)) {
  if (-not (Test-Path $resolvedRunDir)) {
    New-Item -ItemType Directory -Force -Path $resolvedRunDir | Out-Null
  }
  $statusPath = Join-Path $resolvedRunDir "capture-and-report.status.json"
  $logPath = Join-Path $resolvedRunDir "capture-and-report.log"
}

function Write-PhaseLog {
  param(
    [string]$Message,
    [string]$Level = "INFO"
  )

  $line = "[{0}] [{1}] {2}" -f (Get-Date).ToString("o"), $Level, $Message
  Write-Host $line
  if (-not [string]::IsNullOrWhiteSpace($logPath)) {
    Add-Content -Encoding utf8 -Path $logPath -Value $line
  }
}

function Save-Status {
  param([hashtable]$State)

  if (-not [string]::IsNullOrWhiteSpace($statusPath)) {
    $State | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 $statusPath
  }
}

function Write-ResultFailureMarker {
  param(
    [string]$Phase,
    [string]$Message,
    [int]$ExitCode,
    [hashtable]$State
  )

  try {
    New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
    $failure = [ordered]@{
      test_id = $TestId
      scenario = $Scenario
      source = "capture-and-report-loadtest.ps1"
      phase = $Phase
      message = $Message
      exit_code = $ExitCode
      run_dir = $resolvedRunDir
      status_file = $statusPath
      log_file = $logPath
      occurred_at = (Get-Date).ToString("o")
    }

    $failure | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 (Join-Path $resultDir "capture-and-report.failure.json")
    ("CAPTURE/REPORT FAILED`nphase={0}`nexit_code={1}`nmessage={2}`nrun_dir={3}" -f $Phase, $ExitCode, $Message, $resolvedRunDir) |
      Out-File -Encoding utf8 (Join-Path $resultDir "FAILED-CAPTURE-REPORT.txt")

    if ($null -ne $State) {
      $State.result_dir = $resultDir
      Save-Status -State $State
    }
  } catch {
    Write-PhaseLog ("Failed to write result failure marker: " + $_.Exception.Message) "WARN"
  }
}

$state = [ordered]@{
  test_id = $TestId
  scenario = $Scenario
  phase = "init"
  final_status = "running"
  exit_code = $null
  error_message = $null
  started_at = (Get-Date).ToString("o")
  finished_at = $null
  run_dir = $resolvedRunDir
  result_dir = $resultDir
  snapshot_path = Join-Path $resultDir "prometheus-snapshot.json"
  report_path = Join-Path $resultDir ("{0}-summary.md" -f $TestId)
  capture_exit_code = $null
  report_exit_code = $null
}

$phase = "init"
$exitCode = 1

try {
  Save-Status -State $state
  Write-PhaseLog "Capture/report workflow started"

  $phase = "capture_snapshot"
  $state.phase = $phase
  Save-Status -State $state
  Write-PhaseLog "Capture snapshot started"

  & $captureScript `
    -TestId $TestId `
    -Scenario $Scenario `
    -PrometheusBaseUrl $PrometheusBaseUrl `
    -ResultsRoot $ResultsRoot `
    -K6ResultsRoot $K6ResultsRoot `
    -RunDir $RunDir `
    -K6SummaryPath $K6SummaryPath `
    -StartTime $StartTime `
    -EndTime $EndTime `
    -StepSec $StepSec `
    -BaselinePath $BaselinePath `
    -RulesPath $RulesPath `
    -Profile $Profile

  $captureExit = $LASTEXITCODE
  $state.capture_exit_code = $captureExit
  Save-Status -State $state
  Write-PhaseLog ("Capture snapshot finished: exit={0}" -f $captureExit)

  if ($captureExit -ne 0) {
    throw "capture-prometheus-snapshot failed (exit=$captureExit)"
  }

  if (-not (Test-Path $state.snapshot_path)) {
    throw "snapshot file not found after capture: $($state.snapshot_path)"
  }

  $phase = "generate_report"
  $state.phase = $phase
  Save-Status -State $state
  Write-PhaseLog "Report generation started"

  & $reportScript -SnapshotPath $state.snapshot_path -OutputPath $state.report_path

  $reportExit = $LASTEXITCODE
  $state.report_exit_code = $reportExit
  Save-Status -State $state
  Write-PhaseLog ("Report generation finished: exit={0}" -f $reportExit)

  if ($reportExit -ne 0) {
    throw "generate-loadtest-report failed (exit=$reportExit)"
  }

  $exitCode = 0
  $state.final_status = "success"
  $state.phase = "completed"
  $state.exit_code = 0
  $state.finished_at = (Get-Date).ToString("o")
  Save-Status -State $state

  Write-PhaseLog ("Load-test artifact bundle ready: {0}" -f $resultDir)
}
catch {
  $msg = $_.Exception.Message
  $state.final_status = "failed"
  $state.phase = $phase
  $state.error_message = $msg
  $state.exit_code = if ($phase -eq "generate_report" -and $null -ne $state.report_exit_code) { [int]$state.report_exit_code } elseif ($phase -eq "capture_snapshot" -and $null -ne $state.capture_exit_code) { [int]$state.capture_exit_code } else { 1 }
  $state.finished_at = (Get-Date).ToString("o")
  Save-Status -State $state

  Write-PhaseLog ("Capture/report failed at phase '{0}': {1}" -f $phase, $msg) "ERROR"
  Write-ResultFailureMarker -Phase $phase -Message $msg -ExitCode ([int]$state.exit_code) -State $state

  $exitCode = [int]$state.exit_code
}

exit $exitCode
