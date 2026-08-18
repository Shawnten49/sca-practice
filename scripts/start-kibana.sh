#!/usr/bin/env bash
#
# start-kibana.sh —— Kibana 一键启动/重启脚本（Elasticsearch 管理后台）
#
# 行为:
#   1. 已运行 (5601 被监听 或 存在 Kibana 的 node 进程) → 重启
#   2. 未运行 → 直接启动
#   3. 启动后轮询 HTTP 端口, 确认就绪才退出 (失败会打印最近日志)
#
# 停止策略: 先发 TERM 优雅停止, 超过 WAIT_STOP 秒仍未退出则 kill -9
#
# 说明:
#   - Kibana 8.x 自带 Node.js, 脚本不设 NODE_HOME, 直接执行 bin/kibana
#   - 需要先启动 Elasticsearch (start-elasticsearch.sh), 否则 Kibana 起不来
#   - 本地配置已指向 http://127.0.0.1:9200, ES 关闭安全认证, 无需账号密码
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   KIBANA_HOME      安装目录, 默认 /Users/shawn/tools/kibana-8.18.1 (或自动探测 ~/tools/kibana-*)
#   KIBANA_PORT      HTTP 端口, 默认 5601
#   WAIT_STOP        优雅停止最长等待秒数, 默认 30
#   WAIT_START       启动就绪最长等待秒数, 默认 120
#
# 用法:
#   bash start-kibana.sh
# 验证: 浏览器打开 http://127.0.0.1:5601

set -u

# ---------- 1. 定位安装目录 ----------
if [ -z "${KIBANA_HOME:-}" ]; then
  if [ -d "/Users/shawn/tools/kibana-8.18.1" ]; then
    KIBANA_HOME="/Users/shawn/tools/kibana-8.18.1"
  else
    for candidate in "$HOME"/tools/kibana-*; do
      if [ -f "$candidate/bin/kibana" ]; then
        KIBANA_HOME="$candidate"
        break
      fi
    done
  fi
fi

if [ ! -f "$KIBANA_HOME/bin/kibana" ]; then
  echo "[错误] 未找到 Kibana 安装目录: $KIBANA_HOME" >&2
  echo "       请设置 KIBANA_HOME=/path/to/kibana 后重试" >&2
  exit 1
fi

PORT="${KIBANA_PORT:-5601}"
WAIT_STOP="${WAIT_STOP:-30}"
WAIT_START="${WAIT_START:-120}"
CONSOLE_LOG="$KIBANA_HOME/logs/kibana-console.log"
# Kibana 进程命令行形如: node bin/../src/cli/dist
PATTERN='src/cli/dist'

mkdir -p "$KIBANA_HOME/logs"

# ---------- 2. 工具函数 ----------
log() { echo "[$(date '+%H:%M:%S')] $*"; }

port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  else
    nc -z -G 1 127.0.0.1 "$port" >/dev/null 2>&1
  fi
}

pids_by_pattern() {
  local pattern="$1"
  # Kibana 是 node 进程（非 java），不能加 /java/ 过滤
  ps -ax -o pid= -o command= 2>/dev/null | awk -v p="$pattern" '$0 ~ p && $0 !~ /awk/ {print $1}'
}

# ---------- 3. 停止 ----------
stop_kibana() {
  local pids n=0
  pids=$(pids_by_pattern "$PATTERN")

  if [ -z "$pids" ]; then
    if port_in_use "$PORT"; then
      echo "[错误] 端口 $PORT 已被非 Kibana 进程占用:" >&2
      lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2
      exit 1
    fi
    log "Kibana 未在运行, 跳过停止"
    return 0
  fi

  log "停止 Kibana (PID: $(echo $pids | tr '\n' ' ')), 发送 TERM..."
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  while [ "$n" -lt "$WAIT_STOP" ]; do
    if ! port_in_use "$PORT" && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      log "Kibana 已优雅停止"
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

  if port_in_use "$PORT"; then
    echo "[错误] 端口 $PORT 仍被占用, 请手动检查: lsof -nP -iTCP:$PORT -sTCP:LISTEN" >&2
    exit 1
  fi
  log "Kibana 已被强制停止"
}

# ---------- 4. 启动 ----------
start_kibana() {
  log "启动 Kibana (端口 $PORT, 控制台日志: $CONSOLE_LOG)"
  cd "$KIBANA_HOME" || exit 1
  nohup bin/kibana >> "$CONSOLE_LOG" 2>&1 &
}

# ---------- 5. 等待就绪 ----------
wait_ready() {
  local n=0 ps_ok=false
  if ps -ax -o pid= -o command= >/dev/null 2>&1; then
    ps_ok=true
  fi
  while [ "$n" -lt "$WAIT_START" ]; do
    if port_in_use "$PORT"; then
      pid=$(pids_by_pattern "$PATTERN" | head -1)
      log "Kibana 就绪 (端口 $PORT, PID: ${pid:-?})"
      return 0
    fi
    n=$((n + 1))
    if [ "$ps_ok" = true ] && [ "$n" -eq 20 ] && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      break
    fi
    sleep 1
  done
  echo "[错误] Kibana 未在 ${WAIT_START}s 内就绪, 最近日志:" >&2
  tail -40 "$CONSOLE_LOG" 2>/dev/null >&2 || true
  return 1
}

# ---------- 6. 主流程 ----------
if port_in_use "$PORT" || [ -n "$(pids_by_pattern "$PATTERN")" ]; then
  log "检测到 Kibana 已运行, 执行重启..."
  stop_kibana
else
  log "Kibana 未运行, 直接启动..."
fi

start_kibana
wait_ready || exit 1
log "完成。浏览器打开 http://127.0.0.1:$PORT"
