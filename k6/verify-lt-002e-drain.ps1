param(
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [string]$OutputDir = "k6/results/post-run",
  [int]$TimeoutSec = 300,
  [int]$SampleIntervalSec = 10,
  [double]$QueueTolerance = 0.001,
  [double]$OutboxAgeToleranceMs = 0.001,
  [double]$PendingTolerance = 0.001
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$jsonlPath = Join-Path $OutputDir "post_run_drain.samples.jsonl"
$logPath = Join-Path $OutputDir "post_run_drain.log"
$summaryPath = Join-Path $OutputDir "post_run_drain.summary.json"
$snapshotPath = Join-Path $OutputDir "post_run_drain.actuator.prom"
$markerPath = Join-Path $OutputDir "CONTAMINATED.txt"

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

function Test-DrainState {
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
    [bool]$IsDrained
  )

  $sample = [ordered]@{
    timestamp = $State.timestamp
    drained = $IsDrained
    rabbit_ready = $State.rabbit_ready
    rabbit_unacked = $State.rabbit_unacked
    retry_depth = $State.retry_depth
    dlq_depth = $State.dlq_depth
    outbox_age_p95_ms = $State.outbox_age_p95_ms
    outbox_age_p99_ms = $State.outbox_age_p99_ms
    hikari_pending = $State.hikari_pending
  }

  ($sample | ConvertTo-Json -Compress) | Add-Content -Encoding utf8 $jsonlPath
  ("[{0}] drained={1} ready={2:N3} unacked={3:N3} retry={4:N3} dlq={5:N3} outbox_p95={6:N3} outbox_p99={7:N3} hikari_pending={8:N3}" -f `
    $sample.timestamp, $sample.drained.ToString().ToLowerInvariant(), $sample.rabbit_ready, $sample.rabbit_unacked,
    $sample.retry_depth, $sample.dlq_depth, $sample.outbox_age_p95_ms, $sample.outbox_age_p99_ms,
    $sample.hikari_pending) | Add-Content -Encoding utf8 $logPath
}

$deadline = (Get-Date).AddSeconds($TimeoutSec)
$lastState = $null

"LT-002E post-run drain verification" | Out-File -Encoding utf8 $logPath
"timeout_sec=$TimeoutSec sample_interval_sec=$SampleIntervalSec" | Add-Content -Encoding utf8 $logPath

while ((Get-Date) -lt $deadline) {
  $state = Get-State
  $lastState = $state
  $isDrained = Test-DrainState -State $state
  Write-StateSample -State $state -IsDrained $isDrained

  if ($isDrained) {
    Capture-ActuatorSnapshot
    $summary = [ordered]@{
      status = "drained"
      timeout_sec = $TimeoutSec
      sample_interval_sec = $SampleIntervalSec
      last_state = $state
      artifact_files = @($jsonlPath, $logPath, $summaryPath, $snapshotPath)
    }
    $summary | ConvertTo-Json -Depth 5 | Out-File -Encoding utf8 $summaryPath
    if (Test-Path $markerPath) { Remove-Item $markerPath -Force }
    Write-Host "Post-run drain PASS: pipeline returned to baseline."
    Write-Host "Artifacts: $OutputDir"
    exit 0
  }

  Start-Sleep -Seconds $SampleIntervalSec
}

Capture-ActuatorSnapshot
"LT-002E run contaminated: pipeline did not drain within timeout." | Out-File -Encoding utf8 $markerPath
$failure = [ordered]@{
  status = "contaminated"
  timeout_sec = $TimeoutSec
  sample_interval_sec = $SampleIntervalSec
  last_state = $lastState
  artifact_files = @($jsonlPath, $logPath, $summaryPath, $snapshotPath, $markerPath)
}
$failure | ConvertTo-Json -Depth 5 | Out-File -Encoding utf8 $summaryPath
Write-Host "Post-run drain FAIL: run is contaminated." -ForegroundColor Yellow
Write-Host "Artifacts: $OutputDir"
exit 2
