#!/usr/bin/env bash
#
# start-rocketmq-dashboard.sh —— RocketMQ Dashboard 一键启动/重启脚本
#
# 行为:
#   1. 已运行 (7070 被监听, 或存在 rocketmq-dashboard-2.1.0.jar 的 java 进程) → 先停止再启动
#   2. 未运行 → 直接启动
#   3. 启动后轮询端口, 确认就绪才退出 (启动失败会打印最近日志)
#
# 停止策略: 先发 TERM 优雅停止 (Spring Boot shutdown hook), 超过 WAIT_STOP 秒仍未退出则 kill -9
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   DASHBOARD_HOME    rocketmq-dashboard 项目目录 (默认 ~/tools/rocketmq-dashboard)
#   DASHBOARD_PORT    Dashboard 监听端口, 默认 7070
#   WAIT_STOP         优雅停止最长等待秒数, 默认 15
#   WAIT_START        启动就绪最长等待秒数, 默认 60
#
# 用法:
#   bash start-rocketmq-dashboard.sh
#   DASHBOARD_HOME=/path/to/rocketmq-dashboard bash start-rocketmq-dashboard.sh

set -u

# ---------- 1. 定位项目目录 ----------
DASHBOARD_HOME="${DASHBOARD_HOME:-$HOME/tools/rocketmq-dashboard}"
JAR_NAME="rocketmq-dashboard-2.1.0.jar"
JAR_PATH="$DASHBOARD_HOME/target/$JAR_NAME"

if [ ! -f "$JAR_PATH" ]; then
  echo "[错误] 未找到 Dashboard jar: $JAR_PATH" >&2
  echo "       请确认先执行过 mvn clean package -Dmaven.test.skip=true, 或设置 DASHBOARD_HOME" >&2
  exit 1
fi

PORT="${DASHBOARD_PORT:-7070}"
WAIT_STOP="${WAIT_STOP:-15}"
WAIT_START="${WAIT_START:-60}"
CONSOLE_LOG="$DASHBOARD_HOME/logs/startup-console.log"

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

# Dashboard 的 java 进程 PID (按命令行匹配 jar 名)
dashboard_pids() {
  ps -ax -o pid= -o command= 2>/dev/null | awk "/[j]ava .*$JAR_NAME/ {print \$1}"
}

# 端口占用者是否就是 Dashboard (ps 不可用时兜底判断)
port_owner_is_dashboard() {
  local owner
  owner=$(lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | head -1)
  [ -n "$owner" ] || return 1
  ps -p "$owner" -o command= 2>/dev/null | grep -q "$JAR_NAME"
}

# ---------- 3. 停止 ----------
stop_dashboard() {
  local pids
  pids=$(dashboard_pids)

  # ps 拿不到进程时, 用端口占用者的命令行兜底识别
  if [ -z "$pids" ] && port_in_use && port_owner_is_dashboard; then
    pids=$(port_pids)
  fi

  if [ -z "$pids" ]; then
    # 端口被占却没有 Dashboard 进程 → 可能是其他程序占用, 不能乱杀
    if port_in_use; then
      echo "[错误] 端口 $PORT 已被非 Dashboard 的进程占用:" >&2
      lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2
      exit 1
    fi
    return 0
  fi

  log "停止 RocketMQ Dashboard (PID: $(echo $pids | tr '\n' ' ')), 发送 TERM..."
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  local n=0
  while [ "$n" -lt "$WAIT_STOP" ]; do
    if ! port_in_use && [ -z "$(dashboard_pids)" ]; then
      log "RocketMQ Dashboard 已优雅停止"
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
  log "RocketMQ Dashboard 已被强制停止"
}

# ---------- 4. 启动 ----------
start_dashboard() {
  mkdir -p "$DASHBOARD_HOME/logs"
  log "启动 RocketMQ Dashboard (控制台日志: $CONSOLE_LOG)"
  cd "$DASHBOARD_HOME" || exit 1
  nohup java -jar "$JAR_PATH" >> "$CONSOLE_LOG" 2>&1 &
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
      log "RocketMQ Dashboard 就绪, 端口 $PORT 已监听 (PID: ${pid:-?})"
      return 0
    fi
    n=$((n + 1))
    if [ "$ps_ok" = true ] && [ "$n" -eq 15 ] && [ -z "$(dashboard_pids)" ]; then
      break
    fi
    sleep 1
  done

  echo "[错误] RocketMQ Dashboard 未在 ${WAIT_START}s 内就绪, 最近日志:" >&2
  tail -40 "$CONSOLE_LOG" 2>/dev/null >&2 || true
  tail -40 "$HOME/logs/dashboardlogs/rocketmq-dashboard.log" 2>/dev/null >&2 || true
  return 1
}

# ---------- 6. 主流程 ----------
if port_in_use || [ -n "$(dashboard_pids)" ]; then
  log "检测到 RocketMQ Dashboard 已运行, 执行重启..."
  stop_dashboard
else
  log "RocketMQ Dashboard 未运行, 直接启动..."
fi

start_dashboard

if ! wait_ready; then
  exit 1
fi

# 尽力记录 PID 文件, 方便后续排查
pid=$(dashboard_pids | head -1)
if [ -n "$pid" ]; then
  echo "$pid" > "$DASHBOARD_HOME/logs/rocketmq-dashboard.pid"
  log "已记录 PID 到 logs/rocketmq-dashboard.pid"
fi

log "完成。访问 http://127.0.0.1:$PORT  · 运行日志: tail -f $HOME/logs/dashboardlogs/rocketmq-dashboard.log"
