param(
  [Parameter(Mandatory = $true)]
  [string]$SnapshotPath,
  [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function To-Array {
  param([object]$Value)

  if ($null -eq $Value) { return @() }
  if ($Value -is [System.Array]) { return $Value }
  if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
    $arr = @()
    foreach ($v in $Value) { $arr += $v }
    return $arr
  }
  return @($Value)
}

function Safe-Count {
  param([object]$Value)

  $arr = To-Array -Value $Value
  $n = 0
  foreach ($item in $arr) { $n++ }
  return $n
}

function Get-Prop {
  param(
    [object]$Object,
    [string]$Name
  )

  if ($null -eq $Object) { return $null }

  if ($Object -is [hashtable]) {
    if ($Object.ContainsKey($Name)) { return $Object[$Name] }
    return $null
  }

  $props = $Object.PSObject.Properties
  if ($null -eq $props) { return $null }
  $p = $props[$Name]
  if ($null -eq $p) { return $null }
  return $p.Value
}

function Get-PathValue {
  param(
    [object]$Root,
    [string[]]$Path
  )

  $cur = $Root
  foreach ($seg in $Path) {
    $cur = Get-Prop -Object $cur -Name $seg
    if ($null -eq $cur) { return $null }
  }
  return $cur
}

function FmtNum {
  param([object]$Value, [int]$Digits = 3)

  if ($null -eq $Value) { return "n/a" }
  if ($Value -is [string] -and [string]::IsNullOrWhiteSpace($Value)) { return "n/a" }
  if ($Value -is [string] -and $Value -eq "n/a") { return "n/a" }
  try {
    return ([double]$Value).ToString(("N" + $Digits), [Globalization.CultureInfo]::InvariantCulture)
  } catch {
    return [string]$Value
  }
}

function FmtPercent {
  param([object]$Value)

  if ($null -eq $Value) { return "n/a" }
  if ($Value -is [string] -and $Value -eq "n/a") { return "n/a" }
  try {
    return (([double]$Value) * 100.0).ToString("N3", [Globalization.CultureInfo]::InvariantCulture) + "%"
  } catch {
    return "n/a"
  }
}

function SecToMsOrNull {
  param([object]$SecondsValue)

  if ($null -eq $SecondsValue) { return $null }
  if ($SecondsValue -is [string] -and $SecondsValue -eq "n/a") { return "n/a" }
  try {
    return ([double]$SecondsValue) * 1000.0
  } catch {
    return $null
  }
}

function Get-MetricStats {
  param(
    [object]$Kpis,
    [string]$MetricName
  )

  $metric = Get-Prop -Object $Kpis -Name $MetricName
  if ($null -eq $metric) {
    return [pscustomobject]@{
      samples = 0
      first = "n/a"
      last = "n/a"
      min = "n/a"
      max = "n/a"
      avg = "n/a"
      p95 = "n/a"
      p99 = "n/a"
      slope_per_sec = "n/a"
    }
  }

  $stats = Get-Prop -Object $metric -Name "stats"
  if ($null -eq $stats) {
    return [pscustomobject]@{
      samples = 0
      first = "n/a"
      last = "n/a"
      min = "n/a"
      max = "n/a"
      avg = "n/a"
      p95 = "n/a"
      p99 = "n/a"
      slope_per_sec = "n/a"
    }
  }

  # Already normalized in snapshot script, but keep defensive defaults.
  $samples = Get-Prop -Object $stats -Name "samples"
  if ($null -eq $samples) { $samples = 0 }

  return [pscustomobject]@{
    samples = $samples
    first = (Get-Prop -Object $stats -Name "first")
    last = (Get-Prop -Object $stats -Name "last")
    min = (Get-Prop -Object $stats -Name "min")
    max = (Get-Prop -Object $stats -Name "max")
    avg = (Get-Prop -Object $stats -Name "avg")
    p95 = (Get-Prop -Object $stats -Name "p95")
    p99 = (Get-Prop -Object $stats -Name "p99")
    slope_per_sec = (Get-Prop -Object $stats -Name "slope_per_sec")
  }
}

function Write-Utf8BomFile {
  param(
    [string]$Path,
    [string[]]$ContentLines
  )

  $joined = [string]::Join([Environment]::NewLine, $ContentLines)
  # Deterministic UTF-8 BOM output across Windows PowerShell 5.1 and PowerShell 7+.
  $enc = New-Object System.Text.UTF8Encoding($true)
  [System.IO.File]::WriteAllText($Path, $joined + [Environment]::NewLine, $enc)
}

$warnings = @()

$resolvedSnapshot = $null
if (Test-Path $SnapshotPath) {
  try {
    $resolvedSnapshot = (Resolve-Path -Path $SnapshotPath -ErrorAction Stop).Path
  } catch {
    $resolvedSnapshot = [System.IO.Path]::GetFullPath($SnapshotPath)
  }
} else {
  $resolvedSnapshot = [System.IO.Path]::GetFullPath($SnapshotPath)
  $warnings += ("snapshot not found: {0}" -f $resolvedSnapshot)
}

$artifactDir = Split-Path -Parent $resolvedSnapshot
if ([string]::IsNullOrWhiteSpace($artifactDir)) {
  $artifactDir = (Get-Location).Path
}

$metaPath = Join-Path $artifactDir "run-metadata.json"
$k6RefPath = Join-Path $artifactDir "k6-artifacts.json"

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
  # Default output name even when snapshot parse fails.
  $OutputPath = Join-Path $artifactDir "summary.md"
}

$snapshot = $null
if (Test-Path $resolvedSnapshot) {
  try {
    $snapshot = Get-Content $resolvedSnapshot -Raw | ConvertFrom-Json
  } catch {
    $warnings += ("snapshot json parse failed: {0}" -f $_.Exception.Message)
  }
}

if (-not (Test-Path $metaPath)) { $warnings += ("missing metadata file: {0}" -f $metaPath) }
if (-not (Test-Path $k6RefPath)) { $warnings += ("missing k6 artifacts file: {0}" -f $k6RefPath) }

$testId = [string](Get-PathValue -Root $snapshot -Path @("metadata", "test_id"))
if ([string]::IsNullOrWhiteSpace($testId)) {
  $testId = [System.IO.Path]::GetFileName($artifactDir)
  if ([string]::IsNullOrWhiteSpace($testId)) { $testId = "unknown-test-id" }
  $warnings += "metadata.test_id missing"
}

# If caller did not pass OutputPath explicitly and testId is known, keep standardized filename.
if ($OutputPath -eq (Join-Path $artifactDir "summary.md")) {
  $OutputPath = Join-Path $artifactDir ("{0}-summary.md" -f $testId)
}

$scenario = [string](Get-PathValue -Root $snapshot -Path @("metadata", "scenario"))
$runStart = [string](Get-PathValue -Root $snapshot -Path @("metadata", "run_window", "t_start"))
$runEnd = [string](Get-PathValue -Root $snapshot -Path @("metadata", "run_window", "t_end"))
$durationSec = [string](Get-PathValue -Root $snapshot -Path @("metadata", "run_window", "duration_sec"))
$gitSha = [string](Get-PathValue -Root $snapshot -Path @("metadata", "git_sha"))
$runtimeProfiles = To-Array -Value (Get-PathValue -Root $snapshot -Path @("metadata", "runtime_settings", "active_profiles"))
$metaRuntimeHikariMax = [string](Get-PathValue -Root $snapshot -Path @("metadata", "runtime_settings", "hikari_max_pool_size"))
$metaRuntimeConc = [string](Get-PathValue -Root $snapshot -Path @("metadata", "runtime_settings", "consumer_concurrency"))
$metaRuntimeMaxConc = [string](Get-PathValue -Root $snapshot -Path @("metadata", "runtime_settings", "consumer_max_concurrency"))
$metaRuntimePrefetch = [string](Get-PathValue -Root $snapshot -Path @("metadata", "runtime_settings", "consumer_prefetch"))
$holdWindowsMeta = To-Array -Value (Get-PathValue -Root $snapshot -Path @("metadata", "hold_phase_windows"))
$runtimeProfilesText = "n/a"
if ((Safe-Count $runtimeProfiles) -gt 0) {
  $runtimeProfilesText = ($runtimeProfiles -join ", ")
}
$verdict = [string](Get-PathValue -Root $snapshot -Path @("verdict"))

if ([string]::IsNullOrWhiteSpace($scenario)) { $scenario = "n/a"; $warnings += "metadata.scenario missing" }
if ([string]::IsNullOrWhiteSpace($runStart)) { $runStart = "n/a"; $warnings += "run_window.t_start missing" }
if ([string]::IsNullOrWhiteSpace($runEnd)) { $runEnd = "n/a"; $warnings += "run_window.t_end missing" }
if ([string]::IsNullOrWhiteSpace($durationSec)) { $durationSec = "n/a"; $warnings += "run_window.duration_sec missing" }
if ([string]::IsNullOrWhiteSpace($gitSha)) { $gitSha = "n/a" }
if ([string]::IsNullOrWhiteSpace($verdict)) { $verdict = "UNKNOWN"; $warnings += "verdict missing" }

# Critical missing fields force UNKNOWN.
if ($runStart -eq "n/a" -or $runEnd -eq "n/a") {
  $verdict = "UNKNOWN"
}

$kpis = Get-Prop -Object $snapshot -Name "kpis"
$checks = To-Array -Value (Get-Prop -Object $snapshot -Name "checks")
$queries = To-Array -Value (Get-PathValue -Root $snapshot -Path @("appendix", "queries"))
$anomalies = To-Array -Value (Get-Prop -Object $snapshot -Name "anomalies")

$ingest = Get-MetricStats -Kpis $kpis -MetricName "ingest_rps"
$httpP95 = Get-MetricStats -Kpis $kpis -MetricName "http_server_p95_seconds"
$httpP99 = Get-MetricStats -Kpis $kpis -MetricName "http_server_p99_seconds"
$clientToConsumeP95 = Get-MetricStats -Kpis $kpis -MetricName "client_event_to_consume_p95_seconds"
$clientToConsumeP99 = Get-MetricStats -Kpis $kpis -MetricName "client_event_to_consume_p99_seconds"
$clientToConsumeMax = Get-MetricStats -Kpis $kpis -MetricName "client_event_to_consume_max_seconds"
$ingestToConsumeP95 = Get-MetricStats -Kpis $kpis -MetricName "ingest_to_consume_p95_seconds"
$ingestToConsumeP99 = Get-MetricStats -Kpis $kpis -MetricName "ingest_to_consume_p99_seconds"
$ingestToConsumeMax = Get-MetricStats -Kpis $kpis -MetricName "ingest_to_consume_max_seconds"
$publish = Get-MetricStats -Kpis $kpis -MetricName "publish_rps"
$consume = Get-MetricStats -Kpis $kpis -MetricName "consume_rps"
$retry = Get-MetricStats -Kpis $kpis -MetricName "retry_enqueue_rps"
$retryPressure = Get-MetricStats -Kpis $kpis -MetricName "retry_pressure_ratio"
$outbox95 = Get-MetricStats -Kpis $kpis -MetricName "outbox_age_p95_ms"
$outbox99 = Get-MetricStats -Kpis $kpis -MetricName "outbox_age_p99_ms"
$outboxSlope = Get-MetricStats -Kpis $kpis -MetricName "outbox_age_slope_ms_per_10s"
$rReady = Get-MetricStats -Kpis $kpis -MetricName "rabbit_ready_depth"
$rUnacked = Get-MetricStats -Kpis $kpis -MetricName "rabbit_unacked_depth"
$rRetry = Get-MetricStats -Kpis $kpis -MetricName "rabbit_retry_depth"
$rDlq = Get-MetricStats -Kpis $kpis -MetricName "rabbit_dlq_depth"
$hikariActive = Get-MetricStats -Kpis $kpis -MetricName "hikari_active"
$hikariPending = Get-MetricStats -Kpis $kpis -MetricName "hikari_pending"
$hikariMax = Get-MetricStats -Kpis $kpis -MetricName "hikari_max"
$ingestToConsumeOverflow = Get-MetricStats -Kpis $kpis -MetricName "ingest_to_consume_overflow_ratio_120s"
$runtimeHikariMax = Get-MetricStats -Kpis $kpis -MetricName "runtime_hikari_max_pool_size"
$runtimeConsumerConc = Get-MetricStats -Kpis $kpis -MetricName "runtime_consumer_concurrency"
$runtimeConsumerMaxConc = Get-MetricStats -Kpis $kpis -MetricName "runtime_consumer_max_concurrency"
$runtimePrefetch = Get-MetricStats -Kpis $kpis -MetricName "runtime_consumer_prefetch"
$server5xx = Get-MetricStats -Kpis $kpis -MetricName "server_5xx_rate"
$drainMetric = Get-Prop -Object $kpis -Name "drain_time_sec"

$k6Err = $null
$k6Summary = Get-Prop -Object $snapshot -Name "k6_summary"
$k6ErrRaw = Get-Prop -Object $k6Summary -Name "http_req_failed_rate"
if ($null -ne $k6ErrRaw -and -not [string]::IsNullOrWhiteSpace([string]$k6ErrRaw)) {
  try {
    $k6Err = [double]$k6ErrRaw
  } catch {
    $k6Err = $null
  }
}

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add(("# {0} 자동 요약" -f $testId))
$lines.Add("")
$lines.Add("## 1) Run Window")
$lines.Add("")
$lines.Add(("- Test ID: {0}" -f $testId))
$lines.Add(("- Scenario: {0}" -f $scenario))
$lines.Add(("- t_start (UTC): {0}" -f $runStart))
$lines.Add(("- t_end (UTC): {0}" -f $runEnd))
$lines.Add(("- duration: {0} sec" -f $durationSec))
$lines.Add(("- git SHA: {0}" -f $gitSha))
$lines.Add(("- hold windows detected: {0}" -f (Safe-Count $holdWindowsMeta)))
$lines.Add(("- runtime active profiles: {0}" -f $runtimeProfilesText))
$lines.Add(("- runtime settings: hikariMax={0}, consumerConcurrency={1}, consumerMaxConcurrency={2}, prefetch={3}" -f $metaRuntimeHikariMax, $metaRuntimeConc, $metaRuntimeMaxConc, $metaRuntimePrefetch))
$lines.Add("")

$lines.Add("## 2) KPI 요약")
$lines.Add("")
$lines.Add("| KPI | 대표값 | 비고 |")
$lines.Add("| --- | --- | --- |")
$lines.Add(("| ingest RPS | {0} | avg |" -f (FmtNum $ingest.avg 3)))
$lines.Add(("| HTTP p95 / p99 (server, ms) | {0} / {1} | max |" -f (FmtNum (SecToMsOrNull $httpP95.max) 2), (FmtNum (SecToMsOrNull $httpP99.max) 2)))
$lines.Add(("| ingest->consume p95 / p99 / max (ms) | {0} / {1} / {2} | max |" -f (FmtNum (SecToMsOrNull $ingestToConsumeP95.max) 2), (FmtNum (SecToMsOrNull $ingestToConsumeP99.max) 2), (FmtNum (SecToMsOrNull $ingestToConsumeMax.max) 2)))
$lines.Add(("| client event->consume p95 / p99 / max (ms) | {0} / {1} / {2} | max |" -f (FmtNum (SecToMsOrNull $clientToConsumeP95.max) 2), (FmtNum (SecToMsOrNull $clientToConsumeP99.max) 2), (FmtNum (SecToMsOrNull $clientToConsumeMax.max) 2)))
$lines.Add(("| publish / consume RPS | {0} / {1} | avg |" -f (FmtNum $publish.avg 3), (FmtNum $consume.avg 3)))
$lines.Add(("| retry enqueue RPS | {0} | avg |" -f (FmtNum $retry.avg 3)))
$lines.Add(("| retry pressure ratio | {0} | max |" -f (FmtPercent $retryPressure.max)))
$lines.Add(("| outbox age p95 / p99 (ms) | {0} / {1} | max |" -f (FmtNum $outbox95.max 2), (FmtNum $outbox99.max 2)))
$lines.Add(("| outbox age slope (ms/10s) | {0} | max |" -f (FmtNum $outboxSlope.max 2)))
$lines.Add(("| Rabbit ready / unacked | {0} / {1} | max |" -f (FmtNum $rReady.max 3), (FmtNum $rUnacked.max 3)))
$lines.Add(("| Rabbit retry / DLQ depth | {0} / {1} | max |" -f (FmtNum $rRetry.max 3), (FmtNum $rDlq.max 3)))
$lines.Add(("| Hikari active / pending / max | {0} / {1} / {2} | max |" -f (FmtNum $hikariActive.max 3), (FmtNum $hikariPending.max 3), (FmtNum $hikariMax.max 3)))
$lines.Add(("| ingest->consume overflow ratio (>120s) | {0} | max |" -f (FmtPercent $ingestToConsumeOverflow.max)))
$lines.Add(("| Runtime config (hikari / conc / maxConc / prefetch) | {0} / {1} / {2} / {3} | captured |" -f (FmtNum $runtimeHikariMax.max 0), (FmtNum $runtimeConsumerConc.max 0), (FmtNum $runtimeConsumerMaxConc.max 0), (FmtNum $runtimePrefetch.max 0)))
$lines.Add(("| Error rate (k6 failed) | {0} | k6 summary |" -f (FmtPercent $k6Err)))
$lines.Add(("| Error rate (server 5xx) | {0} | max |" -f (FmtPercent $server5xx.max)))

$drainValue = Get-Prop -Object $drainMetric -Name "value"
if ($null -ne $drainValue -and -not [string]::IsNullOrWhiteSpace([string]$drainValue)) {
  $lines.Add(("| Drain time after cooldown (sec) | {0} | drain samples |" -f $drainValue))
} else {
  $lines.Add("| Drain time after cooldown (sec) | n/a | drain samples missing |")
}
$lines.Add("")

$lines.Add("## 3) 최종 판정")
$lines.Add("")
$lines.Add(("- Verdict: **{0}**" -f $verdict))
$lines.Add("")
$lines.Add("### Acceptance Checks")
$lines.Add("")
$lines.Add("| Check | Status | Detail |")
$lines.Add("| --- | --- | --- |")
if ((Safe-Count $checks) -eq 0) {
  $lines.Add("| checks_missing | UNKNOWN | snapshot.checks missing |")
  $verdict = "UNKNOWN"
} else {
  foreach ($c in $checks) {
    $name = [string](Get-Prop -Object $c -Name "name")
    $status = [string](Get-Prop -Object $c -Name "status")
    $detailRaw = [string](Get-Prop -Object $c -Name "detail")
    if ([string]::IsNullOrWhiteSpace($name)) { $name = "n/a" }
    if ([string]::IsNullOrWhiteSpace($status)) { $status = "UNKNOWN" }
    if ([string]::IsNullOrWhiteSpace($detailRaw)) { $detailRaw = "n/a" }
    $detailEsc = ($detailRaw -replace "\|", "\\|")
    $lines.Add(("| {0} | {1} | {2} |" -f $name, $status, $detailEsc))
  }
}
$lines.Add("")

$lines.Add("## 4) 이상 징후")
$lines.Add("")
if ((Safe-Count $anomalies) -eq 0) {
  $lines.Add("- 없음")
} else {
  foreach ($a in $anomalies) {
    $lines.Add(("- {0}" -f [string]$a))
  }
}
$lines.Add("")

$lines.Add("## 5) Appendix: PromQL")
$lines.Add("")
$lines.Add("| Metric ID | PromQL | Samples | last / avg / max |")
$lines.Add("| --- | --- | --- | --- |")
if ((Safe-Count $queries) -eq 0) {
  $lines.Add("| n/a | n/a | 0 | n/a / n/a / n/a |")
} else {
  foreach ($q in $queries) {
    $qid = [string](Get-Prop -Object $q -Name "id")
    $promqlRaw = [string](Get-Prop -Object $q -Name "promql")
    $samples = Get-Prop -Object $q -Name "samples"
    $last = Get-Prop -Object $q -Name "last"
    $avg = Get-Prop -Object $q -Name "avg"
    $max = Get-Prop -Object $q -Name "max"

    if ([string]::IsNullOrWhiteSpace($qid)) { $qid = "n/a" }
    if ([string]::IsNullOrWhiteSpace($promqlRaw)) { $promqlRaw = "n/a" }
    if ($null -eq $samples) { $samples = 0 }

    $promqlEsc = ($promqlRaw -replace "\|", "\\|")
    $lines.Add(("| {0} | {1} | {2} | {3} / {4} / {5} |" -f `
      $qid,
      $promqlEsc,
      $samples,
      (FmtNum $last 3),
      (FmtNum $avg 3),
      (FmtNum $max 3)))
  }
}
$lines.Add("")
$lines.Add("---")
$lines.Add("")
$lines.Add("자동 생성 파일:")
$lines.Add(("- snapshot JSON: {0}" -f $resolvedSnapshot))
$lines.Add(("- metadata: {0}" -f $metaPath))
$lines.Add(("- k6 artifact refs: {0}" -f $k6RefPath))

if ((Safe-Count $warnings) -gt 0) {
  $lines.Add("")
  $lines.Add("경고:")
  foreach ($w in $warnings) {
    $lines.Add(("- {0}" -f $w))
    Write-Warning $w
  }
  # Critical input missing -> UNKNOWN override in report text
  if ($verdict -ne "FAIL") {
    $lines.Add("- verdict adjusted to UNKNOWN due to missing critical inputs")
  }
}

# Ensure target directory exists.
$outputDir = Split-Path -Parent $OutputPath
if (-not [string]::IsNullOrWhiteSpace($outputDir) -and -not (Test-Path $outputDir)) {
  New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}

Write-Utf8BomFile -Path $OutputPath -ContentLines $lines
Write-Host ("Load-test report generated: {0}" -f $OutputPath)
exit 0
