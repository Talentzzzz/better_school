#!/usr/bin/env bash
# 重置一轮压测。每换一个策略、每跑一轮都要先执行一次。
#
#   ./scripts/reset.sh                        # 默认 1001 / 200000 / 1 桶
#   STOCK=50000 ./scripts/reset.sh
#   APP_HOST=172.31.89.1 STOCK=20000 BUCKETS=4 ./scripts/reset.sh
set -euo pipefail

APP_HOST="${APP_HOST:-172.31.89.1}"   # 应用机内网 IP
APP_PORT="${APP_PORT:-8080}"
SKU="${SKU:-1001}"
STOCK="${STOCK:-200000}"
BUCKETS="${BUCKETS:-1}"

echo ">>> reset sku=${SKU} stock=${STOCK} buckets=${BUCKETS} @ ${APP_HOST}:${APP_PORT}"
curl -sS -X POST "http://${APP_HOST}:${APP_PORT}/api/admin/reset?skuId=${SKU}&stock=${STOCK}&buckets=${BUCKETS}"
echo
echo ">>> 等 3 秒，让上一轮在途的 MQ 消息过期"
sleep 3
