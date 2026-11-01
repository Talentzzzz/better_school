-- ============================================================================
-- market-feed-uv-backfill.sql —— 市集（闲置 + 跑腿）feed 异构索引两表的存量回填
-- （market-feed-uv 票 01；表 DDL 见 backend/better-school/sql/schema.sql 市集六表节）
--
-- 何时执行：两张新索引表建好（schema.sql 已含）、票 02/03 的新代码上线**之前**。
--   本脚本不建表（DDL 唯一来源是 schema.sql），只搬存量。
--   开发库 / 测试库不需要跑：两库由 init-*.sql + schema.sql 重建，回填针对的是有存量的线上库。
-- 执行方式（不自带 USE，库名在命令行给，便于喂给 better_school_test 验证）：
--   docker exec -i bs-mysql mysql -uroot -pbs123456 --default-character-set=utf8mb4 better_school < backend/better-school/sql/backfill/market-feed-uv-backfill.sql
--
-- 幂等语义（可重复执行）：
--   ① 两张索引表 INSERT ... ON DUPLICATE KEY UPDATE 全量重投影——重跑 = 追平窗口期增量
--     （回填后、换代码前，旧代码的状态流转/在售改价仍只写主表，重跑把漂移追平），已对齐的行 UPDATE 落回同值。
--   ② state 收口：回填首跑后离开可见态的行（商品离开 ON_SALE / 跑腿离开 PUBLISHED），把索引行状态同步成不可见。
--
-- ⚠ 本脚本随票 06 收口部分退役：product.view_count 已删列（见同目录 market-feed-uv-drop-columns.sql，
--   其头注释含完整执行序与退役的清零脚本原文），① 段原「view_count 从主表 PV 抄入」随之移除——
--   引用的列已不存在。索引热度键此后由运行期浏览旁路 ±N 累计、漂移由 productFeedIndexRebuildJob
--   校准成 product_view_count。本脚本因此**只服务于切码前的存量库**：它投影行集 / feed_time / price
--   与不可见态，不校准计数窄表、也不刷新热度键——切码后要修索引一律走重建 job。
--   仍带旧列存量的更早线上库，若要沿用「PV 起步」的旧形态，先跑下面这版初版 ①、再按
--   market-feed-uv-drop-columns.sql 头部的清零片段把热度键归零，最后才删列：
--     INSERT INTO product_feed_index (product_id, state, feed_time, price, view_count, update_time)
--     SELECT p.product_id, p.state,
--            COALESCE((SELECT MAX(cr.create_time) FROM content_review cr
--                       WHERE cr.target_type = 'PRODUCT' AND cr.target_id = p.product_id
--                         AND cr.action = 'APPROVE'), p.create_time),
--            p.price, p.view_count, NOW()
--       FROM product p
--      WHERE p.state = 'ON_SALE'
--     ON DUPLICATE KEY UPDATE state = VALUES(state), feed_time = VALUES(feed_time),
--                             price = VALUES(price), view_count = VALUES(view_count), update_time = NOW();
--
-- 行集口径（票内拍板）：
--   商品 = ON_SALE（大厅闲置 tab 直查同口径）；跑腿 = PUBLISHED（大厅前端恒传 status=PUBLISHED，
--   见 front/src/views/MarketView.vue；行常驻 + state 快照的驻留口径归票 03，回填只造大厅可见行）。
-- 不回填明细/计数表（product_view_history / errand_view_history / *_view_count）：
--   历史上没有按人明细可搬（商品只有 product.view_count 聚合 PV），UV 从票 02 切码起按 INSERT 成功
--   精确累计（计数表恒等明细行数的不变式从第一天成立）；存量 PV 不换算 UV（用户拍板，老数据废弃）。
-- ============================================================================

-- ① 商品索引回填：ON_SALE 商品插入（一商品一行 1:1 投影）
--    feed_time = content_review 里该商品最近一次通过时刻（target_type='PRODUCT' AND action='APPROVE' 的
--    MAX(create_time)，重提再通过取最近一次）；查不到审批流水的存量老商品取 product.create_time 兜底。
--    price 从主表抄入（ON_SALE 必有价格：提交时必填）；view_count 不抄（列已删，索引行按默认 0 起，
--    切码后由浏览旁路累计——存量 PV 本就要在切码前清零，抄不抄都落 0，见头部退役说明）
INSERT INTO product_feed_index (product_id, state, feed_time, price, update_time)
SELECT p.product_id,
       p.state,
       COALESCE((SELECT MAX(cr.create_time)
                   FROM content_review cr
                  WHERE cr.target_type = 'PRODUCT'
                    AND cr.target_id = p.product_id
                    AND cr.action = 'APPROVE'),
                p.create_time),
       p.price,
       NOW()
  FROM product p
 WHERE p.state = 'ON_SALE'
ON DUPLICATE KEY UPDATE state      = VALUES(state),
                        feed_time  = VALUES(feed_time),
                        price      = VALUES(price),
                        update_time = NOW();

-- ② 跑腿索引回填：PUBLISHED 跑腿插入（一单一行 1:1 投影；无存量浏览量，view_count 恒 0）
--    feed_time = content_review 里该单最近一次通过时刻（target_type='ERRAND' AND action='APPROVE'，
--    跑腿 review_round 恒 1，MAX 即该次）；查不到审批流水的存量老单取 errand_order.create_time 兜底。
--    price = reward（PUBLISHED 必已支付冻结，悬赏必有值）。
--    ODKU 不碰 view_count：热度键由运行期浏览旁路独占维护，重投影不该把 UV 抹成 0
INSERT INTO errand_feed_index (errand_order_id, state, feed_time, price, view_count, update_time)
SELECT e.errand_order_id,
       e.state,
       COALESCE((SELECT MAX(cr.create_time)
                   FROM content_review cr
                  WHERE cr.target_type = 'ERRAND'
                    AND cr.target_id = e.errand_order_id
                    AND cr.action = 'APPROVE'),
                e.create_time),
       e.reward,
       0,
       NOW()
  FROM errand_order e
 WHERE e.state = 'PUBLISHED'
ON DUPLICATE KEY UPDATE state      = VALUES(state),
                        feed_time  = VALUES(feed_time),
                        price      = VALUES(price),
                        update_time = NOW();

-- ③ 索引行 state 收口：回填后、换代码前又有行离开可见态（商品售出/下架/驳回/软删，跑腿锁定/取消/拒绝等）
--    的窗口，把索引行状态同步成主表 state（不可见化）。运行期（票 03）这一步由状态流转事务同事务完成。
UPDATE product_feed_index fi
  JOIN product p ON p.product_id = fi.product_id
   SET fi.state = p.state,
       fi.update_time = NOW()
 WHERE p.state <> 'ON_SALE'
   AND fi.state <> p.state;

UPDATE errand_feed_index fi
  JOIN errand_order e ON e.errand_order_id = fi.errand_order_id
   SET fi.state = e.state,
       fi.update_time = NOW()
 WHERE e.state <> 'PUBLISHED'
   AND fi.state <> e.state;

-- ── 验证（人工核对，期望各 0 行；非 0 = 回填不完整，切码前重跑即可）──
-- 每个可见态行都有索引行
SELECT p.product_id
  FROM product p
  LEFT JOIN product_feed_index fi ON fi.product_id = p.product_id
 WHERE p.state = 'ON_SALE'
   AND fi.product_id IS NULL;
SELECT e.errand_order_id
  FROM errand_order e
  LEFT JOIN errand_feed_index fi ON fi.errand_order_id = e.errand_order_id
 WHERE e.state = 'PUBLISHED'
   AND fi.errand_order_id IS NULL;
-- 索引行 state 与主表 state 无漂移
SELECT fi.product_id, fi.state, p.state
  FROM product_feed_index fi
  JOIN product p ON p.product_id = fi.product_id
 WHERE fi.state <> p.state;
SELECT fi.errand_order_id, fi.state, e.state
  FROM errand_feed_index fi
  JOIN errand_order e ON e.errand_order_id = fi.errand_order_id
 WHERE fi.state <> e.state;
