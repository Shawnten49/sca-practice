#!/usr/bin/env bash
#
# start-leaf.sh —— 美团 Leaf 分布式 ID 服务一键启动/重启脚本
#
# 行为:
#   1. 已运行 (8085 被监听 或 存在 LeafServerApplication 的 java 进程) → 重启
#   2. 未运行 → 直接启动
#   3. 启动后轮询端口, 确认就绪才退出 (失败会打印最近日志)
#
# 依赖: ZooKeeper(2181) 供雪花模式分配 workerId; MySQL(3306) 供号段模式
# JDK21 适配: --add-opens 放开 java.base 模块访问 (Spring Boot 1.5 需要)
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   LEAF_HOME      Leaf 源码目录, 默认 /Users/shawn/tools/leaf
#   LEAF_PORT      HTTP 端口, 默认 8085
#   WAIT_STOP      优雅停止最长等待秒数, 默认 15
#   WAIT_START     启动就绪最长等待秒数, 默认 90
#
# 用法:
#   bash start-leaf.sh
# 验证:
#   curl http://127.0.0.1:8085/api/segment/get/order_id
#   curl http://127.0.0.1:8085/api/snowflake/get/leaf

set -u

# ---------- 1. 定位安装目录和 jar ----------
if [ -z "${LEAF_HOME:-}" ]; then
  if [ -d "/Users/shawn/tools/leaf" ]; then
    LEAF_HOME="/Users/shawn/tools/leaf"
  else
    for candidate in "$HOME"/tools/leaf*; do
      if [ -f "$candidate/leaf-server/target/leaf.jar" ]; then
        LEAF_HOME="$candidate"
        break
      fi
    done
  fi
fi

LEAF_JAR="$LEAF_HOME/leaf-server/target/leaf.jar"
if [ ! -f "$LEAF_JAR" ]; then
  echo "[错误] 未找到 leaf.jar: $LEAF_JAR" >&2
  echo "       请先构建: cd $LEAF_HOME && mvn -pl leaf-server -am clean package -DskipTests -Dmysql-connector-java.version=8.0.33 -Ddruid.version=1.2.21" >&2
  exit 1
fi

PORT="${LEAF_PORT:-8085}"
WAIT_STOP="${WAIT_STOP:-15}"
WAIT_START="${WAIT_START:-90}"
CONSOLE_LOG="$LEAF_HOME/logs/leaf-console.log"
PATTERN='com\.sankuai\.inf\.leaf\.server\.LeafServerApplication'
ADD_OPENS='--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED'

mkdir -p "$LEAF_HOME/logs"

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
stop_leaf() {
  local pids n=0
  pids=$(pids_by_pattern "$PATTERN")

  if [ -z "$pids" ]; then
    if port_in_use "$PORT"; then
      echo "[错误] 端口 $PORT 已被非 Leaf 进程占用:" >&2
      lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2
      exit 1
    fi
    log "Leaf 未在运行, 跳过停止"
    return 0
  fi

  log "停止 Leaf (PID: $(echo $pids | tr '\n' ' ')), 发送 TERM..."
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  while [ "$n" -lt "$WAIT_STOP" ]; do
    if ! port_in_use "$PORT" && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      log "Leaf 已优雅停止"
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
  log "Leaf 已被强制停止"
}

# ---------- 4. 启动 ----------
start_leaf() {
  log "启动 Leaf (端口 $PORT, 控制台日志: $CONSOLE_LOG)"
  cd "$LEAF_HOME/leaf-server" || exit 1
  nohup java $ADD_OPENS -jar target/leaf.jar --server.port="$PORT" >> "$CONSOLE_LOG" 2>&1 &
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
      log "Leaf 就绪 (端口 $PORT, PID: ${pid:-?})"
      return 0
    fi
    n=$((n + 1))
    if [ "$ps_ok" = true ] && [ "$n" -eq 15 ] && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      break
    fi
    sleep 1
  done
  echo "[错误] Leaf 未在 ${WAIT_START}s 内就绪, 最近日志:" >&2
  tail -40 "$CONSOLE_LOG" 2>/dev/null >&2 || true
  return 1
}

# ---------- 6. 主流程 ----------
if port_in_use "$PORT" || [ -n "$(pids_by_pattern "$PATTERN")" ]; then
  log "检测到 Leaf 已运行, 执行重启..."
  stop_leaf
else
  log "Leaf 未运行, 直接启动..."
fi

start_leaf
wait_ready || exit 1
log "完成。验证: curl http://127.0.0.1:$PORT/api/segment/get/order_id"
