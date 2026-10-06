# dev.ps1 — iFinBrain dev mode control script (Windows PowerShell 5.1+)
# 用法： powershell -ExecutionPolicy Bypass -File scripts\dev.ps1 {start-be|stop-be|status|start-fe|stop-fe}
#
# 对应 Linux 版 scripts/dev.sh；Maven 绿色安装在 D:\workspace\tools\maven
#   端口：后端 8080 / 前端 5173 / fapiao_cn 8091（外部服务）

$ErrorActionPreference = 'Stop'

$ROOT     = Split-Path -Parent $PSScriptRoot
$LOG_DIR  = Join-Path $ROOT 'logs'
$BE_PID   = Join-Path $LOG_DIR 'backend.pid'
$FE_PID   = Join-Path $LOG_DIR 'frontend.pid'
$BE_LOG   = Join-Path $LOG_DIR 'backend.log'
$FE_LOG   = Join-Path $LOG_DIR 'frontend.log'

$MAVEN_BIN = 'D:\workspace\tools\maven\bin'
$JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot'
$NODE_DIR  = 'C:\Program Files\nodejs'

if (-not (Test-Path $LOG_DIR)) { New-Item -ItemType Directory -Path $LOG_DIR -Force | Out-Null }

function info($m) { Write-Host "[dev.ps1] $m" -ForegroundColor Cyan }
function ok($m)   { Write-Host "[dev.ps1] $m" -ForegroundColor Green }
function warn($m) { Write-Host "[dev.ps1] $m" -ForegroundColor Yellow }
function err($m)  { Write-Host "[dev.ps1] $m" -ForegroundColor Red }

# ─── .env 加载到当前进程环境（值可能带引号，保留 # 号）───
function load_dotenv($path) {
  if (-not (Test-Path $path)) { warn "no .env at $path — 依赖 env var 由外部注入"; return }
  foreach ($line in [System.IO.File]::ReadAllLines($path)) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    if ($t -notmatch '^([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') { continue }
    $k = $Matches[1]; $v = $Matches[2].Trim()
    if ($v.Length -ge 2 -and (($v.StartsWith('"') -and $v.EndsWith('"')) -or ($v.StartsWith("'") -and $v.EndsWith("'")))) {
      $v = $v.Substring(1, $v.Length - 2)
    }
    Set-Item -Path "env:$k" -Value $v
  }
  info ".env loaded ($([System.IO.File]::ReadAllLines($path).Count) lines)"
}

function setup_path {
  $env:JAVA_HOME = $JAVA_HOME
  $parts = @("$JAVA_HOME\bin", $MAVEN_BIN, $NODE_DIR)
  foreach ($p in $parts) {
    if ((Test-Path $p) -and ($env:PATH -notlike "*$p*")) {
      $env:PATH = "$p;$env:PATH"
    }
  }
}

function is_alive($pidFile) {
  if (-not (Test-Path $pidFile)) { return $false }
  $raw = (Get-Content $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
  if (-not $raw) { return $false }
  $p = 0
  if (-not [int]::TryParse($raw.Trim(), [ref]$p)) { return $false }
  return [bool](Get-Process -Id $p -ErrorAction SilentlyContinue)
}

function kill_tree($procId) {
  # 结束进程树：先 taskkill /T 杀子树，再确认
  & taskkill /PID $procId /T /F 2>&1 | Out-Null
}

# ─── 后端 ───
function start_backend {
  if (is_alive $BE_PID) { ok "backend already running (pid=$(Get-Content $BE_PID))"; return }
  if (Test-Path $BE_PID) { Remove-Item $BE_PID -Force }

  load_dotenv (Join-Path $ROOT '.env')
  setup_path

  info 'starting backend: mvn -DskipTests spring-boot:run ...'
  info "log -> $BE_LOG"
  # 用 cmd 包裹以合并 stdout/stderr 到同一个文件（等价 bash 的 2>&1）
  $cmdline = 'mvn -DskipTests spring-boot:run > "' + $BE_LOG + '" 2>&1'
  $proc = Start-Process -FilePath 'cmd.exe' `
                        -ArgumentList '/c', $cmdline `
                        -WorkingDirectory (Join-Path $ROOT 'backend') `
                        -PassThru -WindowStyle Hidden
  $proc.Id | Out-File -FilePath $BE_PID -Encoding ASCII -Force
  ok "backend pid=$($proc.Id)"
}

function stop_backend {
  if (-not (Test-Path $BE_PID)) { warn 'no backend.pid'; return }
  $p = (Get-Content $BE_PID | Select-Object -First 1).Trim()
  if (is_alive $BE_PID) {
    info "stopping backend (pid=$p) ..."
    kill_tree $p
    Start-Sleep -Seconds 2
    if (is_alive $BE_PID) { warn 'still alive, force kill'; kill_tree $p }
    ok 'backend stopped'
  } else { warn "pid=$p not alive (stale)" }
  Remove-Item $BE_PID -Force -ErrorAction SilentlyContinue
}

# ─── 前端 ───
function start_frontend {
  if (is_alive $FE_PID) { ok "frontend already running (pid=$(Get-Content $FE_PID))"; return }
  if (Test-Path $FE_PID) { Remove-Item $FE_PID -Force }
  setup_path
  $fe = Join-Path $ROOT 'frontend'
  if (-not (Test-Path (Join-Path $fe 'node_modules'))) {
    info 'node_modules missing, running npm install ...'
    Push-Location $fe
    & npm.cmd install 2>&1 | Tee-Object -FilePath (Join-Path $LOG_DIR 'frontend-install.log')
    Pop-Location
  }
  info 'starting frontend: npm run dev ...'
  info "log -> $FE_LOG"
  $cmdline = 'npm run dev > "' + $FE_LOG + '" 2>&1'
  $proc = Start-Process -FilePath 'cmd.exe' `
                        -ArgumentList '/c', $cmdline `
                        -WorkingDirectory $fe `
                        -PassThru -WindowStyle Hidden
  $proc.Id | Out-File -FilePath $FE_PID -Encoding ASCII -Force
  ok "frontend pid=$($proc.Id)"
}

function stop_frontend {
  if (-not (Test-Path $FE_PID)) { warn 'no frontend.pid'; return }
  $p = (Get-Content $FE_PID | Select-Object -First 1).Trim()
  if (is_alive $FE_PID) {
    info "stopping frontend (pid=$p) ..."
    kill_tree $p
    Start-Sleep -Seconds 1
    ok 'frontend stopped'
  } else { warn "pid=$p not alive (stale)" }
  Remove-Item $FE_PID -Force -ErrorAction SilentlyContinue
}

function health($url) {
  try {
    $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 5 -ErrorAction Stop
    "$($r.StatusCode) $($r.Content)"
  } catch { "not responding ($($_.Exception.Message))" }
}

function show_status {
  Write-Host '── backend ──' -ForegroundColor Cyan
  if (is_alive $BE_PID) { Write-Host "  pid=$(Get-Content $BE_PID)"; Write-Host "  $(health 'http://localhost:8080/actuator/health')" }
  else { Write-Host '  not running' }
  Write-Host '── frontend ──' -ForegroundColor Cyan
  if (is_alive $FE_PID) { Write-Host "  pid=$(Get-Content $FE_PID)"; Write-Host "  $(health 'http://localhost:5173/')" }
  else { Write-Host '  not running' }
  Write-Host '── fapiao_cn ──' -ForegroundColor Cyan
  Write-Host "  $(health 'http://localhost:8091/health')"
}

switch ($args[0]) {
  'start-be' { start_backend }
  'stop-be'  { stop_backend }
  'start-fe' { start_frontend }
  'stop-fe'  { stop_frontend }
  'start'    { start_backend; start_frontend }
  'stop'     { stop_frontend; stop_backend }
  'restart'  { stop_frontend; stop_backend; Start-Sleep 1; start_backend; start_frontend }
  'status'   { show_status }
  default {
    Write-Host 'Usage: dev.ps1 {start|stop|restart|status|start-be|stop-be|start-fe|stop-fe}'
    exit 2
  }
}
