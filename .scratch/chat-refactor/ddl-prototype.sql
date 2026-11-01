-- chat 领域重构 DDL 原型（票 02，2026-09-09）
-- 依据：票 01 时间线模型（统一写扩散：room/room_user/message/timeline 四表 + 水位线）
--      + 票 05 群聊产品规则（系统群/封盘/留痕）+ 票 06 消息类型与举报（混合消息体/撤回三件套）
-- 形态：backend/sql/init.sql 风格草稿，供评审迭代，落地时并入 init.sql
-- 前提：旧私信数据本地重置不迁移 —— message_thread 表定义删除，message 表直接重定义
--
-- 模型级决策（对票面问题的回答，逐表说明）：
--   * 消息记录表：不建（票 01 否决——timeline 瘦行就是它，status 被 room_user.last_read_seq 水位线取代）
--   * 已读位：room_user.last_read_seq 会话级水位线（不是 per-message read_seq），未读数 = room.last_seq − 水位线 O(1)，
--     无计数器无 COUNT（seq 事务内原子分配无空洞）
--   * 会话级隐藏：room_user.hidden_at（挂在成员状态行，不碰 message/timeline——时间线不可变）
--   * 撤回：message.status/recalled_at/recalled_by 三件套（撤回是消息状态不是类型，票 06）
--   * 群成员角色：无角色字段（票 05 系统群定向：无群主/管理员/禁言，成员只有进出留痕）
--   * 群上限 500：不落 DB 约束——系统侧建群/加人 API 对 room_user COUNT 校验、可配置（约束在 service 层）
--   * chat 全路径零审核：chat 表族无任何审核字段（票 05），兜底走 content_report 扩 target_type=MESSAGE（文末）
--   * 1v1 唯一会话：room.user_min/user_max 可空列 + uk_direct_pair（群两列为 NULL；MySQL 唯一索引允许多 NULL，
--     同一 uk 覆盖「1v1 同人唯一、群不受限」两个语义）——替代旧 message_thread.uk_user_pair

-- 会话（1v1 与群统一模型；seq 取号 + 会话列表冗余）
-- 核心读写路径：写=发消息事务内 UPDATE last_seq=last_seq+1 原子取号 + 冗余 last_msg 三列；
--               读=会话列表按 last_msg_at 排序（room 由 room_user.user_id 索引驱动回表）
CREATE TABLE IF NOT EXISTS room (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    type             VARCHAR(20)  NOT NULL COMMENT '类型 DIRECT(1v1)/GROUP(系统群)，创建即定死不可互转（票 05）',
    user_min         BIGINT       NULL COMMENT '1v1 较小成员用户 ID（约定较小值；群为 NULL）',
    user_max         BIGINT       NULL COMMENT '1v1 较大成员用户 ID（约定较大值；群为 NULL）',
    name             VARCHAR(50)  NULL COMMENT '群名（仅 GROUP 非空，建群时系统传入，创建后任何人不可改，票 05；1v1 无群名为 NULL，列表展示对方昵称）',
    last_seq         BIGINT       NOT NULL DEFAULT 0 COMMENT '会话内消息序号水位：发消息事务内 UPDATE last_seq=last_seq+1 原子取号，失败回滚无空洞',
    last_msg_type    VARCHAR(20)  NULL COMMENT '最后一条消息类型（列表预览标签：IMAGE=[图片]、CARD=按 ref_type 出标签 [商品]/[帖子]/[跑腿]，不查标题，与卡片实时渲染口径一致）',
    last_msg_preview VARCHAR(200) NULL COMMENT '最后一条消息预览文案（TEXT=原文截断至 200 原文上限 500、SYSTEM=渲染文案、撤回=[已撤回]）',
    last_msg_at      DATETIME     NULL COMMENT '最后消息时间（会话列表排序键）',
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/SEALED：封盘=宿主业务终态软删不可逆（重开要复活水位线/扇出/seq 无场景），禁发言禁成员变更，历史消息全员只读',
    created_at       DATETIME     NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_direct_pair (user_min, user_max)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话表（1v1 与系统群统一）';

-- 会话成员（唯一会被更新的成员维度行；成员状态只在这一张表）
-- 核心读写路径：写=打开会话推高 last_read_seq / 新消息扇出时清空 hidden_at（删除的会话弹回）；
--               读=idx_user 扫成员行 JOIN room（主键回表）出会话列表，未读数一次 JOIN 全算完
CREATE TABLE IF NOT EXISTS room_user (
    id            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    room_id       BIGINT   NOT NULL COMMENT '会话 ID',
    user_id       BIGINT   NOT NULL COMMENT '成员用户 ID',
    last_read_seq BIGINT   NOT NULL DEFAULT 0 COMMENT '已读水位线（Kafka 消费位点同思想）：以下全已读以上全未读；未读数 = room.last_seq − 此列；已读回执 = 对方的此列；打开会话即推到 room.last_seq（会话级语义，不做滚动位置细化）',
    hidden_at     DATETIME NULL COMMENT '会话隐藏时间（用户侧删除会话，软删）；新消息清空弹回（微信行为）',
    left_at       DATETIME NULL COMMENT '被移除时间（留痕不删行：此后停止扇出且不再出现在会话列表，此前消息与详情仍可追溯跑腿纠纷）',
    created_at    DATETIME NOT NULL COMMENT '加入时间（建群/成员加入事件时刻）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_user (room_id, user_id),
    KEY idx_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话成员表（水位线/隐藏/留痕）';

-- 消息内容（内容中心表：内容只写一份，撤回/强制撤回只改这里）
-- 核心读写路径：写=随发消息事务插入一次；读=按 room + seq 范围分页拉历史（uk_room_seq 前缀即游标），
--               (roomId, seq) 同时是 WS 推送判重键
CREATE TABLE IF NOT EXISTS message (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    room_id      BIGINT       NOT NULL COMMENT '会话 ID',
    seq          BIGINT       NOT NULL COMMENT '消息序号（room.last_seq 取号；会话内从 1 起，跨会话不可比）',
    from_user_id BIGINT       NULL COMMENT '发送者用户 ID（SYSTEM 系统消息为 NULL）',
    msg_type     VARCHAR(20)  NOT NULL DEFAULT 'TEXT' COMMENT '类型 TEXT/IMAGE/CARD/SYSTEM 四类定死（撤回是消息状态不是类型，票 06）',
    content      VARCHAR(500) NULL COMMENT '载荷（语义按类型分）：TEXT=裸文本≤500 单一常量；IMAGE=JSON {url,width,height}，url 须本站上传域前缀；SYSTEM=JSON {event,...}+服务端渲染文案（快照式落库）；CARD=恒空（纯实时渲染无快照）',
    ref_type     VARCHAR(20)  NULL COMMENT 'CARD 引用类型 PRODUCT/POST/ERRAND（结构化外键非内容：跳转语义显式、可索引反查）；仅 CARD 非空',
    ref_id       BIGINT       NULL COMMENT 'CARD 引用对象 ID；仅 CARD 非空；渲染时实时查对象，失效显示「内容已失效」',
    status       VARCHAR(20)  NOT NULL DEFAULT 'NORMAL' COMMENT '状态 NORMAL/RECALLED：撤回占 seq 不回退、不动水位线、计入未读；content 库内保留取证，读路径与下发一律脱敏为「已撤回」空壳',
    recalled_at  DATETIME     NULL COMMENT '撤回时间',
    recalled_by  VARCHAR(20)  NULL COMMENT '撤回者 USER=发送者 2 分钟窗口内自行撤回 / AUDITOR=举报强制撤回（走系统侧内部撤回 API，无窗口限制）',
    created_at   DATETIME     NOT NULL COMMENT '发送时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_room_seq (room_id, seq)
    -- idx_ref(ref_type, ref_id) 刻意不建：当前无「按卡片反查消息」的读路径（票 06 只说可索引反查），
    -- 而 message 是写放大敏感表；真出现该读路径时补索引即可
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '消息内容表（内容中心，只写一份）';

-- 时间线（不可变瘦收件箱：只插不改，多值 INSERT 扇出；一条消息 N 个成员 N 行）
-- 核心读写路径：写=扇出事务内 user_seq 计数器取号后单条多值 INSERT（一条消息 N 个成员 N 行）；
--               读=增量补拉 user_id = ? AND delivery_seq > ? （游标谓词，全表唯一读法）
CREATE TABLE IF NOT EXISTS timeline (
    id           BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键（仅物理自增，禁止当同步游标——id 分配序 ≠ 提交序，并发下漏拉，票 01 Comments）',
    user_id      BIGINT   NOT NULL COMMENT '收件人用户 ID',
    room_id      BIGINT   NOT NULL COMMENT '会话 ID（冗余：判重键 (roomId,seq) 与消息定位，免回查）',
    seq          BIGINT   NOT NULL COMMENT '消息序号副本（room 内，从 1 起，跨会话不可比）',
    msg_id       BIGINT   NOT NULL COMMENT '消息 ID（外联 message 内容中心表）',
    delivery_seq BIGINT   NOT NULL COMMENT 'per-user 投递序号（user_seq 计数器扇出事务内取号）：per-user 单调且=提交序，增量补拉游标；与消息序号是两个序号空间不可混用',
    created_at   DATETIME NOT NULL COMMENT '投递时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_delivery (user_id, delivery_seq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '时间线（不可变瘦收件箱，只插不改）';

-- 投递序号计数器（每用户一行；delivery_seq 的取号器）
-- 核心读写路径：写=扇出事务内 UPDATE seq=seq+N 读回各值，行锁到提交保证同一用户 delivery_seq 单调且=提交序；
--               读=仅扇出事务内读回，无独立读路径（1v1 每条消息 +2 行计数器更新：双方各取一个 delivery_seq；500 人群 +N 行/条，写放大已计入票 01 演进口）
CREATE TABLE IF NOT EXISTS user_seq (
    user_id BIGINT NOT NULL COMMENT '用户 ID',
    seq     BIGINT NOT NULL DEFAULT 0 COMMENT '投递序号计数器（0 基）：扇出事务内 UPDATE seq=seq+N 后读回，行锁到提交',
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '投递序号计数器表（每用户一行）';

-- 举报兜底（票 06）：content_report 扩 target_type=MESSAGE，其余一行不动
--   * uk_reporter_target(reporter_id, target_type, target_id) 直接复用 = 一人一消息一单
--   * 举报者须为会话成员（room_user 存在校验，服务层）；已撤回消息可举报（追责留痕，处理时幂等）
--   * content_snapshot 存消息 content（TEXT ≤500、IMAGE/SYSTEM 为 JSON，均在 VARCHAR(1000) 内）；CARD content 为空，snapshot 存 ref 标识（如 CARD:PRODUCT#123）
--   * 类型列 VARCHAR(20) 已容纳 'MESSAGE'，无列变更，仅注释扩值（落地并入 init.sql）：
-- ALTER TABLE content_report MODIFY COLUMN target_type VARCHAR(20) NOT NULL COMMENT '举报对象 POST/COMMENT/USER/MESSAGE';
