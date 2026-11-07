#!/usr/bin/env bash
# 跑完整的一轮：重置 -> 施压 -> 等排空 -> 打印这一轮的账
#
#   ./scripts/run-test.sh db
#   ./scripts/run-test.sh redis_mq
#   ./scripts/run-test.sh merge
#
# 可覆盖的环境变量：
#   APP_HOST     应用机 IP（默认 172.31.89.1，施压机上不用改）
#   THREADS      并发线程（默认 200）
#   RAMP         爬坡秒数（默认 30）
#   DURATION     持续秒数（默认 120）
#   STOCK        初始库存（默认 5000000，200 线程 120 秒卖不完，测的是纯扣减吞吐）
#   BUCKETS      分桶数（默认 1）
#   JMETER_BIN   jmeter 可执行文件（默认 jmeter）
#   DRAIN_TIMEOUT 等排空的最长秒数（默认 180）
set -euo pipefail

STRATEGY="${1:-merge}"
APP_HOST="${APP_HOST:-172.31.89.1}"   # 应用机内网 IP
APP_PORT="${APP_PORT:-8080}"
THREADS="${THREADS:-200}"
RAMP="${RAMP:-30}"
DURATION="${DURATION:-120}"
STOCK="${STOCK:-5000000}"
BUCKETS="${BUCKETS:-1}"
SKU="${SKU:-1001}"
JMETER_BIN="${JMETER_BIN:-jmeter}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-180}"

HERE=$(cd "$(dirname "$0")/.." && pwd)
JMX="${HERE}/jmeter/inventory-deduct.jmx"
STAMP=$(date +%Y%m%d-%H%M%S)
OUT_DIR="${HERE}/results"
JTL="${OUT_DIR}/result-${STRATEGY}-${STAMP}.jtl"
mkdir -p "${OUT_DIR}"

echo "=============================================================="
echo " 策略=${STRATEGY}  目标=${APP_HOST}:${APP_PORT}"
echo " 线程=${THREADS}  爬坡=${RAMP}s  持续=${DURATION}s  库存=${STOCK}  桶=${BUCKETS}"
echo "=============================================================="

echo ">>> 1/4 重置"
curl -sS -X POST \
  "http://${APP_HOST}:${APP_PORT}/api/admin/reset?skuId=${SKU}&stock=${STOCK}&buckets=${BUCKETS}" >/dev/null
echo "    重置完成，等 3 秒让在途消息过期"
sleep 3

echo ">>> 2/4 施压"
"${JMETER_BIN}" -n \
  -t "${JMX}" \
  -Jhost="${APP_HOST}" -Jport="${APP_PORT}" \
  -Jthreads="${THREADS}" -Jramp="${RAMP}" -Jduration="${DURATION}" \
  -Jstrategy="${STRATEGY}" -Jsku="${SKU}" -Jqty=1 \
  -l "${JTL}"

echo ">>> 3/4 等台账排空（最多 ${DRAIN_TIMEOUT}s）"
ELAPSED=0
while [ "${ELAPSED}" -lt "${DRAIN_TIMEOUT}" ]; do
  BODY=$(curl -sS "http://${APP_HOST}:${APP_PORT}/api/admin/stats?skuId=${SKU}")
  DRAINED=$(printf '%s' "${BODY}" | grep -o '"drained":[a-z]*' | head -1 || true)
  case "${DRAINED}" in
    *true*) echo "    已排空（${ELAPSED}s）"; break ;;
  esac
  sleep 2
  ELAPSED=$((ELAPSED + 2))
done

echo ">>> 4/4 这一轮的账"
if command -v python3 >/dev/null 2>&1; then
  printf '%s' "${BODY}" | python3 -m json.tool
else
  printf '%s\n' "${BODY}"
fi

# 库存是否被打穿：打穿后大量请求走「售罄拒绝」的快速路径，吞吐会被高估
SOLD_OUT=$(printf '%s' "${BODY}" | grep -o "\"soldout:${STRATEGY}\":[0-9]*" | head -1 | sed 's/.*://' || true)
if [ -n "${SOLD_OUT}" ] && [ "${SOLD_OUT}" -gt 0 ] 2>/dev/null; then
  echo
  echo "!! 警告：本轮有 ${SOLD_OUT} 次售罄拒绝，库存已见底。"
  echo "   吞吐里混了大量「拒绝」流量（拒绝不动 DB），不能当作纯扣减能力。"
  echo "   要测纯吞吐请把 STOCK 调大：STOCK=20000000 ./scripts/run-test.sh ${STRATEGY}"
fi

echo
echo "原始采样文件：${JTL}"
echo "生成 HTML 报告（可选）："
echo "  ${JMETER_BIN} -g ${JTL} -o ${OUT_DIR}/report-${STRATEGY}-${STAMP}"
