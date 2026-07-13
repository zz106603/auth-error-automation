param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "",
  [string]$ScriptPathInContainer = "/scripts/lt_004_rabbitmq_unavailable.js",
  [string]$TestId = ("LT-004B-" + (Get-Date -Format "yyyy-MM-dd_HHmmss")),
  [string]$PrometheusBaseUrl = "http://localhost:9090",
  [string]$ActuatorBaseUrl = "http://localhost:18081",
  [int]$TargetRps = 30,
  [string]$SteadyDuration = "6m",
  [int]$FaultStartDelaySec = 60,
  [int]$RabbitDownDurationSec = 60,
  [string]$RabbitContainerName = "auth_pipeline_rabbitmq",
  [int]$GateTimeoutSec = 300,
  [int]$DrainTimeoutSec = 600,
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

function Resolve-LocalScriptsDir {
  param([string]$InputDir)
  if (-not [string]::IsNullOrWhiteSpace($InputDir)) {
    return [System.IO.Path]::GetFullPath($InputDir)
  }
  return [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
}

function Assert-DockerContainerRunning {
  param([string]$ContainerName)

  $running = (& docker inspect --format "{{.State.Running}}" $ContainerName 2>$null)
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($running)) {
    throw "RabbitMQ container '$ContainerName' was not found. Start docker compose services before LT-004B."
  }
  if ($running.Trim().ToLowerInvariant() -ne "true") {
    throw "RabbitMQ container '$ContainerName' is not running. Start it before LT-004B."
  }
}

function Assert-ConsumerDelayDisabled {
  param([string]$BaseUrl)

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
    throw "consumer delay preflight failed: metric $metricName not found. Rebuild/restart the app after applying load-test changes."
  }

  $valueText = ($line.Trim() -split "\s+")[-1]
  [double]$actual = 0
  if (-not [double]::TryParse($valueText, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$actual)) {
    throw "consumer delay preflight failed: cannot parse $metricName value from '$line'."
  }

  if ([math]::Abs($actual) -gt 0.001) {
    throw "consumer delay preflight failed: expected 0ms but app reports ${actual}ms. Restart app without auth-error.loadtest.consumer-delay.recorded-ms."
  }

  Write-Host ("Consumer delay preflight PASS: {0}=0ms" -f $metricName)
}

$LocalScriptsDir = Resolve-LocalScriptsDir -InputDir $LocalScriptsDir
$runDir = Join-Path (Join-Path $LocalScriptsDir "results") $TestId
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$stdoutLogPath = Join-Path $runDir "lt_004_rabbitmq_unavailable.stdout.log"
$wrapperStatePath = Join-Path $runDir "wrapper-state.json"
$faultLogPath = Join-Path $runDir "fault-injection.log"
$faultStatusPath = Join-Path $runDir "fault-injection.status.json"
$workflow = Join-Path $PSScriptRoot "invoke-loadtest-workflow.ps1"
$workflowExitCode = 1
$faultJobExitCode = 0
$faultJob = $null

Assert-DockerContainerRunning -ContainerName $RabbitContainerName
if (-not $SkipConsumerDelayCheck) {
  Assert-ConsumerDelayDisabled -BaseUrl $ActuatorBaseUrl
}

$faultJob = Start-Job -ArgumentList @(
  $wrapperStatePath,
  $faultLogPath,
  $faultStatusPath,
  $FaultStartDelaySec,
  $RabbitDownDurationSec,
  $RabbitContainerName
) -ScriptBlock {
  param(
    [string]$WrapperStatePath,
    [string]$FaultLogPath,
    [string]$FaultStatusPath,
    [int]$FaultStartDelaySec,
    [int]$RabbitDownDurationSec,
    [string]$RabbitContainerName
  )

  Set-StrictMode -Version Latest
  $ErrorActionPreference = "Stop"

  function Write-FaultLog {
    param([string]$Message)
    $line = "[{0}] {1}" -f (Get-Date).ToString("o"), $Message
    Add-Content -Encoding utf8 -Path $FaultLogPath -Value $line
  }

  function Save-FaultStatus {
    param([object]$Status)
    $Status | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 $FaultStatusPath
  }

  $status = [ordered]@{
    status = "running"
    k6_started_detected_at = $null
    stop_started_at = $null
    stop_finished_at = $null
    start_started_at = $null
    start_finished_at = $null
    healthy_at = $null
    container = $RabbitContainerName
    fault_start_delay_sec = $FaultStartDelaySec
    rabbit_down_duration_sec = $RabbitDownDurationSec
    error = $null
  }

  try {
    Write-FaultLog "Waiting for wrapper-state k6_started=true"
    $deadline = (Get-Date).AddMinutes(10)
    while ((Get-Date) -lt $deadline) {
      if (Test-Path $WrapperStatePath) {
        try {
          $wrapperState = Get-Content -Raw -Path $WrapperStatePath | ConvertFrom-Json
          if ($wrapperState.k6_started -eq $true) {
            $status.k6_started_detected_at = (Get-Date).ToString("o")
            Save-FaultStatus -Status $status
            Write-FaultLog "k6_started detected. Sleeping ${FaultStartDelaySec}s before RabbitMQ stop."
            break
          }
        } catch {
        }
      }
      Start-Sleep -Milliseconds 500
    }

    if ($null -eq $status.k6_started_detected_at) {
      throw "wrapper-state k6_started=true was not detected before fault deadline."
    }

    Start-Sleep -Seconds $FaultStartDelaySec

    $status.stop_started_at = (Get-Date).ToString("o")
    Save-FaultStatus -Status $status
    Write-FaultLog "Stopping RabbitMQ container: $RabbitContainerName"
    & docker stop $RabbitContainerName | Out-Null
    if ($LASTEXITCODE -ne 0) {
      throw "docker stop failed for $RabbitContainerName"
    }
    $status.stop_finished_at = (Get-Date).ToString("o")
    Save-FaultStatus -Status $status

    Write-FaultLog "RabbitMQ down window started for ${RabbitDownDurationSec}s."
    Start-Sleep -Seconds $RabbitDownDurationSec

    $status.start_started_at = (Get-Date).ToString("o")
    Save-FaultStatus -Status $status
    Write-FaultLog "Starting RabbitMQ container: $RabbitContainerName"
    & docker start $RabbitContainerName | Out-Null
    if ($LASTEXITCODE -ne 0) {
      throw "docker start failed for $RabbitContainerName"
    }
    $status.start_finished_at = (Get-Date).ToString("o")
    Save-FaultStatus -Status $status

    $healthDeadline = (Get-Date).AddMinutes(3)
    while ((Get-Date) -lt $healthDeadline) {
      $health = (& docker inspect --format "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}" $RabbitContainerName 2>$null)
      if ($LASTEXITCODE -eq 0 -and $health -and $health.Trim().ToLowerInvariant() -in @("healthy", "running")) {
        $status.healthy_at = (Get-Date).ToString("o")
        $status.status = "completed"
        Save-FaultStatus -Status $status
        Write-FaultLog "RabbitMQ container recovered. health=$($health.Trim())"
        exit 0
      }
      Start-Sleep -Seconds 2
    }

    throw "RabbitMQ container did not become healthy before timeout."
  } catch {
    $status.status = "failed"
    $status.error = $_.Exception.Message
    Save-FaultStatus -Status $status
    Write-FaultLog ("Fault injection failed: " + $_.Exception.Message)
    try { & docker start $RabbitContainerName | Out-Null } catch {}
    exit 1
  }
}

try {
  & $workflow `
    -Scenario "LT-004B" `
    -TestId $TestId `
    -ScriptPathInContainer $ScriptPathInContainer `
    -StdoutLogName "lt_004_rabbitmq_unavailable.stdout.log" `
    -K6SummaryFilePrefix "lt_004_rabbitmq_unavailable" `
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
    -ExtraEnv @("TARGET_RPS=$TargetRps", "STEADY_DURATION=$SteadyDuration", "FAULT_START_DELAY_SEC=$FaultStartDelaySec", "RABBIT_DOWN_DURATION_SEC=$RabbitDownDurationSec") `
    -ResetStateBeforeRun:$ResetStateBeforeRun `
    -ResetPurgeAllQueues:$ResetPurgeAllQueues
  $workflowExitCode = $LASTEXITCODE
} finally {
  try {
    & docker start $RabbitContainerName | Out-Null
  } catch {
  }

  if ($null -ne $faultJob) {
    if ($workflowExitCode -ne 0 -and $faultJob.State -eq "Running") {
      Stop-Job -Job $faultJob
      $faultJobExitCode = 1
      "Fault injection job stopped because workflow exited with code $workflowExitCode." | Out-File -Encoding utf8 -Append $faultLogPath
    } else {
      $jobTimeout = [Math]::Max(60, $FaultStartDelaySec + $RabbitDownDurationSec + 240)
      $completed = Wait-Job -Job $faultJob -Timeout $jobTimeout
      if ($null -eq $completed) {
        Stop-Job -Job $faultJob
        $faultJobExitCode = 1
        "Fault injection job timed out." | Out-File -Encoding utf8 -Append $faultLogPath
      } else {
        Receive-Job -Job $faultJob | Out-File -Encoding utf8 -Append (Join-Path $runDir "fault-injection.job.log")
        if ($faultJob.State -ne "Completed") {
          $faultJobExitCode = 1
        }
      }
    }
    Remove-Job -Job $faultJob -Force
  }

  if (Test-Path $faultStatusPath) {
    try {
      $faultStatus = Get-Content -Raw -Path $faultStatusPath | ConvertFrom-Json
      if ([string]$faultStatus.status -ne "completed") {
        $faultJobExitCode = 1
      }
    } catch {
      $faultJobExitCode = 1
    }
  } else {
    $faultJobExitCode = 1
  }
}

if ($workflowExitCode -ne 0) {
  exit $workflowExitCode
}

if ($faultJobExitCode -ne 0) {
  exit $faultJobExitCode
}

exit 0
