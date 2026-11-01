# 07: marketing 翻转 + SnowflakeIdGenerator 退役

**What to build:** marketing 域自有业务 ID（bucket_id、activity_id、strategy_id、award 内序、record_id、inv_id、兑换单 id 等）变 UUID 字符串：积分桶 FIFO 扣减、抽奖链路、库存预扣/合并台账、兑换全链路 String 贯通。`SnowflakeIdGenerator` 调用点全部改走统一 IdGenerator 后退役删除——从此全仓库业务 ID 只有一种表示。跨 schema 只存 user_id 的列已在票 02 翻完。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] marketing 相关表 DDL 自有业务键改 `VARCHAR(36)`，schema 脚本重写、删库重建
- [ ] 全部发号点改走统一 IdGenerator，`SnowflakeIdGenerator` 类删除、无残留引用
- [ ] 积分扣减/抽奖/库存合并/兑换集成用例在 String ID 下全绿（FIFO 顺序、撞键幂等、台账对账语义不变）
- [ ] 定向测试通过；marketing 全量测试全绿
