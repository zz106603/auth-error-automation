param(
  [string]$Network = "auth-error-automation_default",
  [string]$EnvFile = "k6/.env",
  [string]$LocalScriptsDir = "$PWD\k6",
  [string]$ScriptPathInContainer = "/scripts/lt_002_rampup.js",
  [string]$TestId = ("LT-002-" + (Get-Date -Format "yyyy-MM-dd_HHmmss")),
  [int]$WaitSeconds = 20,
  [int]$ExpectedStageIndex = 0,
  [int]$ExpectedTargetRps = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$logPath = Join-Path $PWD "lt_002_rampup.log"
if (Test-Path $logPath) { Remove-Item $logPath -Force }

$dockerArgs = @(
  "run","--rm","-i",
  "--network",$Network,
  "--env-file",$EnvFile,
  "-e","K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write",
  "-e","K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),avg,max",
  "-e","K6_PROMETHEUS_RW_PUSH_INTERVAL=5s",
  "-v","${LocalScriptsDir}:/scripts",
  "grafana/k6:latest","run","-o","experimental-prometheus-rw",
  "--tag","testid=$TestId",
  $ScriptPathInContainer
)

Write-Host "==> Starting k6 (testid=$TestId)"
Write-Host "==> Log file: $logPath"
Write-Host "==> Waiting up to $WaitSeconds sec for [STAGE_START]..."

# PowerShell 파이프(Tee-Object) 없이 docker stdout/stderr를 직접 파일로 누적 (NativeCommandError 회피)
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

$start = Get-Date
$found = $false

while ($true) {
  while (-not $proc.StandardOutput.EndOfStream) {
    $line = $proc.StandardOutput.ReadLine()
    $writer.WriteLine($line)
  }
  while (-not $proc.StandardError.EndOfStream) {
    $line = $proc.StandardError.ReadLine()
    $writer.WriteLine($line)
  }
  $writer.Flush()

  $raw = ""
  if (Test-Path $logPath) { $raw = Get-Content $logPath -Raw }

  if ($raw -match "\[STAGE_START\]") {
    # 엄격 검증: stage_index, target_rps까지 확인
    if ($raw -match "\[STAGE_START\].*stage_index=$ExpectedStageIndex" -and
        $raw -match "\[STAGE_START\].*target_rps=$ExpectedTargetRps") {
      $found = $true
      break
    }
  }

  if (((Get-Date) - $start).TotalSeconds -ge $WaitSeconds) { break }
  Start-Sleep -Milliseconds 200
}

$writer.Dispose()

if (-not $found) {
  Write-Host "❌ FAIL: [STAGE_START] (stage_index=$ExpectedStageIndex, target_rps=$ExpectedTargetRps) not found within $WaitSeconds sec." -ForegroundColor Red
  Write-Host "   log=$logPath" -ForegroundColor Yellow
  try { $proc.Kill() } catch {}
  exit 1
}

Write-Host "✅ PASS: STAGE_START detected (stage_index=$ExpectedStageIndex, target_rps=$ExpectedTargetRps)." -ForegroundColor Green
Write-Host "   testid=$TestId"
Write-Host "   log=$logPath"

# k6는 계속 돌게 둔다 (램프업 진행)
exit 0
