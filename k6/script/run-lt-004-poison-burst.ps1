param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "",
  [string]$ScriptPathInContainer = "/scripts/lt_004_poison_burst.js",
  [string]$TestId = ("LT-004D-" + (Get-Date -Format "yyyy-MM-dd_HHmmss")),
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [string]$RabbitManagementBaseUrl = "http://localhost:15672",
  [string]$RabbitUsername = "manager",
  [string]$RabbitPassword = "manager0",
  [string]$RabbitVhost = "/",
  [int]$TargetRps = 10,
  [string]$SteadyDuration = "4m",
  [int]$PoisonPerCase = 20,
  [int]$PoisonStartDelaySec = 30,
  [int]$PoisonPublishIntervalMs = 20,
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

function Resolve-LocalScriptsDir {
  param([string]$InputDir)
  if (-not [string]::IsNullOrWhiteSpace($InputDir)) {
    return [System.IO.Path]::GetFullPath($InputDir)
  }
  return [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
}

function Test-RabbitManagement {
  param(
    [string]$BaseUrl,
    [string]$Username,
    [string]$Password
  )

  $pair = "{0}:{1}" -f $Username, $Password
  $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
  try {
    Invoke-RestMethod `
      -UseBasicParsing `
      -Method Get `
      -Uri ($BaseUrl.TrimEnd("/") + "/api/overview") `
      -Headers @{ Authorization = "Basic $basic" } `
      -TimeoutSec 10 | Out-Null
  } catch {
    throw "RabbitMQ management preflight failed: cannot read $BaseUrl/api/overview. Check RabbitMQ management port and credentials."
  }
}

$LocalScriptsDir = Resolve-LocalScriptsDir -InputDir $LocalScriptsDir
$runDir = Join-Path (Join-Path $LocalScriptsDir "results") $TestId
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$wrapperStatePath = Join-Path $runDir "wrapper-state.json"
$poisonLogPath = Join-Path $runDir "poison-burst.log"
$poisonStatusPath = Join-Path $runDir "poison-burst.status.json"
$workflow = Join-Path $PSScriptRoot "invoke-loadtest-workflow.ps1"
$workflowExitCode = 1
$poisonJobExitCode = 0
$poisonJob = $null
$poisonTotal = $PoisonPerCase * 4

Test-RabbitManagement -BaseUrl $RabbitManagementBaseUrl -Username $RabbitUsername -Password $RabbitPassword

$poisonJob = Start-Job -ArgumentList @(
  $wrapperStatePath,
  $poisonLogPath,
  $poisonStatusPath,
  $RabbitManagementBaseUrl,
  $RabbitUsername,
  $RabbitPassword,
  $RabbitVhost,
  $PoisonPerCase,
  $PoisonStartDelaySec,
  $PoisonPublishIntervalMs,
  $TestId
) -ScriptBlock {
  param(
    [string]$WrapperStatePath,
    [string]$PoisonLogPath,
    [string]$PoisonStatusPath,
    [string]$RabbitManagementBaseUrl,
    [string]$RabbitUsername,
    [string]$RabbitPassword,
    [string]$RabbitVhost,
    [int]$PoisonPerCase,
    [int]$PoisonStartDelaySec,
    [int]$PoisonPublishIntervalMs,
    [string]$TestId
  )

  Set-StrictMode -Version Latest
  $ErrorActionPreference = "Stop"

  function Write-PoisonLog {
    param([string]$Message)
    $line = "[{0}] {1}" -f (Get-Date).ToString("o"), $Message
    Add-Content -Encoding utf8 -Path $PoisonLogPath -Value $line
  }

  function Save-PoisonStatus {
    param([object]$Status)
    $Status | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 $PoisonStatusPath
  }

  function New-BasicAuthHeader {
    param([string]$Username, [string]$Password)
    $pair = "{0}:{1}" -f $Username, $Password
    $basic = [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes($pair))
    return @{ Authorization = "Basic $basic" }
  }

  function Escape-RabbitPathSegment {
    param([string]$Value)
    return [Uri]::EscapeDataString($Value)
  }

  function Invoke-RabbitPublish {
    param(
      [string]$Payload,
      [hashtable]$Headers,
      [string]$CaseName
    )

    $body = [ordered]@{
      properties = [ordered]@{
        content_type = "application/json"
        delivery_mode = 2
        headers = $Headers
      }
      routing_key = "auth.error.recorded.v1"
      payload = $Payload
      payload_encoding = "string"
    }

    $vhost = Escape-RabbitPathSegment -Value $RabbitVhost
    $exchange = Escape-RabbitPathSegment -Value "auth.error.exchange"
    $uri = "{0}/api/exchanges/{1}/{2}/publish" -f $RabbitManagementBaseUrl.TrimEnd("/"), $vhost, $exchange
    $response = Invoke-RestMethod `
      -UseBasicParsing `
      -Method Post `
      -Uri $uri `
      -Headers (New-BasicAuthHeader -Username $RabbitUsername -Password $RabbitPassword) `
      -ContentType "application/json" `
      -Body ($body | ConvertTo-Json -Depth 10) `
      -TimeoutSec 10

    if ($null -eq $response -or $response.routed -ne $true) {
      throw "RabbitMQ publish was not routed. case=$CaseName response=$($response | ConvertTo-Json -Compress)"
    }
  }

  function New-Headers {
    param(
      [Nullable[long]]$OutboxId,
      [string]$EventType,
      [string]$AggregateType
    )

    $headers = @{}
    if ($null -ne $OutboxId) {
      $headers.outboxId = [long]$OutboxId
    }
    if (-not [string]::IsNullOrWhiteSpace($EventType)) {
      $headers.eventType = $EventType
    }
    if (-not [string]::IsNullOrWhiteSpace($AggregateType)) {
      $headers.aggregateType = $AggregateType
    }
    $headers."x-lt-name" = "LT-004D"
    return $headers
  }

  $status = [ordered]@{
    status = "running"
    k6_started_detected_at = $null
    poison_started_at = $null
    poison_finished_at = $null
    poison_per_case = $PoisonPerCase
    total_attempted = 0
    total_published = 0
    cases = [ordered]@{
      malformed_json = 0
      missing_auth_error_id = 0
      missing_outbox_id = 0
      missing_event_type = 0
    }
    error = $null
  }

  try {
    Write-PoisonLog "Waiting for wrapper-state k6_started=true"
    $deadline = (Get-Date).AddMinutes(10)
    while ((Get-Date) -lt $deadline) {
      if (Test-Path $WrapperStatePath) {
        try {
          $wrapperState = Get-Content -Raw -Path $WrapperStatePath | ConvertFrom-Json
          if ($wrapperState.k6_started -eq $true) {
            $status.k6_started_detected_at = (Get-Date).ToString("o")
            Save-PoisonStatus -Status $status
            Write-PoisonLog "k6_started detected. Sleeping ${PoisonStartDelaySec}s before poison publish."
            break
          }
        } catch {
        }
      }
      Start-Sleep -Milliseconds 500
    }

    if ($null -eq $status.k6_started_detected_at) {
      throw "wrapper-state k6_started=true was not detected before poison deadline."
    }

    Start-Sleep -Seconds $PoisonStartDelaySec
    $status.poison_started_at = (Get-Date).ToString("o")
    Save-PoisonStatus -Status $status

    for ($i = 1; $i -le $PoisonPerCase; $i++) {
      $baseOutboxId = 900000000000 + $i

      $status.total_attempted++
      Invoke-RabbitPublish `
        -CaseName "malformed_json" `
        -Payload ('{"authErrorId":' + $baseOutboxId + ',"requestId":"' + $TestId + '-malformed-' + $baseOutboxId + '",') `
        -Headers (New-Headers -OutboxId $baseOutboxId -EventType "auth.error.recorded.v1" -AggregateType "AUTH_ERROR")
      $status.cases["malformed_json"]++
      $status.total_published++

      $status.total_attempted++
      Invoke-RabbitPublish `
        -CaseName "missing_auth_error_id" `
        -Payload (@{
            requestId = "$TestId-missing-auth-error-id-$i"
            occurredAt = (Get-Date).ToUniversalTime().ToString("o")
            receivedAt = (Get-Date).ToUniversalTime().ToString("o")
          } | ConvertTo-Json -Compress) `
        -Headers (New-Headers -OutboxId ($baseOutboxId + 100000) -EventType "auth.error.recorded.v1" -AggregateType "AUTH_ERROR")
      $status.cases["missing_auth_error_id"]++
      $status.total_published++

      $status.total_attempted++
      Invoke-RabbitPublish `
        -CaseName "missing_outbox_id" `
        -Payload (@{
            authErrorId = $baseOutboxId + 200000
            requestId = "$TestId-missing-outbox-id-$i"
            occurredAt = (Get-Date).ToUniversalTime().ToString("o")
            receivedAt = (Get-Date).ToUniversalTime().ToString("o")
          } | ConvertTo-Json -Compress) `
        -Headers (New-Headers -OutboxId $null -EventType "auth.error.recorded.v1" -AggregateType "AUTH_ERROR")
      $status.cases["missing_outbox_id"]++
      $status.total_published++

      $status.total_attempted++
      Invoke-RabbitPublish `
        -CaseName "missing_event_type" `
        -Payload (@{
            authErrorId = $baseOutboxId + 300000
            requestId = "$TestId-missing-event-type-$i"
            occurredAt = (Get-Date).ToUniversalTime().ToString("o")
            receivedAt = (Get-Date).ToUniversalTime().ToString("o")
          } | ConvertTo-Json -Compress) `
        -Headers (New-Headers -OutboxId ($baseOutboxId + 300000) -EventType "" -AggregateType "AUTH_ERROR")
      $status.cases["missing_event_type"]++
      $status.total_published++

      Save-PoisonStatus -Status $status
      if ($PoisonPublishIntervalMs -gt 0) {
        Start-Sleep -Milliseconds $PoisonPublishIntervalMs
      }
    }

    $status.status = "completed"
    $status.poison_finished_at = (Get-Date).ToString("o")
    Save-PoisonStatus -Status $status
    Write-PoisonLog ("Poison burst completed. total_published={0}" -f $status.total_published)
    exit 0
  } catch {
    $status.status = "failed"
    $status.error = $_.Exception.Message
    Save-PoisonStatus -Status $status
    Write-PoisonLog ("Poison burst failed: " + $_.Exception.Message)
    exit 1
  }
}

try {
  & $workflow `
    -Scenario "LT-004D" `
    -TestId $TestId `
    -ScriptPathInContainer $ScriptPathInContainer `
    -StdoutLogName "lt_004_poison_burst.stdout.log" `
    -K6SummaryFilePrefix "lt_004_poison_burst" `
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
    -ExtraEnv @("TARGET_RPS=$TargetRps", "STEADY_DURATION=$SteadyDuration", "POISON_TOTAL=$poisonTotal") `
    -ResetStateBeforeRun:$ResetStateBeforeRun `
    -ResetPurgeAllQueues:$ResetPurgeAllQueues
  $workflowExitCode = $LASTEXITCODE
} finally {
  if ($null -ne $poisonJob) {
    if ($workflowExitCode -ne 0 -and $poisonJob.State -eq "Running") {
      Stop-Job -Job $poisonJob
      $poisonJobExitCode = 1
      "Poison burst job stopped because workflow exited with code $workflowExitCode." | Out-File -Encoding utf8 -Append $poisonLogPath
    } else {
      $jobTimeout = [Math]::Max(60, $PoisonStartDelaySec + 240)
      $completed = Wait-Job -Job $poisonJob -Timeout $jobTimeout
      if ($null -eq $completed) {
        Stop-Job -Job $poisonJob
        $poisonJobExitCode = 1
        "Poison burst job timed out." | Out-File -Encoding utf8 -Append $poisonLogPath
      } else {
        Receive-Job -Job $poisonJob | Out-File -Encoding utf8 -Append (Join-Path $runDir "poison-burst.job.log")
        if ($poisonJob.State -ne "Completed") {
          $poisonJobExitCode = 1
        }
      }
    }
    Remove-Job -Job $poisonJob -Force
  }

  if (Test-Path $poisonStatusPath) {
    try {
      $poisonStatus = Get-Content -Raw -Path $poisonStatusPath | ConvertFrom-Json
      if ([string]$poisonStatus.status -ne "completed") {
        $poisonJobExitCode = 1
      }
    } catch {
      $poisonJobExitCode = 1
    }
  } else {
    $poisonJobExitCode = 1
  }
}

if ($workflowExitCode -ne 0) {
  exit $workflowExitCode
}

if ($poisonJobExitCode -ne 0) {
  exit $poisonJobExitCode
}

exit 0
