#!/usr/bin/env bash
#
# start-mongodb.sh —— MongoDB 一键启动脚本（brew 安装的 mongodb-community@8.0）
#
# 行为:
#   1. 端口已监听 (MONGOD_PORT) → 提示已运行, 不重复启动
#   2. 存在残留 mongod 进程但端口未监听 → 等待其退出 (最多 10s), 未退出则 TERM/KILL 清理
#   3. 未运行 → 启动 mongod (--config MONGOD_CONF)
#   4. 启动后轮询端口, 确认就绪才退出 (失败打印最近日志)
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   MONGOD_BIN    mongod 可执行文件路径
#                 (默认 /opt/homebrew/opt/mongodb-community@8.0/bin/mongod)
#   MONGOD_CONF   mongod 配置文件, 默认 /opt/homebrew/etc/mongod.conf
#   MONGOD_PORT   监听端口, 默认 27017
#   MONGOD_LOG    mongod 日志文件, 默认 /opt/homebrew/var/log/mongodb/mongo.log
#   WAIT_START    启动就绪最长等待秒数, 默认 30
#
# 用法:
#   bash start-mongodb.sh
#   MONGOD_PORT=27018 bash start-mongodb.sh

set -u

MONGOD_BIN="${MONGOD_BIN:-/opt/homebrew/opt/mongodb-community@8.0/bin/mongod}"
MONGOD_CONF="${MONGOD_CONF:-/opt/homebrew/etc/mongod.conf}"
MONGOD_PORT="${MONGOD_PORT:-27017}"
MONGOD_LOG="${MONGOD_LOG:-/opt/homebrew/var/log/mongodb/mongo.log}"
WAIT_START="${WAIT_START:-30}"

log() { echo "[$(date '+%H:%M:%S')] $*"; }

port_in_use() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$MONGOD_PORT" -sTCP:LISTEN >/dev/null 2>&1
  else
    nc -z -G 1 127.0.0.1 "$MONGOD_PORT" >/dev/null 2>&1
  fi
}

mongod_pids() {
  ps -ax -o pid= -o command= 2>/dev/null | awk '$0 ~ /\/mongod( |$)/ && $0 !~ /awk/ && $0 !~ /grep/ {print $1}'
}

# ---------- 1. 校验 ----------
if [ ! -x "$MONGOD_BIN" ]; then
  echo "[错误] 未找到 mongod: $MONGOD_BIN" >&2
  echo "       请设置 MONGOD_BIN=/path/to/mongod 后重试" >&2
  exit 1
fi
if [ ! -f "$MONGOD_CONF" ]; then
  echo "[错误] 未找到 mongod 配置文件: $MONGOD_CONF" >&2
  exit 1
fi

# ---------- 2. 已运行则直接提示 ----------
if port_in_use; then
  log "MongoDB 已运行 (端口 $MONGOD_PORT)"
  exit 0
fi

# ---------- 3. 清理残留进程 ----------
PIDS=$(mongod_pids)
if [ -n "$PIDS" ]; then
  log "发现残留 mongod 进程 (PID: $(echo $PIDS | tr '\n' ' '))，等待退出..."
  n=0
  while [ -n "$(mongod_pids)" ] && [ "$n" -lt 10 ]; do
    sleep 1
    n=$((n + 1))
  done
  PIDS=$(mongod_pids)
  if [ -n "$PIDS" ]; then
    log "残留进程未自动退出，发送 TERM..."
    for pid in $PIDS; do kill -TERM "$pid" 2>/dev/null || true; done
    sleep 3
    PIDS=$(mongod_pids)
    if [ -n "$PIDS" ]; then
      log "TERM 无效，发送 KILL..."
      for pid in $PIDS; do kill -KILL "$pid" 2>/dev/null || true; done
      sleep 1
    fi
  fi
fi

# ---------- 4. 启动 ----------
log "启动 MongoDB (端口 $MONGOD_PORT, 日志: $MONGOD_LOG)"
mkdir -p "$(dirname "$MONGOD_LOG")" 2>/dev/null || true
# 脱离当前 shell 会话启动，避免终端退出把 mongod 一起带走
nohup "$MONGOD_BIN" --config "$MONGOD_CONF" >> "$MONGOD_LOG" 2>&1 &

# ---------- 5. 轮询就绪 ----------
n=0
while [ "$n" -lt "$WAIT_START" ]; do
  if port_in_use; then
    log "MongoDB 就绪 (端口 $MONGOD_PORT)"
    exit 0
  fi
  sleep 1
  n=$((n + 1))
done

echo "[错误] MongoDB 未在 ${WAIT_START}s 内就绪，最近日志:" >&2
tail -30 "$MONGOD_LOG" 2>/dev/null >&2 || true
exit 1
