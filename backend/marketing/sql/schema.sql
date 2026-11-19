-- bs_marketing 营销服务 schema：建表 DDL，不含 CREATE DATABASE / USE（库由调用方先选定）。
-- 开发库与测试库共用本文件——各自一份建库文件在 init-dev.sql / init-test.sql，
-- 表结构与根目录 sql.md 第 2 节逐表一致（唯一事实源，比对脚本 .scratch/project-rework/cmp_schema_marketing.py）：
-- 主键恒为无业务含义的物理自增 id，业务标识单独成列并配 uk；每表 create_time/update_time 收尾
-- （不带默认值，由应用写入），全库无 created_at/updated_at。
-- ⚠️ 唯一例外 strategy_weight 已在票 11 消除：该表按本库口径规范化并回写 sql.md（改名 draw_strategy_weight）。
-- 旧域表与 schema-legacy.sql 已随票 13 整体删除：旧域十张表退出，draw_* 新模型是唯一抽奖落位。
-- hot-row-merge-l2w256 票 09：本文件是**最终形态**——无两张库存桶表、明细表无 bucket_id / state
-- （原存量库过渡形状与切换脚本 sql/cutover/ 已随 uuid-biz-id 票 08 一并退役：不迁移存量、删库重建）。
-- uuid-biz-id 票 07：marketing 自有业务键（bucket_id / activity_id / strategy_id / award_id / record_id /
-- inv_id / 兑换 ref_id / 台账 biz_id）全改 VARCHAR(36)，由应用层统一 IdGenerator 在 insert 前发号一次写入，
-- 物理自增 id 保留为纯内部键；draw_strategy_award.inv_id 的「不限量」哨兵由数值 0 改字符串 '0'；
-- draw_strategy_weight.weights 随 award_id 变长加宽到 VARCHAR(512)。存量数据不迁移，删库重建。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS points_bucket (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    bucket_id   VARCHAR(36)      NOT NULL COMMENT '业务 ID（对外唯一标识）',
    user_id     VARCHAR(36)      NOT NULL COMMENT '用户 ID（跨 schema 只存 id，不 join 单体库）',
    amount      INT         NOT NULL COMMENT '入账积分数（恒正；方向由「在哪张表 + 哪个 reason_type」表达）',
    remain      INT         NOT NULL COMMENT '该桶剩余可用积分（消费按 FIFO 递减；过期桶不计入读数，由 expire_at 懒判）',
    expire_at   DATETIME    NOT NULL COMMENT '过期时间 = 入账时点 + points_validity_config.points_valid_days',
    reason_type VARCHAR(30) NOT NULL COMMENT '积分来源 LOTTERY_WIN(抽奖中奖) / REBATE(行为返利：签到/支付等，规则见 action_rebate_config，ref_id=发放幂等键)',
    ref_id      VARCHAR(36)      NOT NULL COMMENT '来源业务 ID（LOTTERY_WIN = 抽奖记录 id）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_id (bucket_id),
    UNIQUE KEY uk_user_reason_ref (user_id, reason_type, ref_id) COMMENT '入账幂等键：MQ 重投不重复加分',
    KEY idx_user_expire (user_id, expire_at) COMMENT '余额求和与 FIFO 消费（WHERE user_id=? AND remain>0 AND expire_at>NOW() ORDER BY expire_at, id）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分桶（入账流水 + 事实源）';

CREATE TABLE IF NOT EXISTS points_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36)      NOT NULL COMMENT '用户 ID',
    bucket_id   VARCHAR(36)      NOT NULL COMMENT '被扣的桶业务 ID（FIFO 顺序）',
    amount      INT         NOT NULL COMMENT '本次从该桶扣掉的积分数（恒正）',
    reason_type VARCHAR(30) NOT NULL COMMENT '消费原因 REDEEM_DRAW_COUNT(兑换抽奖次数) / REDEEM_PRIZE(兑换奖品)',
    ref_id      VARCHAR(36)      NOT NULL COMMENT '来源业务 ID（REDEEM_DRAW_COUNT = 兑换单 id，与 draw_count_bucket.ref_id 同值同事务；REDEEM_PRIZE = 抽奖记录 id，draw_record.record_id）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_reason_ref (bucket_id, reason_type, ref_id) COMMENT '同一桶同一笔业务不重复扣',
    KEY idx_user_ref (user_id, reason_type, ref_id) COMMENT '兑换边幂等预检',
    KEY idx_bucket (bucket_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分消费明细';

CREATE TABLE IF NOT EXISTS draw_count_bucket (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    bucket_id   VARCHAR(36)      NOT NULL COMMENT '业务 ID（对外唯一标识）',
    user_id     VARCHAR(36)      NOT NULL COMMENT '用户 ID',
    amount      INT         NOT NULL COMMENT '入账次数（恒正）',
    remain      INT         NOT NULL COMMENT '该桶剩余可用次数（消费按 FIFO 递减；过期桶不计入读数，由 expire_at 懒判）',
    expire_at   DATETIME    NOT NULL COMMENT '过期时间 = 入账时点 + draw_count_config.draw_count_valid_days',
    reason_type VARCHAR(20) NOT NULL COMMENT '次数来源 PURCHASE(积分购买) / REBATE(行为返利：签到/支付等，规则见 action_rebate_config，ref_id=发放幂等键)',
    ref_id      VARCHAR(36)      NOT NULL COMMENT '来源业务 ID（PURCHASE = 购买单 id，与 points_detail.ref_id 的 REDEEM_DRAW_COUNT 同值、同事务写入）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_id (bucket_id),
    UNIQUE KEY uk_user_reason_ref (user_id, reason_type, ref_id) COMMENT '入账幂等键：兑换重试不重复加次数',
    KEY idx_user_expire (user_id, expire_at) COMMENT '剩余次数求和与 FIFO 消费（WHERE user_id=? AND remain>0 AND expire_at>NOW() ORDER BY expire_at, id）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖次数桶（与积分桶同构）';

CREATE TABLE IF NOT EXISTS draw_count_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36)      NOT NULL COMMENT '用户 ID',
    bucket_id   VARCHAR(36)      NOT NULL COMMENT '被扣的桶业务 ID（FIFO 顺序）',
    ref_id      VARCHAR(36)      NOT NULL COMMENT '抽奖记录业务 ID（draw_record.record_id，消费幂等键；每次抽奖扣 1 次）',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_bucket_ref (bucket_id, ref_id) COMMENT '同一桶同一笔抽奖不重复扣',
    KEY idx_ref (ref_id) COMMENT '按抽奖记录反查扣次明细'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖次数消费明细';

CREATE TABLE IF NOT EXISTS draw_count_purchase_order (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    purchase_order_id VARCHAR(36)      NOT NULL COMMENT '购买单业务 ID（= hash(userId, token) 幂等键，贯穿 points_detail.ref_id 与 draw_count_bucket.ref_id）',
    user_id           VARCHAR(36)      NOT NULL COMMENT '用户 ID',
    quantity          INT          NOT NULL COMMENT '购买次数份数',
    points_cost       INT          NOT NULL COMMENT '本次扣减积分 = 份数 × 单价（Constants.DRAW_COUNT_PURCHASE_PRICE）',
    state             TINYINT      NOT NULL DEFAULT 0 COMMENT '0已扣分待入账(DEDUCTED) 1已完成(DONE) 2死信(DEAD,重试耗尽，人工介入)',
    retry_count       INT          NOT NULL DEFAULT 0 COMMENT '补偿重试次数（抢占时递增）',
    next_retry_at     DATETIME         NULL COMMENT '下次补偿重试时间（指数退避 1/2/4/8/16 分钟）；NULL = 立即可扫',
    finish_time       DATETIME         NULL COMMENT '入账完成时间',
    create_time       DATETIME     NOT NULL COMMENT '创建时间',
    update_time       DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_purchase_order_id (purchase_order_id) COMMENT '购买幂等键：同 token 重放恒同一单号，撞键即重放',
    KEY idx_state_next_retry (state, next_retry_at) COMMENT '补偿扫描（WHERE state=0 AND next_retry_at <= NOW()）',
    KEY idx_user_time (user_id, create_time) COMMENT '购买历史（读侧后置）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖次数购买单（订单兼 outbox：与扣积分同事务落库，单在即分已扣，入账幂等可重试收敛）';

CREATE TABLE IF NOT EXISTS draw_orphan_prereserve_order (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    record_id     VARCHAR(36)      NOT NULL COMMENT '孤儿抽奖记录业务 ID（= 库存预扣明细 biz_id = hash(userId, token) 幂等键，对账幂等键）',
    award_id      VARCHAR(36)      NOT NULL COMMENT '预扣奖品位 id 快照（种单时自孤儿预扣明细行）',
    user_id       VARCHAR(36)      NOT NULL COMMENT '用户 ID 快照（种单时自 draw_count_detail.idx_ref 反查；孤儿口径下扣次必然已发生）',
    quantity      BIGINT           NOT NULL COMMENT '预扣份数快照（种单时自孤儿预扣明细行）',
    state         TINYINT          NOT NULL DEFAULT 0 COMMENT '0待收敛(ORPHAN) 1已收敛(DONE) 2死信(DEAD,重试耗尽，人工介入)',
    retry_count   INT              NOT NULL DEFAULT 0 COMMENT '补偿重试次数（抢占时递增）',
    next_retry_at DATETIME         NULL COMMENT '下次补偿重试时间（指数退避 1/2/4/8/16 分钟）；NULL = 立即可扫',
    finish_time   DATETIME         NULL COMMENT '收敛完成时间',
    create_time   DATETIME         NOT NULL COMMENT '种单时间',
    update_time   DATETIME         NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_id (record_id) COMMENT '对账幂等键：一次孤儿预扣一张对账单，种单 INSERT IGNORE（DONE/DEAD 后扫描重种按幂等跳过）',
    KEY idx_state_next_retry (state, next_retry_at) COMMENT '补偿扫描（WHERE state=0 AND next_retry_at <= NOW()）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '孤儿预扣对账单（扫描「预扣在、抽奖记录不在」种单；重试状态机 + 死信，收敛两条路：可重放补落库 / 不可重放回补+退还）';

CREATE TABLE IF NOT EXISTS points_validity_config (
    id                BIGINT   NOT NULL AUTO_INCREMENT,
    points_valid_days INT      NOT NULL COMMENT '积分有效期天数（积分入账时算 expire_at）',
    create_time       DATETIME NOT NULL COMMENT '创建时间',
    update_time       DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分配置（单行）';

CREATE TABLE IF NOT EXISTS draw_count_config (
    id                    BIGINT   NOT NULL AUTO_INCREMENT,
    draw_count_valid_days INT      NOT NULL COMMENT '抽奖次数有效期天数（次数入账时算 expire_at）',
    create_time           DATETIME NOT NULL COMMENT '创建时间',
    update_time           DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖次数配置（单行）';

CREATE TABLE IF NOT EXISTS action_rebate_config (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    action_type  VARCHAR(16)  NOT NULL COMMENT '行为类型 sign(签到) / pay(支付)',
    rebate_type  VARCHAR(16)  NOT NULL COMMENT '返利类型 DRAW_COUNT(抽奖次数) / POINTS(用户活动积分)',
    rebate_name  VARCHAR(64)  NOT NULL COMMENT '返利名称',
    rebate_desc  VARCHAR(128) NULL COMMENT '返利描述',
    rebate_value INT          NOT NULL DEFAULT 0 COMMENT '返利值【抽奖次数、积分值】',
    config_id    BIGINT       NOT NULL COMMENT '有效期配置 id（rebate_type=POINTS → points_validity_config.id；DRAW_COUNT → draw_count_config.id）',
    state        VARCHAR(12)  NOT NULL COMMENT '状态 open(开启) / close(关闭)',
    create_time  DATETIME     NOT NULL COMMENT '创建时间',
    update_time  DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_action_rebate (action_type, rebate_type) COMMENT '同一行为同一返利类型一条规则；要多档返利时去掉此键',
    KEY idx_config (config_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '行为返利配置表（签到/支付等行为发放抽奖次数/积分，入账落对应桶表）';

CREATE TABLE IF NOT EXISTS draw_activity (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    activity_id     VARCHAR(36)      NOT NULL COMMENT '业务 ID（对外唯一标识）',
    activity_name   VARCHAR(64) NOT NULL COMMENT '活动名称',
    strategy_id     VARCHAR(36)      NOT NULL COMMENT '抽奖策略 ID',
    begin_date_time DATETIME    NOT NULL COMMENT '开始时间',
    end_date_time   DATETIME    NOT NULL COMMENT '结束时间',
    state           VARCHAR(16) NOT NULL DEFAULT 'create' COMMENT '活动状态 create/open/close',
    create_time     DATETIME    NOT NULL COMMENT '创建时间',
    update_time     DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_activity_id (activity_id),
    UNIQUE KEY uk_strategy_id (strategy_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖活动表';

CREATE TABLE IF NOT EXISTS draw_strategy (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    strategy_id   VARCHAR(36)       NOT NULL COMMENT '业务 ID（对外唯一标识）',
    strategy_desc VARCHAR(128) NULL COMMENT '抽奖策略描述',
    rule          VARCHAR(256) NULL COMMENT '规则模型（逗号分隔的规则链，如 rule_blacklist,rule_weight）',
    create_time   DATETIME     NOT NULL COMMENT '创建时间',
    update_time   DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_id (strategy_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖策略表';

CREATE TABLE IF NOT EXISTS draw_strategy_award (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    strategy_id VARCHAR(36)       NOT NULL COMMENT '所属抽奖策略 ID',
    award_id    VARCHAR(36)       NOT NULL COMMENT '奖品 ID（策略内唯一）',
    award_name  VARCHAR(128) NOT NULL COMMENT '奖品名称',
    award_desc  VARCHAR(255) NULL     COMMENT '奖品描述',
    award_type  VARCHAR(20)  NOT NULL COMMENT '奖品类型 VIRTUAL(虚拟资产：积分、抽奖次数) / PHYSICAL(实物资产) / THANKS(谢谢参与占位：award_rate 计入概率池、type=normal、inv_id=''0'' 无库存)',
    award_rate  DECIMAL(6,4) NOT NULL COMMENT '中奖概率（如 0.0500 = 5%；同一策略内各奖品位概率合计 = 1.0000）',
    level       INT          NOT NULL COMMENT '奖项层级（1 等奖 = 1，越大越低）',
    inv_id      VARCHAR(36)       NOT NULL DEFAULT '0' COMMENT '关联库存业务 ID（''0'' = 无库存/不限量）',
    type        VARCHAR(20)  NOT NULL DEFAULT 'normal' COMMENT '奖品位类型 normal(正常) / fallback(兜底) / blacklist(黑名单)',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '排序值',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_award (strategy_id, award_id),
    KEY idx_inv (inv_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖策略奖品配置表';

-- sql.md §2 该表原设计稿为旧格式（strategy_weight / unsigned 主键 / 时间戳带默认值），票 11 按本库口径
-- 规范化并回写 sql.md：表名并入 draw_* 家族，create_time/update_time 收尾不带默认值（应用写入）。
CREATE TABLE IF NOT EXISTS draw_strategy_weight (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    strategy_id VARCHAR(36)       NOT NULL COMMENT '所属抽奖策略 ID',
    count       INT          NOT NULL COMMENT '权重档的抽数阈值：用户在该策略下累计抽数 ≥ count 时此档生效，多档取满足阈值中 count 最大的一档（累计抽数 = draw_record 中该 user_id + strategy_id 的行数）',
    weights     VARCHAR(512) NOT NULL COMMENT '该档保底奖品池（逗号分隔的 award_id 列表，指向 draw_strategy_award.award_id；命中档位后在池内按 award_rate 归一化概率出奖）',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_strategy_id_count (strategy_id, count),
    KEY idx_strategy_id_count_weights (strategy_id, count, weights)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖策略权重表（按累计抽数分档的保底奖品池；原设计稿名 strategy_weight，落库时按本文件命名与主键口径规范化，见票 11）';

CREATE TABLE IF NOT EXISTS draw_record (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    record_id   VARCHAR(36)       NOT NULL COMMENT '业务 ID（对外唯一标识；points_bucket.ref_id 的 LOTTERY_WIN 指向它）',
    user_id     VARCHAR(36)       NOT NULL COMMENT '用户 ID',
    activity_id VARCHAR(36)       NOT NULL COMMENT '抽奖活动 ID',
    strategy_id VARCHAR(36)       NOT NULL COMMENT '抽奖策略 ID',
    award_id    VARCHAR(36)       NOT NULL COMMENT '命中的奖品 ID（策略内唯一）',
    award_name  VARCHAR(128) NOT NULL COMMENT '奖品名称快照（抽中时写入，此后不可变）',
    award_desc  VARCHAR(255) NULL COMMENT '奖品描述快照（抽中时写入，此后不可变）',
    award_type  VARCHAR(20)  NOT NULL COMMENT '奖品类型快照 VIRTUAL(虚拟资产) / PHYSICAL(实物资产) / THANKS(谢谢参与)',
    level       INT          NOT NULL COMMENT '奖项层级快照（1 等奖 = 1，越大越低）',
    inv_id      VARCHAR(36)       NULL COMMENT '发放来源库存/配额业务 ID（快照；无库存来源为 NULL）',
    state       VARCHAR(16)  NOT NULL DEFAULT '待兑换' COMMENT '奖品状态 待兑换/已兑换/已发货/已完成（THANKS 行落库即已完成，不走兑换/发货）',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_id (record_id),
    KEY idx_user_created (user_id, create_time) COMMENT '我的抽奖记录：create_time DESC 翻页',
    KEY idx_inv_id (inv_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖记录表';

CREATE TABLE IF NOT EXISTS draw_record_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    user_id     VARCHAR(36)      NOT NULL COMMENT '用户 ID（冗余自 draw_record，按人查询/鉴权用）',
    record_id   VARCHAR(36)      NOT NULL COMMENT '抽奖记录业务 ID（draw_record.record_id）',
    type        VARCHAR(20) NOT NULL COMMENT '动作类型 DRAW(抽中落库，对应 state=待兑换) / EXCHANGE(兑换，→已兑换) / SHIP(发货，→已发货) / COMPLETE(完成，→已完成)：落库与 state 每推进一步各落一条',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_record_type (record_id, type) COMMENT '同一记录同一动作只落一条：state 推进重试幂等',
    KEY idx_user_created (user_id, create_time) COMMENT '按人查动作历史'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '抽奖记录动作明细（只插入：创建 draw_record 时同事务落 DRAW，此后 state 每推进一步落一条）';

CREATE TABLE IF NOT EXISTS virtual_inventory_quota (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    inv_id      VARCHAR(36)       NOT NULL COMMENT '业务 ID（库存全局唯一标识）',
    name        VARCHAR(128) NOT NULL COMMENT '库存/权益名称，如「暑期签到积分池」',
    type        VARCHAR(20)  NOT NULL COMMENT '虚拟资产类型 POINTS(积分) / DRAW_COUNT(抽奖次数)',
    value       INT          NOT NULL DEFAULT 0 COMMENT '单次发放面额，如 50 积分、1 次抽奖机会',
    surplus_qty BIGINT       NOT NULL DEFAULT -1 COMMENT '剩余可发份数，-1=不限量',
    issued_qty  BIGINT       NOT NULL DEFAULT 0 COMMENT '累计已发放份数，用于对账/预算',
    state       VARCHAR(12)  NOT NULL DEFAULT 'open' COMMENT 'open/closed/exhausted',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_id (inv_id),
    KEY idx_state (state),
    CONSTRAINT chk_virtual_inventory_quota CHECK (surplus_qty >= -1 AND value >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '外界投放配额池（虚拟）：只被「分配」操作转出 surplus（award-inventory 两层模型，见 draw_strategy_award_inventory），抽奖不再直接扣减';

CREATE TABLE IF NOT EXISTS virtual_inventory_quota_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    inv_id      VARCHAR(36)      NOT NULL COMMENT '配额业务 ID',
    quantity    BIGINT      NOT NULL DEFAULT 0 COMMENT '本次预扣数量',
    biz_type    VARCHAR(32) NOT NULL COMMENT '业务类型：LOTTERY_DRAW（抽奖预扣）',
    biz_id      VARCHAR(36)      NOT NULL COMMENT '业务 ID（抽奖 = draw_record.record_id）',
    create_time DATETIME    NOT NULL COMMENT '插入时间（DB NOW()）',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz (biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '虚拟库存配额扣减明细（进程内合并的台账；撞 uk = 已落过，按成功回放）';

CREATE TABLE IF NOT EXISTS physical_marketing_quota (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    inv_id      VARCHAR(36)       NOT NULL COMMENT '业务 ID（配额全局唯一标识）',
    name        VARCHAR(128) NOT NULL COMMENT '配额名称，如「暑期实物营销配额」',
    decr        VARCHAR(255) NULL     COMMENT '配额说明',
    surplus_qty BIGINT       NOT NULL DEFAULT -1 COMMENT '剩余可发份数，-1=不限量',
    waiting_qty BIGINT       NOT NULL DEFAULT 0 COMMENT '预扣数量（预扣已提交、用户尚未兑换的部分）',
    issued_qty  BIGINT       NOT NULL DEFAULT 0 COMMENT '已占用数量（用户已兑换、正式出库的部分）',
    state       VARCHAR(12)  NOT NULL DEFAULT 'open' COMMENT 'open/closed/exhausted',
    create_time DATETIME     NOT NULL COMMENT '创建时间',
    update_time DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_inv_id (inv_id),
    KEY idx_state (state),
    CONSTRAINT chk_quota_surplus CHECK (surplus_qty >= -1)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '外界投放配额池（实物）：只被「分配」操作转出 surplus（award-inventory 两层模型，见 draw_strategy_award_inventory），抽奖不再直接扣减';

CREATE TABLE IF NOT EXISTS physical_marketing_quota_detail (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    inv_id      VARCHAR(36)      NOT NULL COMMENT '配额业务 ID',
    quantity    BIGINT      NOT NULL DEFAULT 0 COMMENT '本次流转数量',
    type        TINYINT     NOT NULL COMMENT '1-预扣结转（进 waiting）2-兑换确认（waiting → issued）',
    biz_type    VARCHAR(32) NOT NULL COMMENT '业务类型：LOTTERY_DRAW（预扣）/ LOTTERY_CLAIM（兑换确认）',
    biz_id      VARCHAR(36)      NOT NULL COMMENT '业务 ID（抽奖与兑换确认都用 draw_record.record_id）',
    create_time DATETIME    NOT NULL COMMENT '插入时间（DB NOW()）',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_type (type, biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '实物营销配额流转明细（预扣结转 / 兑换确认两类，进程内合并的台账；uk_biz_type 是唯一幂等裁决者）';

-- ─────────────────────────────── award-inventory 两层模型（抽奖只扣奖品库存账户） ───────────────────────────────
-- 两层模型：上层「配额池」（上面两张 *_quota 台账，外界投放）只被「分配」转移；
-- 下层「奖品库存账户」（本表，按奖品位 1:1）是抽奖预扣/确认唯一扣减的台账。

CREATE TABLE IF NOT EXISTS draw_strategy_award_inventory (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    award_id    VARCHAR(36)  NOT NULL COMMENT '奖品位 id（draw_strategy_award.award_id），1 奖品位 1 库存账户',
    type        VARCHAR(16)  NOT NULL COMMENT 'PHYSICAL 实物（两段式）/ VIRTUAL 虚拟（waiting 恒 0）',
    surplus_qty BIGINT       NOT NULL DEFAULT 0 COMMENT '可扣余量；-1 = 不限量（虚拟档沿用）',
    waiting_qty BIGINT       NOT NULL DEFAULT 0 COMMENT '已预占未确认（仅 PHYSICAL 使用，VIRTUAL 恒 0）',
    issued_qty  BIGINT       NOT NULL DEFAULT 0 COMMENT '已发放',
    value_qty   BIGINT       NULL COMMENT '单次发放面额（仅 VIRTUAL：POINTS 积分 / DRAW_COUNT 次数；实物 NULL）',
    state       TINYINT      NOT NULL DEFAULT 0 COMMENT '0 open / 1 exhausted / 2 closed（沿用原台账语义）',
    create_time DATETIME     NOT NULL COMMENT '由应用写入',
    update_time DATETIME     NOT NULL COMMENT '由应用写入',
    PRIMARY KEY (id),
    UNIQUE KEY uk_award_id (award_id),
    KEY idx_type (type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '奖品库存统一台账（抽奖直接扣减的账户，由配额池分配而来）';

CREATE TABLE IF NOT EXISTS draw_strategy_award_inventory_detail (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    award_id    VARCHAR(36)  NOT NULL COMMENT '奖品位 id（= 台账行），幂等键前缀',
    flow_type   TINYINT      NOT NULL COMMENT '0 分配转入 / 1 预扣（PHYSICAL 进 waiting，VIRTUAL 直接发放）/ 2 兑换确认（仅 PHYSICAL）',
    quantity    BIGINT       NOT NULL COMMENT '本行份数',
    biz_type    VARCHAR(30)  NOT NULL COMMENT '业务身份：LOTTERY_DRAW / LOTTERY_CLAIM / AWARD_ALLOCATE',
    biz_id      VARCHAR(36)  NOT NULL COMMENT '幂等锚点：抽奖记录 id / 抽奖记录 id / 分配单号',
    create_time DATETIME     NOT NULL COMMENT '插入时间（DB NOW()）',
    update_time DATETIME     NOT NULL COMMENT '更新时间（DB NOW()）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_award_biz (award_id, biz_type, biz_id),
    KEY idx_award (award_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '奖品库存统一流转明细（预扣/确认/分配转入；INSERT IGNORE + 唯一键裁决幂等）';

CREATE TABLE IF NOT EXISTS inventory_allocation_flow (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    pool_segment VARCHAR(16) NOT NULL COMMENT '来源池段：QUOTA(实物营销配额) / VIRTUAL(虚拟库存配额)',
    pool_inv_id  VARCHAR(36) NOT NULL COMMENT '来源池台账业务 ID（physical_marketing_quota / virtual_inventory_quota.inv_id）',
    award_id     VARCHAR(36) NOT NULL COMMENT '目标奖品位 id（draw_strategy_award_inventory.award_id）',
    quantity     BIGINT      NOT NULL COMMENT '本次分配份数',
    alloc_biz_id VARCHAR(36) NOT NULL COMMENT '分配单号（幂等锚点：撞 uk_alloc_biz = 本单已执行，按成功回放）',
    create_time  DATETIME    NOT NULL COMMENT '插入时间（DB NOW()）',
    update_time  DATETIME    NOT NULL COMMENT '更新时间（DB NOW()）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alloc_biz (alloc_biz_id),
    KEY idx_pool (pool_inv_id),
    KEY idx_award (award_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '配额池分配流水（池侧转出流水：池 surplus 转出量 = SUM(quantity)；不限量池 surplus 不减、流水照记）';

CREATE TABLE IF NOT EXISTS outbox_message (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    msg_id      VARCHAR(64) NOT NULL COMMENT '消息ID（幂等/去重）',
    destination VARCHAR(64) NOT NULL COMMENT '消息目的地（destination 语法：topic 或 topic:tag，由业务端自行解析）',
    payload     TEXT        NOT NULL COMMENT '消息内容 JSON',
    delay_level TINYINT     NOT NULL DEFAULT 0 COMMENT 'RocketMQ 延迟等级；0=不延迟，1=1 秒（约 1.0–1.1s）',
    delay_ms    INT         NULL COMMENT '毫秒级相对延迟（RocketMQ 定时消息）；NULL=走 delay_level',
    state       TINYINT     NOT NULL DEFAULT 0 COMMENT '0待发送 1已发送 2发送失败(重试耗尽)',
    retry_count INT         NOT NULL DEFAULT 0 COMMENT '已重试次数',
    create_time DATETIME    NOT NULL COMMENT '创建时间',
    update_time DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_state (state)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '本地消息表(outbox)';

-- ===========================================================================
-- 种子（幂等可重跑）：单行配置表必须有行，入账算 expire_at 才有依据。
-- 只放新模型表的种子；旧表种子随 schema-legacy.sql 在票 13 一起删除。
-- ===========================================================================

-- 积分有效期 365 天（自旧积分单行配置种子平移）
INSERT IGNORE INTO points_validity_config (id, points_valid_days, create_time, update_time)
VALUES (1, 365, NOW(), NOW());

-- 抽奖次数有效期 30 天（自旧域活动配置的同名种子平移）
INSERT IGNORE INTO draw_count_config (id, draw_count_valid_days, create_time, update_time)
VALUES (1, 30, NOW(), NOW());
