param(
  [string]$ComposeFile = "docker-compose.yml",
  [string]$EnvFile = ".env",
  [string]$PostgresContainer = "auth_pipeline_postgres",
  [string]$RabbitMgmtBaseUrl = "http://localhost:15672",
  [string]$RabbitVhost = "/",
  [switch]$PurgeAllQueues,
  [switch]$IncludeDocsResultCleanup,
  [string]$ResultsRoot = "docs/loadtest/results"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
  param([string]$PathValue)

  if ([System.IO.Path]::IsPathRooted($PathValue)) {
    return [System.IO.Path]::GetFullPath($PathValue)
  }
  return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $PathValue))
}

function Write-Step {
  param([string]$Message)
  Write-Host ("[reset-loadtest-state] {0}" -f $Message)
}

function Read-EnvMap {
  param([string]$Path)

  $map = @{}
  if (-not (Test-Path $Path)) { return $map }

  foreach ($line in (Get-Content $Path)) {
    $trim = $line.Trim()
    if ([string]::IsNullOrWhiteSpace($trim)) { continue }
    if ($trim.StartsWith("#")) { continue }
    $idx = $trim.IndexOf("=")
    if ($idx -lt 1) { continue }
    $k = $trim.Substring(0, $idx).Trim()
    $v = $trim.Substring($idx + 1).Trim()
    if ($v.StartsWith('"') -and $v.EndsWith('"') -and $v.Length -ge 2) {
      $v = $v.Substring(1, $v.Length - 2)
    }
    $map[$k] = $v
  }
  return $map
}

function Get-EnvValueOrThrow {
  param(
    [hashtable]$Map,
    [string]$Key
  )

  if (-not $Map.ContainsKey($Key) -or [string]::IsNullOrWhiteSpace([string]$Map[$Key])) {
    throw "Required key '$Key' is missing in env file."
  }
  return [string]$Map[$Key]
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$resolvedCompose = Resolve-RepoPath -PathValue $ComposeFile
$resolvedEnv = Resolve-RepoPath -PathValue $EnvFile

if (-not (Test-Path $resolvedCompose)) {
  throw "Compose file not found: $resolvedCompose"
}
if (-not (Test-Path $resolvedEnv)) {
  throw "Env file not found: $resolvedEnv"
}

$envMap = Read-EnvMap -Path $resolvedEnv
$dbName = Get-EnvValueOrThrow -Map $envMap -Key "DB_NAME"
$dbUser = Get-EnvValueOrThrow -Map $envMap -Key "DB_USERNAME"
$rabbitUser = Get-EnvValueOrThrow -Map $envMap -Key "RABBIT_USERNAME"
$rabbitPass = Get-EnvValueOrThrow -Map $envMap -Key "RABBIT_PASSWORD"

Write-Step "Ensuring docker services are up"
docker compose -f $resolvedCompose --env-file $resolvedEnv up -d postgres rabbitmq | Out-Null
if ($LASTEXITCODE -ne 0) {
  throw "docker compose up failed."
}

Write-Step "Waiting for Postgres container ($PostgresContainer)"
$pgReady = $false
for ($i = 0; $i -lt 30; $i++) {
  try {
    docker exec $PostgresContainer pg_isready -U $dbUser -d $dbName | Out-Null
    if ($LASTEXITCODE -eq 0) {
      $pgReady = $true
      break
    }
  } catch {
  }
  Start-Sleep -Seconds 2
}
if (-not $pgReady) {
  throw "Postgres is not ready: container=$PostgresContainer db=$dbName user=$dbUser"
}

Write-Step "Truncating load-test tables"
$sql = @'
TRUNCATE TABLE
  auth_error_cluster_item,
  auth_error_cluster,
  auth_error_analysis_result,
  processed_message,
  outbox_message,
  auth_error
RESTART IDENTITY CASCADE;

SELECT 'auth_error' AS table_name, COUNT(*) AS row_count FROM auth_error
UNION ALL SELECT 'auth_error_analysis_result', COUNT(*) FROM auth_error_analysis_result
UNION ALL SELECT 'auth_error_cluster', COUNT(*) FROM auth_error_cluster
UNION ALL SELECT 'auth_error_cluster_item', COUNT(*) FROM auth_error_cluster_item
UNION ALL SELECT 'outbox_message', COUNT(*) FROM outbox_message
UNION ALL SELECT 'processed_message', COUNT(*) FROM processed_message
ORDER BY table_name;
'@

docker exec -i $PostgresContainer psql -v ON_ERROR_STOP=1 -U $dbUser -d $dbName -c $sql
if ($LASTEXITCODE -ne 0) {
  throw "Database reset failed."
}

Write-Step "Purging RabbitMQ queues"
$authInfo = ("{0}:{1}" -f $rabbitUser, $rabbitPass)
$authBytes = [System.Text.Encoding]::ASCII.GetBytes($authInfo)
$authHeader = "Basic " + [Convert]::ToBase64String($authBytes)
$headers = @{ Authorization = $authHeader }

$vhostEnc = [uri]::EscapeDataString($RabbitVhost)
$queuesUri = "{0}/api/queues/{1}" -f $RabbitMgmtBaseUrl.TrimEnd('/'), $vhostEnc
$queues = Invoke-RestMethod -UseBasicParsing -Headers $headers -Method Get -Uri $queuesUri
$queueArray = @($queues)
$purged = New-Object System.Collections.Generic.List[string]
$skipped = New-Object System.Collections.Generic.List[string]

foreach ($q in $queueArray) {
  $name = [string]$q.name
  if ([string]::IsNullOrWhiteSpace($name)) { continue }

  if (-not $PurgeAllQueues.IsPresent) {
    if ($name.StartsWith("amq.")) {
      $skipped.Add($name)
      continue
    }
  }

  $contentsUri = "{0}/api/queues/{1}/{2}/contents" -f $RabbitMgmtBaseUrl.TrimEnd('/'), $vhostEnc, [uri]::EscapeDataString($name)
  Invoke-RestMethod -UseBasicParsing -Headers $headers -Method Delete -Uri $contentsUri | Out-Null
  $purged.Add($name)
}

if ($IncludeDocsResultCleanup.IsPresent) {
  $resolvedResultsRoot = Resolve-RepoPath -PathValue $ResultsRoot
  if (Test-Path $resolvedResultsRoot) {
    Write-Step ("Removing docs result folders: {0}" -f $resolvedResultsRoot)
    Get-ChildItem -Path $resolvedResultsRoot -Directory -ErrorAction SilentlyContinue | Remove-Item -Recurse -Force
  }
}

$report = [ordered]@{
  executed_at = (Get-Date).ToString("o")
  compose_file = $resolvedCompose
  env_file = $resolvedEnv
  postgres_container = $PostgresContainer
  database = $dbName
  database_user = $dbUser
  rabbit_mgmt_base_url = $RabbitMgmtBaseUrl
  rabbit_vhost = $RabbitVhost
  purged_queue_count = $purged.Count
  purged_queues = @($purged)
  skipped_queue_count = $skipped.Count
  skipped_queues = @($skipped)
  purge_all_queues = $PurgeAllQueues.IsPresent
}

$reportPath = Join-Path $repoRoot "k6\results\reset-loadtest-state.last.json"
$reportDir = Split-Path -Parent $reportPath
if (-not (Test-Path $reportDir)) {
  New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
}
$report | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 $reportPath

Write-Step "Reset completed"
Write-Step ("Report: {0}" -f $reportPath)
exit 0
