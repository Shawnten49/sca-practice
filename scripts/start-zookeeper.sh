#!/usr/bin/env bash
#
# start-zookeeper.sh —— ZooKeeper 一键启动/重启脚本（Leaf 雪花模式依赖）
#
# 行为:
#   1. 已运行 (2181 被监听 或 存在 QuorumPeerMain 的 java 进程) → 重启
#   2. 未运行 → 直接启动
#   3. 启动后轮询端口, 确认就绪才退出 (失败会打印最近日志)
#
# 停止策略: 先发 TERM 优雅停止, 超过 WAIT_STOP 秒仍未退出则 kill -9
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   ZOOKEEPER_HOME  安装目录, 默认 /Users/shawn/tools/zookeeper
#   ZK_PORT         客户端端口, 默认 2181
#   WAIT_STOP       优雅停止最长等待秒数, 默认 15
#   WAIT_START      启动就绪最长等待秒数, 默认 90
#
# 用法:
#   bash start-zookeeper.sh
# 验证: printf 'ls /\nquit\n' | $ZOOKEEPER_HOME/bin/zkCli.sh -server 127.0.0.1:2181

set -u

# ---------- 1. 定位安装目录 ----------
if [ -z "${ZOOKEEPER_HOME:-}" ]; then
  if [ -d "/Users/shawn/tools/zookeeper" ]; then
    ZOOKEEPER_HOME="/Users/shawn/tools/zookeeper"
  else
    for candidate in "$HOME"/tools/zookeeper*; do
      if [ -f "$candidate/bin/zkServer.sh" ]; then
        ZOOKEEPER_HOME="$candidate"
        break
      fi
    done
  fi
fi

if [ ! -f "$ZOOKEEPER_HOME/bin/zkServer.sh" ]; then
  echo "[错误] 未找到 ZooKeeper 安装目录: $ZOOKEEPER_HOME" >&2
  echo "       请设置 ZOOKEEPER_HOME=/path/to/zookeeper 后重试" >&2
  exit 1
fi

PORT="${ZK_PORT:-2181}"
WAIT_STOP="${WAIT_STOP:-15}"
WAIT_START="${WAIT_START:-90}"
CONSOLE_LOG="$ZOOKEEPER_HOME/logs/zookeeper-console.log"
PATTERN='org\.apache\.zookeeper\.server\.quorum\.QuorumPeerMain'

mkdir -p "$ZOOKEEPER_HOME/logs"

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
stop_zk() {
  local pids n=0
  pids=$(pids_by_pattern "$PATTERN")

  if [ -z "$pids" ]; then
    if port_in_use "$PORT"; then
      echo "[错误] 端口 $PORT 已被非 ZooKeeper 进程占用:" >&2
      lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2
      exit 1
    fi
    log "ZooKeeper 未在运行, 跳过停止"
    return 0
  fi

  log "停止 ZooKeeper (PID: $(echo $pids | tr '\n' ' ')), 发送 TERM..."
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  while [ "$n" -lt "$WAIT_STOP" ]; do
    if ! port_in_use "$PORT" && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      log "ZooKeeper 已优雅停止"
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
  log "ZooKeeper 已被强制停止"
}

# ---------- 4. 启动 ----------
start_zk() {
  log "启动 ZooKeeper (端口 $PORT, 控制台日志: $CONSOLE_LOG)"
  cd "$ZOOKEEPER_HOME" || exit 1
  # 前台模式 + nohup 转后台, 保证日志可查且进程独立
  nohup sh bin/zkServer.sh start-foreground >> "$CONSOLE_LOG" 2>&1 &
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
      log "ZooKeeper 就绪 (端口 $PORT, PID: ${pid:-?})"
      return 0
    fi
    n=$((n + 1))
    if [ "$ps_ok" = true ] && [ "$n" -eq 15 ] && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      break
    fi
    sleep 1
  done
  echo "[错误] ZooKeeper 未在 ${WAIT_START}s 内就绪, 最近日志:" >&2
  tail -30 "$CONSOLE_LOG" 2>/dev/null >&2 || true
  return 1
}

# ---------- 6. 主流程 ----------
if port_in_use "$PORT" || [ -n "$(pids_by_pattern "$PATTERN")" ]; then
  log "检测到 ZooKeeper 已运行, 执行重启..."
  stop_zk
else
  log "ZooKeeper 未运行, 直接启动..."
fi

start_zk
wait_ready || exit 1
log "完成。验证: printf 'ls /\\nquit\\n' | $ZOOKEEPER_HOME/bin/zkCli.sh -server 127.0.0.1:$PORT"
