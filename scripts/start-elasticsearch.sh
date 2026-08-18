#!/usr/bin/env bash
#
# start-elasticsearch.sh —— Elasticsearch 一键启动/重启脚本
#
# 行为:
#   1. 已运行 (9200 被监听 或 存在 Elasticsearch 的 java 进程) → 重启
#   2. 未运行 → 直接启动
#   3. 启动后轮询 HTTP 端口, 确认就绪才退出 (失败会打印最近日志)
#
# 停止策略: 先发 TERM 优雅停止, 超过 WAIT_STOP 秒仍未退出则 kill -9
#
# 说明:
#   - ES 8.x 自带 JDK (jdk.app), 脚本不设 JAVA_HOME, 直接执行 bin/elasticsearch
#   - 本地开发配置已含 discovery.type=single-node 与 xpack.security.enabled=false
#   - 默认堆内存 1g, 可通过 ES_JAVA_OPTS 覆盖 (如 ES_JAVA_OPTS="-Xms2g -Xmx2g")
#   - ES 不允许以 root 运行, 脚本做了拦截提示
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   ES_HOME          安装目录, 默认 /Users/shawn/tools/elasticsearch-8.18.1 (或自动探测 ~/tools/elasticsearch-*)
#   ES_HTTP_PORT     HTTP 端口, 默认 9200
#   ES_TRANSPORT_PORT transport 端口, 默认 9300
#   ES_JAVA_OPTS     附加 JVM 参数, 默认 -Xms1g -Xmx1g
#   WAIT_STOP        优雅停止最长等待秒数, 默认 30
#   WAIT_START       启动就绪最长等待秒数, 默认 120
#
# 用法:
#   bash start-elasticsearch.sh
# 验证: curl http://127.0.0.1:9200

set -u

# ---------- 0. 安全检查: ES 不能以 root 运行 ----------
if [ "$(id -u)" = "0" ]; then
  echo "[错误] Elasticsearch 不允许以 root 运行, 请切换到普通用户后重试" >&2
  exit 1
fi

# ---------- 1. 定位安装目录 ----------
if [ -z "${ES_HOME:-}" ]; then
  if [ -d "/Users/shawn/tools/elasticsearch-8.18.1" ]; then
    ES_HOME="/Users/shawn/tools/elasticsearch-8.18.1"
  else
    for candidate in "$HOME"/tools/elasticsearch-*; do
      if [ -f "$candidate/bin/elasticsearch" ]; then
        ES_HOME="$candidate"
        break
      fi
    done
  fi
fi

if [ ! -f "$ES_HOME/bin/elasticsearch" ]; then
  echo "[错误] 未找到 Elasticsearch 安装目录: $ES_HOME" >&2
  echo "       请设置 ES_HOME=/path/to/elasticsearch 后重试" >&2
  exit 1
fi

HTTP_PORT="${ES_HTTP_PORT:-9200}"
TRANSPORT_PORT="${ES_TRANSPORT_PORT:-9300}"
WAIT_STOP="${WAIT_STOP:-30}"
WAIT_START="${WAIT_START:-120}"
CONSOLE_LOG="$ES_HOME/logs/elasticsearch-console.log"
PATTERN='org\.elasticsearch\.bootstrap\.Elasticsearch'

mkdir -p "$ES_HOME/logs"

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
  ps -ax -o pid= -o command= 2>/dev/null | awk -v p="$pattern" '$0 ~ p && $0 ~ /java/ {print $1}'
}

# ---------- 3. 停止 ----------
stop_es() {
  local pids n=0
  pids=$(pids_by_pattern "$PATTERN")

  if [ -z "$pids" ]; then
    if port_in_use "$HTTP_PORT" || port_in_use "$TRANSPORT_PORT"; then
      echo "[错误] 端口 $HTTP_PORT / $TRANSPORT_PORT 已被非 Elasticsearch 进程占用:" >&2
      lsof -nP -iTCP:"$HTTP_PORT" -sTCP:LISTEN >&2
      exit 1
    fi
    log "Elasticsearch 未在运行, 跳过停止"
    return 0
  fi

  log "停止 Elasticsearch (PID: $(echo $pids | tr '\n' ' ')), 发送 TERM..."
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  while [ "$n" -lt "$WAIT_STOP" ]; do
    if ! port_in_use "$HTTP_PORT" && ! port_in_use "$TRANSPORT_PORT" \
        && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      log "Elasticsearch 已优雅停止"
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

  if port_in_use "$HTTP_PORT" || port_in_use "$TRANSPORT_PORT"; then
    echo "[错误] 端口 $HTTP_PORT / $TRANSPORT_PORT 仍被占用, 请手动检查" >&2
    exit 1
  fi
  log "Elasticsearch 已被强制停止"
}

# ---------- 4. 启动 ----------
start_es() {
  log "启动 Elasticsearch (HTTP $HTTP_PORT / transport $TRANSPORT_PORT, 控制台日志: $CONSOLE_LOG)"
  cd "$ES_HOME" || exit 1
  # ES 通过环境变量 ES_JAVA_OPTS 接收 JVM 参数
  export ES_JAVA_OPTS="${ES_JAVA_OPTS:--Xms1g -Xmx1g}"
  nohup bin/elasticsearch >> "$CONSOLE_LOG" 2>&1 &
}

# ---------- 5. 等待就绪 ----------
wait_ready() {
  local n=0 ps_ok=false
  if ps -ax -o pid= -o command= >/dev/null 2>&1; then
    ps_ok=true
  fi
  while [ "$n" -lt "$WAIT_START" ]; do
    if port_in_use "$HTTP_PORT"; then
      pid=$(pids_by_pattern "$PATTERN" | head -1)
      log "Elasticsearch 就绪 (HTTP $HTTP_PORT, PID: ${pid:-?})"
      return 0
    fi
    n=$((n + 1))
    if [ "$ps_ok" = true ] && [ "$n" -eq 20 ] && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      break
    fi
    sleep 1
  done
  echo "[错误] Elasticsearch 未在 ${WAIT_START}s 内就绪, 最近日志:" >&2
  tail -40 "$CONSOLE_LOG" 2>/dev/null >&2 || true
  tail -40 "$ES_HOME/logs/elasticsearch.log" 2>/dev/null >&2 || true
  return 1
}

# ---------- 6. 主流程 ----------
if port_in_use "$HTTP_PORT" || port_in_use "$TRANSPORT_PORT" || [ -n "$(pids_by_pattern "$PATTERN")" ]; then
  log "检测到 Elasticsearch 已运行, 执行重启..."
  stop_es
else
  log "Elasticsearch 未运行, 直接启动..."
fi

start_es
wait_ready || exit 1
log "完成。验证: curl http://127.0.0.1:$HTTP_PORT"
