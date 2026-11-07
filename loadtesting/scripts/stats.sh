#!/usr/bin/env bash
# 读取当前一轮的账（台账 / Redis / 明细 / 计数 / 一致性判定）
#
#   ./scripts/stats.sh
#   APP_HOST=172.31.89.1 ./scripts/stats.sh
set -euo pipefail

APP_HOST="${APP_HOST:-172.31.89.1}"   # 应用机内网 IP
APP_PORT="${APP_PORT:-8080}"
SKU="${SKU:-1001}"

BODY=$(curl -sS "http://${APP_HOST}:${APP_PORT}/api/admin/stats?skuId=${SKU}")
if command -v python3 >/dev/null 2>&1; then
  printf '%s' "${BODY}" | python3 -m json.tool
else
  printf '%s\n' "${BODY}"
fi
