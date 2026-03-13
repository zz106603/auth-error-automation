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

trap [System.Exception] {
  $lineNo = $_.InvocationInfo.ScriptLineNumber
  $lineText = $_.InvocationInfo.Line
  $etype = $_.Exception.GetType().FullName
  Write-Error ("[capture-prometheus-snapshot] Unhandled exception type={0} line={1} message={2}" -f $etype, $lineNo, $_.Exception.Message)
  if (-not [string]::IsNullOrWhiteSpace($lineText)) {
    Write-Error ("[capture-prometheus-snapshot] Failing line: {0}" -f $lineText.Trim())
  }
  throw
}

function Get-SafeCount {
  param([object]$Value)

  if ($null -eq $Value) { return 0 }
  if ($Value -is [System.Array]) { return $Value.Length }
  if ($Value -is [System.Collections.ICollection]) { return $Value.Count }
  if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
    $count = 0
    foreach ($item in $Value) { $count++ }
    return $count
  }
  return 1
}

function To-ObjectArray {
  param([object]$Value)

  if ($null -eq $Value) { return @() }
  if ($Value -is [System.Array]) { return $Value }
  if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
    $arr = @()
    foreach ($item in $Value) { $arr += $item }
    return $arr
  }
  return @($Value)
}

function To-PlainArray {
  param([object]$Value)
  return @(To-ObjectArray -Value $Value)
}

function To-ExportStats {
  param([hashtable]$Stats)

  if ($null -eq $Stats) {
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

  $samples = 0
  try { $samples = [int]$Stats.samples } catch { $samples = 0 }

  $mapValue = {
    param([object]$v)
    if ($samples -eq 0 -or $null -eq $v) { return "n/a" }
    return $v
  }

  return [pscustomobject]@{
    samples = $samples
    first = (& $mapValue $Stats.first)
    last = (& $mapValue $Stats.last)
    min = (& $mapValue $Stats.min)
    max = (& $mapValue $Stats.max)
    avg = (& $mapValue $Stats.avg)
    p95 = (& $mapValue $Stats.p95)
    p99 = (& $mapValue $Stats.p99)
    slope_per_sec = (& $mapValue $Stats.slope_per_sec)
  }
}

function Write-ParamDiagnostics {
  param([string]$Name, [object]$Value)

  $typeName = if ($null -eq $Value) { "null" } else { $Value.GetType().FullName }
  $isEmpty = $false
  if ($Value -is [string]) { $isEmpty = [string]::IsNullOrWhiteSpace($Value) }
  Write-Host ("[capture-prometheus-snapshot] Param {0}: value='{1}' type={2} IsNullOrEmpty={3}" -f $Name, $Value, $typeName, $isEmpty)
}

function Resolve-ExistingPathOrNull {
  param([string]$PathValue)

  if ([string]::IsNullOrWhiteSpace($PathValue)) { return $null }

  # Requirement: prefer Resolve-Path for relative inputs.
  try {
    $resolved = Resolve-Path -Path $PathValue -ErrorAction Stop
    if ($null -ne $resolved) { return $resolved.Path }
  } catch {
  }

  $candidate = $PathValue
  if (-not [System.IO.Path]::IsPathRooted($candidate)) {
    $candidate = [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $candidate))
  }

  if (Test-Path $candidate) {
    try {
      return (Resolve-Path -Path $candidate -ErrorAction Stop).Path
    } catch {
      return [System.IO.Path]::GetFullPath($candidate)
    }
  }

  return $null
}

function Resolve-RunDirectoryOrThrow {
  param(
    [string]$InputRunDir,
    [string]$InputK6ResultsRoot,
    [string]$Id
  )

  $cwd = (Get-Location).Path

  $k6ResultsRootResolved = Resolve-ExistingPathOrNull -PathValue $InputK6ResultsRoot
  if ($null -eq $k6ResultsRootResolved) {
    if ([System.IO.Path]::IsPathRooted($InputK6ResultsRoot)) {
      $k6ResultsRootResolved = $InputK6ResultsRoot
    } else {
      $k6ResultsRootResolved = [System.IO.Path]::GetFullPath((Join-Path $cwd $InputK6ResultsRoot))
    }
  }

  $runDirResolved = Resolve-ExistingPathOrNull -PathValue $InputRunDir
  if ($null -ne $runDirResolved) {
    return $runDirResolved
  }

  $expectedRunDir = Join-Path $k6ResultsRootResolved $Id
  $expectedRunDirResolved = Resolve-ExistingPathOrNull -PathValue $expectedRunDir
  if ($null -ne $expectedRunDirResolved) {
    return $expectedRunDirResolved
  }

  $available = @()
  if (Test-Path $k6ResultsRootResolved) {
    $available = @(Get-ChildItem -Path $k6ResultsRootResolved -Directory -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Name)
  }
  $availableText = if ((Get-SafeCount $available) -gt 0) { ($available -join ", ") } else { "(none)" }

  throw ("Run directory not found.`n" +
    "pwd: {0}`n" +
    "provided -RunDir: {1}`n" +
    "expected pattern: {2}`n" +
    "available folders under k6/results: {3}" -f $cwd, $InputRunDir, $expectedRunDir, $availableText)
}

function To-UnixSec {
  param([DateTimeOffset]$Dt)
  return [int64][Math]::Floor($Dt.ToUnixTimeMilliseconds() / 1000.0)
}

function Convert-FromIsoOrThrow {
  param([string]$Value, [string]$FieldName)

  if ([string]::IsNullOrWhiteSpace($Value)) {
    throw "Invalid ${FieldName}: empty value. Expected ISO-8601 (e.g. 2026-03-05T03:37:42.6784680+00:00)"
  }

  $formats = @(
    "o",
    "yyyy-MM-ddTHH:mm:ssK",
    "yyyy-MM-ddTHH:mm:ss.FFFFFFFK"
  )

  try {
    return [DateTimeOffset]::ParseExact(
      $Value,
      $formats,
      [Globalization.CultureInfo]::InvariantCulture,
      [Globalization.DateTimeStyles]::RoundtripKind
    )
  } catch {
    try {
      return [DateTimeOffset]::Parse(
        $Value,
        [Globalization.CultureInfo]::InvariantCulture,
        [Globalization.DateTimeStyles]::RoundtripKind
      )
    } catch {
      throw "Invalid ${FieldName}: $Value. Expected ISO-8601 timestamp (e.g. 2026-03-05T03:37:42.6784680+00:00)"
    }
  }
}

function Parse-KeyValueSummary {
  param([string]$Path)

  if (-not (Test-Path $Path)) {
    throw "k6 summary file not found: $Path"
  }

  $kv = @{}
  Get-Content $Path | ForEach-Object {
    if ($_ -match '^([^=\s]+)=(.*)$') {
      $key = $Matches[1].Trim()
      $value = $Matches[2].Trim()
      if ($key.Length -gt 0) {
        $kv[$key] = $value
      }
    }
  }
  return $kv
}

function Parse-DurationToSeconds {
  param([string]$Value)

  if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
  if ($Value -match '^(?<n>\d+)(?<u>ms|s|m|h)$') {
    $n = [int]$Matches["n"]
    $u = [string]$Matches["u"]
    if ($u -eq "ms") { return [int][Math]::Floor($n / 1000.0) }
    if ($u -eq "s") { return $n }
    if ($u -eq "m") { return $n * 60 }
    if ($u -eq "h") { return $n * 3600 }
  }
  return $null
}

function Parse-K6SlicePlanOrEmpty {
  param([string]$Path)

  $slices = New-Object System.Collections.Generic.List[Object]
  if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path $Path)) {
    return $slices
  }

  $lines = Get-Content $Path
  foreach ($line in $lines) {
    if ($line -match '^\[SLICE_START\]\s+slice_index=(?<idx>\d+)\s+target_rps=(?<rps>-?\d+)\s+duration=(?<dur>\d+(ms|s|m|h))\s+timestamp=(?<ts>\S+)$') {
      try {
        $idx = [int]$Matches["idx"]
        $target = [double]$Matches["rps"]
        $durRaw = [string]$Matches["dur"]
        $durSec = Parse-DurationToSeconds -Value $durRaw
        $start = [DateTimeOffset]::Parse([string]$Matches["ts"])
        if ($null -ne $durSec) {
          $slices.Add([pscustomobject]@{
            slice_index = $idx
            target_rps = $target
            duration_sec = [int]$durSec
            duration_raw = $durRaw
            start_ts = [int64][Math]::Floor($start.ToUnixTimeSeconds())
            end_ts = [int64][Math]::Floor($start.ToUnixTimeSeconds() + [int64]$durSec)
          })
        }
      } catch {
        continue
      }
    }
  }
  return $slices
}

function Build-HoldWindowsOrEmpty {
  param(
    [object]$Slices,
    [int64]$RunStartSec,
    [int64]$RunEndSec
  )

  $windows = New-Object System.Collections.Generic.List[Object]
  $sliceArray = To-ObjectArray -Value $Slices
  if ((Get-SafeCount $sliceArray) -eq 0) { return $windows }

  foreach ($s in $sliceArray) {
    try {
      $target = [double]$s.target_rps
      $durSec = [int]$s.duration_sec
      $startTs = [int64]$s.start_ts
      $endTs = [int64]$s.end_ts
      # LT-002E hold phase: target > 0 and long plateau segments.
      if ($target -gt 0 -and $durSec -ge 120) {
        $clampedStart = [Math]::Max($RunStartSec, $startTs)
        $clampedEnd = [Math]::Min($RunEndSec, $endTs)
        if ($clampedEnd -gt $clampedStart) {
          $windows.Add([pscustomobject]@{
            slice_index = [int]$s.slice_index
            target_rps = $target
            duration_sec = [int]($clampedEnd - $clampedStart)
            start_ts = [int64]$clampedStart
            end_ts = [int64]$clampedEnd
          })
        }
      }
    } catch {
      continue
    }
  }
  return $windows
}

function Filter-PointsByWindows {
  param(
    [object]$Points,
    [object]$Windows
  )

  $pointArray = To-ObjectArray -Value $Points
  $windowArray = To-ObjectArray -Value $Windows
  $out = New-Object System.Collections.Generic.List[Object]
  if ((Get-SafeCount $pointArray) -eq 0) { return $out }
  if ((Get-SafeCount $windowArray) -eq 0) { return $out }

  foreach ($p in $pointArray) {
    $ts = [int64]$p.ts
    foreach ($w in $windowArray) {
      $ws = [int64]$w.start_ts
      $we = [int64]$w.end_ts
      if ($ts -ge $ws -and $ts -lt $we) {
        $out.Add($p)
        break
      }
    }
  }
  return $out
}

function Find-ConsecutiveDurationSecFromPoints {
  param(
    [object]$Points,
    [double]$Threshold,
    [string]$Operator = "gt"
  )

  $pointArray = To-ObjectArray -Value $Points
  if ((Get-SafeCount $pointArray) -lt 2) { return 0 }

  $maxSec = 0
  $curSec = 0
  for ($i = 1; $i -lt (Get-SafeCount $pointArray); $i++) {
    $prev = $pointArray[$i - 1]
    $curr = $pointArray[$i]
    $v = [double]$curr.value
    $dt = [int][Math]::Max(0, ([int64]$curr.ts - [int64]$prev.ts))
    if ($dt -le 0) { continue }

    $cond = $false
    if ($Operator -eq "gt") { $cond = ($v -gt $Threshold) }
    elseif ($Operator -eq "ge") { $cond = ($v -ge $Threshold) }
    elseif ($Operator -eq "lt") { $cond = ($v -lt $Threshold) }
    elseif ($Operator -eq "le") { $cond = ($v -le $Threshold) }

    if ($cond) {
      $curSec += $dt
      if ($curSec -gt $maxSec) { $maxSec = $curSec }
    } else {
      $curSec = 0
    }
  }
  return $maxSec
}

function Find-MaxMonotonicIncreaseDurationSec {
  param([object]$Points)

  $pointArray = To-ObjectArray -Value $Points
  if ((Get-SafeCount $pointArray) -lt 2) { return 0 }

  $maxSec = 0
  $curSec = 0
  for ($i = 1; $i -lt (Get-SafeCount $pointArray); $i++) {
    $prev = $pointArray[$i - 1]
    $curr = $pointArray[$i]
    $dt = [int][Math]::Max(0, ([int64]$curr.ts - [int64]$prev.ts))
    if ($dt -le 0) { continue }
    if ([double]$curr.value -ge [double]$prev.value) {
      $curSec += $dt
      if ($curSec -gt $maxSec) { $maxSec = $curSec }
    } else {
      $curSec = 0
    }
  }
  return $maxSec
}

function Resolve-K6SummaryPath {
  param([string]$Dir, [string]$Id, [string]$Preferred)

  if (-not [string]::IsNullOrWhiteSpace($Preferred)) {
    if (Test-Path $Preferred) {
      return [System.IO.Path]::GetFullPath($Preferred)
    }
    return $null
  }

  if (-not (Test-Path $Dir)) {
    throw "Run directory not found: $Dir"
  }

  $matches = @(Get-ChildItem -Path $Dir -File | Where-Object { $_.Name -match ("-" + [regex]::Escape($Id) + "\\.log$") })
  if ((Get-SafeCount $matches) -eq 0) {
    return $null
  }

  return $matches[0].FullName
}

function Invoke-PromQueryRange {
  param(
    [string]$BaseUrl,
    [string]$Query,
    [int64]$StartSec,
    [int64]$EndSec,
    [string]$Step
  )

  $uri = "{0}/api/v1/query_range?query={1}&start={2}&end={3}&step={4}" -f `
    $BaseUrl.TrimEnd('/'), [uri]::EscapeDataString($Query), $StartSec, $EndSec, [uri]::EscapeDataString($Step)

  $response = Invoke-RestMethod -UseBasicParsing -Uri $uri -Method Get
  if ($null -eq $response -or $response.status -ne "success") {
    throw "Prometheus query_range failed: $Query"
  }

  return $response.data.result
}

function Invoke-PromQuery {
  param(
    [string]$BaseUrl,
    [string]$Query,
    [int64]$TimeSec
  )

  $uri = "{0}/api/v1/query?query={1}&time={2}" -f `
    $BaseUrl.TrimEnd('/'), [uri]::EscapeDataString($Query), $TimeSec

  $response = Invoke-RestMethod -UseBasicParsing -Uri $uri -Method Get
  if ($null -eq $response -or $response.status -ne "success") {
    throw "Prometheus query failed: $Query"
  }

  return $response.data.result
}

function Parse-RangeSeries {
  param([object]$Result)

  $points = New-Object System.Collections.Generic.List[Object]
  if ((Get-SafeCount $Result) -eq 0) {
    return $points
  }

  # Queries are intentionally aggregated with sum()/quantile to return one series.
  $resultArray = @($Result)
  $series = $resultArray[0]
  if ($null -eq $series.values) {
    return $points
  }

  foreach ($row in @($series.values)) {
    $rowArray = @($row)
    if ((Get-SafeCount $rowArray) -lt 2) { continue }
    $ts = [double]$rowArray[0]
    $raw = [string]$rowArray[1]
    if ($raw -eq "NaN" -or $raw -eq "nan" -or $raw -eq "+Inf" -or $raw -eq "-Inf") { continue }

    try {
      $v = [double]::Parse($raw, [Globalization.CultureInfo]::InvariantCulture)
      $points.Add([pscustomobject]@{
        ts = [int64][Math]::Floor($ts)
        value = [double]$v
      })
    } catch {
      continue
    }
  }

  return $points
}

function Get-Percentile {
  param(
    [double[]]$Values,
    [double]$Quantile
  )

  if ((Get-SafeCount $Values) -eq 0) { return $null }
  $sorted = $Values | Sort-Object
  if ((Get-SafeCount $sorted) -eq 1) { return [double]$sorted[0] }

  $index = ((Get-SafeCount $sorted) - 1) * $Quantile
  $lower = [int][Math]::Floor($index)
  $upper = [int][Math]::Ceiling($index)

  if ($lower -eq $upper) {
    return [double]$sorted[$lower]
  }

  $weight = $index - $lower
  return [double]$sorted[$lower] + (([double]$sorted[$upper] - [double]$sorted[$lower]) * $weight)
}

function Build-Stats {
  param([System.Collections.Generic.List[Object]]$Points)

  if ((Get-SafeCount $Points) -eq 0) {
    return [ordered]@{
      samples = 0
      first = $null
      last = $null
      min = $null
      max = $null
      avg = $null
      p95 = $null
      p99 = $null
      slope_per_sec = $null
    }
  }

  $vals = @($Points | ForEach-Object { [double]$_.value })
  $first = [double]$vals[0]
  $last = [double]$vals[(Get-SafeCount $vals) - 1]
  $min = ($vals | Measure-Object -Minimum).Minimum
  $max = ($vals | Measure-Object -Maximum).Maximum
  $avg = ($vals | Measure-Object -Average).Average
  $p95 = Get-Percentile -Values $vals -Quantile 0.95
  $p99 = Get-Percentile -Values $vals -Quantile 0.99

  $firstTs = [double]$Points[0].ts
  $lastTs = [double]$Points[(Get-SafeCount $Points) - 1].ts
  $dt = $lastTs - $firstTs
  $slope = $null
  if ($dt -gt 0) {
    $slope = ($last - $first) / $dt
  }

  return [ordered]@{
    samples = (Get-SafeCount $Points)
    first = [double]$first
    last = [double]$last
    min = [double]$min
    max = [double]$max
    avg = [double]$avg
    p95 = $p95
    p99 = $p99
    slope_per_sec = $slope
  }
}

function Load-JsonFileOrNull {
  param([string]$Path)

  if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path $Path)) {
    return $null
  }

  try {
    return (Get-Content $Path -Raw | ConvertFrom-Json)
  } catch {
    return $null
  }
}

function Resolve-Scenario {
  param([string]$InputScenario, [string]$Id)

  if (-not [string]::IsNullOrWhiteSpace($InputScenario)) {
    return $InputScenario.ToUpperInvariant()
  }

  $u = $Id.ToUpperInvariant()
  if ($u.StartsWith("LT-002E")) { return "LT-002E" }
  if ($u.StartsWith("LT-003")) { return "LT-003" }
  if ($u.StartsWith("LT-002")) { return "LT-002" }
  if ($u.StartsWith("LT-001")) { return "LT-001" }
  return "UNKNOWN"
}

function Resolve-Rules {
  param([object]$RulesJson, [string]$ScenarioName)

  if ($null -eq $RulesJson) {
    return $null
  }

  $base = $RulesJson.default
  $scenarioRules = $null
  if ($RulesJson.PSObject.Properties.Name -contains $ScenarioName) {
    $scenarioRules = $RulesJson.$ScenarioName
  }

  if ($null -eq $base -and $null -eq $scenarioRules) {
    return $null
  }

  $resolved = [ordered]@{}
  if ($null -ne $base) {
    foreach ($p in $base.PSObject.Properties) {
      $resolved[$p.Name] = $p.Value
    }
  }
  if ($null -ne $scenarioRules) {
    foreach ($p in $scenarioRules.PSObject.Properties) {
      $resolved[$p.Name] = $p.Value
    }
  }
  return $resolved
}

function Find-ConsecutiveDurationSec {
  param(
    [object]$Values,
    [int]$StepSeconds,
    [double]$Threshold
  )

  $valueArray = @()
  if ($null -ne $Values) {
    if ($Values -is [System.Collections.IEnumerable] -and -not ($Values -is [string])) {
      foreach ($v in $Values) {
        try {
          $valueArray += [double]$v
        } catch {
          continue
        }
      }
    } else {
      try {
        $valueArray += [double]$Values
      } catch {
      }
    }
  }

  if ((Get-SafeCount $valueArray) -eq 0) { return 0 }
  $maxConsecutive = 0
  $current = 0

  foreach ($v in $valueArray) {
    if ($v -gt $Threshold) {
      $current += $StepSeconds
      if ($current -gt $maxConsecutive) {
        $maxConsecutive = $current
      }
    } else {
      $current = 0
    }
  }

  return $maxConsecutive
}

function Read-DrainTimeSecOrNull {
  param([string]$Dir, [DateTimeOffset]$RunEnd)

  $path = Join-Path $Dir "post_run_drain.samples.jsonl"
  if (-not (Test-Path $path)) {
    return $null
  }

  $lines = @(Get-Content $path | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
  if ((Get-SafeCount $lines) -eq 0) {
    return $null
  }

  foreach ($line in $lines) {
    try {
      $obj = $line | ConvertFrom-Json
      if ($obj.drained -eq $true -and $obj.timestamp) {
        $drainedAt = [DateTimeOffset]::Parse([string]$obj.timestamp)
        $delta = ($drainedAt - $RunEnd).TotalSeconds
        if ($delta -lt 0) { $delta = 0 }
        return [int][Math]::Round($delta)
      }
    } catch {
      continue
    }
  }

  return $null
}

function Detect-CounterResets {
  param([object]$Points)

  $pointArray = To-ObjectArray -Value $Points
  if ((Get-SafeCount $pointArray) -lt 2) { return $false }
  for ($i = 1; $i -lt (Get-SafeCount $pointArray); $i++) {
    if ([double]$pointArray[$i].value -lt [double]$pointArray[$i - 1].value) {
      return $true
    }
  }
  return $false
}

function Build-AlignedRatioPoints {
  param(
    [object]$NumeratorPoints,
    [object]$DenominatorPoints
  )

  $num = To-ObjectArray -Value $NumeratorPoints
  $den = To-ObjectArray -Value $DenominatorPoints
  $out = New-Object System.Collections.Generic.List[Object]
  $pairCount = [Math]::Min((Get-SafeCount $num), (Get-SafeCount $den))
  for ($i = 0; $i -lt $pairCount; $i++) {
    $n = [double]$num[$i].value
    $d = [double]$den[$i].value
    $ts = [int64]$num[$i].ts
    $ratio = 0.0
    if ($d -gt 0.000001) {
      $ratio = [double]($n / $d)
    }
    $out.Add([pscustomobject]@{
      ts = $ts
      value = $ratio
    })
  }
  return $out
}

$scenarioName = Resolve-Scenario -InputScenario $Scenario -Id $TestId
Write-ParamDiagnostics -Name "TestId" -Value $TestId
Write-ParamDiagnostics -Name "Scenario" -Value $Scenario
Write-ParamDiagnostics -Name "PrometheusBaseUrl" -Value $PrometheusBaseUrl
Write-ParamDiagnostics -Name "ResultsRoot" -Value $ResultsRoot
Write-ParamDiagnostics -Name "K6ResultsRoot" -Value $K6ResultsRoot
Write-ParamDiagnostics -Name "RunDir" -Value $RunDir
Write-ParamDiagnostics -Name "K6SummaryPath" -Value $K6SummaryPath
Write-ParamDiagnostics -Name "StartTime" -Value $StartTime
Write-ParamDiagnostics -Name "EndTime" -Value $EndTime
Write-ParamDiagnostics -Name "StepSec" -Value $StepSec
Write-ParamDiagnostics -Name "BaselinePath" -Value $BaselinePath
Write-ParamDiagnostics -Name "RulesPath" -Value $RulesPath
Write-ParamDiagnostics -Name "Profile" -Value $Profile

$RunDir = Resolve-RunDirectoryOrThrow -InputRunDir $RunDir -InputK6ResultsRoot $K6ResultsRoot -Id $TestId

$resolvedSummaryPath = Resolve-K6SummaryPath -Dir $RunDir -Id $TestId -Preferred $K6SummaryPath
$k6Kv = @{}
$k6SummaryMissing = $false
if ($null -eq $resolvedSummaryPath) {
  $k6SummaryMissing = $true
  Write-Warning ("k6 summary log not found for test-id={0}. Continuing with limited metadata." -f $TestId)
} else {
  $K6SummaryPath = [System.IO.Path]::GetFullPath($resolvedSummaryPath)
  $k6Kv = Parse-KeyValueSummary -Path $K6SummaryPath
}

$runEnd = $null
$runStart = $null

if (-not [string]::IsNullOrWhiteSpace($StartTime)) {
  $runStart = Convert-FromIsoOrThrow -Value $StartTime -FieldName "t_start"
}
if (-not [string]::IsNullOrWhiteSpace($EndTime)) {
  $runEnd = Convert-FromIsoOrThrow -Value $EndTime -FieldName "t_end"
}

if ($null -eq $runEnd) {
  if ($k6Kv.ContainsKey("generated_at")) {
    $runEnd = [DateTimeOffset]::Parse($k6Kv["generated_at"])
  } else {
    throw "EndTime not provided and generated_at not found in k6 summary."
  }
}

if ($null -eq $runStart) {
  if ($k6Kv.ContainsKey("duration_ms")) {
    $ms = [double]$k6Kv["duration_ms"]
    $runStart = $runEnd.AddMilliseconds(-1.0 * $ms)
  } else {
    throw "StartTime not provided and duration_ms not found in k6 summary."
  }
}

if ($runEnd -le $runStart) {
  throw "Invalid run window: t_end <= t_start"
}

$startSec = To-UnixSec -Dt $runStart
$endSec = To-UnixSec -Dt $runEnd
$step = "${StepSec}s"

$slicePlan = Parse-K6SlicePlanOrEmpty -Path $K6SummaryPath
$holdWindows = Build-HoldWindowsOrEmpty -Slices $slicePlan -RunStartSec $startSec -RunEndSec $endSec

$queries = @(
  [ordered]@{ id = "ingest_rps"; type = "range"; unit = "rps"; query = 'sum(rate(auth_error_ingest_total{result="success"}[1m]))' },
  [ordered]@{ id = "http_server_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/auth-errors"}[1m])))' },
  [ordered]@{ id = "http_server_p99_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/auth-errors"}[1m])))' },
  [ordered]@{ id = "client_event_to_consume_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_client_event_to_consume_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"}[1m])))' },
  [ordered]@{ id = "client_event_to_consume_p99_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.99, sum by (le) (rate(auth_error_client_event_to_consume_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"}[1m])))' },
  [ordered]@{ id = "client_event_to_consume_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_client_event_to_consume_seconds_max{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"})' },
  [ordered]@{ id = "ingest_to_consume_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_ingest_to_consume_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"}[1m])))' },
  [ordered]@{ id = "ingest_to_consume_p99_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.99, sum by (le) (rate(auth_error_ingest_to_consume_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"}[1m])))' },
  [ordered]@{ id = "ingest_to_consume_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_ingest_to_consume_seconds_max{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"})' },
  [ordered]@{ id = "recorded_consumer_claim_setup_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_recorded_consumer_claim_setup_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"}[1m])))' },
  [ordered]@{ id = "recorded_consumer_claim_setup_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_recorded_consumer_claim_setup_seconds_max{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"})' },
  [ordered]@{ id = "recorded_consumer_handler_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_recorded_consumer_handler_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"}[1m])))' },
  [ordered]@{ id = "recorded_consumer_handler_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_recorded_consumer_handler_seconds_max{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"})' },
  [ordered]@{ id = "recorded_consumer_post_handler_completion_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_recorded_consumer_post_handler_completion_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"}[1m])))' },
  [ordered]@{ id = "recorded_consumer_post_handler_completion_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_recorded_consumer_post_handler_completion_seconds_max{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"})' },
  [ordered]@{ id = "recorded_handler_payload_parse_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_recorded_handler_payload_parse_seconds_bucket{event_type="auth.error.recorded.v1"}[1m])))' },
  [ordered]@{ id = "recorded_handler_payload_parse_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_recorded_handler_payload_parse_seconds_max{event_type="auth.error.recorded.v1"})' },
  [ordered]@{ id = "recorded_handler_auth_error_lookup_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_recorded_handler_auth_error_lookup_seconds_bucket{event_type="auth.error.recorded.v1"}[1m])))' },
  [ordered]@{ id = "recorded_handler_auth_error_lookup_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_recorded_handler_auth_error_lookup_seconds_max{event_type="auth.error.recorded.v1"})' },
  [ordered]@{ id = "recorded_handler_idempotency_guard_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_recorded_handler_idempotency_guard_seconds_bucket{event_type="auth.error.recorded.v1"}[1m])))' },
  [ordered]@{ id = "recorded_handler_idempotency_guard_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_recorded_handler_idempotency_guard_seconds_max{event_type="auth.error.recorded.v1"})' },
  [ordered]@{ id = "recorded_handler_outbox_enqueue_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_recorded_handler_outbox_enqueue_seconds_bucket{event_type="auth.error.recorded.v1"}[1m])))' },
  [ordered]@{ id = "recorded_handler_outbox_enqueue_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_recorded_handler_outbox_enqueue_seconds_max{event_type="auth.error.recorded.v1"})' },
  [ordered]@{ id = "outbox_payload_serialize_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_outbox_payload_serialize_seconds_bucket{event_type="auth.error.recorded.v1"}[1m])))' },
  [ordered]@{ id = "outbox_payload_serialize_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_outbox_payload_serialize_seconds_max{event_type="auth.error.recorded.v1"})' },
  [ordered]@{ id = "outbox_upsert_returning_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_outbox_upsert_returning_seconds_bucket{event_type="auth.error.analysis.requested.v1",queue="auth.error.recorded.q"}[1m])))' },
  [ordered]@{ id = "outbox_upsert_returning_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_outbox_upsert_returning_seconds_max{event_type="auth.error.analysis.requested.v1",queue="auth.error.recorded.q"})' },
  [ordered]@{ id = "processed_message_ensure_row_exists_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_processed_message_ensure_row_exists_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"}[1m])))' },
  [ordered]@{ id = "processed_message_ensure_row_exists_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_processed_message_ensure_row_exists_seconds_max{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"})' },
  [ordered]@{ id = "processed_message_claim_processing_update_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_processed_message_claim_processing_update_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"}[1m])))' },
  [ordered]@{ id = "processed_message_claim_processing_update_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_processed_message_claim_processing_update_seconds_max{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"})' },
  [ordered]@{ id = "processed_message_mark_done_p95_seconds"; type = "range"; unit = "seconds"; query = 'histogram_quantile(0.95, sum by (le) (rate(auth_error_processed_message_mark_done_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"}[1m])))' },
  [ordered]@{ id = "processed_message_mark_done_max_seconds"; type = "range"; unit = "seconds"; query = 'max(auth_error_processed_message_mark_done_seconds_max{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"})' },
  [ordered]@{ id = "ingest_to_consume_overflow_ratio_120s"; type = "range"; unit = "ratio"; query = 'clamp_min((sum(rate(auth_error_ingest_to_consume_seconds_count{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"}[1m])) - sum(rate(auth_error_ingest_to_consume_seconds_bucket{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success",le="120.0"}[1m]))), 0) / clamp_min(sum(rate(auth_error_ingest_to_consume_seconds_count{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"}[1m])), 1e-9)' },
  [ordered]@{ id = "publish_rps"; type = "range"; unit = "rps"; query = 'sum(rate(auth_error_publish_total{event_type="auth.error.recorded.v1",result="success"}[1m]))' },
  [ordered]@{ id = "consume_rps"; type = "range"; unit = "rps"; query = 'sum(rate(auth_error_consume_total{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"}[1m]))' },
  [ordered]@{ id = "retry_enqueue_rps"; type = "range"; unit = "rps"; query = 'sum(rate(auth_error_retry_enqueue_total{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"}[1m]))' },
  [ordered]@{ id = "retry_pressure_ratio"; type = "range"; unit = "ratio"; query = 'sum(rate(auth_error_retry_enqueue_total{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q"}[1m])) / clamp_min(sum(rate(auth_error_consume_total{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"}[1m])), 1e-9)' },
  [ordered]@{ id = "outbox_age_p95_ms"; type = "range"; unit = "ms"; query = 'max(auth_error_outbox_age_p95)' },
  [ordered]@{ id = "outbox_age_p99_ms"; type = "range"; unit = "ms"; query = 'max(auth_error_outbox_age_p99)' },
  [ordered]@{ id = "outbox_age_slope_ms_per_10s"; type = "range"; unit = "ms_per_10s"; query = 'max(auth_error_outbox_age_slope_ms_per_10s)' },
  [ordered]@{ id = "rabbit_ready_depth"; type = "range"; unit = "messages"; query = 'sum(rabbitmq_detailed_queue_messages_ready{queue="auth.error.recorded.q"})' },
  [ordered]@{ id = "rabbit_unacked_depth"; type = "range"; unit = "messages"; query = 'sum(rabbitmq_detailed_queue_messages_unacked{queue="auth.error.recorded.q"})' },
  [ordered]@{ id = "rabbit_retry_depth"; type = "range"; unit = "messages"; query = 'sum(rabbitmq_detailed_queue_messages_ready{queue=~".*\\.retry\\..*"}) + sum(rabbitmq_detailed_queue_messages_unacked{queue=~".*\\.retry\\..*"})' },
  [ordered]@{ id = "rabbit_dlq_depth"; type = "range"; unit = "messages"; query = 'sum(rabbitmq_detailed_queue_messages_ready{queue=~".*\\.dlq"}) + sum(rabbitmq_detailed_queue_messages_unacked{queue=~".*\\.dlq"})' },
  [ordered]@{ id = "hikari_active"; type = "range"; unit = "connections"; query = 'max(hikaricp_connections_active)' },
  [ordered]@{ id = "hikari_pending"; type = "range"; unit = "connections"; query = 'max(hikaricp_connections_pending)' },
  [ordered]@{ id = "hikari_max"; type = "range"; unit = "connections"; query = 'max(hikaricp_connections_max)' },
  [ordered]@{ id = "runtime_hikari_max_pool_size"; type = "range"; unit = "count"; query = 'max(auth_error_runtime_hikari_max_pool_size)' },
  [ordered]@{ id = "runtime_consumer_concurrency"; type = "range"; unit = "count"; query = 'max(auth_error_runtime_consumer_concurrency)' },
  [ordered]@{ id = "runtime_consumer_max_concurrency"; type = "range"; unit = "count"; query = 'max(auth_error_runtime_consumer_max_concurrency)' },
  [ordered]@{ id = "runtime_consumer_prefetch"; type = "range"; unit = "count"; query = 'max(auth_error_runtime_consumer_prefetch)' },
  [ordered]@{ id = "runtime_active_profiles_count"; type = "range"; unit = "count"; query = 'sum(auth_error_runtime_profile_info)' },
  [ordered]@{ id = "server_5xx_rate"; type = "range"; unit = "ratio"; query = '(sum(rate(http_server_requests_seconds_count{uri="/api/auth-errors",status=~"5.."}[1m])) or vector(0)) / clamp_min((sum(rate(http_server_requests_seconds_count{uri="/api/auth-errors"}[1m])) or vector(0)), 1e-9)' },
  [ordered]@{ id = "publish_total_counter"; type = "range"; unit = "count"; query = 'sum(auth_error_publish_total{event_type="auth.error.recorded.v1",result="success"})' },
  [ordered]@{ id = "consume_total_counter"; type = "range"; unit = "count"; query = 'sum(auth_error_consume_total{event_type="auth.error.recorded.v1",queue="auth.error.recorded.q",result="success"})' }
)

$seriesMap = @{}
$statsMap = @{}
$queryEcho = @()
$anomalies = New-Object System.Collections.Generic.List[string]

foreach ($q in $queries) {
  try {
    $raw = Invoke-PromQueryRange -BaseUrl $PrometheusBaseUrl -Query $q.query -StartSec $startSec -EndSec $endSec -Step $step
    $points = Parse-RangeSeries -Result $raw
    $stats = Build-Stats -Points $points

    $seriesMap[$q.id] = @($points)
    $statsMap[$q.id] = $stats

    $queryEcho += [pscustomobject]@{
      id = $q.id
      promql = $q.query
      unit = $q.unit
      samples = $stats.samples
      first = $stats.first
      last = $stats.last
      avg = $stats.avg
      max = $stats.max
    }

    if ([int]$stats.samples -eq 0) {
      Write-Warning ("No data for query '{0}' in run window [{1} ~ {2}]." -f $q.id, $runStart.ToUniversalTime().ToString("o"), $runEnd.ToUniversalTime().ToString("o"))
      $anomalies.Add("query_no_data:$($q.id)")
    }
  } catch {
    Write-Warning ("Query failed for '{0}': {1}" -f $q.id, $_.Exception.Message)
    $anomalies.Add("query_failed:$($q.id):$($_.Exception.Message)")
    $seriesMap[$q.id] = @()
    $statsMap[$q.id] = Build-Stats -Points (New-Object System.Collections.Generic.List[Object])
  }
}

# k6 summary values (if present) are included for traceability.
$k6ErrorRate = $null
if ($k6Kv.ContainsKey("http_req_failed_rate")) {
  $k6ErrorRate = [double]$k6Kv["http_req_failed_rate"]
}

# Counter reset anomaly detection.
if (Detect-CounterResets -Points @($seriesMap["publish_total_counter"])) {
  $anomalies.Add("counter_reset_detected:publish_total_counter")
}
if (Detect-CounterResets -Points @($seriesMap["consume_total_counter"])) {
  $anomalies.Add("counter_reset_detected:consume_total_counter")
}

# Coarse sampling anomaly (too few points can hide sustained patterns).
$expectedPoints = [int][Math]::Max(1, [Math]::Floor((($endSec - $startSec) / [double]$StepSec) + 1))
foreach ($id in @("publish_rps", "consume_rps", "outbox_age_slope_ms_per_10s")) {
  $actual = [int]$statsMap[$id].samples
  if ($actual -lt ([int][Math]::Floor($expectedPoints * 0.8))) {
    $anomalies.Add("sample_coverage_low:${id}:expected=${expectedPoints},actual=${actual}")
  }
}

$baseline = Load-JsonFileOrNull -Path $BaselinePath
$rulesJson = Load-JsonFileOrNull -Path $RulesPath
$rules = Resolve-Rules -RulesJson $rulesJson -ScenarioName $scenarioName

# Derived series for sustained conditions.
$publishPts = @($seriesMap["publish_rps"])
$consumePts = @($seriesMap["consume_rps"])
$pairCount = [Math]::Min((Get-SafeCount $publishPts), (Get-SafeCount $consumePts))
$mismatchPoints = New-Object System.Collections.Generic.List[Object]
for ($i = 0; $i -lt $pairCount; $i++) {
  $p = [double]$publishPts[$i].value
  $c = [double]$consumePts[$i].value
  $ts = [int64]$publishPts[$i].ts
  $gap = 0.0
  if ($p -gt 0.000001) {
    $gap = [double](($p - $c) / $p)
  }
  $mismatchPoints.Add([pscustomobject]@{
    ts = $ts
    value = $gap
  })
}

$drainTimeSec = Read-DrainTimeSecOrNull -Dir $RunDir -RunEnd $runEnd

$checks = New-Object System.Collections.Generic.List[Object]
$failed = $false
$unknown = $false

function Add-Check {
  param(
    [string]$Name,
    [string]$Status,
    [string]$Detail
  )

  $checks.Add([pscustomobject]@{
    name = $Name
    status = $Status
    detail = $Detail
  })

  if ($Status -eq "FAIL") { $script:failed = $true }
  if ($Status -eq "UNKNOWN") { $script:unknown = $true }
}

if ($null -eq $rules) {
  Add-Check -Name "rules_loaded" -Status "UNKNOWN" -Detail "No rules loaded from $RulesPath"
} else {
  if ($k6SummaryMissing) {
    Add-Check -Name "k6_summary_present" -Status "UNKNOWN" -Detail "k6 summary log missing; using provided run window only"
  } else {
    Add-Check -Name "k6_summary_present" -Status "PASS" -Detail ("k6 summary log found: {0}" -f $K6SummaryPath)
  }

  # 1) Error rate threshold.
  $errorRateMax = [double]$rules["error_rate_max"]
  $server5xxMax = [double]$rules["server_5xx_rate_max"]
  if ($null -ne $k6ErrorRate) {
    if ($k6ErrorRate -le $errorRateMax) {
      Add-Check -Name "error_rate" -Status "PASS" -Detail ("k6 http_req_failed_rate={0:N6} <= {1:N6}" -f $k6ErrorRate, $errorRateMax)
    } else {
      Add-Check -Name "error_rate" -Status "FAIL" -Detail ("k6 http_req_failed_rate={0:N6} > {1:N6}" -f $k6ErrorRate, $errorRateMax)
    }
  } else {
    $server5xx = $statsMap["server_5xx_rate"].max
    if ($null -eq $server5xx) {
      Add-Check -Name "error_rate" -Status "UNKNOWN" -Detail "k6 error rate unavailable and server 5xx rate unavailable"
    } elseif ([double]$server5xx -le $server5xxMax) {
      Add-Check -Name "error_rate" -Status "PASS" -Detail ("server_5xx_rate max={0:N6} <= {1:N6}" -f [double]$server5xx, $server5xxMax)
    } else {
      Add-Check -Name "error_rate" -Status "FAIL" -Detail ("server_5xx_rate max={0:N6} > {1:N6}" -f [double]$server5xx, $server5xxMax)
    }
  }

  # Use hold windows for LT-002E by default.
  $useHoldOnly = ($scenarioName -eq "LT-002E" -and (Get-SafeCount $holdWindows) -gt 0)
  if ($scenarioName -eq "LT-002E" -and -not $useHoldOnly) {
    Add-Check -Name "hold_windows_detected" -Status "UNKNOWN" -Detail "LT-002E hold windows not detected; fallback to full run"
  } elseif ($useHoldOnly) {
    Add-Check -Name "hold_windows_detected" -Status "PASS" -Detail ("hold windows detected: {0}" -f (Get-SafeCount $holdWindows))
  }

  $pipelineP95EvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $seriesMap["ingest_to_consume_p95_seconds"] -Windows $holdWindows } else { To-ObjectArray -Value $seriesMap["ingest_to_consume_p95_seconds"] }
  $pipelineP99EvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $seriesMap["ingest_to_consume_p99_seconds"] -Windows $holdWindows } else { To-ObjectArray -Value $seriesMap["ingest_to_consume_p99_seconds"] }
  $outboxSlopeEvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $seriesMap["outbox_age_slope_ms_per_10s"] -Windows $holdWindows } else { To-ObjectArray -Value $seriesMap["outbox_age_slope_ms_per_10s"] }
  $mismatchEvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $mismatchPoints -Windows $holdWindows } else { To-ObjectArray -Value $mismatchPoints }
  $retryPressureEvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $seriesMap["retry_pressure_ratio"] -Windows $holdWindows } else { To-ObjectArray -Value $seriesMap["retry_pressure_ratio"] }
  $hikariActiveEvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $seriesMap["hikari_active"] -Windows $holdWindows } else { To-ObjectArray -Value $seriesMap["hikari_active"] }
  $hikariPendingEvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $seriesMap["hikari_pending"] -Windows $holdWindows } else { To-ObjectArray -Value $seriesMap["hikari_pending"] }
  $hikariMaxEvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $seriesMap["hikari_max"] -Windows $holdWindows } else { To-ObjectArray -Value $seriesMap["hikari_max"] }
  $rabbitReadyEvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $seriesMap["rabbit_ready_depth"] -Windows $holdWindows } else { To-ObjectArray -Value $seriesMap["rabbit_ready_depth"] }
  $rabbitUnackedEvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $seriesMap["rabbit_unacked_depth"] -Windows $holdWindows } else { To-ObjectArray -Value $seriesMap["rabbit_unacked_depth"] }
  $pipelineOverflowEvalPts = if ($useHoldOnly) { Filter-PointsByWindows -Points $seriesMap["ingest_to_consume_overflow_ratio_120s"] -Windows $holdWindows } else { To-ObjectArray -Value $seriesMap["ingest_to_consume_overflow_ratio_120s"] }

  $e2eNeedSec = if ($null -ne $rules["e2e_sustained_sec"]) { [int]$rules["e2e_sustained_sec"] } else { 60 }
  $outboxNeedSec = if ($null -ne $rules["outbox_slope_sustained_sec"]) { [int]$rules["outbox_slope_sustained_sec"] } else { 30 }
  $retryNeedSec = if ($null -ne $rules["retry_pressure_sustained_sec"]) { [int]$rules["retry_pressure_sustained_sec"] } else { 60 }
  $hikariNeedSec = if ($null -ne $rules["hikari_saturation_sustained_sec"]) { [int]$rules["hikari_saturation_sustained_sec"] } else { 15 }
  $readyNeedSec = if ($null -ne $rules["rabbit_ready_growth_sustained_sec"]) { [int]$rules["rabbit_ready_growth_sustained_sec"] } else { 60 }
  $unackedNeedSec = if ($null -ne $rules["unacked_saturation_sustained_sec"]) { [int]$rules["unacked_saturation_sustained_sec"] } else { 60 }
  $unackedRatioMax = if ($null -ne $rules["unacked_saturation_ratio_max"]) { [double]$rules["unacked_saturation_ratio_max"] } else { 0.8 }
  $retryRatioMax = if ($null -ne $rules["retry_pressure_ratio_max"]) { [double]$rules["retry_pressure_ratio_max"] } else { 0.1 }
  $overflowRatioMax = if ($null -ne $rules["e2e_overflow_ratio_max"]) { [double]$rules["e2e_overflow_ratio_max"] } else { 0.05 }
  $overflowNeedSec = if ($null -ne $rules["e2e_overflow_sustained_sec"]) { [int]$rules["e2e_overflow_sustained_sec"] } else { 60 }

  # 2) Baseline-relative p95/p99 sustained threshold (if baseline exists).
  if ($null -eq $baseline) {
    Add-Check -Name "ingest_to_consume_p95_vs_baseline_sustained" -Status "UNKNOWN" -Detail "baseline file missing"
    Add-Check -Name "ingest_to_consume_p99_vs_baseline_sustained" -Status "UNKNOWN" -Detail "baseline file missing"
  } else {
    $baseP95ms = if ($null -ne $baseline.baseline_ingest_to_consume_p95_ms) { [double]$baseline.baseline_ingest_to_consume_p95_ms } else { [double]$baseline.baseline_e2e_p95_ms }
    $baseP99ms = if ($null -ne $baseline.baseline_ingest_to_consume_p99_ms) { [double]$baseline.baseline_ingest_to_consume_p99_ms } else { [double]$baseline.baseline_e2e_p99_ms }
    $limit95 = $baseP95ms * [double]$rules["p95_baseline_multiplier_max"]
    $limit99 = $baseP99ms * [double]$rules["p99_baseline_multiplier_max"]

    if ((Get-SafeCount $pipelineP95EvalPts) -lt 2 -or $baseP95ms -le 0) {
      Add-Check -Name "ingest_to_consume_p95_vs_baseline_sustained" -Status "UNKNOWN" -Detail "missing eval points or baseline p95"
    } else {
      $over95Pts = New-Object System.Collections.Generic.List[Object]
      foreach ($p in $pipelineP95EvalPts) {
        $over95Pts.Add([pscustomobject]@{ ts = [int64]$p.ts; value = ([double]$p.value * 1000.0) })
      }
      $s95 = Find-ConsecutiveDurationSecFromPoints -Points $over95Pts -Threshold $limit95 -Operator "gt"
      $peak95 = ([double]$statsMap["ingest_to_consume_p95_seconds"].max) * 1000.0
      if ($s95 -ge $e2eNeedSec) {
        Add-Check -Name "ingest_to_consume_p95_vs_baseline_sustained" -Status "FAIL" -Detail ("ingest_to_consume_p95_ms > {0:N2} sustained {1}s (threshold {2}s), peak={3:N2}" -f $limit95, $s95, $e2eNeedSec, $peak95)
      } else {
        Add-Check -Name "ingest_to_consume_p95_vs_baseline_sustained" -Status "PASS" -Detail ("ingest_to_consume_p95_ms > {0:N2} sustained {1}s (< {2}s), peak={3:N2}" -f $limit95, $s95, $e2eNeedSec, $peak95)
      }
    }

    if ((Get-SafeCount $pipelineP99EvalPts) -lt 2 -or $baseP99ms -le 0) {
      Add-Check -Name "ingest_to_consume_p99_vs_baseline_sustained" -Status "UNKNOWN" -Detail "missing eval points or baseline p99"
    } else {
      $over99Pts = New-Object System.Collections.Generic.List[Object]
      foreach ($p in $pipelineP99EvalPts) {
        $over99Pts.Add([pscustomobject]@{ ts = [int64]$p.ts; value = ([double]$p.value * 1000.0) })
      }
      $s99 = Find-ConsecutiveDurationSecFromPoints -Points $over99Pts -Threshold $limit99 -Operator "gt"
      $peak99 = ([double]$statsMap["ingest_to_consume_p99_seconds"].max) * 1000.0
      if ($s99 -ge $e2eNeedSec) {
        Add-Check -Name "ingest_to_consume_p99_vs_baseline_sustained" -Status "FAIL" -Detail ("ingest_to_consume_p99_ms > {0:N2} sustained {1}s (threshold {2}s), peak={3:N2}" -f $limit99, $s99, $e2eNeedSec, $peak99)
      } else {
        Add-Check -Name "ingest_to_consume_p99_vs_baseline_sustained" -Status "PASS" -Detail ("ingest_to_consume_p99_ms > {0:N2} sustained {1}s (< {2}s), peak={3:N2}" -f $limit99, $s99, $e2eNeedSec, $peak99)
      }
    }
  }

  # 3) Backlog growth condition (sustained).
  $slopeMax = [double]$rules["outbox_slope_ms_per_10s_max"]
  if ((Get-SafeCount $outboxSlopeEvalPts) -lt 2) {
    Add-Check -Name "outbox_backlog_growth" -Status "UNKNOWN" -Detail "outbox slope metric missing"
  } else {
    $slopePeak = $statsMap["outbox_age_slope_ms_per_10s"].max
    $slopeSec = Find-ConsecutiveDurationSecFromPoints -Points $outboxSlopeEvalPts -Threshold $slopeMax -Operator "gt"
    if ($slopeSec -ge $outboxNeedSec) {
      Add-Check -Name "outbox_backlog_growth" -Status "FAIL" -Detail ("outbox_slope > {0:N2} sustained {1}s (threshold {2}s), peak={3:N2}" -f $slopeMax, $slopeSec, $outboxNeedSec, [double]$slopePeak)
    } else {
      Add-Check -Name "outbox_backlog_growth" -Status "PASS" -Detail ("outbox_slope > {0:N2} sustained {1}s (< {2}s), peak={3:N2}" -f $slopeMax, $slopeSec, $outboxNeedSec, [double]$slopePeak)
    }
  }

  # 4) Publish/consume mismatch sustained.
  $ratioMax = [double]$rules["publish_consume_gap_ratio_max"]
  $needSec = [int]$rules["publish_consume_sustained_sec"]
  if ((Get-SafeCount $mismatchEvalPts) -lt 2) {
    Add-Check -Name "publish_consume_mismatch" -Status "UNKNOWN" -Detail "missing publish/consume series"
  } else {
    $sustainedSec = Find-ConsecutiveDurationSecFromPoints -Points $mismatchEvalPts -Threshold $ratioMax -Operator "gt"
    $mismatchPeak = (($mismatchEvalPts | ForEach-Object { [double]$_.value }) | Measure-Object -Maximum).Maximum
    if ($sustainedSec -ge $needSec) {
      Add-Check -Name "publish_consume_mismatch" -Status "FAIL" -Detail ("gap_ratio > {0:P2} sustained {1}s (threshold {2}s), publish_peak={3:N3}" -f $ratioMax, $sustainedSec, $needSec, [double]$mismatchPeak)
    } else {
      Add-Check -Name "publish_consume_mismatch" -Status "PASS" -Detail ("gap_ratio > {0:P2} sustained {1}s (< {2}s)" -f $ratioMax, $sustainedSec, $needSec)
    }
  }

  # 5) Retry pressure sustained.
  if ((Get-SafeCount $retryPressureEvalPts) -lt 2) {
    Add-Check -Name "retry_pressure" -Status "UNKNOWN" -Detail "retry pressure metric missing"
  } else {
    $retrySec = Find-ConsecutiveDurationSecFromPoints -Points $retryPressureEvalPts -Threshold $retryRatioMax -Operator "gt"
    $retryPeak = $statsMap["retry_pressure_ratio"].max
    if ($retrySec -ge $retryNeedSec) {
      Add-Check -Name "retry_pressure" -Status "FAIL" -Detail ("retry/consume ratio > {0:P2} sustained {1}s (threshold {2}s), peak={3:P2}" -f $retryRatioMax, $retrySec, $retryNeedSec, [double]$retryPeak)
    } else {
      Add-Check -Name "retry_pressure" -Status "PASS" -Detail ("retry/consume ratio > {0:P2} sustained {1}s (< {2}s), peak={3:P2}" -f $retryRatioMax, $retrySec, $retryNeedSec, [double]$retryPeak)
    }
  }

  # 6) Hikari saturation correlation (pending > 0 and active == maxPool).
  $hikariPair = [Math]::Min([Math]::Min((Get-SafeCount $hikariActiveEvalPts), (Get-SafeCount $hikariPendingEvalPts)), (Get-SafeCount $hikariMaxEvalPts))
  if ($hikariPair -lt 2) {
    Add-Check -Name "hikari_pool_saturation" -Status "UNKNOWN" -Detail "hikari active/pending/max missing"
  } else {
    $satPts = New-Object System.Collections.Generic.List[Object]
    for ($i = 0; $i -lt $hikariPair; $i++) {
      $active = [double]$hikariActiveEvalPts[$i].value
      $pending = [double]$hikariPendingEvalPts[$i].value
      $maxPool = [double]$hikariMaxEvalPts[$i].value
      $sat = 0.0
      if ($pending -gt 0.0 -and [Math]::Abs($active - $maxPool) -le 0.0001) { $sat = 1.0 }
      $satPts.Add([pscustomobject]@{ ts = [int64]$hikariActiveEvalPts[$i].ts; value = $sat })
    }
    $hikariSatSec = Find-ConsecutiveDurationSecFromPoints -Points $satPts -Threshold 0.5 -Operator "gt"
    if ($hikariSatSec -ge $hikariNeedSec) {
      Add-Check -Name "hikari_pool_saturation" -Status "FAIL" -Detail ("pending>0 AND active==max sustained {0}s (threshold {1}s)" -f $hikariSatSec, $hikariNeedSec)
    } else {
      Add-Check -Name "hikari_pool_saturation" -Status "PASS" -Detail ("pending>0 AND active==max sustained {0}s (< {1}s)" -f $hikariSatSec, $hikariNeedSec)
    }
  }

  # 7) Rabbit ready monotonic growth with publish>consume lead.
  if ((Get-SafeCount $rabbitReadyEvalPts) -lt 2 -or (Get-SafeCount $mismatchEvalPts) -lt 2) {
    Add-Check -Name "rabbit_ready_growth" -Status "UNKNOWN" -Detail "rabbit ready or mismatch series missing"
  } else {
    $readyGrowSec = Find-MaxMonotonicIncreaseDurationSec -Points $rabbitReadyEvalPts
    $publishLeadSec = Find-ConsecutiveDurationSecFromPoints -Points $mismatchEvalPts -Threshold 0.0 -Operator "gt"
    if ($readyGrowSec -ge $readyNeedSec -and $publishLeadSec -ge $readyNeedSec) {
      Add-Check -Name "rabbit_ready_growth" -Status "FAIL" -Detail ("ready monotonic growth {0}s and publish>consume {1}s (threshold {2}s)" -f $readyGrowSec, $publishLeadSec, $readyNeedSec)
    } else {
      Add-Check -Name "rabbit_ready_growth" -Status "PASS" -Detail ("ready monotonic growth {0}s, publish>consume {1}s (threshold {2}s)" -f $readyGrowSec, $publishLeadSec, $readyNeedSec)
    }
  }

  # 8) Rabbit unacked saturation.
  $runtimeConc = $statsMap["runtime_consumer_concurrency"].max
  $runtimePrefetch = $statsMap["runtime_consumer_prefetch"].max
  $capacity = $null
  if ($null -ne $runtimeConc -and $null -ne $runtimePrefetch) {
    $capacity = [double]$runtimeConc * [double]$runtimePrefetch
  }
  if ($null -eq $capacity -or $capacity -le 0 -or (Get-SafeCount $rabbitUnackedEvalPts) -lt 2) {
    Add-Check -Name "rabbit_unacked_saturation" -Status "UNKNOWN" -Detail "missing runtime consumer capacity or unacked series"
  } else {
    $ratioPts = New-Object System.Collections.Generic.List[Object]
    foreach ($p in $rabbitUnackedEvalPts) {
      $ratioPts.Add([pscustomobject]@{
        ts = [int64]$p.ts
        value = ([double]$p.value / [double]$capacity)
      })
    }
    $unackedSec = Find-ConsecutiveDurationSecFromPoints -Points $ratioPts -Threshold $unackedRatioMax -Operator "gt"
    $unackedPeak = $statsMap["rabbit_unacked_depth"].max
    if ($unackedSec -ge $unackedNeedSec) {
      Add-Check -Name "rabbit_unacked_saturation" -Status "FAIL" -Detail ("unacked/capacity > {0:P2} sustained {1}s (threshold {2}s), peak_unacked={3:N3}, capacity={4:N3}" -f $unackedRatioMax, $unackedSec, $unackedNeedSec, [double]$unackedPeak, [double]$capacity)
    } else {
      Add-Check -Name "rabbit_unacked_saturation" -Status "PASS" -Detail ("unacked/capacity > {0:P2} sustained {1}s (< {2}s), peak_unacked={3:N3}, capacity={4:N3}" -f $unackedRatioMax, $unackedSec, $unackedNeedSec, [double]$unackedPeak, [double]$capacity)
    }
  }

  # 9) E2E overflow (histogram ceiling saturation hint).
  if ((Get-SafeCount $pipelineOverflowEvalPts) -lt 2) {
    Add-Check -Name "ingest_to_consume_histogram_overflow" -Status "UNKNOWN" -Detail "ingest_to_consume overflow ratio unavailable"
  } else {
    $overflowSec = Find-ConsecutiveDurationSecFromPoints -Points $pipelineOverflowEvalPts -Threshold $overflowRatioMax -Operator "gt"
    $overflowPeak = $statsMap["ingest_to_consume_overflow_ratio_120s"].max
    if ($overflowSec -ge $overflowNeedSec) {
      Add-Check -Name "ingest_to_consume_histogram_overflow" -Status "FAIL" -Detail ("ingest_to_consume_overflow_ratio_120s > {0:P2} sustained {1}s (threshold {2}s), peak={3:P2}" -f $overflowRatioMax, $overflowSec, $overflowNeedSec, [double]$overflowPeak)
    } else {
      Add-Check -Name "ingest_to_consume_histogram_overflow" -Status "PASS" -Detail ("ingest_to_consume_overflow_ratio_120s > {0:P2} sustained {1}s (< {2}s), peak={3:P2}" -f $overflowRatioMax, $overflowSec, $overflowNeedSec, [double]$overflowPeak)
    }
  }

  # 10) Drain time after cooldown.
  $drainLimit = [int]$rules["drain_time_sec_max"]
  if ($null -eq $drainTimeSec) {
    Add-Check -Name "drain_time" -Status "UNKNOWN" -Detail "post_run_drain.samples.jsonl missing or no drained sample"
  } elseif ([int]$drainTimeSec -le $drainLimit) {
    Add-Check -Name "drain_time" -Status "PASS" -Detail ("drain_time_sec={0} <= {1}" -f [int]$drainTimeSec, $drainLimit)
  } else {
    Add-Check -Name "drain_time" -Status "FAIL" -Detail ("drain_time_sec={0} > {1}" -f [int]$drainTimeSec, $drainLimit)
  }
}

$verdict = "PASS"
if ($failed) {
  $verdict = "FAIL"
} elseif ($unknown) {
  $verdict = "UNKNOWN"
}

$repoSha = "unknown"
try {
  $repoSha = (& git rev-parse HEAD).Trim()
} catch {
  $repoSha = "unknown"
}

$activeProfiles = @()
try {
  $profileSeries = Invoke-PromQuery -BaseUrl $PrometheusBaseUrl -Query 'auth_error_runtime_profile_info' -TimeSec $endSec
  foreach ($row in @(To-ObjectArray -Value $profileSeries)) {
    try {
      $metric = $row.metric
      if ($null -ne $metric -and $metric.profile) {
        $activeProfiles += [string]$metric.profile
      }
    } catch {
      continue
    }
  }
  $activeProfiles = @($activeProfiles | Sort-Object -Unique)
} catch {
}

$runtimeHikariMax = $statsMap["runtime_hikari_max_pool_size"].max
if ($null -eq $runtimeHikariMax) { $runtimeHikariMax = $statsMap["hikari_max"].max }
$runtimeConsumerConcurrency = $statsMap["runtime_consumer_concurrency"].max
$runtimeConsumerMaxConcurrency = $statsMap["runtime_consumer_max_concurrency"].max
$runtimeConsumerPrefetch = $statsMap["runtime_consumer_prefetch"].max

$resultDir = Join-Path $ResultsRoot $TestId
New-Item -ItemType Directory -Force -Path $resultDir | Out-Null
$resultDir = [System.IO.Path]::GetFullPath($resultDir)

$k6StdoutPath = Join-Path $RunDir "lt_002_slice_knee.stdout.log"
if (-not (Test-Path $k6StdoutPath)) {
  $stdoutCandidates = @(Get-ChildItem -Path $RunDir -Filter "*.stdout.log" -File -ErrorAction SilentlyContinue)
  if ((Get-SafeCount $stdoutCandidates) -gt 0) {
    $k6StdoutPath = $stdoutCandidates[0].FullName
  } else {
    $k6StdoutPath = ""
  }
}

$metadata = [ordered]@{
  test_id = $TestId
  scenario = $scenarioName
  profile = $Profile
  run_window = [ordered]@{
    t_start = $runStart.ToUniversalTime().ToString("o")
    t_end = $runEnd.ToUniversalTime().ToString("o")
    duration_sec = [int][Math]::Round(($runEnd - $runStart).TotalSeconds)
    step_sec = $StepSec
  }
  source_of_truth = "prometheus_snapshot_script"
  prometheus_base_url = $PrometheusBaseUrl
  git_sha = $repoSha
  hold_phase_windows = @(To-PlainArray -Value $holdWindows)
  runtime_settings = [ordered]@{
    active_profiles = @($activeProfiles)
    hikari_max_pool_size = $runtimeHikariMax
    consumer_concurrency = $runtimeConsumerConcurrency
    consumer_max_concurrency = $runtimeConsumerMaxConcurrency
    consumer_prefetch = $runtimeConsumerPrefetch
  }
  baseline_path = if ($null -eq $baseline) { $null } else { [System.IO.Path]::GetFullPath($BaselinePath) }
  rules_path = if ($null -eq $rulesJson) { $null } else { [System.IO.Path]::GetFullPath($RulesPath) }
  k6_artifacts = [ordered]@{
    run_dir = $RunDir
    k6_summary_log = if ($k6SummaryMissing) { $null } else { $K6SummaryPath }
    k6_stdout_log = if ([string]::IsNullOrWhiteSpace($k6StdoutPath)) { $null } else { $k6StdoutPath }
  }
}

$kpisObject = @{}
$checksArray = @(To-PlainArray -Value $checks)
$anomaliesArray = @(To-PlainArray -Value $anomalies)
$queryEchoArray = @(To-PlainArray -Value $queryEcho)

$snapshot = [pscustomobject]@{
  metadata = $metadata
  rules = $rules
  k6_summary = $k6Kv
  kpis = $kpisObject
  checks = $checksArray
  verdict = $verdict
  anomalies = $anomaliesArray
  appendix = [pscustomobject]@{
    queries = $queryEchoArray
  }
}

foreach ($q in $queries) {
  $id = $q.id
  $snapshot.kpis[$id] = [pscustomobject]@{
    unit = $q.unit
    promql = $q.query
    stats = To-ExportStats -Stats $statsMap[$id]
    points = @(To-PlainArray -Value $seriesMap[$id])
  }
}

if ($null -ne $drainTimeSec) {
  $snapshot.kpis["drain_time_sec"] = [pscustomobject]@{
    unit = "seconds"
    source = "post_run_drain.samples.jsonl"
    value = [int]$drainTimeSec
  }
}

$snapshotPath = Join-Path $resultDir "prometheus-snapshot.json"
$txtPath = Join-Path $resultDir "prometheus-snapshot.txt"
$metaPath = Join-Path $resultDir "run-metadata.json"
$k6RefPath = Join-Path $resultDir "k6-artifacts.json"

$snapshot | ConvertTo-Json -Depth 8 | Out-File -Encoding utf8 $snapshotPath
$metadata | ConvertTo-Json -Depth 6 | Out-File -Encoding utf8 $metaPath
$metadata.k6_artifacts | ConvertTo-Json -Depth 4 | Out-File -Encoding utf8 $k6RefPath

$txtLines = New-Object System.Collections.Generic.List[string]
$txtLines.Add(("test_id={0}" -f $TestId))
$txtLines.Add(("scenario={0}" -f $scenarioName))
$txtLines.Add(("run_window_start={0}" -f $metadata.run_window.t_start))
$txtLines.Add(("run_window_end={0}" -f $metadata.run_window.t_end))
$txtLines.Add(("duration_sec={0}" -f $metadata.run_window.duration_sec))
$txtLines.Add(("hold_windows={0}" -f (Get-SafeCount $metadata.hold_phase_windows)))
$txtLines.Add(("runtime_hikari_max_pool_size={0}" -f $metadata.runtime_settings.hikari_max_pool_size))
$txtLines.Add(("runtime_consumer_concurrency={0}" -f $metadata.runtime_settings.consumer_concurrency))
$txtLines.Add(("runtime_consumer_max_concurrency={0}" -f $metadata.runtime_settings.consumer_max_concurrency))
$txtLines.Add(("runtime_consumer_prefetch={0}" -f $metadata.runtime_settings.consumer_prefetch))
$txtLines.Add(("runtime_active_profiles={0}" -f (($metadata.runtime_settings.active_profiles -join ","))))
$txtLines.Add(("verdict={0}" -f $verdict))
$txtLines.Add("")
$txtLines.Add("[kpis]")
foreach ($q in $queries) {
  $id = $q.id
  $st = $statsMap[$id]
  $txtLines.Add(("{0}: last={1} avg={2} max={3} samples={4} unit={5}" -f $id, $st.last, $st.avg, $st.max, $st.samples, $q.unit))
}
if ($null -ne $drainTimeSec) {
  $txtLines.Add(("drain_time_sec: value={0}" -f [int]$drainTimeSec))
}
$txtLines.Add("")
$txtLines.Add("[checks]")
foreach ($c in $checksArray) {
  $txtLines.Add(("{0}: {1} - {2}" -f $c.name, $c.status, $c.detail))
}
$txtLines.Add("")
$txtLines.Add("[anomalies]")
$anomaliesArrayType = if ($null -eq $anomaliesArray) { "null" } else { $anomaliesArray.GetType().FullName }
Write-Host ("[capture-prometheus-snapshot] anomaliesArray type at write-stage: {0}" -f $anomaliesArrayType)
if ((Get-SafeCount $anomaliesArray) -eq 0) {
  $txtLines.Add("none")
} else {
  foreach ($a in $anomaliesArray) { $txtLines.Add($a) }
}

$txtLines | Out-File -Encoding utf8 $txtPath

Write-Host "Prometheus snapshot captured."
Write-Host " - JSON: $snapshotPath"
Write-Host " - Text: $txtPath"
Write-Host " - Metadata: $metaPath"

exit 0
