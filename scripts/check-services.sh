#!/usr/bin/env bash
#
# check-services.sh —— 检测 Nacos / RocketMQ / Seata / Sentinel 四个服务状态
#
# 行为:
#   已启动 → 告知该服务正在运行
#   未启动 → 告知该服务未启动, 并询问是否立即启动 (y 启动 / N 跳过)
#
# 用法:
#   bash check-services.sh       交互式: 每个未启动的服务都会询问
#   bash check-services.sh -y    自动启动所有未启动的服务 (不询问)
#   bash check-services.sh -n    只检测状态, 不启动任何服务
#
# 端口与环境变量与四个 start-*.sh 脚本保持一致:
#   Nacos:    NACOS_API_PORT(8848) / NACOS_CONSOLE_PORT(8847)
#   RocketMQ: NAMESRV_PORT(9876) / BROKER_PORT(10911)
#   Seata:    SEATA_SERVER_PORT(8091)
#   Sentinel: SENTINEL_PORT(8858)

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

AUTO_START=false
CHECK_ONLY=false
while [ $# -gt 0 ]; do
  case "$1" in
    -y|--yes) AUTO_START=true ;;
    -n|--no)  CHECK_ONLY=true ;;
    -h|--help)
      sed -n '2,14p' "$0"
      exit 0
      ;;
    *)
      echo "未知参数: $1 (支持 -y / -n / -h)" >&2
      exit 1
      ;;
  esac
  shift
done

NACOS_API_PORT="${NACOS_API_PORT:-8848}"
NACOS_CONSOLE_PORT="${NACOS_CONSOLE_PORT:-8847}"
NAMESRV_PORT="${NAMESRV_PORT:-9876}"
BROKER_PORT="${BROKER_PORT:-10911}"
SEATA_PORT="${SEATA_SERVER_PORT:-8091}"
SENTINEL_PORT="${SENTINEL_PORT:-8858}"

GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
NC='\033[0m'

# ---------- 工具函数 ----------
port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
  else
    nc -z -G 1 127.0.0.1 "$port" >/dev/null 2>&1
  fi
}

print_running() { echo -e "${GREEN}[运行中]${NC} $*"; }
print_down()    { echo -e "${RED}[未启动]${NC} $*"; }

# 服务未启动时调用: 提示 + 询问是否启动 (y 启动 / N 跳过)
start_if_approved() {
  local name="$1" start_script="$2"
  local answer

  if [ "$CHECK_ONLY" = true ]; then
    return 1
  fi

  if [ "$AUTO_START" = true ]; then
    answer="y"
  else
    read -r -p "    是否现在启动 $name? [y/N] " answer
  fi

  case "$answer" in
    y|Y|yes|YES)
      echo "    正在启动 $name ..."
      if bash "$start_script"; then
        echo -e "${GREEN}    ✓ $name 启动成功${NC}"
        return 0
      else
        echo -e "${RED}    ✗ $name 启动失败, 请查看上方日志${NC}"
        return 1
      fi
      ;;
    *)
      echo -e "${YELLOW}    已跳过 $name${NC}"
      return 1
      ;;
  esac
}

# ---------- 逐个服务检查 ----------
echo "========== 服务状态检测 =========="

down_count=0

# 1. Nacos
if port_in_use "$NACOS_API_PORT" || port_in_use "$NACOS_CONSOLE_PORT"; then
  print_running "Nacos (API $NACOS_API_PORT / 控制台 $NACOS_CONSOLE_PORT)"
else
  print_down "Nacos (API $NACOS_API_PORT / 控制台 $NACOS_CONSOLE_PORT)"
  start_if_approved "Nacos" "$SCRIPT_DIR/start-nacos.sh" || down_count=$((down_count + 1))
fi

# 2. RocketMQ (mqnamesrv + mqbroker)
if port_in_use "$NAMESRV_PORT" && port_in_use "$BROKER_PORT"; then
  print_running "RocketMQ (mqnamesrv $NAMESRV_PORT + mqbroker $BROKER_PORT 均已启动)"
else
  print_down "RocketMQ (mqnamesrv $NAMESRV_PORT / mqbroker $BROKER_PORT 存在未启动的组件)"
  if [ "$CHECK_ONLY" = false ]; then
    answer_rmq=""
    if [ "$AUTO_START" = true ]; then
      answer_rmq="y"
    else
      read -r -p "    是否现在启动 RocketMQ (会确保 mqnamesrv + mqbroker 都就绪)? [y/N] " answer_rmq
    fi
    case "$answer_rmq" in
      y|Y|yes|YES)
        echo "    正在启动 RocketMQ ..."
        if bash "$SCRIPT_DIR/start-rocketmq.sh"; then
          echo -e "${GREEN}    ✓ RocketMQ 启动成功${NC}"
        else
          echo -e "${RED}    ✗ RocketMQ 启动失败, 请查看上方日志${NC}"
          down_count=$((down_count + 1))
        fi
        ;;
      *)
        echo -e "${YELLOW}    已跳过 RocketMQ${NC}"
        down_count=$((down_count + 1))
        ;;
    esac
  else
    down_count=$((down_count + 1))
  fi
fi

# 3. Seata
if port_in_use "$SEATA_PORT"; then
  print_running "Seata Server (端口 $SEATA_PORT)"
else
  print_down "Seata Server (端口 $SEATA_PORT)"
  start_if_approved "Seata Server" "$SCRIPT_DIR/start-seata-server.sh" || down_count=$((down_count + 1))
fi

# 4. Sentinel
if port_in_use "$SENTINEL_PORT"; then
  print_running "Sentinel Dashboard (端口 $SENTINEL_PORT)"
else
  print_down "Sentinel Dashboard (端口 $SENTINEL_PORT)"
  start_if_approved "Sentinel Dashboard" "$SCRIPT_DIR/start-sentinel.sh" || down_count=$((down_count + 1))
fi

# ---------- 最终汇总 (重新检测一遍) ----------
echo ""
echo "========== 最终状态 =========="
if port_in_use "$NACOS_API_PORT" || port_in_use "$NACOS_CONSOLE_PORT"; then
  print_running "Nacos            (API $NACOS_API_PORT / 控制台 $NACOS_CONSOLE_PORT)"
else
  print_down "Nacos            (API $NACOS_API_PORT / 控制台 $NACOS_CONSOLE_PORT)"
fi
if port_in_use "$NAMESRV_PORT" && port_in_use "$BROKER_PORT"; then
  print_running "RocketMQ         (mqnamesrv $NAMESRV_PORT + mqbroker $BROKER_PORT)"
else
  print_down "RocketMQ         (mqnamesrv $NAMESRV_PORT / mqbroker $BROKER_PORT 未完全就绪)"
fi
if port_in_use "$SEATA_PORT"; then
  print_running "Seata Server     (端口 $SEATA_PORT)"
else
  print_down "Seata Server     (端口 $SEATA_PORT)"
fi
if port_in_use "$SENTINEL_PORT"; then
  print_running "Sentinel         (端口 $SENTINEL_PORT)"
else
  print_down "Sentinel         (端口 $SENTINEL_PORT)"
fi

if [ "$down_count" -eq 0 ]; then
  echo -e "${GREEN}全部服务已就绪。${NC}"
else
  echo -e "${YELLOW}还有 $down_count 个服务未启动 (或启动失败), 可再次运行本脚本或对应的 start-*.sh。${NC}"
fi
