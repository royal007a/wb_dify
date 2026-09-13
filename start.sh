#!/bin/sh

set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RUNTIME_DIR="$PROJECT_DIR/.hify"
BACKEND_PID_FILE="$RUNTIME_DIR/backend.pid"
FRONTEND_PID_FILE="$RUNTIME_DIR/frontend.pid"
BACKEND_LOG="$RUNTIME_DIR/backend.log"
FRONTEND_LOG="$RUNTIME_DIR/frontend.log"
BACKEND_PORT=${HIFY_PORT:-8080}
FRONTEND_PORT=${HIFY_WEB_PORT:-5173}
POSTGRES_PORT=${HIFY_POSTGRES_PORT:-54329}
POSTGRES_CONTAINER=${HIFY_POSTGRES_CONTAINER:-hify-dev-postgres}
BACKEND_STARTED=0
FRONTEND_STARTED=0

info() { printf '[INFO]  %s\n' "$*"; }
error() { printf '[ERROR] %s\n' "$*" >&2; }

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    error "缺少命令：${1}"
    exit 1
  }
}

stop_pid_file() {
  pid_file=$1
  if [ -f "$pid_file" ]; then
    pid=$(cat "$pid_file")
    case "$pid" in
      *[!0-9]*|'') ;;
      *) kill "$pid" >/dev/null 2>&1 || true ;;
    esac
    rm -f "$pid_file"
  fi
}

cleanup_on_failure() {
  status=$?
  if [ "$status" -ne 0 ]; then
    [ "$FRONTEND_STARTED" -eq 0 ] || stop_pid_file "$FRONTEND_PID_FILE"
    [ "$BACKEND_STARTED" -eq 0 ] || stop_pid_file "$BACKEND_PID_FILE"
    error "启动失败；已回收本次启动的应用进程。日志位于 $RUNTIME_DIR"
  fi
  exit "$status"
}
trap cleanup_on_failure EXIT HUP INT TERM

port_in_use() {
  lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

wait_for_http() {
  url=$1
  attempts=$2
  count=0
  while [ "$count" -lt "$attempts" ]; do
    if curl --silent --fail --output /dev/null "$url"; then
      return 0
    fi
    count=$((count + 1))
    sleep 1
  done
  return 1
}

for command_name in java mvn npm curl lsof; do
  require_command "$command_name"
done

mkdir -p "$RUNTIME_DIR"

if port_in_use "$BACKEND_PORT"; then
  error "后端端口 ${BACKEND_PORT} 已被占用；如为 Hify，请先执行 ./stop.sh"
  exit 1
fi
if port_in_use "$FRONTEND_PORT"; then
  error "前端端口 ${FRONTEND_PORT} 已被占用；如为 Hify，请先执行 ./stop.sh"
  exit 1
fi

if [ -z "${HIFY_DB_URL:-}" ]; then
  require_command docker
  info "准备 PostgreSQL（容器 ${POSTGRES_CONTAINER}，端口 ${POSTGRES_PORT}）..."
  if docker container inspect "$POSTGRES_CONTAINER" >/dev/null 2>&1; then
    docker start "$POSTGRES_CONTAINER" >/dev/null
  else
    docker run -d \
      --name "$POSTGRES_CONTAINER" \
      -e POSTGRES_DB=hify \
      -e POSTGRES_USER=hify \
      -e POSTGRES_PASSWORD=hify \
      -p "127.0.0.1:$POSTGRES_PORT:5432" \
      -v hify-dev-postgres-data:/var/lib/postgresql/data \
      postgres:16-alpine >/dev/null
  fi
  postgres_attempt=0
  until docker exec "$POSTGRES_CONTAINER" pg_isready -U hify -d hify >/dev/null 2>&1; do
    postgres_attempt=$((postgres_attempt + 1))
    if [ "$postgres_attempt" -ge 30 ]; then
      error "PostgreSQL 未在 30 秒内就绪"
      exit 1
    fi
    sleep 1
  done
  HIFY_DB_URL="jdbc:postgresql://127.0.0.1:$POSTGRES_PORT/hify"
  HIFY_DB_USERNAME=hify
  HIFY_DB_PASSWORD=hify
else
  info "使用外部 PostgreSQL：${HIFY_DB_URL}"
  HIFY_DB_USERNAME=${HIFY_DB_USERNAME:-hify}
  HIFY_DB_PASSWORD=${HIFY_DB_PASSWORD:-hify}
fi

if [ "${HIFY_REDIS_ENABLED:-false}" = "true" ]; then
  require_command redis-cli
  redis_host=${HIFY_REDIS_HOST:-127.0.0.1}
  redis_port=${HIFY_REDIS_PORT:-6379}
  info "检查 Redis ${redis_host}:${redis_port} ..."
  redis-cli -h "$redis_host" -p "$redis_port" ping 2>/dev/null | grep -q PONG || {
    error "Redis 不可达（${redis_host}:${redis_port}）"
    exit 1
  }
else
  info "Redis 未启用，跳过检查（它不是当前启动链路的强依赖）"
fi

info "构建后端..."
(cd "$PROJECT_DIR/backend" && mvn --quiet package -DskipTests)
BACKEND_JAR="$PROJECT_DIR/backend/hify-app/target/hify-app-0.1.0-SNAPSHOT.jar"
if [ ! -f "$BACKEND_JAR" ]; then
  error "未找到后端可执行包：$BACKEND_JAR"
  exit 1
fi

info "启动后端，日志：${BACKEND_LOG}"
nohup env \
  HIFY_PORT="$BACKEND_PORT" \
  HIFY_DB_URL="$HIFY_DB_URL" \
  HIFY_DB_USERNAME="$HIFY_DB_USERNAME" \
  HIFY_DB_PASSWORD="$HIFY_DB_PASSWORD" \
  HIFY_REDIS_ENABLED="${HIFY_REDIS_ENABLED:-false}" \
  java \
    '-Dhttp.nonProxyHosts=localhost|127.*|[::1]' \
    '-Dhttps.nonProxyHosts=localhost|127.*|[::1]' \
    '-DsocksNonProxyHosts=localhost|127.*|[::1]' \
    -jar "$BACKEND_JAR" >"$BACKEND_LOG" 2>&1 &
echo $! >"$BACKEND_PID_FILE"
BACKEND_STARTED=1

if ! wait_for_http "http://127.0.0.1:$BACKEND_PORT/api/v1/health" 60; then
  error "后端健康检查失败；最近日志："
  tail -n 30 "$BACKEND_LOG" >&2 || true
  exit 1
fi
info "后端健康检查通过：HTTP 200"

info "准备前端依赖..."
if [ -d "$PROJECT_DIR/frontend/node_modules" ]; then
  (cd "$PROJECT_DIR/frontend" && npm install --no-audit --no-fund >/dev/null)
else
  (cd "$PROJECT_DIR/frontend" && npm ci --no-audit --no-fund >/dev/null)
fi

info "启动前端，日志：${FRONTEND_LOG}"
cd "$PROJECT_DIR/frontend"
nohup npm run dev -- --host 127.0.0.1 --port "$FRONTEND_PORT" --strictPort >"$FRONTEND_LOG" 2>&1 &
echo $! >"$FRONTEND_PID_FILE"
cd "$PROJECT_DIR"
FRONTEND_STARTED=1

if ! wait_for_http "http://127.0.0.1:$FRONTEND_PORT" 30; then
  error "前端未在 30 秒内就绪；最近日志："
  tail -n 30 "$FRONTEND_LOG" >&2 || true
  exit 1
fi

trap - EXIT HUP INT TERM
info "Hify 已启动"
info "前端：http://localhost:$FRONTEND_PORT"
info "后端：http://localhost:$BACKEND_PORT/api/v1/health"
info "停止：./stop.sh"

if [ "${HIFY_NO_OPEN:-0}" != "1" ] && command -v open >/dev/null 2>&1; then
  open "http://localhost:$FRONTEND_PORT"
fi
