#!/usr/bin/env bash
#
# start-seata-server.sh —— seata-server 一键启动/重启脚本
#
# 行为:
#   1. 已运行 (8091 被监听, 或存在 seata-server.jar 的 java 进程) → 先停止再启动
#   2. 未运行 → 直接启动
#   3. 启动后轮询端口, 确认就绪才退出 (启动失败会打印最近日志)
#
# 停止策略: 先发 TERM 优雅停止, 超过 WAIT_STOP 秒仍未退出则 kill -9
# (Seata 2.6 的 shutdown hook 偶发卡住, 之前遇到过 SIGTERM 无效的情况)
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   SEATA_HOME         seata-server 安装目录
#                      (默认自动探测 ~/tools/seata/apache-seata-*/seata-server)
#   SEATA_SERVER_PORT  监听端口, 默认 8091
#   WAIT_STOP          优雅停止最长等待秒数, 默认 15
#   WAIT_START         启动就绪最长等待秒数, 默认 90
#
# 用法:
#   bash start-seata-server.sh
#   SEATA_HOME=/path/to/seata-server bash start-seata-server.sh

set -u

# ---------- 1. 定位 seata-server 安装目录 ----------
if [ -z "${SEATA_HOME:-}" ]; then
  if [ -d "$HOME/tools/seata/apache-seata-2.6.0-incubating-bin/seata-server" ]; then
    SEATA_HOME="$HOME/tools/seata/apache-seata-2.6.0-incubating-bin/seata-server"
  else
    for candidate in "$HOME"/tools/seata/apache-seata-*/seata-server; do
      if [ -d "$candidate" ]; then
        SEATA_HOME="$candidate"
        break
      fi
    done
  fi
fi

if [ ! -f "$SEATA_HOME/bin/seata-server.sh" ]; then
  echo "[错误] 未找到 seata-server 安装目录: $SEATA_HOME" >&2
  echo "       请设置 SEATA_HOME=/path/to/seata-server 后重试" >&2
  exit 1
fi

PORT="${SEATA_SERVER_PORT:-8091}"
WAIT_STOP="${WAIT_STOP:-15}"
WAIT_START="${WAIT_START:-90}"
CONSOLE_LOG="$SEATA_HOME/logs/startup-console.log"

# ---------- 2. 工具函数 ----------
log() { echo "[$(date '+%H:%M:%S')] $*"; }

# 端口是否在监听
port_in_use() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1
  else
    nc -z -G 1 127.0.0.1 "$PORT" >/dev/null 2>&1
  fi
}

# 监听该端口的进程 PID (可能为空)
port_pids() {
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null
}

# seata-server 的 java 进程 PID (按命令行匹配 seata-server.jar)
seata_pids() {
  ps -ax -o pid= -o command= 2>/dev/null | awk '/[j]ava .*seata-server\.jar/ {print $1}'
}

# 端口占用者是否就是 seata-server (ps 不可用时兜底判断)
port_owner_is_seata() {
  local owner
  owner=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$owner" ] || return 1
  ps -p "$owner" -o command= 2>/dev/null | grep -q 'seata-server\.jar'
}

# ---------- 3. 停止 ----------
stop_seata() {
  local pids
  pids=$(seata_pids)

  # ps 拿不到进程时, 用端口占用者的命令行兜底识别
  if [ -z "$pids" ] && port_in_use && port_owner_is_seata; then
    pids=$(port_pids)
  fi

  if [ -z "$pids" ]; then
    # 端口被占却没有 seata 进程 → 可能是其他程序占用, 不能乱杀
    if port_in_use; then
      echo "[错误] 端口 $PORT 已被非 seata-server 的进程占用:" >&2
      lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2
      exit 1
    fi
    return 0
  fi

  log "停止 seata-server (PID: $(echo $pids | tr '\n' ' ')), 发送 TERM..."
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  local n=0
  while [ "$n" -lt "$WAIT_STOP" ]; do
    if ! port_in_use && [ -z "$(seata_pids)" ]; then
      log "seata-server 已优雅停止"
      return 0
    fi
    sleep 1
    n=$((n + 1))
  done

  log "优雅停止超时 (${WAIT_STOP}s), 强制 kill -9..."
  for pid in $pids; do
    kill -KILL "$pid" 2>/dev/null || true
  done
  sleep 2

  if port_in_use; then
    echo "[错误] 端口 $PORT 仍被占用, 请手动检查: lsof -nP -iTCP:$PORT -sTCP:LISTEN" >&2
    exit 1
  fi
  log "seata-server 已被强制停止"
}

# ---------- 4. 启动 ----------
start_seata() {
  mkdir -p "$SEATA_HOME/logs"
  log "启动 seata-server (控制台日志: $CONSOLE_LOG)"
  # seata-server.sh 内部会自己 nohup 后台运行 java, 这里只负责把输出固定到日志文件
  cd "$SEATA_HOME" || exit 1
  nohup sh bin/seata-server.sh >> "$CONSOLE_LOG" 2>&1 &
}

# ---------- 5. 等待就绪 ----------
wait_ready() {
  local n=0 pid ps_ok=false

  # ps 可用时, 15 秒内 java 进程就消失视为启动失败, 提前报错
  if ps -ax -o pid= -o command= >/dev/null 2>&1; then
    ps_ok=true
  fi

  while [ "$n" -lt "$WAIT_START" ]; do
    if port_in_use; then
      pid=$(port_pids | head -1)
      log "seata-server 就绪, 端口 $PORT 已监听 (PID: ${pid:-?})"
      return 0
    fi
    n=$((n + 1))
    if [ "$ps_ok" = true ] && [ "$n" -eq 15 ] && [ -z "$(seata_pids)" ]; then
      break
    fi
    sleep 1
  done

  echo "[错误] seata-server 未在 ${WAIT_START}s 内就绪, 最近日志:" >&2
  tail -40 "$CONSOLE_LOG" 2>/dev/null >&2 || true
  tail -40 "$SEATA_HOME/logs/seata-server.${PORT}.error.log" 2>/dev/null >&2 || true
  return 1
}

# ---------- 6. 主流程 ----------
if port_in_use || [ -n "$(seata_pids)" ]; then
  log "检测到 seata-server 已运行, 执行重启..."
  stop_seata
else
  log "seata-server 未运行, 直接启动..."
fi

start_seata

if ! wait_ready; then
  exit 1
fi

# 尽力记录 PID 文件, 方便后续排查
pid=$(seata_pids | head -1)
if [ -n "$pid" ]; then
  echo "$pid" > "$SEATA_HOME/logs/seata-server.pid"
  log "已记录 PID 到 logs/seata-server.pid"
fi

log "完成。查看运行日志: tail -f $SEATA_HOME/logs/seata-server.${PORT}.all.log"
