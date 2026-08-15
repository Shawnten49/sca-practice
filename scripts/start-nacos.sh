#!/usr/bin/env bash
#
# start-nacos.sh —— Nacos Server 一键启动/重启脚本
#
# 行为:
#   1. 已运行 (8848/8847 任一被监听, 或存在 nacos-server.jar 的 java 进程) → 重启
#   2. 未运行 → 直接启动 (standalone 单机模式)
#   3. 启动后轮询 API 端口, 确认就绪才退出 (失败会打印最近日志)
#
# 停止策略: 先发 TERM 优雅停止, 超过 WAIT_STOP 秒仍未退出则 kill -9
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   NACOS_HOME          安装目录, 默认 /Users/shawn/tools/nacos
#   NACOS_API_PORT      API 端口, 默认 8848
#   NACOS_CONSOLE_PORT  控制台端口, 默认 8847 (与 conf/application.properties 的 nacos.console.port 一致)
#   WAIT_STOP           优雅停止最长等待秒数, 默认 15
#   WAIT_START          启动就绪最长等待秒数, 默认 90
#
# 用法:
#   bash start-nacos.sh
# 控制台: http://127.0.0.1:8847/nacos

set -u

# ---------- 1. 定位 Nacos 安装目录 ----------
if [ -z "${NACOS_HOME:-}" ]; then
  if [ -d "/Users/shawn/tools/nacos" ]; then
    NACOS_HOME="/Users/shawn/tools/nacos"
  else
    for candidate in "$HOME"/tools/nacos*; do
      if [ -f "$candidate/bin/startup.sh" ]; then
        NACOS_HOME="$candidate"
        break
      fi
    done
  fi
fi

if [ ! -f "$NACOS_HOME/bin/startup.sh" ] || [ ! -f "$NACOS_HOME/conf/application.properties" ]; then
  echo "[错误] 未找到 Nacos 安装目录: $NACOS_HOME" >&2
  echo "       请设置 NACOS_HOME=/path/to/nacos 后重试" >&2
  exit 1
fi

API_PORT="${NACOS_API_PORT:-8848}"
CONSOLE_PORT="${NACOS_CONSOLE_PORT:-8847}"
WAIT_STOP="${WAIT_STOP:-15}"
WAIT_START="${WAIT_START:-90}"
CONSOLE_LOG="$NACOS_HOME/logs/startup-console.log"
PATTERN='nacos-server\.jar|nacos\.nacos'

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

# 服务是否在运行: API/控制台任一端口被监听 或 存在对应 java 进程
service_running() {
  port_in_use "$API_PORT" || port_in_use "$CONSOLE_PORT" || [ -n "$(pids_by_pattern "$PATTERN")" ]
}

all_ports_free() {
  ! port_in_use "$API_PORT" && ! port_in_use "$CONSOLE_PORT"
}

# ---------- 3. 停止 ----------
stop_nacos() {
  local pids n=0
  pids=$(pids_by_pattern "$PATTERN")

  if [ -z "$pids" ]; then
    # 端口被占却没有 nacos 进程 → 可能是其他程序占用, 不能乱杀
    if ! all_ports_free; then
      echo "[错误] 端口 $API_PORT / $CONSOLE_PORT 已被非 Nacos 的进程占用:" >&2
      lsof -nP -iTCP:"$API_PORT" -sTCP:LISTEN >&2
      lsof -nP -iTCP:"$CONSOLE_PORT" -sTCP:LISTEN >&2
      exit 1
    fi
    log "nacos-server 未在运行, 跳过停止"
    return 0
  fi

  log "停止 nacos-server (PID: $(echo $pids | tr '\n' ' ')), 发送 TERM..."
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  while [ "$n" -lt "$WAIT_STOP" ]; do
    if all_ports_free && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      log "nacos-server 已优雅停止"
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

  if ! all_ports_free; then
    echo "[错误] 端口仍被占用, 请手动检查: lsof -nP -iTCP:$API_PORT -sTCP:LISTEN" >&2
    exit 1
  fi
  log "nacos-server 已被强制停止"
}

# ---------- 4. 启动 ----------
start_nacos() {
  mkdir -p "$NACOS_HOME/logs"

  # 防呆: 该 key 为空值时 startup.sh 会交互式等待输入, 后台启动会卡住
  if grep -q '^nacos\.core\.auth\.plugin\.nacos\.token\.secret\.key=$' "$NACOS_HOME/conf/application.properties"; then
    echo "[错误] conf/application.properties 中 nacos.core.auth.plugin.nacos.token.secret.key 是空值," >&2
    echo "       startup.sh 会交互式等待输入而卡住, 请先补一个 Base64 密钥再启动" >&2
    exit 1
  fi

  log "启动 nacos-server (standalone, API $API_PORT / 控制台 $CONSOLE_PORT, 日志: $CONSOLE_LOG)"
  cd "$NACOS_HOME" || exit 1
  nohup sh bin/startup.sh -m standalone >> "$CONSOLE_LOG" 2>&1 &
}

# ---------- 5. 等待就绪 ----------
wait_ready() {
  local n=0 pid ps_ok=false

  if ps -ax -o pid= -o command= >/dev/null 2>&1; then
    ps_ok=true
  fi

  while [ "$n" -lt "$WAIT_START" ]; do
    if port_in_use "$API_PORT"; then
      pid=$(lsof -nP -iTCP:"$API_PORT" -sTCP:LISTEN -t 2>/dev/null | head -1)
      log "nacos-server 就绪, API 端口 $API_PORT 已监听 (PID: ${pid:-?})"
      return 0
    fi
    n=$((n + 1))
    if [ "$ps_ok" = true ] && [ "$n" -eq 15 ] && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      break
    fi
    sleep 1
  done

  echo "[错误] nacos-server 未在 ${WAIT_START}s 内就绪, 最近日志:" >&2
  tail -40 "$CONSOLE_LOG" 2>/dev/null >&2 || true
  tail -40 "$NACOS_HOME/logs/startup.log" 2>/dev/null >&2 || true
  tail -40 "$NACOS_HOME/logs/nacos.log" 2>/dev/null >&2 || true
  return 1
}

# ---------- 6. 主流程 ----------
if service_running; then
  log "检测到 nacos-server 已运行, 执行重启..."
  stop_nacos
else
  log "nacos-server 未运行, 直接启动..."
fi

start_nacos

if ! wait_ready; then
  exit 1
fi

pid=$(pids_by_pattern "$PATTERN" | head -1)
if [ -n "$pid" ]; then
  echo "$pid" > "$NACOS_HOME/logs/nacos.pid"
  log "已记录 PID 到 logs/nacos.pid"
fi

log "完成。控制台: http://127.0.0.1:${CONSOLE_PORT}/nacos  (API: ${API_PORT})"
