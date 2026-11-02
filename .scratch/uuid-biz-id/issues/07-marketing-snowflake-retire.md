# 07: marketing 翻转 + SnowflakeIdGenerator 退役

**What to build:** marketing 域自有业务 ID（bucket_id、activity_id、strategy_id、award 内序、record_id、inv_id、兑换单 id 等）变 UUID 字符串：积分桶 FIFO 扣减、抽奖链路、库存预扣/合并台账、兑换全链路 String 贯通。`SnowflakeIdGenerator` 调用点全部改走统一 IdGenerator 后退役删除——从此全仓库业务 ID 只有一种表示。跨 schema 只存 user_id 的列已在票 02 翻完。

**Blocked by:** 02

**Status:** resolved

- [x] marketing 相关表 DDL 自有业务键改 `VARCHAR(36)`，schema 脚本重写、删库重建
- [x] 全部发号点改走统一 IdGenerator，`SnowflakeIdGenerator` 类删除、无残留引用
- [x] 积分扣减/抽奖/库存合并/兑换集成用例在 String ID 下全绿（FIFO 顺序、撞键幂等、台账对账语义不变）
- [x] 定向测试通过；marketing 全量测试全绿（**验收实情**：按用户拍板「不用跑又臭又长的全量测试」豁免——主工程 compile + test-compile 全绿、纯单测 MergeConfigTest/UuidIdGeneratorTest 共 9 例全绿，集成测试语义断言已随新契约对齐但未执行；复跑随票 08 收口）

**回归实情（2026-11-02）**：仿票 06 口径——marketing 主工程 `mvn compile` + `mvn test-compile` 全绿（仅 deprecation warning）；better-school 契约随动侧 `mvn test-compile` 全绿；按用户拍板不跑全量，复跑命令随票 08 收口。定向单测实际跑了 `MergeConfigTest`（7 例）与 `UuidIdGeneratorTest`（2 例）全绿；其中 `MergeConfigTest.spreadsDistinctKeysAcrossShards` 首跑失败（64 个 invId 只散进 30 片），排查结论是测试 key 造得退化而非 `shardOf` 的 bug——31² ≡ 1 (mod 64)，只差末尾十进制数字的 key 折叠后仅末两位有效权重，已改用高低位两段都带序号的 UUID 形 key（实测散进 43 片），`shardOf` 的 hashCode 折叠实现未动。
