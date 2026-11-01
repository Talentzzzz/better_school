-- ============================================================================
-- market-feed-uv-drop-columns.sql —— product.view_count 删列收口
-- （market-feed-uv 票 06；market-feed-uv effort 的最后一步存量收口）
--
-- 何时执行：线上库跑完 market-feed-uv-backfill.sql、且票 02~05 的新代码已上线
--   （浏览读路径已换源 product_view_count、落库三步不再写主表列），确认停写期无残留写入后。
--   开发库 / 测试库不需要跑：两库由 init-*.sql + schema.sql 重建，schema.sql 已按删列后的形状
--   建表（本脚本针对的是带着旧列存量的线上库）。
-- 执行方式（不自带 USE，库名在命令行给）：
--   docker exec -i bs-mysql mysql -uroot -pbs123456 --default-character-set=utf8mb4 better_school < backend/better-school/sql/backfill/market-feed-uv-drop-columns.sql
--
-- 前置确认（执行前人工核对）：
--   该列的存量值是**冻结的历史 PV**（票 02 起停写，不要求为 0/NULL）——确认停写期无残留写入的
--   口径是「值不再变化」，观察手段：
--     SELECT COUNT(*) FROM product WHERE view_count > 0;   -- 隔段时间跑两次，数字不再增长
--   存量 PV 不换算 UV（用户拍板，老数据废弃）：UV 从票 02 切码起按 INSERT 成功精确累计，
--   删列不丢 UV 口径数据（真源 product_view_history、加速列 product_view_count）。
--
-- 执行序（三步缺一不可）：
--   ① 建表（schema.sql 已含六张市集表）→
--   ② 回填索引（market-feed-uv-backfill.sql）→
--   ③ 切码（票 02/03 上线；切码**之前**跑过一次性的 PV 清零）→
--   ④ 本脚本删列。
--   第 ③ 步的清零脚本（原 market-feed-uv-view-count-reset.sql）已随本票退役：它的对象
--   product.view_count 就是本脚本要删的列，删列后整脚本无从下手。原文保留在此，仅供仍带旧列
--   存量的更早线上库在切码前执行（先清零再切码，顺序反了会把 UV 真值抹掉）：
--     -- 归零主表 PV 列与索引热度键；不碰 UV 三表（明细/计数已是新口径真值）
--     UPDATE product SET view_count = 0 WHERE view_count > 0;
--     UPDATE product_feed_index SET view_count = 0 WHERE view_count > 0;
--   切码之后索引热度键就是 UV 真值，任何脚本都不许再把它归零（要修漂移走 productFeedIndexRebuildJob）。
--
-- 不幂等：MySQL 8.0 的 ALTER TABLE 不支持 DROP COLUMN IF EXISTS，二次执行会报 1091（列不存在）。
--   报错即目标已达成，无需处理。
-- ============================================================================

ALTER TABLE product
    DROP COLUMN view_count;

-- 验证（人工核对，期望 0 行）：
--   SHOW COLUMNS FROM product LIKE 'view_count';
