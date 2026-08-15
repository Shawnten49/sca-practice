#!/usr/bin/env bash
#
# start-sentinel.sh —— Sentinel Dashboard 一键启动/重启脚本
#
# 行为:
#   1. 已运行 (端口被监听 或 存在 sentinel-dashboard jar 的 java 进程) → 重启
#   2. 未运行 → 直接启动
#   3. 启动后轮询端口, 确认就绪才退出 (失败会打印最近日志)
#
# 停止策略: 先发 TERM 优雅停止, 超过 WAIT_STOP 秒仍未退出则 kill -9
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   SENTINEL_HOME     安装目录, 默认 /Users/shawn/tools/sentinel
#   SENTINEL_PORT     监听端口, 默认 8858
#   SENTINEL_JVM_OPTS 附加 JVM 参数 (默认自带 server.port / dashboard.server / project.name)
#   WAIT_STOP         优雅停止最长等待秒数, 默认 15
#   WAIT_START        启动就绪最长等待秒数, 默认 90
#
# 用法:
#   bash start-sentinel.sh
# 登录: http://127.0.0.1:8858  默认账号 sentinel / sentinel

set -u

# ---------- 1. 定位安装目录和 jar ----------
if [ -z "${SENTINEL_HOME:-}" ]; then
  if [ -d "/Users/shawn/tools/sentinel" ]; then
    SENTINEL_HOME="/Users/shawn/tools/sentinel"
  else
    for candidate in "$HOME"/tools/sentinel*; do
      if [ -d "$candidate" ]; then
        SENTINEL_HOME="$candidate"
        break
      fi
    done
  fi
fi

SENTINEL_JAR=""
if [ -f "$SENTINEL_HOME/sentinel-dashboard-1.8.9.jar" ]; then
  SENTINEL_JAR="$SENTINEL_HOME/sentinel-dashboard-1.8.9.jar"
else
  for jar in "$SENTINEL_HOME"/sentinel-dashboard-*.jar; do
    if [ -f "$jar" ]; then
      SENTINEL_JAR="$jar"
      break
    fi
  done
fi

if [ -z "$SENTINEL_JAR" ] || [ ! -f "$SENTINEL_JAR" ]; then
  echo "[错误] 未找到 sentinel-dashboard jar: $SENTINEL_HOME/sentinel-dashboard-*.jar" >&2
  echo "       请设置 SENTINEL_HOME=/path/to/sentinel 后重试" >&2
  exit 1
fi

PORT="${SENTINEL_PORT:-8858}"
WAIT_STOP="${WAIT_STOP:-15}"
WAIT_START="${WAIT_START:-90}"
CONSOLE_LOG="$SENTINEL_HOME/logs/sentinel-console.log"
PATTERN='sentinel-dashboard-.*\.jar'
DEFAULT_JVM_OPTS="-Dserver.port=${PORT} -Dcsp.sentinel.dashboard.server=127.0.0.1:${PORT} -Dproject.name=sentinel-dashboard"

# ---------- 2. 工具函数 ----------
log() { echo "[$(date '+%H:%M:%S')] $*"; }

port_in_use() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1
  else
    nc -z -G 1 127.0.0.1 "$PORT" >/dev/null 2>&1
  fi
}

pids_by_pattern() {
  local pattern="$1"
  ps -ax -o pid= -o command= 2>/dev/null | awk -v p="$pattern" '$0 ~ p && $0 ~ /java/ {print $1}'
}

service_running() {
  port_in_use || [ -n "$(pids_by_pattern "$PATTERN")" ]
}

# ---------- 3. 停止 ----------
stop_sentinel() {
  local pids n=0
  pids=$(pids_by_pattern "$PATTERN")

  if [ -z "$pids" ]; then
    # 端口被占却没有 sentinel 进程 → 可能是其他程序占用, 不能乱杀
    if port_in_use; then
      echo "[错误] 端口 $PORT 已被非 Sentinel 的进程占用:" >&2
      lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2
      exit 1
    fi
    log "sentinel-dashboard 未在运行, 跳过停止"
    return 0
  fi

  log "停止 sentinel-dashboard (PID: $(echo $pids | tr '\n' ' ')), 发送 TERM..."
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  while [ "$n" -lt "$WAIT_STOP" ]; do
    if ! port_in_use && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      log "sentinel-dashboard 已优雅停止"
      return 0
    fi
    sleep 1
    n=$((n + 1))
  done

  log "优雅停止超时 (${WAIT_STOP}s), kill -9..."
  for pid in $pids; do
    kill -KILL "$pid" 2>/dev/null || true
  done
  sleep 2

  if port_in_use; then
    echo "[错误] 端口 $PORT 仍被占用, 请手动检查: lsof -nP -iTCP:$PORT -sTCP:LISTEN" >&2
    exit 1
  fi
  log "sentinel-dashboard 已被强制停止"
}

# ---------- 4. 启动 ----------
start_sentinel() {
  mkdir -p "$SENTINEL_HOME/logs"
  log "启动 sentinel-dashboard (端口 $PORT, 日志: $CONSOLE_LOG)"
  cd "$SENTINEL_HOME" || exit 1
  # shellcheck disable=SC2086
  nohup java $DEFAULT_JVM_OPTS ${SENTINEL_JVM_OPTS:-} -jar "$SENTINEL_JAR" >> "$CONSOLE_LOG" 2>&1 &
}

# ---------- 5. 等待就绪 ----------
wait_ready() {
  local n=0 pid ps_ok=false

  if ps -ax -o pid= -o command= >/dev/null 2>&1; then
    ps_ok=true
  fi

  while [ "$n" -lt "$WAIT_START" ]; do
    if port_in_use; then
      pid=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1)
      log "sentinel-dashboard 就绪, 端口 $PORT 已监听 (PID: ${pid:-?})"
      return 0
    fi
    n=$((n + 1))
    if [ "$ps_ok" = true ] && [ "$n" -eq 15 ] && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      break
    fi
    sleep 1
  done

  echo "[错误] sentinel-dashboard 未在 ${WAIT_START}s 内就绪, 最近日志:" >&2
  tail -40 "$CONSOLE_LOG" 2>/dev/null >&2 || true
  return 1
}

# ---------- 6. 主流程 ----------
if service_running; then
  log "检测到 sentinel-dashboard 已运行, 执行重启..."
  stop_sentinel
else
  log "sentinel-dashboard 未运行, 直接启动..."
fi

start_sentinel

if ! wait_ready; then
  exit 1
fi

pid=$(pids_by_pattern "$PATTERN" | head -1)
if [ -n "$pid" ]; then
  echo "$pid" > "$SENTINEL_HOME/logs/sentinel.pid"
  log "已记录 PID 到 logs/sentinel.pid"
fi

log "完成。浏览器打开 http://127.0.0.1:${PORT} (默认账号 sentinel / sentinel)"
