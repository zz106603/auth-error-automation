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

function Write-CallParamDiagnostics {
  param([string]$Name, [object]$Value)
  $typeName = if ($null -eq $Value) { "null" } else { $Value.GetType().FullName }
  $isEmpty = $false
  if ($Value -is [string]) { $isEmpty = [string]::IsNullOrWhiteSpace($Value) }
  Write-Host ("[capture-and-report-loadtest] Param {0}: value='{1}' type={2} IsNullOrEmpty={3}" -f $Name, $Value, $typeName, $isEmpty)
}

Write-CallParamDiagnostics -Name "TestId" -Value $TestId
Write-CallParamDiagnostics -Name "Scenario" -Value $Scenario
Write-CallParamDiagnostics -Name "PrometheusBaseUrl" -Value $PrometheusBaseUrl
Write-CallParamDiagnostics -Name "ResultsRoot" -Value $ResultsRoot
Write-CallParamDiagnostics -Name "K6ResultsRoot" -Value $K6ResultsRoot
Write-CallParamDiagnostics -Name "RunDir" -Value $RunDir
Write-CallParamDiagnostics -Name "K6SummaryPath" -Value $K6SummaryPath
Write-CallParamDiagnostics -Name "StartTime" -Value $StartTime
Write-CallParamDiagnostics -Name "EndTime" -Value $EndTime
Write-CallParamDiagnostics -Name "StepSec" -Value $StepSec
Write-CallParamDiagnostics -Name "BaselinePath" -Value $BaselinePath
Write-CallParamDiagnostics -Name "RulesPath" -Value $RulesPath
Write-CallParamDiagnostics -Name "Profile" -Value $Profile

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
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

$resultDir = Join-Path $ResultsRoot $TestId
$snapshotPath = Join-Path $resultDir "prometheus-snapshot.json"
$reportPath = Join-Path $resultDir ("{0}-summary.md" -f $TestId)

& $reportScript -SnapshotPath $snapshotPath -OutputPath $reportPath
if ($LASTEXITCODE -ne 0) {
  exit $LASTEXITCODE
}

Write-Host "Load-test artifact bundle ready: $resultDir"
exit 0
