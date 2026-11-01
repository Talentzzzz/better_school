-- ============================================================================
-- IM（chat 域）建表语句 —— 仅 1v1 私信 + 市集 1v1 会话
-- 范围：不包含群聊。群相关的一切都从这版里删掉了（见下方"相对群版本删了什么"）。
--
-- ID 约定：每表 `id BIGINT AUTO_INCREMENT` 物理主键（无业务含义，不出网关、不被引用）；
--          跨表引用 / 对外暴露 / 幂等键一律用业务键列 conv_id / msg_id / report_id（应用侧雪花生成）。
--
-- 相对"含群版本"删了什么，为什么：
--   * conversation.kind        —— 只有一种会话，判别列失去意义（恒为 DIRECT）
--   * conversation.status      —— SEALED 封盘是群终态（宿主业务结束）；1v1 没有终态语义
--   * 身份子表 direct/group_conversation —— 只有一种身份，user_a_id/user_b_id 直接落在会话行
--   * conversation_member.left_at —— 1v1 没有"移除成员"（删除会话是 hidden_at）
--   * conversation_member.created_at —— 1v1 加入时间 ≡ conversation.created_at
--   * message 的 SYSTEM 类型与 from_user_id 可空 —— 系统消息（建群/成员进出/封盘）都是群事件，1v1 无
--   保留：骨架的取号器与列表冗余、成员水位线、消息、时间线、市集上下文、举报
-- ============================================================================

USE better_school;

-- ---------------------------------------------------------------------------
-- 1. conversation —— 1v1 会话本体（身份 + 取号器 + 列表冗余三合一）
--    写：发消息事务内 UPDATE last_seq = last_seq + 1（行锁到提交 ⇒ 取号序 = 提交序，回滚无空洞）
--    读：会话列表从 conversation_member 驱动；"我和某人的会话"走 uk_pair 直接命中
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    conv_id          BIGINT       NOT NULL COMMENT '业务主键（雪花）：跨表引用、对外暴露、幂等键一律用它',
    user_a_id        BIGINT       NOT NULL COMMENT '成员之一（约定 a < b，写入前规范化）',
    user_b_id        BIGINT       NOT NULL COMMENT '另一成员（约定 a < b，写入前规范化）',
    last_seq         BIGINT       NOT NULL DEFAULT 0 COMMENT '消息序号水位（取号器）：会话内从 1 起；未读数 = 此列 − 我的水位线',
    last_msg_type    VARCHAR(20)  NULL COMMENT '列表预览标签：IMAGE→[图片]、CARD→按引用类型出 [商品]/[帖子]/[跑腿]',
    last_msg_preview VARCHAR(200) NULL COMMENT '列表预览文案：TEXT 截断 / 撤回→[已撤回]',
    last_msg_at      DATETIME     NULL COMMENT '最后消息时间（会话列表排序键）',
    created_at       DATETIME     NOT NULL COMMENT '创建时间（≠ 第一条消息时间：建了会话可能一直没说话）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_id (conv_id) COMMENT '业务键唯一；跨表引用的唯一入口',
    UNIQUE KEY uk_pair (user_a_id, user_b_id) COMMENT '一对人一条会话；命中即幂等返回既有 conv_id（建会话的并发兜底）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '1v1 会话（身份 + 取号器 + 列表冗余）';

-- ---------------------------------------------------------------------------
-- 2. conversation_member —— 每人一份的会话状态（1v1 恒两行）
--    为什么 1v1 也要这张表：水位线与隐藏都是"每人一份"，用行表达就不必在每个读写点
--    判断"我是 a 还是 b"；列表查询也由 idx_user 驱动，天然只出我参与的会话。
--    写：打开会话推高 last_read_seq（只前进）/ 新消息清空 hidden_at（删除的会话弹回）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation_member (
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    conv_id       BIGINT   NOT NULL COMMENT '会话业务键',
    user_id       BIGINT   NOT NULL COMMENT '成员 user_id',
    last_read_seq BIGINT   NOT NULL DEFAULT 0
        COMMENT '已读水位线：以下全已读以上全未读。未读数 = conversation.last_seq − 此列；已读回执 = 对方的此列；打开会话即推到 last_seq',
    hidden_at     DATETIME NULL COMMENT '会话隐藏时间（用户侧删除会话，软删）；新消息清空弹回',
    PRIMARY KEY (id),
    UNIQUE KEY uk_member (conv_id, user_id),
    KEY idx_user (user_id) COMMENT '会话列表驱动索引'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话成员状态（1v1 恒两行）';

-- ---------------------------------------------------------------------------
-- 3. message —— 消息内容中心（内容只写一份，撤回只改这里）
--    写：随发消息事务插入一次
--    读：按 (conv_id, seq) 范围分页拉历史；uk 前缀即游标，(conv_id, seq) 同时是推送判重键
--    注意：本表带 3 个唯一索引，是写热点；实测写放大吃紧时第一顺位砍 uk_msg_id
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS message (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    msg_id          BIGINT        NOT NULL COMMENT '消息业务键（雪花）：跨表引用与对外暴露用它',
    conv_id         BIGINT        NOT NULL COMMENT '会话业务键',
    seq             BIGINT        NOT NULL COMMENT '消息序号（conversation.last_seq 取号；会话内从 1 起，跨会话不可比）',
    from_user_id    BIGINT        NOT NULL COMMENT '发送者 user_id（1v1 无系统消息，恒非空）',
    msg_type        VARCHAR(20)   NOT NULL DEFAULT 'TEXT'
        COMMENT 'TEXT/IMAGE/CARD 三类（群的 SYSTEM 系统消息已随群一起省掉）',
    content         VARCHAR(1024) NULL
        COMMENT '载荷按类型分：TEXT=裸文本（业务上限 500 由代码常量收敛）/ IMAGE=JSON{url,width,height} / CARD=恒空（纯实时渲染，引用走 ref 两列）',
    client_msg_id   VARCHAR(36)   NULL COMMENT '客户端幂等键（UUID）：乐观上屏后重发复用同键',
    ref_type        VARCHAR(20)   NULL COMMENT 'CARD 引用类型 PRODUCT/POST/ERRAND（仅 CARD 非空）',
    ref_id          BIGINT        NULL COMMENT 'CARD 引用对象 ID（对应域雪花业务键；渲染时实时查，失效显示「内容已失效」）',
    status          VARCHAR(20)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/RECALLED：撤回占 seq 不回退、不动水位线、计入未读',
    recalled_at     DATETIME      NULL COMMENT '撤回时间',
    recalled_by     VARCHAR(20)   NULL COMMENT 'USER=发送者限时窗口内自撤 / AUDITOR=举报强制撤回（无窗口限制）',
    created_at      DATETIME      NOT NULL COMMENT '发送时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id) COMMENT '业务键唯一；timeline 与对外接口的引用锚点',
    UNIQUE KEY uk_conv_seq (conv_id, seq) COMMENT '历史分页游标 + 推送判重键',
    UNIQUE KEY uk_conv_client (conv_id, client_msg_id) COMMENT '发送幂等：撞键回查收敛同一条'
    -- idx_ref(ref_type, ref_id) 刻意不建：无「按卡片反查消息」的读路径
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息内容（内容中心，只写一份）';

-- ---------------------------------------------------------------------------
-- 4. timeline —— 写扩散瘦收件箱（只插不改）
--    ⚠ 1v1 只有两个收件人，扇出没有"一次写多人读"的收益，本表纯粹为了拿到
--      "与已读水位线无关的交付游标"。可砍，见文件末尾"可砍清单"。
--    写：扇出事务内为双方各插一行，per-user delivery_seq 取号
--    读：增量补拉 user_id = ? AND delivery_seq > ?（全表唯一读法）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS timeline (
    id           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '物理主键；同时禁止被当同步游标（分配序 ≠ 提交序，并发会漏拉）',
    user_id      BIGINT   NOT NULL COMMENT '收件人 user_id',
    conv_id      BIGINT   NOT NULL COMMENT '会话业务键（冗余：判重键与消息定位，免回查）',
    seq          BIGINT   NOT NULL COMMENT '消息序号副本（会话内，跨会话不可比）',
    msg_id       BIGINT   NOT NULL COMMENT '消息业务键（外联 message.msg_id）',
    delivery_seq BIGINT   NOT NULL COMMENT 'per-user 投递序号：单调且 = 提交序，增量补拉游标；与消息序号是两个编号空间',
    created_at   DATETIME NOT NULL COMMENT '投递时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_delivery (user_id, delivery_seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '时间线（写扩散收件箱，只插不改）';

-- ---------------------------------------------------------------------------
-- 5. user_seq —— 投递序号计数器（每用户一行；delivery_seq 的取号器）
--    写：扇出事务内 INSERT ... ON DUPLICATE KEY UPDATE seq = seq + N 后读回（冲突键是 uk_user）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_seq (
    id      BIGINT NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    seq     BIGINT NOT NULL DEFAULT 0 COMMENT '投递序号计数器（0 基）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (user_id) COMMENT '计数器取号的冲突键（ODKU 靠它命中）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投递序号计数器（每用户一行）';

-- ---------------------------------------------------------------------------
-- 6. conversation_market —— 市集上下文（仅市集入口建的那条会话有行）
--    纯私信不需要市集字段，所以市集相关的一切都放这张表，私信读路径永不碰它。
--    首来源钉死：只在建会话时插一行，此后不改（同一对人先聊商品后私信仍是同一条会话）。
--    negotiate_status 独立于会话本身：CLOSED 只是"从市集页移出/置灰"，不是禁言。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation_market (
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    conv_id          BIGINT      NOT NULL COMMENT '业务主键（= conversation.conv_id，1:1）',
    object_type      VARCHAR(20) NOT NULL COMMENT '来源域兼业务对象类型 PRODUCT/WANT_TO_BUY/ERRAND：创建即定死',
    object_id        BIGINT      NOT NULL COMMENT '业务对象 ID（商品/求购/跑腿单的雪花业务键）',
    initiator_id     BIGINT      NOT NULL COMMENT '首建发起人 user_id：对家角色（卖家/买家/跑手）的相对视角锚点',
    order_id         BIGINT      NULL COMMENT '关联订单 ID（成交后回填）',
    negotiate_status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED：市集侧归档可见性，不是禁言',
    updated_at       DATETIME    NOT NULL COMMENT '状态/订单回填时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_id (conv_id) COMMENT '一条会话最多一行市集上下文',
    KEY idx_object (object_type, object_id) COMMENT '反查入口：商品/求购/跑腿单详情页 → 与谁的会话',
    KEY idx_order (order_id) COMMENT '反查入口：订单详情页 → 交易沟通'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '市集会话上下文（市集专属字段全在这里）';

-- ---------------------------------------------------------------------------
-- 7. message_report —— 消息举报（1v1 零审核策略下的唯一兜底）
--    处理动作 = 强制撤回（复用 message 的撤回三件套）。
--    若项目另有统一举报中心（帖子/评论/用户/消息共表），本表并入共享表，target_type=MESSAGE。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS message_report (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    report_id        BIGINT        NOT NULL COMMENT '举报单业务键（雪花）：对外工单号，管理端按它操作',
    msg_id           BIGINT        NOT NULL COMMENT '被举报消息业务键',
    conv_id          BIGINT        NOT NULL COMMENT '冗余：处理与定位时零反查',
    author_id        BIGINT        NOT NULL COMMENT '被举报消息发送者 user_id（冗余）',
    reporter_id      BIGINT        NOT NULL COMMENT '举报人 user_id（服务层校验须为该会话成员）',
    reason           VARCHAR(200)  NOT NULL COMMENT '举报理由',
    content_snapshot VARCHAR(1000) NULL COMMENT '举报时内容快照（消息可能已撤回且读路径脱敏，事后无法取证）',
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSED(核实)/IGNORED(不实)',
    handler_id       BIGINT        NULL COMMENT '处理人 user_id',
    handled_at       DATETIME      NULL COMMENT '处理时间',
    created_at       DATETIME      NOT NULL COMMENT '举报时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_id (report_id) COMMENT '业务键唯一',
    UNIQUE KEY uk_reporter_msg (reporter_id, msg_id) COMMENT '一人一消息一单',
    KEY idx_status (status, created_at) COMMENT '待处理队列'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息举报（1v1 零审核的兜底）';

-- ============================================================================
-- 三个关键查询（校验索引是否够用）
-- ============================================================================
-- 会话列表（我参与的会话，按最后消息时间倒序，未读一次算出）
--   驱动：conversation_member.idx_user；排序：对"我的会话数"做 filesort（几十~几百行，可接受）
--   会话数上千且排序成瓶颈时，才需要把 last_msg_at 冗余进 member 并建 (user_id, last_msg_at)
SELECT c.conv_id, c.user_a_id, c.user_b_id, c.last_msg_type, c.last_msg_preview, c.last_msg_at,
       m.last_read_seq, (c.last_seq - m.last_read_seq) AS unread
FROM conversation_member m
JOIN conversation c ON c.conv_id = m.conv_id
WHERE m.user_id = ? AND m.hidden_at IS NULL
ORDER BY c.last_msg_at DESC
LIMIT ?, ?;

-- 我和某人的会话（一对人唯一：入参先规范化 a < b）
SELECT c.* FROM conversation c WHERE c.user_a_id = ? AND c.user_b_id = ?;

-- 历史分页（游标倒退取页）：uk_conv_seq 前缀即游标
SELECT * FROM message WHERE conv_id = ? AND seq < ? ORDER BY seq DESC LIMIT ?;

-- 增量补拉（有 timeline 时）：单一全局游标，一次请求补齐所有会话的缺口
SELECT t.delivery_seq, m.*
FROM timeline t JOIN message m ON m.msg_id = t.msg_id
WHERE t.user_id = ? AND t.delivery_seq > ?
ORDER BY t.delivery_seq
LIMIT ?;

-- ============================================================================
-- 可砍清单（1v1 专属：这三处砍掉都还能跑，代价写清楚了）
-- ============================================================================
-- A. timeline + user_seq（写扩散收件箱）
--    收益：省掉本域行数最大的表（消息数 × 2 行）、每消息 2 次扇出插入与 2 次计数器更新。
--    代价：失去"与已读水位线无关的交付游标"。替代补拉查询：
--      SELECT m.* FROM message m
--      JOIN conversation_member cm ON cm.conv_id = m.conv_id AND cm.user_id = ?
--      WHERE m.seq > cm.last_read_seq
--      ORDER BY m.created_at;
--    替代方案的语义差别：交付游标与已读水位混为一个概念。只要"打开会话 = 先拉历史再推水位"，
--    就不会漏；一旦客户端在没拿到消息的情况下推了水位，那批消息补不回来。
--    结论：想要"断线后保证不漏"就留着；极简 v1 可以砍。
--
-- B. conversation_market（市集上下文）
--    只做纯私信时整张可删，删掉后 conversation 就是纯粹的两人会话，一个市集字段都没有。
--
-- C. message.ref_type / ref_id（卡片引用）
--    若 1v1 里不允许发站内对象卡片，这两列与 msg_type 的 CARD 一起删。
--
-- ============================================================================
-- 可选变体：要求「同一对人可有市集会话与私信两条并行会话」时，只改唯一键
-- ============================================================================
-- ALTER TABLE conversation
--     ADD COLUMN context_kind VARCHAR(20) NOT NULL DEFAULT 'SOCIAL' COMMENT 'SOCIAL 日常私信 / MARKET 市集沟通',
--     ADD COLUMN context_ref  VARCHAR(64) NULL     COMMENT 'MARKET: object_type#object_id；SOCIAL 为 NULL',
--     DROP INDEX uk_pair,
--     -- MySQL 无部分唯一索引，用生成列 + 多 NULL 语义表达两套唯一性：
--     ADD COLUMN social_key VARCHAR(64) GENERATED ALWAYS AS
--         (IF(context_kind = 'SOCIAL', CONCAT(user_a_id, ':', user_b_id), NULL)) STORED,
--     ADD COLUMN market_key VARCHAR(96) GENERATED ALWAYS AS
--         (IF(context_kind = 'MARKET', CONCAT(user_a_id, ':', user_b_id, ':', context_ref), NULL)) STORED,
--     ADD UNIQUE KEY uk_social (social_key),   -- 一对人最多一条私信会话
--     ADD UNIQUE KEY uk_market (market_key);   -- 一对人每个业务对象最多一条市集会话

-- ============================================================================
-- 将来要加群聊时的迁移面（本方案唯一的"欠账"）
-- ============================================================================
-- 好消息：下游全部只认 conv_id，所以 message / timeline / conversation_member 一行都不用动。
-- 只需动会话身份：
--   1. conversation 加 kind VARCHAR(20) NOT NULL DEFAULT 'DIRECT'
--   2. 把 user_a_id / user_b_id 迁到新表 direct_conversation（或保留在 conversation 并允许群行为 NULL）
--   3. 新增 group_conversation（群名 + 宿主绑定）与 conversation.status（封盘终态，放在取号器同一行）
-- 也就是说：这份 1v1 schema 不是死路，只是把"多态"这笔债推迟到真的需要时再还。
