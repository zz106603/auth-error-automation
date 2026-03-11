param(
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [string]$OutputDir = "k6/results/pre-run",
  [int]$RequiredCleanSec = 60,
  [int]$SampleIntervalSec = 10,
  [int]$TimeoutSec = 300,
  [double]$QueueTolerance = 0.001,
  [double]$OutboxAgeToleranceMs = 0.001,
  [double]$PendingTolerance = 0.001
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$jsonlPath = Join-Path $OutputDir "pre_run_gate.samples.jsonl"
$logPath = Join-Path $OutputDir "pre_run_gate.log"
$summaryPath = Join-Path $OutputDir "pre_run_gate.summary.json"
$snapshotPath = Join-Path $OutputDir "pre_run_gate.actuator.prom"
$errorMarkerPath = Join-Path $OutputDir "PRE-RUN-GATE-ERROR.txt"

function Invoke-PromScalar {
  param([string]$Query)

  $uri = "{0}/api/v1/query?query={1}" -f $PrometheusBaseUrl.TrimEnd('/'), [uri]::EscapeDataString($Query)
  $response = Invoke-RestMethod -UseBasicParsing -Uri $uri -Method Get
  if ($null -eq $response -or $response.status -ne "success") {
    throw "Prometheus query failed: $Query"
  }

  $result = $response.data.result
  if ($null -eq $result -or $result.Count -eq 0) { return 0.0 }
  $value = $result[0].value[1]
  return [double]$value
}

function Capture-ActuatorSnapshot {
  try {
    (Invoke-WebRequest -UseBasicParsing -Uri ($ActuatorBaseUrl.TrimEnd('/') + "/actuator/prometheus")).Content |
      Out-File -Encoding utf8 $snapshotPath
  } catch {
    "snapshot_error=$($_.Exception.Message)" | Out-File -Encoding utf8 $snapshotPath
  }
}

function Get-State {
  $ordered = [ordered]@{
    timestamp = (Get-Date).ToString("o")
    rabbit_ready = Invoke-PromScalar 'sum(rabbitmq_detailed_queue_messages_ready{queue!=""})'
    rabbit_unacked = Invoke-PromScalar 'sum(rabbitmq_detailed_queue_messages_unacked{queue!=""})'
    retry_depth = Invoke-PromScalar 'sum(rabbitmq_detailed_queue_messages_ready{queue=~".*\\.retry\\..*"}) + sum(rabbitmq_detailed_queue_messages_unacked{queue=~".*\\.retry\\..*"})'
    dlq_depth = Invoke-PromScalar 'sum(rabbitmq_detailed_queue_messages_ready{queue=~".*\\.dlq"}) + sum(rabbitmq_detailed_queue_messages_unacked{queue=~".*\\.dlq"})'
    outbox_age_p95_ms = Invoke-PromScalar 'max(auth_error_outbox_age_p95)'
    outbox_age_p99_ms = Invoke-PromScalar 'max(auth_error_outbox_age_p99)'
    hikari_pending = Invoke-PromScalar 'max(hikaricp_connections_pending)'
  }
  return $ordered
}

function Test-CleanState {
  param([hashtable]$State)

  return (
    $State.rabbit_ready -le $QueueTolerance -and
    $State.rabbit_unacked -le $QueueTolerance -and
    $State.retry_depth -le $QueueTolerance -and
    $State.dlq_depth -le $QueueTolerance -and
    $State.outbox_age_p95_ms -le $OutboxAgeToleranceMs -and
    $State.outbox_age_p99_ms -le $OutboxAgeToleranceMs -and
    $State.hikari_pending -le $PendingTolerance
  )
}

function Write-StateSample {
  param(
    [hashtable]$State,
    [bool]$IsClean,
    [int]$CleanSeconds
  )

  $sample = [ordered]@{
    timestamp = $State.timestamp
    is_clean = $IsClean
    clean_seconds = $CleanSeconds
    rabbit_ready = $State.rabbit_ready
    rabbit_unacked = $State.rabbit_unacked
    retry_depth = $State.retry_depth
    dlq_depth = $State.dlq_depth
    outbox_age_p95_ms = $State.outbox_age_p95_ms
    outbox_age_p99_ms = $State.outbox_age_p99_ms
    hikari_pending = $State.hikari_pending
  }

  ($sample | ConvertTo-Json -Compress) | Add-Content -Encoding utf8 $jsonlPath
  ("[{0}] clean={1} clean_seconds={2} ready={3:N3} unacked={4:N3} retry={5:N3} dlq={6:N3} outbox_p95={7:N3} outbox_p99={8:N3} hikari_pending={9:N3}" -f `
    $sample.timestamp, $sample.is_clean.ToString().ToLowerInvariant(), $sample.clean_seconds,
    $sample.rabbit_ready, $sample.rabbit_unacked, $sample.retry_depth, $sample.dlq_depth,
    $sample.outbox_age_p95_ms, $sample.outbox_age_p99_ms, $sample.hikari_pending) | Add-Content -Encoding utf8 $logPath
}

function Write-GateSummary {
  param(
    [string]$Status,
    [int]$AchievedCleanSec,
    [object]$LastState,
    [string]$ErrorMessage = ""
  )

  $summary = [ordered]@{
    status = $Status
    required_clean_sec = $RequiredCleanSec
    sample_interval_sec = $SampleIntervalSec
    timeout_sec = $TimeoutSec
    achieved_clean_sec = $AchievedCleanSec
    last_state = $LastState
    error_message = $ErrorMessage
    artifact_files = @($jsonlPath, $logPath, $summaryPath, $snapshotPath, $errorMarkerPath)
  }
  $summary | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 $summaryPath
}

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$cleanSeconds = 0
$lastState = $null

"Load-test pre-run clean gate" | Out-File -Encoding utf8 $logPath
"required_clean_sec=$RequiredCleanSec sample_interval_sec=$SampleIntervalSec timeout_sec=$TimeoutSec" | Add-Content -Encoding utf8 $logPath

try {
  while ((Get-Date) -lt $deadline) {
    $state = Get-State
    $lastState = $state
    $isClean = Test-CleanState -State $state
    if ($isClean) {
      $cleanSeconds += $SampleIntervalSec
    } else {
      $cleanSeconds = 0
    }

    Write-StateSample -State $state -IsClean $isClean -CleanSeconds $cleanSeconds

    if ($cleanSeconds -ge $RequiredCleanSec) {
      Capture-ActuatorSnapshot
      if (Test-Path $errorMarkerPath) { Remove-Item $errorMarkerPath -Force }
      Write-GateSummary -Status "clean" -AchievedCleanSec $cleanSeconds -LastState $state
      Write-Host "Pre-run gate PASS: clean state sustained for $cleanSeconds seconds."
      Write-Host "Artifacts: $OutputDir"
      exit 0
    }

    Start-Sleep -Seconds $SampleIntervalSec
  }

  Capture-ActuatorSnapshot
  if (Test-Path $errorMarkerPath) { Remove-Item $errorMarkerPath -Force }
  Write-GateSummary -Status "timeout" -AchievedCleanSec $cleanSeconds -LastState $lastState
  Write-Host "Pre-run gate FAIL: clean state was not sustained for $RequiredCleanSec seconds within $TimeoutSec seconds." -ForegroundColor Red
  Write-Host "Artifacts: $OutputDir"
  exit 1
}
catch {
  $msg = $_.Exception.Message
  Capture-ActuatorSnapshot
  ("PRE-RUN GATE ERROR: {0}" -f $msg) | Out-File -Encoding utf8 $errorMarkerPath
  Write-GateSummary -Status "error" -AchievedCleanSec $cleanSeconds -LastState $lastState -ErrorMessage $msg
  Write-Host ("Pre-run gate ERROR: {0}" -f $msg) -ForegroundColor Red
  Write-Host "Artifacts: $OutputDir"
  exit 2
}
