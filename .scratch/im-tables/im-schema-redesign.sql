-- ============================================================================
-- IM（chat 域）建表语句 —— 从零设计草案 v2（按「自增 id 无业务含义」规范重写）
--
-- ID 约定（本文件全表遵守）：
--   * 每张表都有 `id BIGINT NOT NULL AUTO_INCREMENT` 作为物理主键：
--     只用于聚簇顺序与页内定位，禁止出现在跨表引用、对外接口、幂等判定里。
--   * 跨表引用 / 对外暴露 / 幂等键一律用业务键列：conv_id / msg_id / report_id ...
--     业务键由应用侧雪花生成（WorkerId 按实例分配），BIGINT。
--   * 例外说明：timeline / conversation_member / user_seq 是纯内部行表，
--     没有任何一方会引用"某一条记录"（引用形态是 (conv_id, user_id) 或 (user_id)），
--     故不设业务键列——"自增 id 不得承载业务含义"的要求依然满足。
--     若团队要求全表风格统一，可以给它们补 xxx_id，代价是每表多一个唯一索引。
--
-- 为什么这条约定在本设计里特别值钱（不只是风格）：
--   1. 物理主键自增 ⇒ 插入顺序追加；若用雪花 ID 直接做聚簇主键，插入落在随机位置，
--      页分裂与写放大明显（本域 message 是写放大敏感表）。
--   2. 建会话一个事务要写 conversation + direct_conversation + 两条成员行：
--      业务键由应用侧预生成，才能"先定 id 再落库"，重试也用同一个值（幂等）；
--      靠自增回填就得依赖 LAST_INSERT_ID 与写入顺序，无法幂等重放。
--   3. 对外只暴露 conv_id/msg_id，自增 id 不出网关（不可遍历、不可被当业务序号）。
--
-- 设计取向（沿用上一版结论）：
--   骨架共用 / 类型分家（1v1、群）/ 上下文分家（市集）/ 成员与消息管道不分家 / id 空间唯一
--
-- 不在本文件：推送 outbox、连接票据与心跳（Redis）、站内通知（notification 域）、
--             统一举报中心（若与帖子/评论/用户共表，则 message_report 并入该表）
-- ============================================================================

USE better_school;

-- ---------------------------------------------------------------------------
-- 1. conversation —— 会话骨架
--    唯一 id 来源 + 取号器 + 列表冗余 + 终态。
--    写：发消息事务内 UPDATE last_seq = last_seq + 1（行锁到提交 ⇒ 取号序 = 提交序，回滚无空洞）
--    读：会话列表一律从 conversation_member 驱动，按 uk_conv_id 回表，按 last_msg_at 降序
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    conv_id          BIGINT       NOT NULL COMMENT '业务主键（雪花）：跨表引用、对外暴露、幂等键一律用它',
    kind             VARCHAR(20)  NOT NULL COMMENT 'DIRECT/GROUP，创建即定死不可互转',
    last_seq         BIGINT       NOT NULL DEFAULT 0 COMMENT '消息序号水位（取号器）：会话内从 1 起',
    last_msg_type    VARCHAR(20)  NULL COMMENT '列表预览标签：IMAGE→[图片]、CARD→按引用类型出标签',
    last_msg_preview VARCHAR(200) NULL COMMENT '列表预览文案：TEXT 截断 / SYSTEM 渲染文案 / 撤回→[已撤回]',
    last_msg_at      DATETIME     NULL COMMENT '最后消息时间（会话列表排序键）',
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE/SEALED。刻意与取号器同行：发送与封盘各锁这一行，即同时序列化「取号」与「终态判定」，不必维护跨表加锁顺序。1v1 恒 ACTIVE',
    created_at       DATETIME     NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_id (conv_id) COMMENT '业务键唯一；跨表引用的唯一入口'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话骨架（1v1 与群共有的状态 + 序号取号器）';

-- ---------------------------------------------------------------------------
-- 2. direct_conversation —— 1v1 身份
--    只有"这两个人是谁 + 一对人一条会话"；一个市集字段都没有（市集在 market_conversation）。
--    规范化写入（a < b）由唯一入口保证：uk_pair 是身份约束，不是展示约束。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS direct_conversation (
    id         BIGINT NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    conv_id    BIGINT NOT NULL COMMENT '业务主键（= conversation.conv_id）',
    user_a_id  BIGINT NOT NULL COMMENT '成员之一（约定 a < b）',
    user_b_id  BIGINT NOT NULL COMMENT '另一成员（约定 a < b）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_id (conv_id) COMMENT '与 conversation 1:1（同时兜住一个 conv_id 只能有一行身份）',
    UNIQUE KEY uk_pair (user_a_id, user_b_id) COMMENT '一对人一条 1v1 会话；命中即幂等返回既有会话'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '1v1 会话身份（纯身份，无业务字段）';

-- ---------------------------------------------------------------------------
-- 3. group_conversation —— 群身份与宿主绑定
--    终态（封盘）在 conversation.status，不在这里：与取号器同行才能一把锁完成取号+终态判定。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS group_conversation (
    id       BIGINT      NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    conv_id  BIGINT      NOT NULL COMMENT '业务主键（= conversation.conv_id）',
    name     VARCHAR(50) NOT NULL COMMENT '群名：系统创建时传入，创建后任何人不可改',
    ref_type VARCHAR(20) NULL COMMENT '宿主业务类型（ERRAND=跑腿多人单成团建群）；预留空值给非宿主群',
    ref_id   BIGINT      NULL COMMENT '宿主业务 ID（对应域的雪花业务键）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_id (conv_id) COMMENT '与 conversation 1:1',
    UNIQUE KEY uk_ref (ref_type, ref_id)
        COMMENT '同一宿主一对一建群：至少一次投递/重发撞键即幂等返回既有群。两列同为 NULL 的行不受限（唯一索引允许多 NULL），故非宿主群不受此约束'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '群身份（群名 + 宿主绑定）';

-- ---------------------------------------------------------------------------
-- 4. market_conversation —— 市集会话上下文
--    有行 = 这条 1v1 会话由市集入口首次建立（首来源钉死：只插一行，此后不改）。
--    市集要向会话挂什么，都挂这张表；私信会话没有行，私信读路径永不碰这张表。
--    生命周期刻意独立于 conversation.status：CLOSED 只是"从市集页移出/置灰"，不是禁言，
--    因此不进发送热路径，也就不需要为它加锁或定加锁顺序。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS market_conversation (
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    conv_id          BIGINT      NOT NULL COMMENT '业务主键（= conversation.conv_id）',
    object_type      VARCHAR(20) NOT NULL COMMENT '来源域兼会话围绕的业务对象类型 PRODUCT/WANT_TO_BUY/ERRAND：创建即定死',
    object_id        BIGINT      NOT NULL COMMENT '业务对象 ID（商品/求购/跑腿单的雪花业务键）',
    initiator_id     BIGINT      NOT NULL COMMENT '首建发起人 user_id：对家角色（卖家/买家/跑手）的相对视角锚点，按请求者现算不落库',
    order_id         BIGINT      NULL COMMENT '关联订单 ID（成交后回填）：product_order / errand_order 的雪花业务键',
    negotiate_status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED：市集侧归档可见性，不是禁言',
    updated_at       DATETIME    NOT NULL COMMENT '状态/订单回填时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_conv_id (conv_id) COMMENT '与 conversation 1:1',
    KEY idx_object (object_type, object_id) COMMENT '反查入口：商品/求购/跑腿单详情页 → 与谁的会话',
    KEY idx_order (order_id) COMMENT '反查入口：订单详情页 → 交易沟通'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '市集会话上下文（市集专属字段全在这里）';

-- ---------------------------------------------------------------------------
-- 5. conversation_member —— 成员状态（唯一会被更新的成员维度行）
--    1v1 恰两行，群 N 行；写扩散内核只走这一条路径，不按类型分叉。
--    写：打开会话推高 last_read_seq（只前进）/ 新消息清空 hidden_at（删除的会话弹回）/ 群移除写 left_at
--    读：idx_user 扫成员行 → uk_conv_id 回表 → 按 last_msg_at 降序；未读数一次算出
--    无业务键列：没有任何一方引用"某一条成员记录"，引用形态是 (conv_id, user_id)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS conversation_member (
    id              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    conv_id         BIGINT   NOT NULL COMMENT '会话业务键',
    user_id         BIGINT   NOT NULL COMMENT '成员用户 ID',
    last_read_seq   BIGINT   NOT NULL DEFAULT 0
        COMMENT '已读水位线：以下全已读以上全未读。未读数 = conversation.last_seq − 此列；已读回执 = 对方的此列；打开会话即推到 last_seq',
    hidden_at       DATETIME NULL COMMENT '会话隐藏时间（用户侧删除会话，软删）；新消息清空弹回',
    left_at         DATETIME NULL COMMENT '被移除时间（仅群写；留痕不删行：停止扇出、不出列表，历史仍可追溯纠纷）',
    created_at      DATETIME NOT NULL COMMENT '加入时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_member (conv_id, user_id),
    KEY idx_user (user_id) COMMENT '会话列表驱动索引；排序键在 conversation.last_msg_at，故不并入本索引'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话成员（水位线 / 隐藏 / 移除留痕）';

-- ---------------------------------------------------------------------------
-- 6. message —— 消息内容中心（内容只写一份，撤回只改这里）
--    写：随发消息事务插入一次
--    读：按 (conv_id, seq) 范围分页拉历史；uk 前缀即游标，(conv_id, seq) 同时是推送判重键
--    ！本表是全域最热的写表，带 3 个唯一索引（uk_msg_id / uk_conv_seq / uk_conv_client），
--      见文件末尾"约定代价"一节的取舍说明。
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS message (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    msg_id          BIGINT        NOT NULL COMMENT '消息业务键（雪花）：跨表引用（timeline）与对外暴露用它',
    conv_id         BIGINT        NOT NULL COMMENT '会话业务键',
    seq             BIGINT        NOT NULL COMMENT '消息序号（conversation.last_seq 取号；会话内从 1 起，跨会话不可比）',
    from_user_id    BIGINT        NULL COMMENT '发送者用户 ID（SYSTEM 系统消息为 NULL）',
    msg_type        VARCHAR(20)   NOT NULL DEFAULT 'TEXT' COMMENT '类型 TEXT/IMAGE/CARD/SYSTEM 四类定死；撤回是状态不是类型',
    content         VARCHAR(1024) NULL
        COMMENT '载荷，语义按类型分：TEXT=裸文本（业务上限 500 由代码常量收敛）/ IMAGE=JSON{url,width,height} / SYSTEM=JSON{event,text,...} 快照式落库 / CARD=恒空（纯实时渲染）。列宽留 JSON 与 url 增长余量',
    client_msg_id   VARCHAR(36)   NULL COMMENT '客户端幂等键（UUID）：乐观上屏后重发复用同键；SYSTEM 为 NULL',
    ref_type        VARCHAR(20)   NULL COMMENT 'CARD 引用类型 PRODUCT/POST/ERRAND（仅 CARD 非空）',
    ref_id          BIGINT        NULL COMMENT 'CARD 引用对象 ID（对应域雪花业务键；仅 CARD 非空）',
    status          VARCHAR(20)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/RECALLED：撤回占 seq 不回退、不动水位线、计入未读',
    recalled_at     DATETIME      NULL COMMENT '撤回时间',
    recalled_by     VARCHAR(20)   NULL COMMENT '撤回者 USER=发送者限时窗口内自撤 / AUDITOR=举报强制撤回（无窗口限制）',
    created_at      DATETIME      NOT NULL COMMENT '发送时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id) COMMENT '业务键唯一；timeline 与对外接口的引用锚点',
    UNIQUE KEY uk_conv_seq (conv_id, seq) COMMENT '历史分页游标 + 推送判重键',
    UNIQUE KEY uk_conv_client (conv_id, client_msg_id) COMMENT '发送幂等：撞键回查收敛同一条'
    -- idx_ref(ref_type, ref_id) 刻意不建：当前无「按卡片反查消息」的读路径
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息内容（内容中心，只写一份）';

-- ---------------------------------------------------------------------------
-- 7. timeline —— 写扩散瘦收件箱（只插不改）
--    写：扇出事务内按成员多值 INSERT，per-user delivery_seq 取号
--    读：增量补拉 user_id = ? AND delivery_seq > ?（全表唯一读法）
--    无业务键列：引用形态是 (user_id, delivery_seq)，没有一方引用"某一条收件行"
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS timeline (
    id              BIGINT   NOT NULL AUTO_INCREMENT COMMENT '物理主键；同时禁止被当同步游标（分配序 ≠ 提交序，并发会漏拉）',
    user_id         BIGINT   NOT NULL COMMENT '收件人用户 ID',
    conv_id         BIGINT   NOT NULL COMMENT '会话业务键（冗余：判重键与消息定位，免回查）',
    seq             BIGINT   NOT NULL COMMENT '消息序号副本（会话内，跨会话不可比）',
    msg_id          BIGINT   NOT NULL COMMENT '消息业务键（外联 message.msg_id）',
    delivery_seq    BIGINT   NOT NULL COMMENT 'per-user 投递序号：per-user 单调且 = 提交序，增量补拉游标；与消息序号是两个编号空间，不可混用',
    created_at      DATETIME NOT NULL COMMENT '投递时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_delivery (user_id, delivery_seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '时间线（不可变瘦收件箱，只插不改）';

-- ---------------------------------------------------------------------------
-- 8. user_seq —— 投递序号计数器（每用户一行）
--    写：扇出事务内 INSERT ... ON DUPLICATE KEY UPDATE seq = seq + N 后读回（行锁到提交）
--    注意：物理主键改为 id 后，ODKU 的冲突判定依赖 uk_user 而非主键，语义不变
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_seq (
    id      BIGINT NOT NULL AUTO_INCREMENT COMMENT '物理主键：不作任何业务引用',
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    seq     BIGINT NOT NULL DEFAULT 0 COMMENT '投递序号计数器（0 基）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user (user_id) COMMENT '计数器取号的冲突键（ODKU 靠它命中）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投递序号计数器（每用户一行）';

-- ---------------------------------------------------------------------------
-- 9. message_report —— 消息举报
--    IM 侧零审核策略下的唯一兜底；处理动作 = 强制撤回（复用 message 的撤回三件套）。
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
    content_snapshot VARCHAR(1000) NULL COMMENT '举报时内容快照：消息可能已被撤回且读路径一律脱敏，事后无法取证',
    status           VARCHAR(20)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSED(核实)/IGNORED(不实)',
    handler_id       BIGINT        NULL COMMENT '处理人 user_id',
    handled_at       DATETIME      NULL COMMENT '处理时间',
    created_at       DATETIME      NOT NULL COMMENT '举报时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_id (report_id) COMMENT '业务键唯一',
    UNIQUE KEY uk_reporter_msg (reporter_id, msg_id) COMMENT '一人一消息一单',
    KEY idx_status (status, created_at) COMMENT '待处理队列'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息举报（IM 零审核的兜底）';

-- ============================================================================
-- 可选变体：产品要求「同一对人可有市集会话与私信两条并行会话」时，只改唯一键
-- （默认按"一对人一条会话"设计；下面是不改任何其他表的切换方式）
-- ============================================================================
-- ALTER TABLE direct_conversation
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
-- 约定代价与取舍（诚实记账，供评审逐条反驳）
-- ============================================================================
-- 1. 自增 id + 业务键的代价
--    * 每张表多一列；每个被引用的表多一个唯一索引（uk_conv_id / uk_msg_id / uk_report_id）。
--    * 跨表查询走二级索引再回表：message 原本的 uk(conv, seq) 就是二级索引，此处无回归；
--      子表（direct/group/market）原本 PK=conv_id 可直接聚簇命中，现在多一次二级索引跳转——
--      但它们每会话仅一行，量级可忽略。
--    * 真正的新增在本域最热的写表 message：插入路径要维护 3 个唯一索引。
--      若实测写放大吃紧，第一顺位是砍 uk_msg_id（让 timeline.msg_id 引用 message.id）——
--      那等于承认"自增 id 承载了内部引用"，需要你拍板；这是唯一一处我会考虑对约定开例外的地方。
--
-- 2. 为什么 status 留在骨架而不下放群表
--    发送事务 = 锁 conversation 行 → 取号 → 写消息；封盘事务 = 锁同一行 → 改 status → 投 SEALED 消息。
--    同表同行 ⇒ 天然全序，零跨表加锁顺序要求。若 status 挪进 group_conversation，
--    两条路径各自要锁两行且方向相反，必须靠纪律维持顺序，否则并发封盘/发送会死锁。
--    代价：1v1 行带一个恒为 ACTIVE 的字段。
--
-- 3. 为什么不拆 conversation_member / message / timeline
--    成员水位线与消息管道对两类会话完全同构，而扇出是写热点；拆开 = 写热点分叉成两条路径，
--    或两张同构大表 + 两套扇出/未读/推送/撤回/搜索。该分家的是"身份"与"上下文"，不是"通信机制"。
--
-- 4. 拆表拿不到什么
--    * 不减少 JOIN：会话列表多两次 LEFT JOIN，或按 kind 分两条 SQL 合并分页。
--    * 不消灭跨表一致性：kind ↔ 子表行、object_type ↔ market_conversation 仍是约定。
--      全库不用外键，靠"同一事务多表写入 + 单一仓储入口 + 一条巡检断言"兜底，例如：
--        SELECT c.conv_id FROM conversation c
--         LEFT JOIN direct_conversation d ON d.conv_id = c.conv_id
--         LEFT JOIN group_conversation  g ON g.conv_id = c.conv_id
--         WHERE (c.kind = 'DIRECT' AND d.id IS NULL) OR (c.kind = 'GROUP' AND g.id IS NULL);
--    * 不自动让 1v1 代码干净：表的可空列是一次性丑陋，代码里的类型分支才是持续成本。
--      若只能做一件事，先拆代码（1v1 服务 / 群服务 / 市集读模型各自独立），再拆表。
--
-- 5. 什么时候就不该这么设计
--    市集侧若不是"聊天"（围绕业务对象的结构化协商：问价/报价/确认/状态流转，不需要实时、
--    无未读水位线、无历史分页），那它不该复用 message/timeline/user_seq，而应属市集域自己的
--    inquiry/negotiation 聚合，字段完全定制——那时"市集单独一套表"才是对的。
--    强判据是权限与留存：平台需要能读/审计交易沟通（纠纷仲裁、合规留存），而私信不可读；
--    这种分化一旦成立，就该连消息管道一起拆，接受两套聊天能力。
