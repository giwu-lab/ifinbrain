#!/usr/bin/env bash
# Stop the dev backend (by pid file or by pattern).
PID_FILE="$(dirname "$0")/../logs/backend.pid"
if [ -f "$PID_FILE" ]; then
  PID=$(cat "$PID_FILE")
  kill "$PID" 2>/dev/null && echo "Sent SIGTERM to $PID"
  for i in 1 2 3 4 5; do
    sleep 1
    if ! kill -0 "$PID" 2>/dev/null; then
      rm -f "$PID_FILE"
      echo "Backend stopped"
      exit 0
    fi
  done
  kill -9 "$PID" 2>/dev/null
  rm -f "$PID_FILE"
fi
# Fallback: brute force
pkill -9 -f "iFinBrainApplication" 2>/dev/null
pkill -9 -f "spring-boot:run" 2>/dev/null
echo "Backend stopped"