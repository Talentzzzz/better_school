-- ============================================================================
-- d9-finalize.sql —— D9 收口（**不可逆**，此后不再考虑回滚）
-- (hot-row-merge-l2w256 票 09 / spec §6.4)
--
-- 前提：D2 切流成功后完整观察了 7 个日历日（含至少两个晚高峰），抽样对账持续为平，
--       回滚的 24 小时价值期已过。执行前确认今天不需要回滚。
-- 三步：① 删列与索引（在线 INSTANT）→ ② 删两张桶表（白名单）→ ③ 删 xxl 注册。
-- 执行方式：docker exec -i bs-mysql mysql -uroot -pbs123456 < backend/marketing/sql/cutover/d9-finalize.sql
-- ⚠️ 同库还有 points_bucket / draw_count_bucket —— 那是用户资产（积分桶 / 次数桶），
--    与本改动无关。按白名单写表名，**不要按 %_bucket 匹配**。
-- ============================================================================

USE bs_marketing;

-- ① 删列与索引：明细表退到最终形态（台账三列 + 明细 + 唯一键，见 schema.sql）
ALTER TABLE physical_marketing_quota_detail
  DROP COLUMN bucket_id, DROP COLUMN state,
  DROP KEY idx_key_cell, DROP KEY idx_pending;

ALTER TABLE virtual_inventory_quota_detail
  DROP COLUMN bucket_id, DROP COLUMN state,
  DROP KEY idx_key_cell, DROP KEY idx_pending;

-- ② 删两张桶表（只删这两张）
DROP TABLE physical_marketing_quota_bucket;
DROP TABLE virtual_inventory_quota_bucket;

-- ③ 删 xxl 注册（切换窗口里只停用 trigger_status = 0，此时升级为删除）
SET @group_id = (SELECT id FROM xxl_job_group WHERE app_name = 'marketing-executor' LIMIT 1);
DELETE FROM xxl_job.xxl_job_info
 WHERE executor_handler IN ('inventoryMergeBackstop', 'inventoryBucketWarmup')
   AND job_group = @group_id;

-- ============================================================================
-- 验证
-- ============================================================================

-- 明细表已无 bucket_id / state 列（期望 0 行）
SELECT column_name FROM information_schema.columns
 WHERE table_schema = 'bs_marketing'
   AND table_name IN ('physical_marketing_quota_detail', 'virtual_inventory_quota_detail')
   AND column_name IN ('bucket_id', 'state');

-- 两条死索引已删（期望 0 行）
SELECT index_name FROM information_schema.statistics
 WHERE table_schema = 'bs_marketing'
   AND table_name IN ('physical_marketing_quota_detail', 'virtual_inventory_quota_detail')
   AND index_name IN ('idx_key_cell', 'idx_pending');

-- 两张桶表已删（期望 0 行；同时确认白名单外的两张还在——应返回 2 行）
SELECT table_name FROM information_schema.tables
 WHERE table_schema = 'bs_marketing'
   AND table_name IN ('physical_marketing_quota_bucket', 'virtual_inventory_quota_bucket');
SELECT table_name FROM information_schema.tables
 WHERE table_schema = 'bs_marketing'
   AND table_name IN ('points_bucket', 'draw_count_bucket');

-- xxl 注册已删（期望 0）
SELECT COUNT(*) AS backstop_rows FROM xxl_job.xxl_job_info
 WHERE executor_handler IN ('inventoryMergeBackstop', 'inventoryBucketWarmup');

-- 收尾：schema.sql 已是最终形态（本仓库随本票落地），存量库自此与新装库对齐。
