-- ============================================================================
-- feed-index-uv-backfill.sql —— feed 异构索引 + 浏览 UV 三表的存量回填
-- （feed-index-uv 票 01；表 DDL 见 backend/better-school/sql/schema.sql）
--
-- 何时执行：三张新表建好（schema.sql 已含）、票 02/03 的新代码上线**之前**。
--   本脚本不建表（DDL 唯一来源是 schema.sql），只搬存量。
--   开发库 / 测试库不需要跑：两库由 init-*.sql + schema.sql 重建，回填针对的是有存量的线上库。
-- 执行方式（不自带 USE，库名在命令行给，便于喂给 better_school_test 验证）：
--   docker exec -i bs-mysql mysql -uroot -pbs123456 --default-character-set=utf8mb4 better_school < backend/better-school/sql/backfill/feed-index-uv-backfill.sql
--
-- 幂等语义（可重复执行）：
--   ① post_feed_index：INSERT ... ON DUPLICATE KEY UPDATE 全量重投影——重跑 = 追平窗口期增量
--     （回填后、换代码前，旧代码的点赞/状态流转仍只写旧表，重跑把漂移追平），已对齐的行 UPDATE 落回同值。
--   ② state 收口：回填首跑后帖子离开 NORMAL 态的（驳回/撤回/软删），把索引行状态同步成不可见。
--
-- ⚠ 本脚本随票 07 收口整体退役：post.category / post.view_count 已删列（见同目录
--   feed-index-uv-drop-columns.sql，其前置说明含执行序），初版脚本的 ③ 段
--   （post.view_count → post_view_count 一次性搬数）引用的列已不存在、随之移除——
--   仍带旧列存量的更早线上库，先跑下面的初版 ③ 完成搬数再跑删列脚本：
--     INSERT INTO post_view_count (post_id, view_count, create_time, update_time)
--     SELECT p.post_id, p.view_count, NOW(), NOW() FROM post p WHERE p.view_count > 0
--     ON DUPLICATE KEY UPDATE view_count = VALUES(view_count), update_time = NOW();
--
-- 不回填 user_view_history：历史上只有 post.view_count 聚合值、没有按人明细，
--   UV 从本票起以 INSERT 成功为准，存量浏览不追溯（计数从 0 起步，不回补）。
-- ============================================================================

-- ① post_feed_index 回填：NORMAL 帖插入（一帖一行 1:1 投影）
--    feed_time = content_review 里该帖最近一次通过时刻（target_type='POST' AND action='APPROVE' 的
--    MAX(create_time)，重提再通过取第二次）；查不到审批流水的存量老帖取 post.create_time 兜底。
--    like_count 从 post_like_count 抄入（无行 = 0，与运行期「窄表无行即计数 0」同口径）。
INSERT INTO post_feed_index (post_id, state, feed_time, like_count, update_time)
SELECT p.post_id,
       p.state,
       COALESCE((SELECT MAX(cr.create_time)
                   FROM content_review cr
                  WHERE cr.target_type = 'POST'
                    AND cr.target_id = p.post_id
                    AND cr.action = 'APPROVE'),
                p.create_time),
       COALESCE(lc.like_count, 0),
       NOW()
  FROM post p
  LEFT JOIN post_like_count lc ON lc.post_id = p.post_id
 WHERE p.state = 'NORMAL'
ON DUPLICATE KEY UPDATE state      = VALUES(state),
                        feed_time  = VALUES(feed_time),
                        like_count = VALUES(like_count),
                        update_time = NOW();

-- ② 索引行 state 收口：回填后、换代码前又有帖子离开 NORMAL（驳回/撤回/被拒转草稿/软删）的窗口，
--    把索引行同步成 post.state（不可见化）。运行期（票 03）这一步由状态流转事务同事务完成。
UPDATE post_feed_index fi
  JOIN post p ON p.post_id = fi.post_id
   SET fi.state = p.state,
       fi.update_time = NOW()
 WHERE p.state <> 'NORMAL'
   AND fi.state <> p.state;

-- ── 验证（人工核对，期望各 0 行；非 0 = 回填不完整，重跑即可）──
-- 每 NORMAL 帖都有索引行
SELECT p.post_id
  FROM post p
  LEFT JOIN post_feed_index fi ON fi.post_id = p.post_id
 WHERE p.state = 'NORMAL'
   AND fi.post_id IS NULL;
-- 索引行 state 与 post.state 无漂移
SELECT fi.post_id, fi.state, p.state
  FROM post_feed_index fi
  JOIN post p ON p.post_id = fi.post_id
 WHERE fi.state <> p.state;
