#!/usr/bin/env bash
# fapiao_web — unified dev mode control script.
# 用法： scripts/dev.sh {start|stop|restart|status|logs|logs-be|logs-fc}
#
# 行为：
#   start   - 后端 (mvn spring-boot:run) + 前端 (npm run dev) 后台启动，日志到 logs/
#   stop    - 通过 pid 文件停止两端，必要时 pkill 兜底
#   restart - stop + start
#   status  - 显示两个进程 pid + 端口监听 + 健康检查
#   logs    - 实时尾随两端的日志
#   logs-be - 仅后端日志
#   logs-fc - 仅前端日志

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_DIR="$ROOT/logs"
BE_PID_FILE="$LOG_DIR/backend.pid"
FE_PID_FILE="$LOG_DIR/frontend.pid"
mkdir -p "$LOG_DIR"

# ─── .env → 导出到当前 shell（修复旧脚本 "> /dev/null" 吞掉输出的 bug） ───
if [ -f "$ROOT/.env" ]; then
  set -a
  # 取 KEY=VALUE 行，去掉行内 # 注释
  while IFS= read -r line; do
    case "$line" in
      ''|'#'*) continue ;;
      *=*)      eval "export $line" ;;
    esac
  done < <(grep -E '^[A-Z][A-Z0-9_]*=' "$ROOT/.env" | sed 's/[[:space:]]*#.*$//')
  set +a
fi

# ─── 颜色 ───
if [ -t 1 ]; then
  G="\033[32m"; Y="\033[33m"; R="\033[31m"; B="\033[36m"; D="\033[2m"; X="\033[0m"
else
  G=""; Y=""; R=""; B=""; D=""; X=""
fi
info() { printf "${B}[dev.sh]${X} %s\n" "$*"; }
ok()   { printf "${G}[dev.sh]${X} %s\n" "$*"; }
warn() { printf "${Y}[dev.sh]${X} %s\n" "$*"; }
err()  { printf "${R}[dev.sh]${X} %s\n" "$*" >&2; }

is_alive() { kill -0 "$1" 2>/dev/null; }

# ─── 后端：mvn spring-boot:run，nohup 后台 ───
start_backend() {
  if [ -f "$BE_PID_FILE" ] && is_alive "$(cat "$BE_PID_FILE")"; then
    ok "Backend already running, pid=$(cat "$BE_PID_FILE")"
    return 0
  fi
  rm -f "$BE_PID_FILE"
  info "Starting backend (mvn spring-boot:run) ..."
  cd "$ROOT/backend"
  # 项目默认时区 Asia/Shanghai，覆盖宿主机 TZ
  export TZ="Asia/Shanghai"
  nohup mvn -DskipTests -q spring-boot:run > "$LOG_DIR/backend.log" 2>&1 < /dev/null &
  echo $! > "$BE_PID_FILE"
  disown 2>/dev/null || true
  cd "$ROOT"
  ok "Backend pid=$(cat "$BE_PID_FILE"), logs -> $LOG_DIR/backend.log"
}

# ─── 前端：npm run dev，nohup 后台 ───
start_frontend() {
  if [ -f "$FE_PID_FILE" ] && is_alive "$(cat "$FE_PID_FILE")"; then
    ok "Frontend already running, pid=$(cat "$FE_PID_FILE")"
    return 0
  fi
  rm -f "$FE_PID_FILE"
  if [ ! -d "$ROOT/frontend/node_modules" ]; then
    info "node_modules missing, running npm install ..."
    (cd "$ROOT/frontend" && npm install) >> "$LOG_DIR/frontend-install.log" 2>&1
  fi
  info "Starting frontend (npm run dev) ..."
  cd "$ROOT/frontend"
  nohup npm run dev > "$LOG_DIR/frontend.log" 2>&1 < /dev/null &
  echo $! > "$FE_PID_FILE"
  disown 2>/dev/null || true
  cd "$ROOT"
  ok "Frontend pid=$(cat "$FE_PID_FILE"), logs -> $LOG_DIR/frontend.log"
}

stop_pid() {
  local pidfile="$1" name="$2" patterns=("${@:3}")
  if [ ! -f "$pidfile" ]; then return 0; fi
  local pid; pid="$(cat "$pidfile")"
  if is_alive "$pid"; then
    info "Stopping $name (pid=$pid) ..."
    kill "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      sleep 0.5
      is_alive "$pid" || break
    done
    if is_alive "$pid"; then
      warn "$name still alive, sending SIGKILL"
      kill -9 "$pid" 2>/dev/null || true
      sleep 0.5
    fi
    ok "$name stopped"
  fi
  rm -f "$pidfile"
  # 兜底：杀同进程族
  for p in "${patterns[@]}"; do
    pkill -9 -f "$p" 2>/dev/null || true
  done
}

stop_backend() {
  stop_pid "$BE_PID_FILE" "backend" \
    "iFinBrainApplication" "spring-boot:run" "ifinbrain-web"
}

stop_frontend() {
  stop_pid "$FE_PID_FILE" "frontend" \
    "vite$" "node.*vite" "ifinbrain-frontend"
}

wait_health() {
  local url="$1" name="$2" timeout="${3:-60}"
  for i in $(seq 1 "$timeout"); do
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then
      ok "$name is UP ($url)"
      return 0
    fi
    sleep 1
  done
  warn "$name did not become healthy in ${timeout}s ($url)"
  return 1
}

show_status() {
  printf "${B}── backend ──${X}\n"
  if [ -f "$BE_PID_FILE" ] && is_alive "$(cat "$BE_PID_FILE")"; then
    printf "  pid=$(cat "$BE_PID_FILE")  "
    curl -fsS http://localhost:8080/actuator/health 2>/dev/null \
      && echo " ✅" || echo "  (not responding yet)"
  else
    echo "  not running"
  fi
  printf "${B}── frontend ──${X}\n"
  if [ -f "$FE_PID_FILE" ] && is_alive "$(cat "$FE_PID_FILE")"; then
    printf "  pid=$(cat "$FE_PID_FILE")  "
    curl -fsS -o /dev/null http://localhost:5173/ && echo "http://localhost:5173 ✅" \
      || echo "(not responding yet)"
  else
    echo "  not running"
  fi
  printf "${B}── fapiao_cn ──${X}\n"
  curl -fsS http://localhost:8091/health 2>/dev/null && echo " ✅" \
    || echo "  ⚠️  fapiao_cn at :8091 not reachable"
}

cmd="${1:-}"
case "$cmd" in
  start)
    start_backend
    start_frontend
    info "Waiting for health ..."
    wait_health http://localhost:8080/actuator/health "backend"  90 || true
    wait_health http://localhost:5173/                  "frontend" 60 || true
    echo
    info "Open http://localhost:5173  (default admin / Admin@123)"
    ;;
  stop)
    stop_frontend
    stop_backend
    ok "All dev services stopped"
    ;;
  restart)
    "$0" stop
    sleep 1
    "$0" start
    ;;
  status)
    show_status
    ;;
  logs)
    if [ -f "$LOG_DIR/backend.log" ] && [ -f "$LOG_DIR/frontend.log" ]; then
      tail -n 50 -F "$LOG_DIR/backend.log" "$LOG_DIR/frontend.log"
    elif [ -f "$LOG_DIR/console.log" ]; then
      tail -n 50 -F "$LOG_DIR/console.log"
    else
      err "no log files yet"
      exit 1
    fi
    ;;
  logs-be) tail -n 80 -F "$LOG_DIR/backend.log"  ;;
  logs-fe) tail -n 80 -F "$LOG_DIR/frontend.log" ;;
  *)
    cat <<EOF
Usage: $0 {start|stop|restart|status|logs|logs-be|logs-fe}
EOF
    exit 2
    ;;
esac