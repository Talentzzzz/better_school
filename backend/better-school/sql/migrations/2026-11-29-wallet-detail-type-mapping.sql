-- 钱包流水类型一次性历史映射（platform-escrow / ADR-0019）
--
-- 背景：商品链路的资金口径从「买家冻结」改为「平台代收」——支付不再抬高买家 frozen_amount，
-- 而是买家出账（PRODUCT_PAY）+ 平台入账（PLATFORM_IN）；取消/超时由平台出账（PLATFORM_OUT）
-- 退回买家（PRODUCT_REFUND）。流水类型名同步换成双边语义。
--
-- 本脚本只处理**本次改造前（冻结口径）已经落库的历史行**：执行一次即与新类型对齐；
-- 之后可重复执行（无匹配行即无操作，幂等）。
-- 开发/测试库亦可直接 `scripts/test-env.sh reset-test-db` 重建，不需要跑本脚本。
--
-- 唯一键 uk_order_type (order_id, type, biz_round, user_id) 不受影响：
-- 同一 (订单, 轮次, 人) 上 PRODUCT_FROZEN 与 PRODUCT_UNFROZEN 各至多一行，
-- 分别映射为 PRODUCT_PAY / PRODUCT_REFUND，仍然一一对应、不会撞键。
-- 列、索引、约束均不改（只改数据）。

UPDATE wallet_detail SET type = 'PRODUCT_PAY'    WHERE type = 'PRODUCT_FROZEN';
UPDATE wallet_detail SET type = 'PRODUCT_REFUND' WHERE type = 'PRODUCT_UNFROZEN';
