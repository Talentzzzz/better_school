-- ============================================================================
-- room-list-dim-cutover.sql —— 会话列表职责迁往 room_member（room 删三列、room_member 增四列）
-- （chat-shard-ready 票 01；分库就绪：列表所需数据全部落用户维度行）
--
-- 何时执行：只针对**存量（线上）库**。开发库 / 测试库不需要跑——两库由 init-*.sql + schema.sql 重建，
--   schema.sql 已按迁出后的形状建表（本脚本针对的是 room 还带着 last_msg_* 三列、
--   room_member 还没有四列的存量库）。
--
-- 执行序（四步缺一不可，本文件只承担第 ①②④ 步）：
--   ① 本文件加列段：room_member 增 last_seq_copy/last_msg_type/last_msg_preview/last_msg_at 四列
--      + 增 idx_user_msg（user_id, last_msg_at）。旧 jar 不受影响：不写不读新列，
--      idx_user 暂留（旧列表查询还拿它当驱动索引）。
--   ② 本文件回填段：从 message + room 现值一次性回填 room_member 四列（此刻 room 三列还在，
--      正好是搬数的数据源；last_seq_copy = room.last_seq，预览三件套按 seq = room.last_seq
--      的 message 行复算——CASE 语义与 ChatMessageFanoutService.previewOf / 撤回替换一致，
--      room 三列作 COALESCE 兜底，两源本就同事务维护、应当一致）。
--   ③ 起新 jar —— 发送/撤回在 room_member 上刷副本，列表/未读读 room_member。
--      **前提是 ① 与回填已完成**：回填前起新 jar，存量成员行 last_seq_copy=0、预览为空——
--      列表排序与未读数全错（新消息会推进副本，存量会话不弹新消息就永远错）。
--   ④ 本文件删列段：删 room 三列 + 删 room_member.idx_user —— 只能在 ③ 之后跑。此时列表
--      排序键/预览/未读写读全在成员行，room 三列与 idx_user 既无写方也无读方，删才不打断链路。
--
-- 前置确认（执行第 ④ 步前人工核对）：
--   新 jar 已接管：room_member 新列有活写入（有新消息时 update_time 接近当前、last_msg_at 在涨）：
--     SELECT COUNT(*), MAX(update_time) FROM room_member WHERE last_msg_at IS NOT NULL;
--   旧列无写方：值域冻结——隔一段时间跑两次，数字不再增长：
--     SELECT COUNT(*) FROM room WHERE last_msg_at IS NOT NULL AND update_time > NOW() - INTERVAL 1 DAY;
--
-- 回滚口径：
--   - 不可逆点就是删列段。**删列前**回滚到本 effort 之前的线上 jar 无害：那版代码只认 room 三列、
--     不读不写 room_member 四列（新列多余，放着不动即可；回滚窗口内新消息只刷新 room 三列，
--     成员行副本短暂滞后，重新跑一遍 ② 回填段即可追平）。
--   - **删列后**不能再回滚到那版 jar：它的发送 UPDATE 与列表 SELECT 都写着 room.last_msg_*，
--     回滚会立刻撞 Unknown column。必须先手工反向 DDL：
--       ALTER TABLE room ADD COLUMN last_msg_type VARCHAR(20) NULL,
--                        ADD COLUMN last_msg_preview VARCHAR(200) NULL,
--                        ADD COLUMN last_msg_at DATETIME NULL;
--     且加回来的是三列**空列**：切码后列表数据的真相只在 room_member，旧列不会自己长回来。
--   - 本 effort 单 PR 交付、不单独发版：线上只存在「effort 之前」与「effort 之后」两版 jar。
--
-- 不幂等：① 加列段二次执行报 1060（列已存在）；④ 删列段二次执行报 1091（列不存在）。
--   报错即目标已达成，无需处理。② 回填段幂等（UPDATE 定值，可重复跑、可在 ③ 后补跑追平）。
-- 执行方式（不自带 USE，库名在命令行给；①② 先跑、③ 起新 jar、④ 后跑）：
--   docker exec -i bs-mysql mysql -uroot -pbs123456 --default-character-set=utf8mb4 better_school < backend/better-school/sql/backfill/room-list-dim-cutover.sql
-- ============================================================================

-- ① 加列：列表职责的用户维度落点（排序键 + 预览三件套 + 水位副本）
ALTER TABLE room_member
    ADD COLUMN last_seq_copy    BIGINT       NOT NULL DEFAULT 0 COMMENT '会话水位副本：未读数 = 此列 − last_read_seq（用户维度就地可算）；写链路 GREATEST 只进不退，真源 room.last_seq 仍由取号事务维护' AFTER user_id,
    ADD COLUMN last_msg_type    VARCHAR(20)  NULL COMMENT '最后一条消息类型副本（列表预览标签）' AFTER last_seq_copy,
    ADD COLUMN last_msg_preview VARCHAR(200) NULL COMMENT '最后一条消息预览文案副本（TEXT=原文截断至 200，撤回=[已撤回]）' AFTER last_msg_type,
    ADD COLUMN last_msg_at      DATETIME     NULL COMMENT '最后消息时间副本（会话列表排序键）' AFTER last_msg_preview,
    ADD KEY idx_user_msg (user_id, last_msg_at);

-- ② 回填：从 message + room 现值一次性回填四列（此刻 room 三列还在，是搬数的数据源）
UPDATE room_member rm
JOIN room r ON r.room_id = rm.room_id
LEFT JOIN message m ON m.room_id = r.room_id AND m.seq = r.last_seq
SET rm.last_seq_copy    = r.last_seq,
    rm.last_msg_type    = COALESCE(m.msg_type, r.last_msg_type),
    rm.last_msg_preview = CASE
        -- 撤回语义优先：最后一条已撤回时三列里 type 原样、preview 恒 [已撤回]
        WHEN m.state = 'RECALLED' THEN '[已撤回]'
        WHEN m.msg_type = 'TEXT'  THEN LEFT(m.content, 200)
        WHEN m.msg_type = 'IMAGE' THEN '[图片]'
        WHEN m.msg_type = 'CARD'  THEN CASE m.ref_type
            WHEN 'PRODUCT' THEN '[商品]'
            WHEN 'POST'    THEN '[帖子]'
            WHEN 'ERRAND'  THEN '[跑腿卡片]'
            ELSE r.last_msg_preview
        END
        ELSE r.last_msg_preview
    END,
    rm.last_msg_at      = COALESCE(m.create_time, r.last_msg_at),
    rm.update_time      = NOW();

-- 验证（人工核对，期望两数相等）：
--   SELECT COUNT(*) FROM room_member WHERE last_msg_at IS NOT NULL;
--   SELECT COUNT(*) FROM room WHERE last_msg_at IS NOT NULL;
--   SELECT COUNT(*) FROM room_member rm JOIN room r ON r.room_id = rm.room_id
--     WHERE rm.last_seq_copy != r.last_seq;

-- ============================================================================
-- ④ 删列：列表职责的会话维度旧落点退役（真源已切 room_member；只能在 ③ 起新 jar 之后执行）
-- ============================================================================

ALTER TABLE room
    DROP COLUMN last_msg_type,
    DROP COLUMN last_msg_preview,
    DROP COLUMN last_msg_at;

ALTER TABLE room_member
    DROP KEY idx_user;

-- 验证（人工核对，期望 0 行）：
--   SHOW COLUMNS FROM room LIKE 'last_msg%';
--   SHOW INDEX FROM room_member WHERE Key_name = 'idx_user';
