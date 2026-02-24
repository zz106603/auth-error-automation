param(
  [string]$CaptureDir = "docs/loadtest/baseline-captures",
  [int]$IntervalSec = 10
)

$ErrorActionPreference = "Stop"

function Get-LatestManifest {
  param([string]$Dir)
  $path = Join-Path $Dir "capture-manifest.txt"
  if (-not (Test-Path $path)) {
    throw "capture-manifest.txt not found in $Dir. Run capture-baseline.ps1 first."
  }
  return $path
}

function Parse-PromLines {
  param([string]$Path)
  $map = @{}
  Get-Content $Path | ForEach-Object {
    $line = $_.Trim()
    if ($line.Length -eq 0) { return }
    if ($line.StartsWith("#")) { return }

    # metric{labels} value OR metric value
    if ($line -match '^(?<metric>[a-zA-Z_:][a-zA-Z0-9_:]*)(\{(?<labels>[^}]*)\})?\s+(?<value>[-+]?(\d+(\.\d+)?([eE][-+]?\d+)?)|NaN|nan)$') {
      $metric = $Matches.metric
      $labels = $Matches.labels
      $valueStr = $Matches.value
      if ($valueStr -match '^(NaN|nan)$') { return }
      $value = [double]$valueStr

      $key = if ([string]::IsNullOrEmpty($labels)) { $metric } else { "$metric`{$labels`}" }
      $map[$key] = $value
    }
  }
  return $map
}

function Extract-LinesByPrefix {
  param($map, [string]$prefix)
  $map.Keys | Where-Object { $_.StartsWith($prefix) }
}

function Get-ValueOrNull {
  param($map, [string]$key)
  if ($map.ContainsKey($key)) { return $map[$key] }
  return $null
}

function Format-Rate {
  param([double]$delta, [double]$seconds)
  if ($seconds -le 0) { return "n/a" }
  $r = $delta / $seconds
  return ("{0:N3}/s" -f $r)
}

# Normalize key if duplicated accidentally
function Normalize-Key {
  param([string]$key)
  if ($key -match '^(?<m>.+)\1$') { return $Matches.m }
  if ($key -match '^(?<m>[a-zA-Z_:][a-zA-Z0-9_:]*\{[^}]*\})\k<m>$') { return $Matches.m }
  if ($key -match '^(?<m>[a-zA-Z_:][a-zA-Z0-9_:]*\{[^}]*\})(?<rest>.+)$') {
    if ($Matches.rest -like ("*" + $Matches.m + "*")) { return $Matches.m }
  }
  return $key
}

# Build label map from key string: metric{a="b",c="d"}
function Parse-LabelMap {
  param([string]$key)
  $labelMap = @{}
  if ($key -match '^[^{]+\{(?<labels>.*)\}$') {
    $labels = $Matches.labels
    $pairs = $labels -split ','
    foreach ($p in $pairs) {
      if ($p -match '^\s*(?<k>[^=]+)="(?<v>.*)"\s*$') {
        $labelMap[$Matches.k] = $Matches.v
      }
    }
  }
  return $labelMap
}

# Select a single key matching required labels (all required labels must match).
function Select-KeyByLabels {
  param(
    [string[]]$keys,
    [hashtable]$required
  )
  foreach ($k in $keys) {
    $labels = Parse-LabelMap -key $k
    $ok = $true
    foreach ($rk in $required.Keys) {
      if (-not $labels.ContainsKey($rk)) { $ok = $false; break }
      if ($labels[$rk] -ne $required[$rk]) { $ok = $false; break }
    }
    if ($ok) { return $k }
  }
  return $null
}

# Check if any key contains a given label key
function Has-LabelKey {
  param(
    [string[]]$keys,
    [string]$labelKey
  )
  foreach ($k in $keys) {
    $labels = Parse-LabelMap -key $k
    if ($labels.ContainsKey($labelKey)) { return $true }
  }
  return $false
}

# Select key by required labels, and optionally apply preferred labels only if they exist.
function Select-KeyByLabelsFlexible {
  param(
    [string[]]$keys,
    [hashtable]$required,
    [hashtable]$preferred
  )
  $effective = @{}
  foreach ($k in $required.Keys) { $effective[$k] = $required[$k] }

  foreach ($pk in $preferred.Keys) {
    if (Has-LabelKey -keys $keys -labelKey $pk) {
      $effective[$pk] = $preferred[$pk]
    }
  }
  return Select-KeyByLabels -keys $keys -required $effective
}

# Print rate for a required series; handle missing series/metric per rules.
function Print-RateForSeries {
  param(
    [string]$metricName,
    [hashtable]$requiredLabels,
    [hashtable]$preferredLabels,
    [bool]$missingAsZero,
    [double]$windowSec
  )
  $keys0 = Extract-LinesByPrefix -map $map0 -prefix ($metricName + "{")
  $keys1 = Extract-LinesByPrefix -map $map1 -prefix ($metricName + "{")
  $allKeys = @($keys0 + $keys1) | Sort-Object -Unique

  if ($allKeys.Count -eq 0) {
    if ($missingAsZero) {
      Write-Host (" - {0}: 0.000/s (metric not present, assumed none)" -f $metricName)
    } else {
      Write-Host (" - {0}: N/A (metric not present)" -f $metricName)
    }
    return
  }

  $k = Select-KeyByLabelsFlexible -keys $allKeys -required $requiredLabels -preferred $preferredLabels
  if ($null -eq $k) {
    if ($missingAsZero) {
      Write-Host (" - {0}: 0.000/s (series not present, assumed none)" -f $metricName)
    } else {
      Write-Host (" - {0}: N/A (series not present)" -f $metricName)
    }
    return
  }
  $k = Normalize-Key -key $k

  $v0 = Get-ValueOrNull -map $map0 -key $k
  $v1 = Get-ValueOrNull -map $map1 -key $k
  if ($null -eq $v0) { $v0 = 0.0 }
  if ($null -eq $v1) { $v1 = 0.0 }
  $delta = $v1 - $v0
  $rate = Format-Rate -delta $delta -seconds $windowSec
  Write-Host (" - {0}  delta={1:N0}  rate={2}" -f $k, $delta, $rate)
}

# Select gauge key by queue label (vhost value is discovered from series)
function Select-GaugeKeyByQueue {
  param(
    [string]$metricName,
    [string]$queueValue
  )
  $keys = Extract-LinesByPrefix -map $map1 -prefix ($metricName + "{")
  foreach ($k in $keys) {
    $labels = Parse-LabelMap -key $k
    if ($labels.ContainsKey("queue") -and $labels["queue"] -eq $queueValue) {
      return $k
    }
  }
  return $null
}

# Print gauge with fallback rules
function Print-GaugeWithFallback {
  param(
    [string]$key,
    [string]$displayName,
    [string]$missingMode
  )
  if ([string]::IsNullOrEmpty($key)) {
    if ($missingMode -eq "zero") {
      Write-Host (" - {0} = 0.000 (missing, assumed none)" -f $displayName)
    } else {
      Write-Host (" - {0} = N/A (missing)" -f $displayName)
    }
    return
  }
  $v = Get-ValueOrNull -map $map1 -key $key
  if ($null -eq $v) {
    if ($missingMode -eq "zero") {
      Write-Host (" - {0} = 0.000 (missing, assumed none)" -f $key)
    } else {
      Write-Host (" - {0} = N/A (missing)" -f $key)
    }
  } else {
    Write-Host (" - {0} = {1:N3}" -f $key, $v)
  }
}
# Histogram quantile (client-side) for Prometheus histogram buckets
# Inputs: list of buckets (le, count), already aggregated for a fixed label set
function Histogram-Quantile {
  param(
    [double]$q,
    [System.Collections.Generic.List[Object]]$buckets
  )

  if ($buckets.Count -eq 0) { return $null }

  # Sort by le (numeric, +Inf last)
  $sorted = $buckets | Sort-Object -Property leNum
  $total = $sorted[$sorted.Count - 1].count
  if ($total -le 0) { return $null }

  $target = $q * $total
  $prevCount = 0.0
  $prevLe = 0.0

  foreach ($b in $sorted) {
    $c = $b.count
    $le = $b.leNum
    if ($c -ge $target) {
      # linear interpolation within bucket
      $inBucket = $c - $prevCount
      if ($inBucket -le 0) { return $le }
      $pos = ($target - $prevCount) / $inBucket
      return $prevLe + ($le - $prevLe) * $pos
    }
    $prevCount = $c
    $prevLe = $le
  }
  return $sorted[$sorted.Count - 1].leNum
}

$manifest = Get-LatestManifest -Dir $CaptureDir
$files = Get-Content $manifest | Where-Object { $_ -and (Test-Path $_) }

if ($files.Count -lt 2) {
  throw "Need at least 2 snapshot files to compute deltas. Found: $($files.Count)"
}

$first = $files[0]
$last = $files[$files.Count - 1]

Write-Host "== Baseline summary =="
Write-Host "CaptureDir : $CaptureDir"
Write-Host "First      : $first"
Write-Host "Last       : $last"
Write-Host "Samples    : $($files.Count)"
Write-Host "IntervalSec: $IntervalSec"
Write-Host ""

$map0 = Parse-PromLines -Path $first
$map1 = Parse-PromLines -Path $last

$windowSec = ($files.Count - 1) * $IntervalSec

# 1) Core counters -> rate (Recorded path only)
Write-Host "## Stage throughput (Counters -> Rate, Recorded path only)"
Print-RateForSeries -metricName "auth_error_ingest_total" -requiredLabels @{ result="success" } -preferredLabels @{ api="/api/auth-errors" } -missingAsZero $false -windowSec $windowSec
Print-RateForSeries -metricName "auth_error_publish_total" -requiredLabels @{ event_type="auth.error.recorded.v1"; result="success" } -preferredLabels @{} -missingAsZero $false -windowSec $windowSec
Print-RateForSeries -metricName "auth_error_consume_total" -requiredLabels @{ event_type="auth.error.recorded.v1"; queue="auth.error.recorded.q"; result="success" } -preferredLabels @{} -missingAsZero $false -windowSec $windowSec
Print-RateForSeries -metricName "auth_error_retry_enqueue_total" -requiredLabels @{ event_type="auth.error.recorded.v1"; queue="auth.error.recorded.q" } -preferredLabels @{} -missingAsZero $true -windowSec $windowSec
Print-RateForSeries -metricName "auth_error_dlq_total" -requiredLabels @{ event_type="auth.error.recorded.v1"; queue="auth.error.recorded.q" } -preferredLabels @{} -missingAsZero $true -windowSec $windowSec
Write-Host ""

# 2) Gauges (take last snapshot values)
function Summarize-Gauge {
  param([string]$key)
  $v = Get-ValueOrNull -map $map1 -key $key
  if ($null -eq $v) {
    Write-Host " - ${key}: NOT FOUND"
  } else {
    Write-Host (" - {0} = {1:N3}" -f $key, $v)
  }
}

Write-Host "## Outbox age gauges (last snapshot)"
Summarize-Gauge -key "auth_error_outbox_age_p95"
Summarize-Gauge -key "auth_error_outbox_age_p99"
Summarize-Gauge -key "auth_error_outbox_age_slope_ms_per_10s"
Write-Host ""

Write-Host "## Publish last success (last snapshot)"
Summarize-Gauge -key "auth_error_publish_last_success_epoch_ms"
Write-Host ""

Write-Host "## RabbitMQ gauges (baseline fields)"
$readyKey = Select-GaugeKeyByQueue -metricName "auth_error_rabbit_ready" -queueValue "auth.error.recorded.q"
Print-GaugeWithFallback -key $readyKey -displayName 'auth_error_rabbit_ready{queue="auth.error.recorded.q"}' -missingMode "na"
$unackedKey = Select-GaugeKeyByQueue -metricName "auth_error_rabbit_unacked" -queueValue "auth.error.recorded.q"
Print-GaugeWithFallback -key $unackedKey -displayName 'auth_error_rabbit_unacked{queue="auth.error.recorded.q"}' -missingMode "na"
$pubRateKey = Select-GaugeKeyByQueue -metricName "auth_error_rabbit_publish_rate" -queueValue "auth.error.recorded.q"
Print-GaugeWithFallback -key $pubRateKey -displayName 'auth_error_rabbit_publish_rate{queue="auth.error.recorded.q"}' -missingMode "na"
$delRateKey = Select-GaugeKeyByQueue -metricName "auth_error_rabbit_deliver_rate" -queueValue "auth.error.recorded.q"
Print-GaugeWithFallback -key $delRateKey -displayName 'auth_error_rabbit_deliver_rate{queue="auth.error.recorded.q"}' -missingMode "na"
$retryDepthKey = Select-GaugeKeyByQueue -metricName "auth_error_rabbit_retry_depth" -queueValue "all"
Print-GaugeWithFallback -key $retryDepthKey -displayName 'auth_error_rabbit_retry_depth{queue="all"}' -missingMode "zero"
$dlqDepthKey = Select-GaugeKeyByQueue -metricName "auth_error_rabbit_dlq_depth" -queueValue "all"
Print-GaugeWithFallback -key $dlqDepthKey -displayName 'auth_error_rabbit_dlq_depth{queue="all"}' -missingMode "zero"
Write-Host ""

# 3) E2E histogram quantiles (p95/p99) from last snapshot (Recorded only)
Write-Host "## E2E (Timer histogram) p95/p99 estimated from buckets (Recorded only)"
# find all bucket series for auth_error_e2e_seconds_bucket
$bucketPrefix = "auth_error_e2e_seconds_bucket{"
$bucketKeys = Extract-LinesByPrefix -map $map1 -prefix $bucketPrefix
if ($bucketKeys.Count -eq 0) {
  Write-Host " - auth_error_e2e_seconds_bucket: NOT FOUND"
} else {
  # Group by label set excluding le
  $groups = @{}
  foreach ($k in $bucketKeys) {
    # Extract labels content
    if ($k -match '^auth_error_e2e_seconds_bucket\{(?<labels>.*)\}$') {
      $labels = $Matches.labels
      # split labels into map
      $pairs = $labels -split ','
      $labelMap = @{}
      foreach ($p in $pairs) {
        if ($p -match '^\s*(?<key>[^=]+)="(?<val>.*)"\s*$') {
          $labelMap[$Matches.key] = $Matches.val
        }
      }
      if (-not $labelMap.ContainsKey("le")) { continue }
      $leStr = $labelMap["le"]
      $leNum = if ($leStr -eq "+Inf") { [double]::PositiveInfinity } else { [double]$leStr }

      # build group key (remove le)
      $labelMap.Remove("le")
      $groupLabels = ($labelMap.Keys | Sort-Object | ForEach-Object { $_ + '="' + $labelMap[$_] + '"' }) -join ','
      $groupKey = "auth_error_e2e_seconds_bucket{" + $groupLabels + "}"

      if (-not $groups.ContainsKey($groupKey)) {
        $groups[$groupKey] = New-Object System.Collections.Generic.List[Object]
      }
      $obj = [pscustomobject]@{ leNum = $leNum; count = $map1[$k] }
      $groups[$groupKey].Add($obj)
    }
  }

  $targetKey = $null
  foreach ($gk in $groups.Keys) {
    if ($gk -match '^auth_error_e2e_seconds_bucket\{(?<labels>.*)\}$') {
      $labels = $Matches.labels
      $pairs = $labels -split ','
      $labelMap = @{}
      foreach ($p in $pairs) {
        if ($p -match '^\s*(?<key>[^=]+)="(?<val>.*)"\s*$') {
          $labelMap[$Matches.key] = $Matches.val
        }
      }
      if ($labelMap.ContainsKey("event_type") -and $labelMap.ContainsKey("queue")) {
        if ($labelMap["event_type"] -eq "auth.error.recorded.v1" -and $labelMap["queue"] -eq "auth.error.recorded.q") {
          $targetKey = $gk
          break
        }
      }
    }
  }

  if ($null -eq $targetKey) {
    Write-Host " - recorded bucket group: N/A (not found)"
  } else {
    $buckets = $groups[$targetKey]
    $p95 = Histogram-Quantile -q 0.95 -buckets $buckets
    $p99 = Histogram-Quantile -q 0.99 -buckets $buckets
    $p95ms = if ($null -eq $p95 -or [double]::IsInfinity($p95)) { $null } else { $p95 * 1000.0 }
    $p99ms = if ($null -eq $p99 -or [double]::IsInfinity($p99)) { $null } else { $p99 * 1000.0 }

    Write-Host " - $targetKey"
    if ($null -eq $p95ms) { Write-Host "    p95: n/a" } else { Write-Host ("    p95: {0:N2} ms" -f $p95ms) }
    if ($null -eq $p99ms) { Write-Host "    p99: n/a" } else { Write-Host ("    p99: {0:N2} ms" -f $p99ms) }
  }
}
Write-Host ""

Write-Host "== Done =="
Write-Host "Use this output to fill docs/loadtest/LT-001-baseline.md (baseline window: $windowSec sec)."
