-- ============================================================================
-- 库存扣减压测库的表结构（应用启动时自动执行，幂等）
-- 与主项目 marketing 域的「库存配额」模型同构，只是去掉营销业务列，
-- 便于把压测结果直接映射回线上实现。
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 库存台账：三种策略共用的「真相表」
--   db        策略：直接 UPDATE 这一行的 surplus_qty（单行行锁即瓶颈）
--   redis_mq  策略：每条 MQ 消费时 UPDATE 一次（写放大 = 每条请求一次）
--   merge     策略：按时间格批量 UPDATE 一次（写放大 = 每秒一次）
-- 不变量：surplus_qty + waiting_qty + issued_qty == total_qty
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inventory_ledger
(
    sku_id      BIGINT      NOT NULL COMMENT '商品/SKU 主键（压测只打这一行，制造单热点）',
    total_qty   BIGINT      NOT NULL COMMENT '初始库存总量',
    surplus_qty BIGINT      NOT NULL COMMENT '台账侧仍可售余量（已减去已合并部分）',
    waiting_qty BIGINT      NOT NULL DEFAULT 0 COMMENT '合并策略专用：已预扣、未确认（在途量）',
    issued_qty  BIGINT      NOT NULL DEFAULT 0 COMMENT '已确认占用（= 已卖出）',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (sku_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='库存台账（单行热点）';

-- ----------------------------------------------------------------------------
-- 分桶台账：Redis 桶余量在 DB 侧的对账副本（reset 时写入，合并时递减）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inventory_bucket
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    sku_id      BIGINT      NOT NULL,
    bucket_id   INT         NOT NULL COMMENT '桶编号（0 起）',
    redis_qty   BIGINT      NOT NULL DEFAULT 0 COMMENT '该桶已合并后的余量副本（对账用，不是实时值）',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sku_bucket (sku_id, bucket_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='Redis 分桶余量对账副本';

-- ----------------------------------------------------------------------------
-- 订单流水：db / redis_mq 两种同步落库策略写这张表（每成功一单一行）
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inventory_order
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_no    VARCHAR(80) NOT NULL COMMENT '业务单号（压测用 UUID）',
    sku_id      BIGINT      NOT NULL,
    qty         INT         NOT NULL,
    strategy    VARCHAR(16) NOT NULL COMMENT 'db / redis_mq',
    create_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_sku (sku_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='扣减订单流水（同步落库策略）';

-- ----------------------------------------------------------------------------
-- 预扣明细：merge 策略的核心表
--   state=0 未合并（在途），state=1 已合并
--   create_time 用秒精度（DATETIME 无小数秒）——它就是「时间格」的键
--   uk_biz 保证同一单号只落一行，撞键 = 重放
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS inventory_deduct_detail
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    sku_id      BIGINT      NOT NULL,
    bucket_id   INT         NOT NULL,
    qty         INT         NOT NULL DEFAULT 1,
    biz_type    VARCHAR(32) NOT NULL DEFAULT 'DEDUCT' COMMENT '业务类型（合并时按 type 分段聚合的口径）',
    biz_no      VARCHAR(80) NOT NULL COMMENT '业务单号',
    state       TINYINT     NOT NULL DEFAULT 0 COMMENT '0-未合并 1-已合并',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '秒精度：时间格键',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz (biz_type, biz_no),
    KEY idx_cell (sku_id, bucket_id, state, create_time),
    KEY idx_state_create (state, create_time) COMMENT '兜底扫描：捞陈旧未合并格'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='库存预扣明细（时间格合并的事实源）';
