-- ============================================================================
-- 库存扣减库表初始化（MySQL 9.x / 8.0.19+）
-- 挂载到 /docker-entrypoint-initdb.d/ 时只在数据目录为空的首次启动执行；
-- 想重跑初始化：docker compose down -v（把 mysql-data 卷一起删掉）。
-- ============================================================================
CREATE DATABASE IF NOT EXISTS inventory_demo
    DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE inventory_demo;

-- 库存表：热点行就在这张表（同一 sku_id 每秒上万次 UPDATE 就是被测对象）
CREATE TABLE IF NOT EXISTS stock (
    sku_id      BIGINT      NOT NULL COMMENT 'SKU 编号',
    qty         INT         NOT NULL COMMENT '剩余库存',
    update_time DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (sku_id)
) ENGINE = InnoDB COMMENT = '库存表';

-- 扣减流水表：biz_no 唯一索引 = 幂等键（正确性基线，也是唯一的幂等裁决者）
-- 注意：这张表是写热点（每笔扣减都插一行），它的二级索引数量直接影响 TPS，
-- 压测期间不要临时加索引，否则前后两轮不可比。
CREATE TABLE IF NOT EXISTS stock_detail (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    biz_no     VARCHAR(64) NOT NULL COMMENT '业务单号（幂等键）',
    sku_id     BIGINT      NOT NULL COMMENT 'SKU 编号',
    qty        INT         NOT NULL COMMENT '扣减数量',
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '1=扣减成功',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_no (biz_no),
    KEY idx_sku_id (sku_id)
) ENGINE = InnoDB COMMENT = '扣减流水表';

-- 种子数据
--   1001：单 SKU 吞吐压测的热点（reset 会把它改回 10 万）
--   1002：单 SKU 抢扣正确性（100 件，200 并发抢）
--   1003：中途售罄用例
--   9999：启动预热专用（应用启动时 reset 再扣 1 件，反复重启也不会扣空）
INSERT IGNORE INTO stock (sku_id, qty) VALUES (1001, 100000);
INSERT IGNORE INTO stock (sku_id, qty) VALUES (1002, 100);
INSERT IGNORE INTO stock (sku_id, qty) VALUES (1003, 5000);
INSERT IGNORE INTO stock (sku_id, qty) VALUES (9999, 200000);
