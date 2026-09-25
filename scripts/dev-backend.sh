#!/usr/bin/env bash
# Source .env (only KEY=VALUE lines) then start backend in true background.
# Caller does not block — process is fully detached via nohup.
set -a
# 用 process substitution 让 grep 输出真正作为赋值被执行；之前用 '> /dev/null'
# 把 pipeline 输出丢掉， set -a 不会触发，导致 IFINBRAIN_MASTER_KEY 等 env 没注入。
source <(grep -E '^[A-Z][A-Z0-9_]*=' "$(dirname "$0")/../.env" 2>/dev/null \
    | sed 's/#.*$//' \
    | grep -v '^#')
set +a

LOG_DIR="$(dirname "$0")/../logs"
mkdir -p "$LOG_DIR"

cd "$(dirname "$0")/../backend"
nohup mvn -DskipTests -q spring-boot:run > "$LOG_DIR/console.log" 2>&1 < /dev/null &
echo $! > "$LOG_DIR/backend.pid"
disown 2>/dev/null || true
echo "Backend starting, pid=$(cat $LOG_DIR/backend.pid), logs -> $LOG_DIR/"
exit 0