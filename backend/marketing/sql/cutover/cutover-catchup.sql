-- ============================================================================
-- cutover-catchup.sql —— 切换第 3 步【仅 B】在途收口（hot-row-merge-l2w256 票 09 / spec §6.5）
--
-- 何时执行：两进程已优雅停机（SIGTERM，退出码 0）之后、最小 DDL（state SET DEFAULT 1）之前。
-- 干什么：把在途 state = 0 明细按旧合并逻辑（原 mergeCell 的加减方向）一次性合完。
--   必须含 type = 2（兑换确认）：不合完的话，切换后新代码不再有兜底与消息，
--   waiting 会永久高挂、issued 永远短缺。
-- 幂等性：可重复执行——合完的行已翻 state = 1，重跑各 UPDATE 落空集。
-- 执行方式：docker exec -i bs-mysql mysql -uroot -pbs123456 < backend/marketing/sql/cutover/cutover-catchup.sql
--   （脚本自带 USE bs_marketing；在一次性演练库验证时先删掉 USE 行再喂给目标库）
--
-- SET 从左到右取新值：state 的 IF 必须排在 surplus_qty 赋值之前（判断的是旧值），
-- 与 mapper 里 flushDeductGroup 的写法一致。
-- ============================================================================

USE bs_marketing;

-- ① 实物段 type = 1（进 waiting）：先于 type = 2，顺序即旧 mergeCell 的顺序
UPDATE physical_marketing_quota q
  JOIN (SELECT inv_id, SUM(quantity) s FROM physical_marketing_quota_detail
         WHERE state = 0 AND type = 1 GROUP BY inv_id) d ON d.inv_id = q.inv_id
   SET q.state = IF(q.surplus_qty >= 0 AND q.surplus_qty = d.s AND q.state = 'open', 'exhausted', q.state),
       q.waiting_qty = q.waiting_qty + d.s,
       q.surplus_qty = IF(q.surplus_qty < 0, q.surplus_qty, q.surplus_qty - d.s),
       q.update_time = NOW();

-- ② 断言：waiting 必须足以承接 type = 2 的确认（期望 0 行；下面先打印明细供人工看）。
--    护栏：非 0 时 gate 一句会引用一张不存在的表、主动报错终止脚本
--    （mysql CLI 默认遇错即停，**不要 --force**）——②③④⑤ 都不再执行，在途证据留在 state = 0。
--    此时 = 不变量破坏（B5），停手交人工。
SELECT q.inv_id, q.waiting_qty, d.s
  FROM physical_marketing_quota q
  JOIN (SELECT inv_id, SUM(quantity) s FROM physical_marketing_quota_detail
         WHERE state = 0 AND type = 2 GROUP BY inv_id) d ON d.inv_id = q.inv_id
 WHERE q.waiting_qty < d.s;

SET @bad = (SELECT COUNT(*)
  FROM physical_marketing_quota q
  JOIN (SELECT inv_id, SUM(quantity) s FROM physical_marketing_quota_detail
         WHERE state = 0 AND type = 2 GROUP BY inv_id) d ON d.inv_id = q.inv_id
 WHERE q.waiting_qty < d.s);
SET @gate = IF(@bad = 0,
               'SELECT 0 AS gate_ok',
               'SELECT * FROM `HALT_waiting_insufficient_invariant_broken_stop_and_call_human`');
PREPARE gate_stmt FROM @gate;
EXECUTE gate_stmt;
DEALLOCATE PREPARE gate_stmt;

-- ③ 实物段 type = 2（waiting → issued）
UPDATE physical_marketing_quota q
  JOIN (SELECT inv_id, SUM(quantity) s FROM physical_marketing_quota_detail
         WHERE state = 0 AND type = 2 GROUP BY inv_id) d ON d.inv_id = q.inv_id
   SET q.waiting_qty = q.waiting_qty - d.s,
       q.issued_qty  = q.issued_qty  + d.s,
       q.update_time = NOW();

-- ④ 虚拟段：只有一段流转（无 type 列），直接进 issued
UPDATE virtual_inventory_quota q
  JOIN (SELECT inv_id, SUM(quantity) s FROM virtual_inventory_quota_detail
         WHERE state = 0 GROUP BY inv_id) d ON d.inv_id = q.inv_id
   SET q.state = IF(q.surplus_qty >= 0 AND q.surplus_qty = d.s AND q.state = 'open', 'exhausted', q.state),
       q.issued_qty  = q.issued_qty  + d.s,
       q.surplus_qty = IF(q.surplus_qty < 0, q.surplus_qty, q.surplus_qty - d.s),
       q.update_time = NOW();

-- ⑤ 封口：余下 state = 0 的行全部置 1（此后不再有「未合并」态）
UPDATE physical_marketing_quota_detail SET state = 1, update_time = NOW() WHERE state = 0;
UPDATE virtual_inventory_quota_detail  SET state = 1, update_time = NOW() WHERE state = 0;

-- ============================================================================
-- 验证（三条都要为 0；读数抄进切换记录 cutover-<日期>.md）
-- ============================================================================
SELECT COUNT(*) AS physical_pending    FROM physical_marketing_quota_detail WHERE state = 0;  -- 期望 0
SELECT COUNT(*) AS virtual_pending     FROM virtual_inventory_quota_detail  WHERE state = 0;  -- 期望 0
SELECT COUNT(*) AS waiting_negative    FROM physical_marketing_quota WHERE waiting_qty < 0;    -- 期望 0
