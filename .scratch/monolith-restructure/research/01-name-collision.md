# 01 · marketing 并入 better-school 名称冲突预扫报告

> 扫描范围：`Z:/better_school/backend/{marketing,better-school}` 下全部 `src/main/java` 与 `src/test/java`，忽略 `target/`。以工作区当前未提交内容为准（2026-11-23 扫描）。
>
> **平级化规则**：去掉 `com.betterschool.marketing` 中间层，即 `com.betterschool.marketing.X.Y` → `com.betterschool.X.Y`；模块按后缀并入（marketing-types→bs-types、marketing-api→bs-api、marketing-domain→bs-domain、marketing-case→bs-case、marketing-infrastructure→bs-infrastructure、marketing-trigger→bs-trigger、marketing-app→bs-app）。
>
> 扫描基数：marketing 264 个类（main 224 / test 40），better-school 516 个类（main 437 / test 79）。本报告只读，不改任何业务代码。

## 结论摘要

| 项 | 数量 |
|---|---|
| 全限定名冲突（平级化后同包同名同类） | **10 处** |
| 跨服务简单名冲突（不同包、类名相同） | **29 个类名**（其中 10 个与全限定名冲突同源，净新增 19 处） |
| marketing 内部重名（合并后 bean 名隐患） | 3 组（Constants ×4、IUserQueryPort ×2、PingController ×2） |
| marketing→bs 反向依赖 | **有，1 处**：marketing-infrastructure → bs-api |
| domain/infra 同名双层 | 两侧均存在，但**包结构不一致**（见第 4 部分） |

---

## 1. 全限定名冲突（10 处）

平级化后 marketing 类与 bs 现有类落到**同一目标包、同名同类**，Java 编译期直接冲突。

| # | marketing 原全限定名 | 目标全限定名 | 与 bs 现有类撞名 | 所在模块对 |
|---|---|---|---|---|
| 1 | `com.betterschool.marketing.config.MybatisConfig` | `com.betterschool.config.MybatisConfig` | 是（bs-app） | marketing-app ↔ bs-app |
| 2 | `com.betterschool.marketing.config.RocketMQConsumerConfig` | `com.betterschool.config.RocketMQConsumerConfig` | 是（bs-app） | marketing-app ↔ bs-app |
| 3 | `com.betterschool.marketing.config.SecurityConfig` | `com.betterschool.config.SecurityConfig` | 是（bs-app） | marketing-app ↔ bs-app |
| 4 | `com.betterschool.marketing.config.TransactionConfig` | `com.betterschool.config.TransactionConfig` | 是（bs-app） | marketing-app ↔ bs-app |
| 5 | `com.betterschool.marketing.config.VirtualThreadRocketMQListenerContainerRegistrar` | `com.betterschool.config.VirtualThreadRocketMQListenerContainerRegistrar` | 是（bs-app） | marketing-app ↔ bs-app |
| 6 | `com.betterschool.marketing.config.XxlJobConfig` | `com.betterschool.config.XxlJobConfig` | 是（bs-app） | marketing-app ↔ bs-app |
| 7 | `com.betterschool.marketing.integration.support.VtConsumeThreadProbe` | `com.betterschool.integration.support.VtConsumeThreadProbe` | 是（bs-app test） | marketing-app(test) ↔ bs-app(test) |
| 8 | `com.betterschool.marketing.integration.support.VtWebThreadProbe` | `com.betterschool.integration.support.VtWebThreadProbe` | 是（bs-app test） | marketing-app(test) ↔ bs-app(test) |
| 9 | `com.betterschool.marketing.types.common.Constants` | `com.betterschool.types.common.Constants` | 是（bs-types） | marketing-types ↔ bs-types |
| 10 | `org.apache.rocketmq.client.impl.consumer.VirtualThreadConsumeMessageConcurrentlyService` | `org.apache.rocketmq.client.impl.consumer.VirtualThreadConsumeMessageConcurrentlyService` | 是（bs-trigger）——**两侧今天就同名**，是对 rocketmq jar 的同包补丁类 | marketing-trigger ↔ bs-trigger |

**处置建议（简述，决策留给 grilling 票）：**
- #1–#6：六对 config 均为同职责 Spring 配置类，不可能简单重命名共存（@Configuration bean 冲突）。逐对 diff 后**合并为单类**或改名（如 `XxlJobConfig` vs `MarketingXxlJobConfig`）；SecurityConfig 涉及安全链路，需重点审查。
- #7–#8：test probe 类，两侧可能基本相同，diff 后取一侧或改名区分。
- #9：bs 只有一个 `types.common.Constants`；marketing 侧四个域 Constants（common/inventory/points/raffle）平级化后仅 `common` 撞。建议把 marketing 的 common.Constants 并入 bs 的或改名（如 `MarketingCommonConstants`）；另三个域 Constants 不撞 FQN，可保留域分包。
- #10：同包补丁类，先 diff 两份实现；若逻辑不同需合并为一份（同模块同包不允许重复类）。

---

## 2. 简单名冲突（29 个类名）

两侧**不同包但类名相同**的类。合并后同一模块内：跨包 import 同名类需全限定名书写；@Component/@Service 等 Spring bean 默认名（simpleName 首字母小写）冲突。

> 注意：marketing-app 已使用 `FqcnAnnotationBeanNameGenerator`（FQCN bean 命名），bs-app 未使用——合并后 bean 命名策略需统一，否则 #A 组即使不撞 import 也会撞 bean 名。

### 2.1 与第 1 部分同源（10 个，不再重复展开）
`MybatisConfig`、`RocketMQConsumerConfig`、`SecurityConfig`、`TransactionConfig`、`VirtualThreadRocketMQListenerContainerRegistrar`、`XxlJobConfig`、`VtConsumeThreadProbe`、`VtWebThreadProbe`、`Constants`、`VirtualThreadConsumeMessageConcurrentlyService`。

### 2.2 净新增跨服务简单名冲突（19 个）

| # | 类名 | marketing 侧原全限定名（模块） | bs 侧全限定名（模块） | 冲突面 |
|---|---|---|---|---|
| 1 | `ConsumeHandlerVirtualThreadIntegrationTest` | `com.betterschool.marketing.integration.common.ConsumeHandlerVirtualThreadIntegrationTest`（marketing-app test） | `com.betterschool.integration.consume.ConsumeHandlerVirtualThreadIntegrationTest`（bs-app test） | JUnit 同名类，IDE/报告展示混淆 |
| 2 | `WebHandlerVirtualThreadIntegrationTest` | `com.betterschool.marketing.integration.common.WebHandlerVirtualThreadIntegrationTest`（marketing-app test） | `com.betterschool.integration.web.WebHandlerVirtualThreadIntegrationTest`（bs-app test） | 同上 |
| 3 | `GlobalExceptionHandler` | `com.betterschool.marketing.trigger.common.http.GlobalExceptionHandler`（marketing-trigger） | `com.betterschool.trigger.http.GlobalExceptionHandler`（bs-trigger） | @RestControllerAdvice bean 冲突 |
| 4 | `IOutboxDao` | `com.betterschool.marketing.infrastructure.common.dao.outbox.IOutboxDao`（marketing-infrastructure） | `com.betterschool.infrastructure.dao.outbox.IOutboxDao`（bs-infrastructure） | 同职责 DAO 接口，建议合并 |
| 5 | `IUserQueryPort` | `com.betterschool.marketing.domain.points.adapter.port.marketing.IUserQueryPort`、`com.betterschool.marketing.domain.raffle.adapter.port.marketing.IUserQueryPort`（marketing-domain，**内部已重名 2 个**） | `com.betterschool.domain.chat.adapter.port.IUserQueryPort`、`com.betterschool.domain.community.adapter.port.IUserQueryPort`（bs-domain，**也已重名 2 个**） | 4 个同名接口；平级化后 marketing 两个仍不同包（points/raffle 分开），但 bean 名与可读性冲突 |
| 6 | `JwtAuthenticationFilter` | `com.betterschool.marketing.infrastructure.common.security.JwtAuthenticationFilter`（marketing-infrastructure） | `com.betterschool.infrastructure.security.JwtAuthenticationFilter`（bs-infrastructure） | Spring Security 过滤器 bean 冲突，安全链路必须合并为一 |
| 7 | `JwtService` | `com.betterschool.marketing.infrastructure.common.security.JwtService`（marketing-infrastructure） | `com.betterschool.infrastructure.security.JwtService`（bs-infrastructure） | 同上，JWT 签发/校验逻辑须合并或确认差异 |
| 8 | `LotteryDrawnConsumer` | `com.betterschool.marketing.trigger.points.listener.LotteryDrawnConsumer`（marketing-trigger） | `com.betterschool.trigger.listener.LotteryDrawnConsumer`（bs-trigger） | MQ 消费者 bean 冲突；且可能消费同一 topic，需审查 |
| 9 | `LotteryDrawnMessage` | `com.betterschool.marketing.types.common.LotteryDrawnMessage`（marketing-types） | `com.betterschool.usecase.marketing.LotteryDrawnMessage`（bs-case） | MQ 消息体两侧各一份，反序列化字段须对齐，建议合并到 types |
| 10 | `MergeConfig` | `com.betterschool.marketing.infrastructure.merge.MergeConfig`（marketing-infrastructure） | `com.betterschool.infrastructure.adapter.merge.MergeConfig`（bs-infrastructure） | 同名不同包；bs 侧已有 merge 基建（15 个类），疑似两套合并引擎 |
| 11 | `MergeConfigTest` | `com.betterschool.marketing.infrastructure.merge.MergeConfigTest`（marketing-infrastructure test） | `com.betterschool.infrastructure.adapter.merge.MergeConfigTest`（bs-infrastructure test） | 同上测试 |
| 12 | `MergeResult` | `com.betterschool.marketing.infrastructure.merge.MergeResult`（marketing-infrastructure） | `com.betterschool.infrastructure.adapter.merge.MergeResult`（bs-infrastructure） | 同 #10 |
| 13 | `OutboxMessageSender` | `com.betterschool.marketing.infrastructure.common.outbox.OutboxMessageSender`（marketing-infrastructure） | `com.betterschool.infrastructure.mq.OutboxMessageSender`（bs-infrastructure） | 同职责 outbox 发送器，建议合并 |
| 14 | `OutboxMessageStore` | `com.betterschool.marketing.infrastructure.common.outbox.OutboxMessageStore`（marketing-infrastructure） | `com.betterschool.infrastructure.outbox.OutboxMessageStore`（bs-infrastructure） | 同职责 outbox 存储，建议合并 |
| 15 | `OutboxStatus` | `com.betterschool.marketing.types.common.OutboxStatus`（marketing-types） | `com.betterschool.types.enums.OutboxStatus`（bs-types） | 枚举两份，合并进 bs-types |
| 16 | `PageResult` | `com.betterschool.marketing.api.common.response.PageResult`（marketing-api） | `com.betterschool.api.response.PageResult`（bs-api） | API 分页包装类两份，合并进 bs-api |
| 17 | `PointsChangedMessage` | `com.betterschool.marketing.domain.points.model.valobj.PointsChangedMessage`（marketing-domain） | `com.betterschool.usecase.marketing.PointsChangedMessage`（bs-case） | 消息体两份；注意 bs-case 已有 marketing 相关用例类 |
| 18 | `Response` | `com.betterschool.marketing.api.common.response.Response`（marketing-api） | `com.betterschool.api.response.Response`（bs-api） | API 统一响应包装，合并进 bs-api |
| 19 | `ResponseCode` | `com.betterschool.marketing.types.common.ResponseCode`（marketing-types） | `com.betterschool.types.enums.ResponseCode`（bs-types） | 响应码枚举两份，需合并去重 |
| 20 | `SecurityUtils` | `com.betterschool.marketing.trigger.common.http.security.SecurityUtils`（marketing-trigger） | `com.betterschool.trigger.http.security.SecurityUtils`（bs-trigger） | 鉴权工具类，建议合并 |

### 2.3 marketing 内部重名（平级化后不撞 FQN，但撞 bean 名/混淆）

| 类名 | 出现位置 |
|---|---|
| `Constants`（×4） | `marketing.types.{common,inventory,points,raffle}.Constants`（平级化后仅 common 撞 bs，见第 1 部分 #9；域内 4 份保留可读性尚可，但 bean/静态引用需注意） |
| `IUserQueryPort`（×2） | `marketing.domain.points.adapter.port.marketing.IUserQueryPort` 与 `marketing.domain.raffle.adapter.port.marketing.IUserQueryPort`（同一接口语义出现两份） |
| `PingController`（×2） | `marketing.trigger.points.http.PingController` 与 `marketing.trigger.raffle.http.PingController`（health-check 探针，合并后建议留一个） |

**处置建议（简述）：**
- types/api 层（#9、#15–#19、LotteryDrawnMessage/PointsChangedMessage）：本质是"同一 DTO/枚举两份拷贝"，优先 diff 字段后**合并进 bs 现有类**，引用改 import。
- infra 层（#4、#6、#7、#10、#12–#14）：outbox/security/merge 三组均为"两套同职责基建"，合并是主要方向；security 与 rocketmq 补丁类不允许两份共存。
- trigger 层（#3、#8、#20）：bean 冲突必须消解——合并或按域拆包后重命名。
- 若短期不想改类名，可全局启用 FQCN bean 命名（marketing 侧已验证可行），但这只是止血，import 撞名与语义重复仍需 grilling 决策。

---

## 3. pom 依赖图

### 3.1 marketing-* 内部依赖（箭头 = 依赖）

```
marketing-types      ← （无内部依赖，最底层）
marketing-api      → marketing-types
marketing-domain   → marketing-types
marketing-case     → marketing-domain
marketing-infrastructure → marketing-domain, marketing-api, 【bs-api ← 反向依赖！】
marketing-trigger  → marketing-api, marketing-domain, marketing-case
marketing-app      → marketing-trigger, marketing-infrastructure, marketing-case, marketing-api, marketing-domain, marketing-types
```

与 bs 侧对照差异：
- marketing-infrastructure 额外依赖 **marketing-api**（bs-infrastructure 不依赖 bs-api）。
- marketing-trigger **不依赖** marketing-infrastructure（bs-trigger 依赖 bs-infrastructure）。
- 两例都说明 marketing 依赖方向不完全同构，合并时不能机械按模块后缀映射。

### 3.2 反向依赖（marketing-* → bs-*）：**有，1 处**

- `marketing-infrastructure/pom.xml` 依赖 `com.betterschool:bs-api`。
- 实际引用点（共 2 个类、4 行 import）：
  - `marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/points/adapter/port/marketing/PointsUserQueryPort.java`：`com.betterschool.api.IUserQueryService`、`com.betterschool.api.dto.UserBasicDTO`
  - `marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/raffle/adapter/port/marketing/RaffleUserQueryPort.java`：同上两个类
- 含义：marketing 通过 Dubbo 直调 bs 的用户查询 API。合并后 bs-infrastructure 需新增对 bs-api 的依赖（或这两处 port 改走 bs-trigger 侧既有机制），这是唯一需要**给 bs 模块加边**的地方。
- marketing-* 除此之外无其他 bs-* 依赖；bs-* 也无任何 marketing-* 依赖。

### 3.3 bs-* 内部依赖

```
bs-types            ← （无内部依赖）
bs-api            → bs-types
bs-domain         → bs-types
bs-case           → bs-domain
bs-infrastructure → bs-domain
bs-trigger        → bs-api, bs-domain, bs-case, bs-infrastructure
bs-app            → bs-trigger, bs-infrastructure, bs-case, bs-api, bs-domain, bs-types
```

---

## 4. domain 层与 infra 层同名双层清单

bs 侧结构：`domain.<域>.adapter.{port, repository}` 定义接口，`infrastructure.adapter.{port, repository}.<域>` 放实现，`infrastructure.dao.<域>(.po)` 放 DAO/PO。

### 4.1 bs-domain 的 adapter 域清单

| 域 | port 接口包 | repository 接口包 |
|---|---|---|
| chat | `com.betterschool.domain.chat.adapter.port`（2 类） | `...chat.adapter.repository` |
| community | `...community.adapter.port`（1 类） | `...community.adapter.repository` |
| credit | —（无 port） | `...credit.adapter.repository` |
| errand | `...errand.adapter.port`（1 类） | `...errand.adapter.repository` |
| file | `...file.adapter.port`（2 类） | — |
| notification | `...notification.adapter.port`（1 类） | `...notification.adapter.repository` |
| search | `...search.adapter.port`（1 类） | `...search.adapter.repository` |
| timeout | `...timeout.adapter.port.callback` | `...timeout.adapter.repository` |
| trade | — | `...trade.adapter.repository` |
| user | — | `...user.adapter.repository` |
| wallet | — | `...wallet.adapter.repository` |

### 4.2 bs-infrastructure 的 adapter/dao 域清单

- `infrastructure.adapter.port.<域>`：chat(2)、community(1)、errand(1)、file(2)、notification(1)、search(1) —— 与 domain port 一一对应。
- `infrastructure.adapter.repository.<域>`：chat、community、credit、errand、notification、search、timeout、trade、user、wallet（各 1 类）。
- `infrastructure.dao.<域>(.po)`：chat(7+10po)、comment(5+3po)、credit(2+2po)、errand(5+3po)、notification(3+1po)、outbox(1+1po)、post(6+3po)、product(5+2po)、productinventory(2+2po)、productorder(2+2po)、timeout(1+1po)、user(1+2po)、wallet(2+2po)。
- 其他：`adapter.cache.post`、`adapter.merge`（15 类，合并引擎）、`adapter.search`、`mq`、`outbox`、`security`（2 类）、`sharding`、`ws`(+wsframe)。

### 4.3 marketing-domain 的 adapter 包结构（平级化前）

| 域 | 结构 |
|---|---|
| inventory | `domain.inventory.adapter.repository.IInventoryRepository`（仅 repository，无 port） |
| points | `domain.points.adapter.port.marketing.IUserQueryPort`、`domain.points.adapter.port.rebate.IPointsRebatePort`、`domain.points.adapter.repository.IPointsRepository` |
| raffle | `domain.raffle.adapter.port.inventory.{IInventoryDeductPort, IQuotaConfirmPort}`、`domain.raffle.adapter.port.marketing.IUserQueryPort`、`domain.raffle.adapter.port.rebate.IDrawCountRebatePort`、`domain.raffle.adapter.repository.IRaffleRepository` |
| rebate | `domain.rebate.adapter.repository.IRebateRepository`（仅 repository，无 port） |

marketing 实现侧（marketing-infrastructure）把实现放在 **`infrastructure.<域>.adapter.port.<子域>`** 与 `infrastructure.<域>.adapter.repository`，与 bs 的 `infrastructure.adapter.<port|repository>.<域>` **包序相反**。

**结构性差异与建议：**
1. 平级化后 marketing 域（inventory/points/raffle/rebate）的 domain 接口包 `com.betterschool.domain.<域>.adapter.{port,repository}` 与 bs 模式一致，可直接落位。
2. marketing port 子包多了第三层（`port.marketing` / `port.inventory` / `port.rebate`），bs 无此层；压平方案（去第三层 or 保留子包）需统一决策，且 `IUserQueryPort` 两份重复正卡在这一层。
3. marketing infra 实现包序与 bs 相反，"压平并入统一 adapter/{port,repository} + dao" 时要把 `infrastructure.<域>.adapter.X` 重排为 `infrastructure.adapter.X.<域>`、`infrastructure.<域>.dao` 重排为 `infrastructure.dao.<域>`，涉及第 5 部分全部类。
4. bs 侧 `adapter.merge`（15 类）与 marketing 侧 `infrastructure.merge`（6 类）+ marketing-app `MergeEngineConfig` 疑似两套合并引擎，压平时必须先做能力合并决策（见第 2 部分 #10/#12）。

---

## 5. marketing-infrastructure 按域子树完整类清单（压平输入）

> 每项：类名 + 当前全限定名。平级化时统一去掉 `.marketing` 段，并按第 4 部分结论重排包序。

### 5.1 inventory 子树（15 类）

adapter/repository：
- `InventoryQuotaFlushService` — `com.betterschool.marketing.infrastructure.inventory.adapter.repository.InventoryQuotaFlushService`
- `InventoryQuotaMergeWriter` — `com.betterschool.marketing.infrastructure.inventory.adapter.repository.InventoryQuotaMergeWriter`
- `InventoryRepository` — `com.betterschool.marketing.infrastructure.inventory.adapter.repository.InventoryRepository`

dao：
- `IAwardInventoryDao` — `com.betterschool.marketing.infrastructure.inventory.dao.IAwardInventoryDao`
- `IAwardInventoryDetailDao` — `com.betterschool.marketing.infrastructure.inventory.dao.IAwardInventoryDetailDao`
- `IPhysicalQuotaDao` — `com.betterschool.marketing.infrastructure.inventory.dao.IPhysicalQuotaDao`
- `IQuotaAllocateFlowDao` — `com.betterschool.marketing.infrastructure.inventory.dao.IQuotaAllocateFlowDao`
- `IVirtualInventoryQuotaDao` — `com.betterschool.marketing.infrastructure.inventory.dao.IVirtualInventoryQuotaDao`

dao/po：
- `AwardInventoryDetailPO` — `...inventory.dao.po.AwardInventoryDetailPO`
- `AwardInventoryPO` — `...inventory.dao.po.AwardInventoryPO`
- `PhysicalMarketingQuotaPO` — `...inventory.dao.po.PhysicalMarketingQuotaPO`
- `QuotaAllocateFlowPO` — `...inventory.dao.po.QuotaAllocateFlowPO`
- `VirtualInventoryQuotaPO` — `...inventory.dao.po.VirtualInventoryQuotaPO`

### 5.2 points 子树（11 类）

adapter/port：
- `PointsUserQueryPort` — `com.betterschool.marketing.infrastructure.points.adapter.port.marketing.PointsUserQueryPort`
- `PointsRebatePort` — `com.betterschool.marketing.infrastructure.points.adapter.port.rebate.PointsRebatePort`

adapter/repository：
- `PointsRepository` — `com.betterschool.marketing.infrastructure.points.adapter.repository.PointsRepository`

dao：
- `IPointsDao` — `...points.dao.IPointsDao`
- `IPointsDetailDao` — `...points.dao.IPointsDetailDao`
- `IPointsValidityConfigDao` — `...points.dao.IPointsValidityConfigDao`

dao/po：
- `PointsBucketPO` — `...points.dao.po.PointsBucketPO`
- `PointsDetailPO` — `...points.dao.po.PointsDetailPO`
- `PointsMergeGroupPO` — `...points.dao.po.PointsMergeGroupPO`
- `PointsTransactionPO` — `...points.dao.po.PointsTransactionPO`
- `PointsValidityConfigPO` — `...points.dao.po.PointsValidityConfigPO`

### 5.3 raffle 子树（29 类）

adapter/port：
- `InventoryDeductPort` — `com.betterschool.marketing.infrastructure.raffle.adapter.port.inventory.InventoryDeductPort`
- `QuotaConfirmPort` — `...raffle.adapter.port.inventory.QuotaConfirmPort`
- `RaffleUserQueryPort` — `...raffle.adapter.port.marketing.RaffleUserQueryPort`
- `DrawCountRebatePort` — `...raffle.adapter.port.rebate.DrawCountRebatePort`

adapter/repository：
- `RaffleRepository` — `com.betterschool.marketing.infrastructure.raffle.adapter.repository.raffle.RaffleRepository`（注意多一层 `raffle` 子包）

dao：
- `IDrawActivityDao` — `...raffle.dao.IDrawActivityDao`
- `IDrawCountConfigDao` — `...raffle.dao.IDrawCountConfigDao`
- `IDrawCountDao` — `...raffle.dao.IDrawCountDao`
- `IDrawCountDetailDao` — `...raffle.dao.IDrawCountDetailDao`
- `IDrawCountPurchaseOrderDao` — `...raffle.dao.IDrawCountPurchaseOrderDao`
- `IDrawOrphanPrereserveOrderDao` — `...raffle.dao.IDrawOrphanPrereserveOrderDao`
- `IDrawRecordDetailDao` — `...raffle.dao.IDrawRecordDetailDao`
- `IDrawStrategyAwardDao` — `...raffle.dao.IDrawStrategyAwardDao`
- `IDrawStrategyDao` — `...raffle.dao.IDrawStrategyDao`
- `IDrawStrategyWeightDao` — `...raffle.dao.IDrawStrategyWeightDao`
- `IRaffleDao` — `...raffle.dao.IRaffleDao`

dao/po：
- `DrawActivityPO` — `...raffle.dao.po.DrawActivityPO`
- `DrawCountBucketPO` — `...raffle.dao.po.DrawCountBucketPO`
- `DrawCountConfigPO` — `...raffle.dao.po.DrawCountConfigPO`
- `DrawCountDetailPO` — `...raffle.dao.po.DrawCountDetailPO`
- `DrawCountPurchaseOrderPO` — `...raffle.dao.po.DrawCountPurchaseOrderPO`
- `DrawOrphanPrereserveOrderPO` — `...raffle.dao.po.DrawOrphanPrereserveOrderPO`
- `DrawRecordDetailPO` — `...raffle.dao.po.DrawRecordDetailPO`
- `DrawRecordPO` — `...raffle.dao.po.DrawRecordPO`
- `DrawRecordViewPO` — `...raffle.dao.po.DrawRecordViewPO`
- `DrawStrategyAwardPO` — `...raffle.dao.po.DrawStrategyAwardPO`
- `DrawStrategyPO` — `...raffle.dao.po.DrawStrategyPO`
- `DrawStrategyWeightPO` — `...raffle.dao.po.DrawStrategyWeightPO`
- `DrawUserPrizeViewPO` — `...raffle.dao.po.DrawUserPrizeViewPO`
- `ExchangeDetailRowPO` — `...raffle.dao.po.ExchangeDetailRowPO`
- `VirtualQuotaRowPO` — `...raffle.dao.po.VirtualQuotaRowPO`

### 5.4 rebate 子树（3 类）

adapter/repository：
- `RebateRepository` — `com.betterschool.marketing.infrastructure.rebate.adapter.repository.RebateRepository`

dao：
- `IActionRebateConfigDao` — `...rebate.dao.IActionRebateConfigDao`

dao/po：
- `ActionRebateConfigPO` — `...rebate.dao.po.ActionRebateConfigPO`

### 5.5 common 子树（7 类）

common/dao：
- `IOutboxDao` — `com.betterschool.marketing.infrastructure.common.dao.outbox.IOutboxDao`（与 bs `infrastructure.dao.outbox.IOutboxDao` 撞简单名，建议合并）
- `OutboxPO` — `...common.dao.outbox.po.OutboxPO`

common/outbox：
- `OutboxMessageSender` — `...common.outbox.OutboxMessageSender`（与 bs `infrastructure.mq.OutboxMessageSender` 撞）
- `OutboxMessageStore` — `...common.outbox.OutboxMessageStore`（与 bs `infrastructure.outbox.OutboxMessageStore` 撞）

common/security：
- `JwtAuthenticationFilter` — `...common.security.JwtAuthenticationFilter`（与 bs `infrastructure.security.JwtAuthenticationFilter` 撞）
- `JwtService` — `...common.security.JwtService`（与 bs `infrastructure.security.JwtService` 撞）

### 5.6 common 之外的平铺基建（压平时需一并归位）

infrastructure.merge（6 类，平级化目标包 `com.betterschool.infrastructure.merge`，与 bs 的 `infrastructure.adapter.merge` 是两套）：
- `MergeConfig`、`MergeEngine`、`MergeItem`、`MergeResult`、`MergeShard`、`MergeStrategy` — `com.betterschool.marketing.infrastructure.merge.*`

对应测试（marketing-infrastructure test）：
- `MergeConfigTest`、`MergeEngineTest`、`RecordingFlush` — `com.betterschool.marketing.infrastructure.merge.*`

---

## 附：非类名但影响合并的事实

1. **启动类**：`com.betterschool.marketing.MarketingApplication` → 目标 `com.betterschool.MarketingApplication`，与 bs 的 `com.betterschool.BetterSchoolApplication` 不撞名，但合并后必须收敛为**一个** @SpringBootApplication + 合并两份 @ComponentScan/@MapperScan/@EnableXxx。
2. **bean 命名策略**：marketing-app 的 `FqcnAnnotationBeanNameGenerator` 平级化后落到 `com.betterschool.config.FqcnAnnotationBeanNameGenerator`（不撞名），需决策是否全局启用以缓解第 2 部分 bean 冲突。
3. **marketing-app 独有 config**（平级化后不撞 FQN）：`IdGeneratorConfig`、`MergeEngineConfig`、`FqcnAnnotationBeanNameGenerator`。
4. **bs-case 已有 marketing 用例类**：`com.betterschool.usecase.marketing.{LotteryDrawnMessage, PointsChangedMessage}`——bs 侧已在为 marketing 埋点，合并时这两处与 marketing-domain 侧消息类要收敛为一处定义。
5. **触发器空缺**：marketing-trigger 不依赖 marketing-infrastructure（依赖 bs-trigger 的模式不同），合并 bs-trigger 时需确认是否要补这条边（QuotaAllocateController、job 等如在 case 层绕过了 infra）。
6. **rocketmq 补丁类**（第 1 部分 #10）两侧包路径完全一致，merge 时任一侧都会直接覆盖冲突，必须先 diff。
