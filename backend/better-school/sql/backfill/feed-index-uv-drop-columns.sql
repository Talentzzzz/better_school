-- ============================================================================
-- feed-index-uv-drop-columns.sql —— post.category / post.view_count 删列收口
-- （feed-index-uv 票 07；feed-index-uv effort 的最后一步存量收口）
--
-- 何时执行：线上库跑完 feed-index-uv-backfill.sql 且票 02~06 的新代码已上线（两列停写生效）、
--   确认停写期无残留写入后。开发库 / 测试库不需要跑：两库由 init-*.sql + schema.sql 重建，
--   schema.sql 已按删列后的形状建表（本脚本针对的是带着旧列存量的线上库）。
-- 执行方式（不自带 USE，库名在命令行给）：
--   docker exec -i bs-mysql mysql -uroot -pbs123456 --default-character-set=utf8mb4 better_school < backend/better-school/sql/backfill/feed-index-uv-drop-columns.sql
--
-- 前置确认（执行前人工核对）：
--   两列存量值是**冻结的历史值**（票 06 / 票 02 起停写，不要求为 0/NULL）——确认停写期无残留写入
--   的口径是「值不再变化」，观察手段：
--     SELECT COUNT(*) FROM post WHERE category IS NOT NULL;   -- 隔段时间跑两次，数字不再增长
--     SELECT COUNT(*) FROM post WHERE view_count > 0;         -- 同上（票 02 起不再累加）
--   view_count 的存量累计已在票 01 回填时一次性搬进 post_view_count（对齐验证见 backfill 脚本验证段），
--   删列不丢 UV 口径数据。
--
-- 不幂等：MySQL 8.0 的 ALTER TABLE 不支持 DROP COLUMN IF EXISTS，二次执行会报 1091（列不存在）。
--   报错即目标已达成，无需处理。跑完后 feed-index-uv-backfill.sql 整体退役（其 ③ 段引用的
--   post.view_count 已不存在），该脚本仅供仍带旧列的更早存量库升级使用。
-- ============================================================================

ALTER TABLE post
    DROP INDEX idx_category_created,
    DROP COLUMN category,
    DROP COLUMN view_count;

-- 验证（人工核对，期望各 0 行）：
--   SHOW COLUMNS FROM post LIKE 'category';
--   SHOW COLUMNS FROM post LIKE 'view_count';
