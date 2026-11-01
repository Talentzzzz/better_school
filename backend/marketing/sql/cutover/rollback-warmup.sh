#!/usr/bin/env bash
# ============================================================================
# rollback-warmup.sh —— 回滚第 3 步：按台账 surplus 重灌 Redis 桶
# (hot-row-merge-l2w256 票 09 / spec §6.3)
#
# 用法：./rollback-warmup.sh [quota|virtual|all]     # 默认 all
#
# 何时用：回滚四步「停新 jar → 回滚 jar → 重灌桶(本脚本) → 放开 xxl 注册」的第三步。
# 为什么不用 inventoryBucketWarmup：那条路要恢复 xxl 注册、要 admin 放行，变量更多；
# 本脚本直接从台账读 surplus 写 Redis，秒级完成。回滚窗口里没有在途预扣，SET 语义安全。
#
# 规则：
#   - 只灌 surplus_qty >= 0 的 inv（-1 不限量没有桶，本来也没被扣过）；
#   - 桶号沿用 *_bucket 表里该 inv 现存的 bucket_id 行（表在 D9 前仍在），
#     没有行则退化为单桶 bucket_id = 0（能跑，只是热点变差）；
#   - 数量均分到各桶，余数摊给前面的桶；灌之前先 DEL 同前缀旧 key，避免残留叠加；
#   - 灌完逐 inv 回读求和与台账比对，任何一行不平则以非零码退出。
#
# 环境变量可覆盖：MYSQL_CONTAINER / REDIS_CONTAINER / MYSQL_USER / MYSQL_PASS / REDIS_PASS / DB
# ============================================================================
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-bs-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-bs-redis}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASS="${MYSQL_PASS:-bs123456}"
REDIS_PASS="${REDIS_PASS:-bs123456}"
DB="${DB:-bs_marketing}"

TARGET="${1:-all}"
case "$TARGET" in
  quota|virtual|all) ;;
  *) echo "用法: $0 [quota|virtual|all]" >&2; exit 2 ;;
esac

mysql_q() {
  docker exec -i "$MYSQL_CONTAINER" mysql -u"$MYSQL_USER" -p"$MYSQL_PASS" -N -B "$DB" 2>/dev/null
}

redis_set() {
  docker exec "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASS" --no-auth-warning SET "$1" "$2" >/dev/null
}

redis_get() {
  docker exec "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASS" --no-auth-warning --raw GET "$1"
}

# warmup_segment <ledger表> <bucket表> <key前缀>
warmup_segment() {
  local ledger="$1" bucket_tbl="$2" prefix="$3"
  local fail=0

  # inv_id / surplus_qty / 现存桶数（无行则按 1 个桶算）
  local rows
  rows=$(mysql_q <<SQL
SELECT q.inv_id, q.surplus_qty, COALESCE(b.cnt, 0)
  FROM ${ledger} q
  LEFT JOIN (SELECT inv_id, COUNT(*) cnt FROM ${bucket_tbl} GROUP BY inv_id) b ON b.inv_id = q.inv_id
 WHERE q.surplus_qty >= 0
 ORDER BY q.inv_id;
SQL
)

  if [ -z "$rows" ]; then
    echo "[$prefix] 无限量行（surplus_qty >= 0 的 inv 一条没有），无事可做"
    return 0
  fi

  while IFS=$'\t' read -r inv surplus bucket_cnt; do
    [ -z "$inv" ] && continue
    local n="${bucket_cnt:-0}"
    [ "$n" -eq 0 ] 2>/dev/null && n=1

    # 先按模式清该 inv 的**全部**旧桶 key（不只桶表现存的 0..n-1——
    # 历史残留的更大 bucket_id 若不删，回读校验会「平」但实际可扣余量虚高）
    docker exec "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASS" --no-auth-warning --scan \
      --pattern "bs:inventory:bucket:${prefix}:${inv}:*" \
      | xargs -r docker exec -i "$REDIS_CONTAINER" redis-cli -a "$REDIS_PASS" --no-auth-warning DEL >/dev/null

    # 均分 + 余数摊前桶
    local base=$(( surplus / n )) rem=$(( surplus % n )) sum=0 v
    local keys=() i
    for ((i = 0; i < n; i++)); do keys+=("bs:inventory:bucket:${prefix}:${inv}:${i}"); done
    for ((i = 0; i < n; i++)); do
      v=$(( base + (i < rem ? 1 : 0) ))
      redis_set "${keys[$i]}" "$v"
      sum=$(( sum + v ))
    done

    # 回读校验（防 SET 失败静默通过）
    local back=0 g
    for ((i = 0; i < n; i++)); do
      g=$(redis_get "${keys[$i]}")
      back=$(( back + ${g:-0} ))
    done

    if [ "$back" -eq "$surplus" ]; then
      echo "[$prefix] inv=$inv OK  surplus=$surplus 桶数=$n 回读=$back"
    else
      echo "[$prefix] inv=$inv FAIL  surplus=$surplus 回读=$back —— 停手，人工核对" >&2
      fail=1
    fi
  done <<< "$rows"

  return "$fail"
}

echo "== 回滚重灌桶：目标 = $TARGET，库 = $DB =="
rc=0
if [ "$TARGET" = "quota" ] || [ "$TARGET" = "all" ]; then
  warmup_segment physical_marketing_quota physical_marketing_quota_bucket quota || rc=1
fi
if [ "$TARGET" = "virtual" ] || [ "$TARGET" = "all" ]; then
  warmup_segment virtual_inventory_quota virtual_inventory_quota_bucket virtual || rc=1
fi

if [ "$rc" -eq 0 ]; then
  echo "== 全部重灌完成且回读平账 =="
else
  echo "== 有行不平，见上方 FAIL；不要放开 xxl 注册，先人工处理 ==" >&2
fi
exit "$rc"
