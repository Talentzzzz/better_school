-- ============================================================================
-- cutover-cleanup.sql —— 切换第 4 步【仅 B】对账与清桶（hot-row-merge-l2w256 票 09 / spec §6.5）
--
-- 何时执行：cutover-catchup.sql 跑完且三条验证为 0 之后、起新 jar 之前。
-- 干什么：① 删掉消费者即将消失的合并 outbox 行；② 逐 inv 比「Σ桶余量 vs 台账 surplus」，
--   差额**只记不修**，写进切换记录；③ 清空 bs:inventory:*（桶 key + 已无用的去重 key）。
--   顺序不能反：先合完（catchup）→ 再对账（此刻等式才成立）→ 最后清。
-- 差额流向：没有出入库流水表可写，差额 = 切换记录「差额表」里的一行
--   （inv_id / 台账 surplus / 桶合计 / 差额），并在 ADR-0013 的切换点记一句「差额 = X，以台账为准」。
-- 执行方式：docker exec -i bs-mysql mysql -uroot -pbs123456 < backend/marketing/sql/cutover/cutover-cleanup.sql
--   （脚本自带 USE bs_marketing；在一次性演练库验证时先删掉 USE 行再喂给目标库）
-- ============================================================================

USE bs_marketing;

-- ① outbox 收尾：bs-inventory-merge 是「唤醒」不是数据，消费者即将消失；
--    留着会被共享的 outboxScan 发往无人消费的 topic，污染积压/死信观测。
DELETE FROM outbox_message WHERE destination = 'bs-inventory-merge';

-- 验证：期望 0
SELECT COUNT(*) AS merge_outbox_left FROM outbox_message WHERE destination = 'bs-inventory-merge';

-- ② 逐 inv 对账基准：台账 surplus（-1 不限量没有桶等式，不参与）
SELECT inv_id, surplus_qty FROM physical_marketing_quota WHERE surplus_qty >= 0 ORDER BY inv_id;
SELECT inv_id, surplus_qty FROM virtual_inventory_quota  WHERE surplus_qty >= 0 ORDER BY inv_id;

-- ③ 生成逐 inv 的 Redis 桶合计比对命令（在 shell 里逐行执行，把差额填进切换记录）：
--    每行输出一条现成的 docker/redis-cli MGET 命令，末尾直接打印 ledger / bucket_sum / diff。
--    group_concat_max_len 按桶数放开；桶 key 形态见 inventory.md（bucket:{quota|virtual}:{inv}:{bucketId}）。
--    覆盖**所有** surplus_qty >= 0 的 inv（LEFT JOIN）：桶表无行的 inv 退化为探 bucket 0——
--    MGET 打不中的 key 返回 nil、按 0 计，diff = surplus 正好把「有余量却无桶」的异常暴露出来。
SET SESSION group_concat_max_len = 65536;

SELECT CONCAT(
  'docker exec bs-redis redis-cli -a bs123456 --no-auth-warning --raw MGET ',
  COALESCE(
    GROUP_CONCAT(CONCAT('bs:inventory:bucket:quota:', inv_id, ':', bucket_id) ORDER BY bucket_id SEPARATOR ' '),
    CONCAT('bs:inventory:bucket:quota:', inv_id, ':0')),
  ' | awk ''{s+=$1} END{printf "inv=%s ledger=%s bucket_sum=%d diff=%d\n", ',
  inv_id, ',', surplus_qty, ', s+0, ', surplus_qty, '-(s+0)}'''
) AS compare_cmd
FROM physical_marketing_quota q LEFT JOIN physical_marketing_quota_bucket b USING (inv_id)
WHERE q.surplus_qty >= 0 GROUP BY inv_id, surplus_qty ORDER BY inv_id;

SELECT CONCAT(
  'docker exec bs-redis redis-cli -a bs123456 --no-auth-warning --raw MGET ',
  COALESCE(
    GROUP_CONCAT(CONCAT('bs:inventory:bucket:virtual:', inv_id, ':', bucket_id) ORDER BY bucket_id SEPARATOR ' '),
    CONCAT('bs:inventory:bucket:virtual:', inv_id, ':0')),
  ' | awk ''{s+=$1} END{printf "inv=%s ledger=%s bucket_sum=%d diff=%d\n", ',
  inv_id, ',', surplus_qty, ', s+0, ', surplus_qty, '-(s+0)}'''
) AS compare_cmd
FROM virtual_inventory_quota q LEFT JOIN virtual_inventory_quota_bucket b USING (inv_id)
WHERE q.surplus_qty >= 0 GROUP BY inv_id, surplus_qty ORDER BY inv_id;

-- 判读：diff = 台账 surplus − Σ桶余量。0 = 无差额（切换记录差额表写 0）；
-- 非 0 = 真实泄漏，只记不修（台账是唯一真相），并在切换记录里写一句诊断。

-- ④ 清桶（在 shell 里执行；桶 key 与去重 key 一次清完。新代码运行期不再出现 bs:inventory:*）：
--    docker exec bs-redis redis-cli -a bs123456 --no-auth-warning --scan --pattern 'bs:inventory:*' \
--      | xargs -r docker exec -i bs-redis redis-cli -a bs123456 --no-auth-warning DEL
--
-- 验证（期望 0）：
--    docker exec bs-redis redis-cli -a bs123456 --no-auth-warning --scan --pattern 'bs:inventory:*' | wc -l
