#!/usr/bin/env bash
#
# start-rocketmq.sh —— RocketMQ (mqnamesrv + mqbroker) 一键启动/重启脚本
#
# 行为 (两个服务独立判断):
#   1. mqnamesrv 已运行 → 重启; 未运行 → 启动
#   2. mqbroker   已运行 → 重启; 未运行 → 启动
#   3. 启动顺序: 先 namesrv, 等 9876 就绪后再启动 broker
#   4. 启动后轮询端口确认就绪才退出 (失败会打印最近日志)
#
# 停止策略: 先发 TERM 优雅停止, 超过 WAIT_STOP 秒仍未退出则 kill -9
# 运行状态判断: 端口 (9876 / 10911) 或进程 (NamesrvStartup / BrokerStartup|ProxyStartup)
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   ROCKETMQ_HOME     RocketMQ 安装目录, 默认 /Users/shawn/tools/rocketmq-5.5.0
#   NAMESRV_ADDR      broker 连接的 namesrv 地址, 默认 127.0.0.1:9876
#   BROKER_EXTRA_ARGS 附加给 mqbroker 的参数 (如 "-c conf/broker.conf")
#   WAIT_STOP         优雅停止最长等待秒数, 默认 15
#   WAIT_START        启动就绪最长等待秒数, 默认 90
#
# 日志:
#   控制台输出:   $ROCKETMQ_HOME/logs/namesrv-console.log 和 broker-console.log
#   RocketMQ 自身: ~/logs/rocketmqlogs/namesrv.log、broker.log、broker_default.log
#
# 用法:
#   bash start-rocketmq.sh

set -u

# ---------- 1. 定位 RocketMQ 安装目录 ----------
if [ -z "${ROCKETMQ_HOME:-}" ]; then
  if [ -d "/Users/shawn/tools/rocketmq-5.5.0" ]; then
    ROCKETMQ_HOME="/Users/shawn/tools/rocketmq-5.5.0"
  else
    for candidate in "$HOME"/tools/rocketmq-*; do
      if [ -f "$candidate/bin/mqnamesrv" ]; then
        ROCKETMQ_HOME="$candidate"
        break
      fi
    done
  fi
fi

if [ ! -f "$ROCKETMQ_HOME/bin/mqnamesrv" ] || [ ! -f "$ROCKETMQ_HOME/bin/mqbroker" ]; then
  echo "[错误] 未找到 RocketMQ 安装目录: $ROCKETMQ_HOME" >&2
  echo "       请设置 ROCKETMQ_HOME=/path/to/rocketmq 后重试" >&2
  exit 1
fi

NAMESRV_PORT="${NAMESRV_PORT:-9876}"
BROKER_PORT="${BROKER_PORT:-10911}"
NAMESRV_ADDR="${NAMESRV_ADDR:-127.0.0.1:${NAMESRV_PORT}}"
WAIT_STOP="${WAIT_STOP:-15}"
WAIT_START="${WAIT_START:-90}"

NAMESRV_PATTERN='org\.apache\.rocketmq\.namesrv\.NamesrvStartup'
BROKER_PATTERN='org\.apache\.rocketmq\.broker\.BrokerStartup|org\.apache\.rocketmq\.proxy\.ProxyStartup'
NAMESRV_CONSOLE_LOG="$ROCKETMQ_HOME/logs/namesrv-console.log"
BROKER_CONSOLE_LOG="$ROCKETMQ_HOME/logs/broker-console.log"

# ---------- 2. 工具函数 ----------
log() { echo "[$(date '+%H:%M:%S')] $*"; }

# 端口是否在监听
port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  else
    nc -z -G 1 127.0.0.1 "$port" >/dev/null 2>&1
  fi
}

# 按命令行模式匹配 RocketMQ 的 java 进程 PID
pids_by_pattern() {
  local pattern="$1"
  ps -ax -o pid= -o command= 2>/dev/null | awk -v p="$pattern" '$0 ~ p && $0 ~ /java/ {print $1}'
}

# 服务是否在运行: 端口被监听 或 存在对应 java 进程
service_running() {
  local port="$1" pattern="$2"
  port_in_use "$port" || [ -n "$(pids_by_pattern "$pattern")" ]
}

# ---------- 3. 停止单个服务 ----------
stop_service() {
  local name="$1" port="$2" pattern="$3"
  local pids n=0
  pids=$(pids_by_pattern "$pattern")

  if [ -z "$pids" ]; then
    # 端口被占却没有对应进程 → 可能是其他程序占用, 不能乱杀
    if port_in_use "$port"; then
      echo "[错误] 端口 $port 已被非 RocketMQ 的进程占用:" >&2
      lsof -nP -iTCP:"$port" -sTCP:LISTEN >&2
      exit 1
    fi
    log "$name 未在运行, 跳过停止"
    return 0
  fi

  log "停止 $name (PID: $(echo $pids | tr '\n' ' ')), 发送 TERM..."
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  while [ "$n" -lt "$WAIT_STOP" ]; do
    if ! port_in_use "$port" && [ -z "$(pids_by_pattern "$pattern")" ]; then
      log "$name 已优雅停止"
      return 0
    fi
    sleep 1
    n=$((n + 1))
  done

  log "$name 优雅停止超时 (${WAIT_STOP}s), kill -9..."
  for pid in $pids; do
    kill -KILL "$pid" 2>/dev/null || true
  done
  sleep 2

  if port_in_use "$port"; then
    echo "[错误] 端口 $port 仍被占用, 请手动检查: lsof -nP -iTCP:$port -sTCP:LISTEN" >&2
    exit 1
  fi
  log "$name 已被强制停止"
}

# ---------- 4. 启动 ----------
start_namesrv() {
  mkdir -p "$ROCKETMQ_HOME/logs"
  log "启动 mqnamesrv (控制台日志: $NAMESRV_CONSOLE_LOG)"
  cd "$ROCKETMQ_HOME" || exit 1
  nohup sh bin/mqnamesrv >> "$NAMESRV_CONSOLE_LOG" 2>&1 &
}

start_broker() {
  mkdir -p "$ROCKETMQ_HOME/logs"
  log "启动 mqbroker (namesrv=$NAMESRV_ADDR, 控制台日志: $BROKER_CONSOLE_LOG)"
  cd "$ROCKETMQ_HOME" || exit 1
  # shellcheck disable=SC2086
  nohup sh bin/mqbroker -n "$NAMESRV_ADDR" ${BROKER_EXTRA_ARGS:-} >> "$BROKER_CONSOLE_LOG" 2>&1 &
}

# ---------- 5. 等待就绪 ----------
wait_ready() {
  local name="$1" port="$2" pattern="$3" logfile="$4" rmq_log_base="$5"
  local n=0 pid ps_ok=false

  # ps 可用时, 15 秒内 java 进程就消失视为启动失败, 提前报错
  if ps -ax -o pid= -o command= >/dev/null 2>&1; then
    ps_ok=true
  fi

  while [ "$n" -lt "$WAIT_START" ]; do
    if port_in_use "$port"; then
      pid=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | head -1)
      log "$name 就绪, 端口 $port 已监听 (PID: ${pid:-?})"
      return 0
    fi
    n=$((n + 1))
    if [ "$ps_ok" = true ] && [ "$n" -eq 15 ] && [ -z "$(pids_by_pattern "$pattern")" ]; then
      break
    fi
    sleep 1
  done

  echo "[错误] $name 未在 ${WAIT_START}s 内就绪, 最近日志:" >&2
  tail -40 "$logfile" 2>/dev/null >&2 || true
  tail -40 "$HOME/logs/rocketmqlogs/${rmq_log_base}.log" 2>/dev/null >&2 || true
  return 1
}

# ---------- 6. 主流程: 先 namesrv, 后 broker ----------
if service_running "$NAMESRV_PORT" "$NAMESRV_PATTERN"; then
  log "检测到 mqnamesrv 已运行, 执行重启..."
  stop_service "mqnamesrv" "$NAMESRV_PORT" "$NAMESRV_PATTERN"
else
  log "mqnamesrv 未运行, 直接启动..."
fi
start_namesrv
wait_ready "mqnamesrv" "$NAMESRV_PORT" "$NAMESRV_PATTERN" "$NAMESRV_CONSOLE_LOG" namesrv || exit 1

if service_running "$BROKER_PORT" "$BROKER_PATTERN"; then
  log "检测到 mqbroker 已运行, 执行重启..."
  stop_service "mqbroker" "$BROKER_PORT" "$BROKER_PATTERN"
else
  log "mqbroker 未运行, 直接启动..."
fi
start_broker
wait_ready "mqbroker" "$BROKER_PORT" "$BROKER_PATTERN" "$BROKER_CONSOLE_LOG" broker || exit 1

log "完成。RocketMQ 自身日志: ~/logs/rocketmqlogs/namesrv.log、broker.log、broker_default.log"
