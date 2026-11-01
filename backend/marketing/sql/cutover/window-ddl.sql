-- ============================================================================
-- window-ddl.sql —— 切换第 6 步【仅 B】最小 DDL（hot-row-merge-l2w256 票 09 / spec §5.3）
--
-- 目的只有一个：新代码不写 state 列时落 1（= 已合并），旧代码显式传 0，
-- 回滚后不会重合历史行。元数据级、可逆、秒级。
-- **只做这一件事**：不删任何列、不删任何表（删列 / 删表在 D9 的 d9-finalize.sql）。
-- 执行方式：docker exec -i bs-mysql mysql -uroot -pbs123456 < backend/marketing/sql/cutover/window-ddl.sql
-- ============================================================================

USE bs_marketing;

ALTER TABLE physical_marketing_quota_detail ALTER COLUMN state SET DEFAULT 1;
ALTER TABLE virtual_inventory_quota_detail  ALTER COLUMN state SET DEFAULT 1;

-- 验证：两处 SHOW CREATE TABLE 里 state 都是 DEFAULT '1'
SHOW CREATE TABLE physical_marketing_quota_detail;
SHOW CREATE TABLE virtual_inventory_quota_detail;
