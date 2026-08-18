#!/usr/bin/env bash
#
# init-xxl-job-tasks.sh —— 初始化 XXL-JOB 调度配置（执行器组 + 任务）
#
# 行为:
#   1. 登录 XXL-JOB Admin (默认 http://127.0.0.1:7080, 账号 admin/123456)
#   2. 查找执行器组 task-service, 不存在则创建 (自动注册方式)
#   3. 依次创建 3 个任务 (分片广播 + CRON), 已存在则跳过 (按 JobHandler 去重)
#
# 说明:
#   - 任务创建后为"停止"状态, 需在 Admin 控制台手动点"启动"才按 cron 调度
#   - 代码里的 @XxlJob handler 是自动注册的, 本脚本只负责 Admin 侧的任务配置
#
# 可选环境变量 (都有默认值, 一般不用设置):
#   XXL_ADMIN_URL        调度中心地址, 默认 http://127.0.0.1:7080
#   XXL_ADMIN_USER       登录账号, 默认 admin
#   XXL_ADMIN_PASSWORD   登录密码, 默认 123456
#   XXL_APPNAME          执行器 appname, 默认 task-service
#   XXL_GROUP_TITLE      执行器分组名称, 默认 任务调度服务
#   XXL_JOB_AUTHOR       任务负责人, 默认 shawn
#
# 用法:
#   bash init-xxl-job-tasks.sh
#   XXL_ADMIN_URL=http://10.0.0.10:7080 XXL_ADMIN_PASSWORD=xxx bash init-xxl-job-tasks.sh

set -u

ADMIN_URL="${XXL_ADMIN_URL:-http://127.0.0.1:7080}"
ADMIN_URL="${ADMIN_URL%/}"   # 去掉尾部斜杠
ADMIN_USER="${XXL_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${XXL_ADMIN_PASSWORD:-123456}"
APPNAME="${XXL_APPNAME:-task-service}"
GROUP_TITLE="${XXL_GROUP_TITLE:-任务调度服务}"
AUTHOR="${XXL_JOB_AUTHOR:-shawn}"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'

COOKIE_JAR="$(mktemp /tmp/xxl-init-cookie.XXXXXX)"
trap 'rm -f "$COOKIE_JAR"' EXIT

log()  { echo -e "${GREEN}[$(date '+%H:%M:%S')]${NC} $*"; } >&2
warn() { echo -e "${YELLOW}[$(date '+%H:%M:%S')]${NC} $*"; } >&2
die()  { echo -e "${RED}[错误]${NC} $*" >&2; exit 1; }

# ---------- 工具函数 ----------
api_post() {
  local path="$1"; shift
  curl -s -b "$COOKIE_JAR" -c "$COOKIE_JAR" -X POST "$ADMIN_URL$path" "$@"
}

# ---------- 1. 登录 ----------
login() {
  local resp
  resp=$(api_post "/auth/doLogin" \
    --data-urlencode "userName=$ADMIN_USER" \
    --data-urlencode "password=$ADMIN_PASSWORD")
  if ! echo "$resp" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("code")==200 else 1)' 2>/dev/null; then
    die "Admin 登录失败: $ADMIN_URL（请检查 XXL_ADMIN_URL / XXL_ADMIN_USER / XXL_ADMIN_PASSWORD）"
  fi
  log "Admin 登录成功: $ADMIN_URL"
}

# ---------- 2. 执行器组: 查找/创建 ----------
get_jobgroup_id() {
  api_post "/jobgroup/pageList" "start=0&length=50&appname=$APPNAME" \
  | python3 -c '
import json,sys
d=json.load(sys.stdin)
rows=[r for r in d["data"]["data"] if r["appname"]==sys.argv[1]]
print(rows[0]["id"] if rows else "")
' "$APPNAME" 2>/dev/null
}

ensure_jobgroup() {
  local gid
  gid=$(get_jobgroup_id)
  if [ -n "$gid" ]; then
    log "执行器组已存在: $APPNAME (id=$gid)"
    echo "$gid"
    return
  fi
  api_post "/jobgroup/insert" \
    --data-urlencode "appname=$APPNAME" \
    --data-urlencode "title=$GROUP_TITLE" \
    --data-urlencode "addressType=0" \
    --data-urlencode "addressList=" >/dev/null
  gid=$(get_jobgroup_id)
  [ -n "$gid" ] || die "执行器组创建失败: $APPNAME"
  log "已创建执行器组: $APPNAME (id=$gid)"
  echo "$gid"
}

# ---------- 3. 任务: 按 JobHandler 查找/创建 ----------
get_job_id_by_handler() {
  local handler="$1"
  api_post "/jobinfo/pageList" \
    --data-urlencode "offset=0" \
    --data-urlencode "pagesize=50" \
    --data-urlencode "jobGroup=$GROUP_ID" \
    --data-urlencode "triggerStatus=-1" \
    --data-urlencode "jobDesc=" \
    --data-urlencode "executorHandler=$handler" \
    --data-urlencode "author=" \
  | python3 -c '
import json,sys
d=json.load(sys.stdin)
rows=d["data"]["data"]
print(rows[0]["id"] if rows else "")
' 2>/dev/null
}

ensure_job() {
  local desc="$1" cron="$2" handler="$3" jid
  jid=$(get_job_id_by_handler "$handler")
  if [ -n "$jid" ]; then
    log "任务已存在: $desc ($handler, id=$jid)，跳过"
    return
  fi
  api_post "/jobinfo/insert" \
    --data-urlencode "jobGroup=$GROUP_ID" \
    --data-urlencode "jobDesc=$desc" \
    --data-urlencode "scheduleType=CRON" \
    --data-urlencode "scheduleConf=$cron" \
    --data-urlencode "misfireStrategy=DO_NOTHING" \
    --data-urlencode "executorRouteStrategy=SHARDING_BROADCAST" \
    --data-urlencode "executorHandler=$handler" \
    --data-urlencode "executorParam=" \
    --data-urlencode "executorBlockStrategy=SERIAL_EXECUTION" \
    --data-urlencode "executorTimeout=0" \
    --data-urlencode "executorFailRetryCount=0" \
    --data-urlencode "glueType=BEAN" \
    --data-urlencode "author=$AUTHOR" \
    --data-urlencode "alarmEmail=" \
    --data-urlencode "childJobId=" >/dev/null
  jid=$(get_job_id_by_handler "$handler")
  [ -n "$jid" ] || die "任务创建失败: $desc ($handler)"
  log "已创建任务: $desc ($handler, id=$jid, cron=$cron)"
}

# ---------- 4. 主流程 ----------
case "${1:-}" in
  -h|--help)
    sed -n '2,20p' "$0"
    exit 0
    ;;
esac

command -v curl >/dev/null 2>&1 || die "未找到 curl"
command -v python3 >/dev/null 2>&1 || die "未找到 python3（用于解析 Admin 接口响应）"

login
GROUP_ID=$(ensure_jobgroup)

ensure_job "订单缓存刷新" "0 30 2 * * ?" "refreshOrderTask"
ensure_job "用户缓存刷新" "0 30 3 * * ?" "refreshUserTask"
ensure_job "商品缓存刷新" "0 0 * * * ?" "refreshProduct"

echo
warn "全部完成。任务当前为「停止」状态，请到 Admin 任务管理里点击「启动」后才会按 cron 调度；"
warn "执行器 task-service 需处于运行状态（端口 9999）才会被调度到。"
