#!/bin/sh

set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RUNTIME_DIR="$PROJECT_DIR/.hify"

stop_process() {
  name=$1
  pid_file=$2
  if [ ! -f "$pid_file" ]; then
    printf '[INFO]  %s 未运行（无 PID 文件）\n' "$name"
    return
  fi

  pid=$(cat "$pid_file")
  case "$pid" in
    *[!0-9]*|'')
      printf '[WARN]  %s PID 文件无效，已清理\n' "$name"
      rm -f "$pid_file"
      return
      ;;
  esac

  if ! kill -0 "$pid" >/dev/null 2>&1; then
    printf '[INFO]  %s 已停止，清理旧 PID 文件\n' "$name"
    rm -f "$pid_file"
    return
  fi

  printf '[INFO]  正在停止 %s（PID %s）...\n' "$name" "$pid"
  if command -v pgrep >/dev/null 2>&1; then
    child_pids=$(pgrep -P "$pid" || true)
    [ -z "$child_pids" ] || kill -TERM $child_pids >/dev/null 2>&1 || true
  fi
  kill -TERM "$pid"
  waited=0
  while kill -0 "$pid" >/dev/null 2>&1 && [ "$waited" -lt 10 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" >/dev/null 2>&1; then
    printf '[WARN]  %s 未在 10 秒内退出，发送 SIGKILL\n' "$name"
    kill -KILL "$pid" >/dev/null 2>&1 || true
  fi
  rm -f "$pid_file"
}

stop_process "前端" "$RUNTIME_DIR/frontend.pid"
stop_process "后端" "$RUNTIME_DIR/backend.pid"

if [ "${HIFY_STOP_DATABASE:-false}" = "true" ] && command -v docker >/dev/null 2>&1; then
  postgres_container=${HIFY_POSTGRES_CONTAINER:-hify-dev-postgres}
  if docker container inspect "$postgres_container" >/dev/null 2>&1; then
    printf '[INFO]  正在停止 PostgreSQL 容器 %s...\n' "$postgres_container"
    docker stop "$postgres_container" >/dev/null
  fi
fi

printf '[INFO]  Hify 应用进程已停止\n'
