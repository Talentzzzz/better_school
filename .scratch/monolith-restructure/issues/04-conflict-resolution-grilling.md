# 04 冲突处置与执行顺序拍板

Type: grilling
Status: resolved
Blocked by: 01, 02

## Question

基于 [01 包名/类名冲突扫描](01-name-collision-scan.md) 与 [02 合库/配置冲突扫描](02-db-config-collision-scan.md) 的结果，与用户拍板：

1. 冲突类（全限定名/简单名/common 技术类）的重命名与归并处置方案；
2. 模块并入与领域包平级化的具体执行顺序是否需要按扫描发现调整；
3. 如发现反向依赖（marketing-* → bs-*）或隐藏耦合，决定在本 effort 内清退还是先记录不处理——扫描已发现 1 处：`marketing-infrastructure → bs-api`（PointsUserQueryPort / RaffleUserQueryPort 引用 IUserQueryService/UserBasicDTO）；
4. `outbox_message` 双表归并方案（同名表，marketing 侧多 delay_level/delay_ms 两列的超集）；
5. 反向依赖同源的模块依赖处置：marketing-infrastructure 并入 bs-infrastructure 后是否直接补 bs-api 依赖。

## Answer

（2026-11-23 拍板，依据 research/01-name-collision.md 与 research/02-db-config.md）

**1. 冲突类处置总原则：同职责一律归并，不留改名共存的两套。**

- config 六对（MybatisConfig/RocketMQConsumerConfig/SecurityConfig/TransactionConfig/VirtualThreadRocketMQListenerContainerRegistrar/XxlJobConfig）：逐对 diff 后合并为单类；SecurityConfig 涉及安全链路，diff 时重点审查差异。
- test probe 两对（VtConsumeThreadProbe/VtWebThreadProbe）：diff 后取一侧。
- `types.common.Constants`：marketing common.Constants 并入 bs 侧，不改名；域内 inventory/points/raffle 三份 Constants 保留。
- rocketmq 同包补丁类 `VirtualThreadConsumeMessageConcurrentlyService`：两侧今天即完全同名，diff 后合并为一份。
- types/api 层 DTO/枚举：`Response`/`PageResult`/`ResponseCode`/`OutboxStatus`/`LotteryDrawnMessage`/`PointsChangedMessage` 合并进 bs 现有类；注意 bs-case 已有 `usecase.marketing.{LotteryDrawnMessage,PointsChangedMessage}`，消息体收敛为一处定义（归 types）。
- infra 同职责：IOutboxDao、OutboxMessageSender、OutboxMessageStore、JwtAuthenticationFilter、JwtService、SecurityUtils、GlobalExceptionHandler 全部合并为一份。
- trigger：`LotteryDrawnConsumer` 两侧各一（同消费 bs-lottery-drawn），按同职责归并处理，最终去留与 MQ 链路进程内化（02 报告 §6.3）联动；`PingController` ×2 留一个。
- `IUserQueryPort` ×4：marketing 两份随反向依赖清退删除（见第 3 条）；bs 侧 chat/community 两份维持现状。
- **merge 引擎两套不归并**：功能不同（bs `merge.count.*` 计数合并 vs marketing `merge.batch.*` 库存/配额合并），各自保留、包归位（bs `infrastructure.adapter.merge` / marketing 平级化后 `infrastructure.merge`，互不撞 FQN）。
- **bean 命名兜底**：全局启用 `FqcnAnnotationBeanNameGenerator`（marketing 已验证），消解归并后剩余的跨包同名 bean 冲突（如 MergeConfig/MergeResult）。

**2. 执行顺序：自底向上不变（types→api→domain→case→infra→trigger→app），05 票按扫描发现修正执行说明。**

已修正 05 票，补充：① marketing-infra→api 特例边处置；② marketing-trigger 不依赖 marketing-infrastructure 的事实保持（不补边）；③ adapter 包序压平规则（`infrastructure.<域>.adapter.X` → `infrastructure.adapter.X.<域>`，dao 同理）；④ 启动类收敛为单一 @SpringBootApplication。

**3. 反向依赖（marketing-infrastructure → bs-api）：本 effort 清退，按"跨域调用归 case 层"原则处理。**

- 删除 domain 层两处 `IUserQueryPort`（points/raffle 的 port.marketing 子包）与 infra 层 `PointsUserQueryPort`/`RaffleUserQueryPort`（Dubbo IUserQueryService 引用）；case 用例编排直接调用用户域能力。
- bs-infrastructure **不补** bs-api 依赖（决策点 5 一并落定：否）。
- 本票拍板范围仅覆盖 marketing→bs 的跨域用户查询；marketing 内部跨域 port（raffle→inventory/rebate、points→rebate 等）本 effort 维持现状不阻塞并入，"跨域调用归 case"记为后续重构原则。

**4. outbox_message 归并：marketing 侧为基座。**

- DDL 取 marketing 超集形状（含 delay_level/delay_ms），07 票合库落地。
- 实现（OutboxDao/OutboxMessageSender/OutboxMessageStore）以 marketing 侧为基座（功能超集：delay 支持 + timeout-seconds 兜底），bs 侧实现退役，行为差异 diff 后吸收进基座；05/06 票落地。
- `outboxScan` 合并后只留一条（07 票处理 seed 与 postReviewTimeoutScan/pointsBucketCompaction 两处不一致）。
