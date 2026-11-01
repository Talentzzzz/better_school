-- ============================================================================
-- direct-room-pair-cutover.sql —— 1v1 会话配对迁出 room + room 删 user_min/user_max
-- （direct-room-pair 收口；本 effort 的不可逆点）
--
-- 何时执行：只针对**存量（线上）库**。开发库 / 测试库不需要跑——两库由 init-*.sql + schema.sql 重建，
--   schema.sql 已按迁出后的形状建表（本脚本针对的是还带着旧列、尚未删列的存量库）。
--
-- 执行序（四步缺一不可，本文件只承担第 ②④ 步）：
--   ① 建 direct_room_pair（1v1 配对映射）——执行 schema.sql 该表的 DDL
--      （`CREATE TABLE IF NOT EXISTS direct_room_pair ...`）。本脚本不复制 DDL：唯一来源仍是
--      schema.sql（同 comment-like-count-cutover.sql 的口径），照那节原样执行即可。
--   ② 本文件第 ③ 步之前的回填段：把存量 room 行的 (user_min, user_max) 折进 direct_room_pair
--      （小 id 在前与建会话归一化同口径；此刻旧列还在，正好是搬数的数据源）。
--   ③ 起新 jar —— 建会话写 direct_room_pair、查重经它、会话列表对家自连 room_member 直出。
--      **前提是 ① 与回填已完成**：回填前起新 jar，存量会话查不到 direct_room_pair 行——
--      后果只是「同一对人再点私信」会建成第二场会话（uk_pair 拦不住没有配对行的旧会话），不会报错；
--      但 1v1 查重语义在回填完成前是缺口的，所以回填要赶在 ③ 之前跑完。
--   ④ 本文件删列段：删 room.user_min / room.user_max —— 只能在 ③ 之后跑。此时建会话写 pair 表、
--      查重读 pair 表、列表对家读成员行，两列既无写方也无读方，删它才不打断任何链路。
--
-- 前置确认（执行第 ④ 步前人工核对，两条都过才动手）：
--   新 jar 已接管：本 effort 之前的那个 jar 只认 room 上的旧列、压根不写 direct_room_pair，所以
--   「这张表有活写入」就是新 jar 在跑的证明——
--     SELECT COUNT(*), MAX(update_time) FROM direct_room_pair;   -- 行数在涨、update_time 接近当前（有新会话时）
--   旧列无写方：值域冻结（观察口径同 comment-like-count-cutover.sql）——隔一段时间跑两次，数字不再增长：
--     SELECT COUNT(*) FROM room WHERE user_min IS NOT NULL AND update_time > NOW() - INTERVAL 1 DAY;
--
-- 回滚口径：
--   - 不可逆点就是删列段。**删列前**回滚到本 effort 之前的线上 jar 无害：那版代码只认旧列、
--     不读也不写 direct_room_pair（新表多余，放着不动即可；切码后新会话在 pair 表有行、旧列没有，
--     那版 jar 的「先查后插」会对这些会话漏判——回滚窗口内新会话极少，接受）。
--   - **删列后**不能再回滚到那版 jar：它的建会话 INSERT 与查重 SELECT 都写着 user_min/user_max，
--     回滚会立刻撞 Unknown column。必须先手工反向 DDL：
--       ALTER TABLE room ADD COLUMN user_min BIGINT NULL, ADD COLUMN user_max BIGINT NULL;
--     且加回来的是两列**空列**：切码后新会话的配对只存在于 direct_room_pair，旧列不会自己长回来。
--   - 本 effort 单 PR 交付、不单独发版：线上只存在「effort 之前」与「effort 之后」两版 jar。
--
-- 不幂等：回填段二次执行撞 uk_pair（Duplicate entry）；删列段二次执行报 1091（列不存在）。
--   报错即目标已达成，无需处理。
-- 执行方式（不自带 USE，库名在命令行给；① 建表完成后、④ 前回填先跑一遍，③ 起新 jar 后再跑删列段）：
--   docker exec -i bs-mysql mysql -uroot -pbs123456 --default-character-set=utf8mb4 better_school < backend/better-school/sql/backfill/direct-room-pair-cutover.sql
-- ============================================================================

-- ② 回填：存量 room 行折进配对表（此刻旧列还在，正好当数据源；直接插不改写 room）
INSERT INTO direct_room_pair (user_min, user_max, room_id, create_time, update_time)
SELECT user_min, user_max, room_id, create_time, update_time
FROM room
WHERE user_min IS NOT NULL
  AND user_max IS NOT NULL;

-- 验证（人工核对，期望两数相等）：
--   SELECT COUNT(*) FROM direct_room_pair;
--   SELECT COUNT(*) FROM room WHERE user_min IS NOT NULL AND user_max IS NOT NULL;

-- ============================================================================
-- ④ 删列：1v1 配对的旧落点退役（真源已切 direct_room_pair；只能在 ③ 起新 jar 之后执行）
-- ============================================================================

ALTER TABLE room
    DROP COLUMN user_min,
    DROP COLUMN user_max;

-- 验证（人工核对，期望 0 行）：
--   SHOW COLUMNS FROM room LIKE 'user_min';
--   SHOW COLUMNS FROM room LIKE 'user_max';
