# 05 模块合并与领域包平级化

Type: task
Status: open
Blocked by: 03, 04

## Question

执行（按 04 票拍板的顺序与处置方案）：

1. marketing 的 7 个 Maven 模块（marketing-types/api/domain/case/infrastructure/trigger/app）按层并入对应 `bs-*` 模块，根 reactor 收敛为单组分层模块 + 单个 Spring Boot 应用的 `bs-app`；
2. 包平级化：`com.betterschool.marketing.*` → `com.betterschool.*`，领域包铺开（`domain/raffle`、`domain/inventory`、`domain/points`、`domain/rebate` 等，无 marketing 命名空间层），case/trigger/types/api 同理；
3. 合并 application.yml（server、mybatis 扫描、rocketmq、xxl-job、redis 等；**数据库合并不在本票**，见 07）；
4. 处置 04 票拍板的全部命名冲突。

## Execution Notes（04 票拍板修正，2026-11-23）

1. **并入顺序**：自底向上 types→api→domain→case→infrastructure→trigger→app，每步保持 reactor 编译可达。
2. **依赖特例边**：
   - marketing-infrastructure 原依赖 marketing-api（bs-infrastructure 不依赖 bs-api）：**不补边**。随反向依赖清退（下条）后该依赖消失，若残留 api 类型引用，按"跨域调用归 case 层"上移。
   - marketing-trigger 不依赖 marketing-infrastructure：并入 bs-trigger 后**按现状不补依赖边**（QuotaAllocateController 等绕 infra 的既有事实保持）。
3. **反向依赖清退（bs-infrastructure 不补 bs-api 依赖）**：删除 domain 层两处 `IUserQueryPort`（points/raffle 的 port.marketing 子包）与 infra 层 `PointsUserQueryPort`/`RaffleUserQueryPort`（Dubbo IUserQueryService/UserBasicDTO 引用）；case 用例编排直接调用用户域能力。marketing 内部跨域 port（raffle→inventory/rebate、points→rebate）本票维持现状。
4. **adapter 包序压平**（bs 包序为准）：marketing 侧 `infrastructure.<域>.adapter.{port,repository}` → `infrastructure.adapter.{port,repository}.<域>`；`infrastructure.<域>.dao(.po)` → `infrastructure.dao.<域>(.po)`；`RaffleRepository` 多一层 `raffle` 子包一并压平。
5. **启动类收敛**：单一 @SpringBootApplication，合并两份 @ComponentScan/@MapperScan/@EnableXxx；全局启用 `FqcnAnnotationBeanNameGenerator` 作 bean 名兜底。
6. **outbox 实现以 marketing 侧为基座**：bs 侧 OutboxDao/OutboxMessageSender/OutboxMessageStore 退役（DDL 超集与 outboxScan 归一见 04 票第 4 条）。

完成标准：`Z:/wt-draw-recon/tools/` 的 Maven + JDK21 全 reactor `compile` + `test-compile` 绿；独立 commit；不跑测试。

## Answer

（待执行后记录 commit hash 与要点）
