#!/usr/bin/env bash
#
# start-xxl-job-admin.sh —— XXL-JOB 调度中心 一键启动/重启脚本
#
# 行为:
#   1. 已运行 (7080 被监听 或 存在 xxl-job-admin jar 的 java 进程) → 停止后重新启动
#   2. 未运行 → 直接启动
#   3. 启动后轮询端口, 确认就绪才退出 (失败会打印最近日志)
#
# 停止策略: 先发 TERM 优雅停止, 超过 WAIT_STOP 秒仍未退出则 kill -9
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   XXL_JOB_HOME     XXL-JOB 源码/构建根目录, 默认 /Users/shawn/tools/xxl-job
#                    (自动探测 ~/tools/xxl-job*)
#   XXL_JOB_PORT     调度中心端口, 默认 7080 (保持该端口, 勿改)
#   JAVA_BIN         java 可执行文件, 默认使用 PATH 中的 java
#   WAIT_STOP        优雅停止最长等待秒数, 默认 15
#   WAIT_START       启动就绪最长等待秒数, 默认 90
#
# 用法:
#   bash start-xxl-job-admin.sh
# 验证: 浏览器打开 http://127.0.0.1:7080/ (本构建 context-path=/，默认账号 admin/123456)

set -u

# ---------- 1. 定位安装目录和 jar ----------
if [ -z "${XXL_JOB_HOME:-}" ]; then
  if [ -d "/Users/shawn/tools/xxl-job" ]; then
    XXL_JOB_HOME="/Users/shawn/tools/xxl-job"
  else
    for candidate in "$HOME"/tools/xxl-job*; do
      if [ -d "$candidate" ]; then
        XXL_JOB_HOME="$candidate"
        break
      fi
    done
  fi
fi

JAR=""
if [ -f "$XXL_JOB_HOME/xxl-job-admin/target/xxl-job-admin-3.4.2.jar" ]; then
  JAR="$XXL_JOB_HOME/xxl-job-admin/target/xxl-job-admin-3.4.2.jar"
else
  for jar in "$XXL_JOB_HOME"/xxl-job-admin/target/xxl-job-admin-*.jar; do
    case "$jar" in
      *-javadoc.jar|*-sources.jar) continue ;;
    esac
    JAR="$jar"
    break
  done
fi

if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
  echo "[错误] 未找到 xxl-job-admin jar: $XXL_JOB_HOME/xxl-job-admin/target/xxl-job-admin-*.jar" >&2
  echo "       请设置 XXL_JOB_HOME=/path/to/xxl-job 后重试" >&2
  exit 1
fi

PORT="${XXL_JOB_PORT:-7080}"
WAIT_STOP="${WAIT_STOP:-15}"
WAIT_START="${WAIT_START:-90}"
CONSOLE_LOG="$XXL_JOB_HOME/logs/xxl-job-admin-console.log"
JAVA_BIN="${JAVA_BIN:-$(command -v java)}"
# 运行命令为 java -jar xxl-job-admin-*.jar，命令行不含主类，按 jar 路径匹配
PATTERN='xxl-job-admin-[0-9][^ ]*\.jar'

mkdir -p "$XXL_JOB_HOME/logs"

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
  ps -ax -o pid= -o command= 2>/dev/null | awk -v p="$pattern" '$0 ~ p && $0 ~ /java/ && $0 !~ /awk/ {print $1}'
}

# ---------- 3. 停止 ----------
stop_xxl() {
  local pids n=0
  pids=$(pids_by_pattern "$PATTERN")

  if [ -z "$pids" ]; then
    if port_in_use "$PORT"; then
      echo "[错误] 端口 $PORT 已被非 XXL-JOB 进程占用:" >&2
      lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >&2
      exit 1
    fi
    log "XXL-JOB 未在运行, 跳过停止"
    return 0
  fi

  log "停止 XXL-JOB (PID: $(echo $pids | tr '\n' ' ')), 发送 TERM..."
  for pid in $pids; do
    kill -TERM "$pid" 2>/dev/null || true
  done

  while [ "$n" -lt "$WAIT_STOP" ]; do
    if ! port_in_use "$PORT" && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      log "XXL-JOB 已优雅停止"
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
  log "XXL-JOB 已被强制停止"
}

# ---------- 4. 启动 ----------
start_xxl() {
  log "启动 XXL-JOB (端口 $PORT, 控制台日志: $CONSOLE_LOG)"
  cd "$XXL_JOB_HOME/xxl-job-admin" || exit 1
  # 显式指定端口, 保证固定为 7080
  nohup "$JAVA_BIN" -jar "$JAR" --server.port="$PORT" >> "$CONSOLE_LOG" 2>&1 &
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
      log "XXL-JOB 就绪 (端口 $PORT, PID: ${pid:-?})"
      return 0
    fi
    n=$((n + 1))
    if [ "$ps_ok" = true ] && [ "$n" -eq 15 ] && [ -z "$(pids_by_pattern "$PATTERN")" ]; then
      break
    fi
    sleep 1
  done
  echo "[错误] XXL-JOB 未在 ${WAIT_START}s 内就绪, 最近日志:" >&2
  tail -40 "$CONSOLE_LOG" 2>/dev/null >&2 || true
  return 1
}

# ---------- 6. 主流程 ----------
if port_in_use "$PORT" || [ -n "$(pids_by_pattern "$PATTERN")" ]; then
  log "检测到 XXL-JOB 已运行, 执行重启..."
  stop_xxl
else
  log "XXL-JOB 未运行, 直接启动..."
fi

start_xxl
wait_ready || exit 1
log "完成。验证: curl http://127.0.0.1:$PORT/"
