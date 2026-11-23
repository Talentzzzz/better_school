# 05 模块合并与领域包平级化

Type: task
Status: done
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

已完成（2026-11-23）。

- Commit hash：`8af97ca`（分支 `chat-ws-layering-rollout`，作者 TalentXD666 <a13683639985@163.com>）
- 验证：全 reactor `compile` + `test-compile` 绿（Z:/wt-draw-recon/tools Maven 3.9.9 + Temurin JDK21）；不跑测试、不冒烟
- 执行要点：
  - 自底向上 types→api→domain→case→infrastructure→trigger→app 逐层 `git mv` 并入，根 reactor 收敛为单组 bs-* 七模块；marketing 工程整体退役（docs → backend/docs/marketing、sql → backend/sql/marketing，供票 07 使用）
  - 包平级化：`com.betterschool.marketing.*` → `com.betterschool.*`，四域铺开 `domain/{inventory,points,raffle,rebate}`；infra 侧按 bs 包序压平（`adapter.{port,repository}.<域>` / `dao.<域>(.po)`，RaffleRepository 多层 raffle 子包一并压平）
  - 冲突归并：config 六对同职责归并（SecurityConfig 取白名单并集、VirtualThreadRocketMQListenerContainerRegistrar 取单体演进版）；Response 补 `fail(AppException)` 重载、ResponseCode 并入营销码（四个 400 段域别名保留）、OutboxStatus/消息体（LotteryDrawnMessage/PointsChangedMessage）收敛归 types；rocketmq 补丁类与 security 三件取单体份；GlobalExceptionHandler 并入 AppException 分支；PageResult 取单体超集（含 cursor）
  - 反向依赖清退：IUserQueryPort 生产用点核实为**仅两个探活 PingController**（case 层零引用，测试桩是死代码）——删 domain 两处接口 + infra 两处 Dubbo 实现 + 两测试桩；PingController 留一份（points 域），改进程内直调 `IUserRepository`；bs-infrastructure 未补 bs-api 依赖
  - outbox 基座切换：marketing 侧 OutboxDao/OutboxPO/OutboxMessageSender/OutboxMessageStore 落位 `infrastructure/{dao/}outbox`（含 delay 支持 + timeout-seconds 兜底 + `@XxlJob("outboxScan")`），bs 侧实现退役；OutboxMapper.xml 取 marketing 形状，命名空间平级化后与 bs 目标契合；bs 仓储调用面（`save(dest, payload)`）零改动，仅测试 import 改一行
  - merge 引擎两套不归并：`infrastructure.adapter.merge`（计数）与 `infrastructure.merge`（库存配额）并存；启动类全局启用 `FqcnAnnotationBeanNameGenerator`（@SpringBootApplication + @MapperScan）兜底跨包同名 bean
  - application.yml 合并：mapper-locations 改 `**`、redis timeout/connect-timeout 取营销超集、`outbox.scan.timeout-seconds: 60`、`merge.batch.*` 与 `lottery.bucket.degrade-cooldown` 并入、dubbo `consumer.check=false` 保留；producer group / xxl appname / 端口 / log-path 归一取单体值；test yml 同步营销侧测试键（send-message-timeout / outbox 超时 2s / 降级冷却 300ms）
- 实施偏差说明：拍板注 3「case 用例编排直接调用用户域能力」——勘察发现 case 层并无 IUserQueryPort 用点，实际清退面收窄为探活链路（PingController → IUserRepository），语义不变
- 遗留给票 06/07/08：Dubbo 双 Provider（bs UserQueryDubboService、营销 PointsExchangeDubboService）现状保留（无边未删）；营销 schema/xxl seed 合并归票 07；backend/CLAUDE.md 与 docs 口径同步归票 08
