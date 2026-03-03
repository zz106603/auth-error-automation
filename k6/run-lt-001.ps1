param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "$PWD\k6",
  [string]$ScriptPathInContainer = "/scripts/lt_001_baseline.js",
  [string]$TestId = ("LT-001-" + (Get-Date -Format "yyyy-MM-dd_HHmmss"))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resultsDir = Join-Path $LocalScriptsDir "results"
if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Path $resultsDir | Out-Null }
$runDir = Join-Path $resultsDir $TestId
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$logPath = Join-Path $runDir "lt_001_baseline.stdout.log"
if (Test-Path $logPath) { Remove-Item $logPath -Force }

$dockerArgs = @(
  "run","--rm","-i",
  "--network",$Network,
  "--env-file",$EnvFile,
  "-e","K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write",
  "-e","K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),avg,max",
  "-e","K6_PROMETHEUS_RW_PUSH_INTERVAL=5s",
  "-e","TEST_ID=$TestId",
  "-e","RESULTS_DIR=/scripts/results/$TestId",
  "-v","${LocalScriptsDir}:/scripts",
  "grafana/k6:latest","run","-o","experimental-prometheus-rw",
  "--tag","testid=$TestId",
  $ScriptPathInContainer
)

Write-Host "==> Starting k6 baseline (testid=$TestId)"
Write-Host "==> Log file: $logPath"
Write-Host "==> Artifact directory: $runDir"

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = "docker"
$psi.Arguments = ($dockerArgs -join " ")
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$null = $proc.Start()

$writer = [System.IO.StreamWriter]::new($logPath, $true)

while (-not $proc.HasExited) {
  while (-not $proc.StandardOutput.EndOfStream) {
    $line = $proc.StandardOutput.ReadLine()
    $writer.WriteLine($line)
  }
  while (-not $proc.StandardError.EndOfStream) {
    $line = $proc.StandardError.ReadLine()
    $writer.WriteLine($line)
  }
  $writer.Flush()
  Start-Sleep -Milliseconds 200
}

while (-not $proc.StandardOutput.EndOfStream) {
  $line = $proc.StandardOutput.ReadLine()
  $writer.WriteLine($line)
}
while (-not $proc.StandardError.EndOfStream) {
  $line = $proc.StandardError.ReadLine()
  $writer.WriteLine($line)
}
$writer.Flush()
$writer.Dispose()

Write-Host "==> k6 finished with exit code $($proc.ExitCode)"
Write-Host "==> Summary file: $(Join-Path $runDir ("lt_001_baseline-" + $TestId + ".log"))"
Write-Host "==> Stdout log: $logPath"
Write-Host "==> Final artifact directory: $runDir"

exit $proc.ExitCode
