# 03 四个接入方改同事务直插 + 删注册通道 + 旧链路下线

Type: task
Status: resolved
Blocked by: 02

## Answer

**已落地（2026-10-16）。**

**三分支幂等**：域接口新增 `ITimeoutRepository.register(task)`，实现收在 `TimeoutRepository.register`——`insertIfAbsent` 撞 `uk_type_biz` 即转 `reviveTerminal`（条件 UPDATE 只打 SUCCESS/FAILED/CANCELED、刷新 action_time/next_fire_time、retry_count 清零）；没打中 = 在途（WAITING/RETRYING）= 幂等成功不改期。裁决权全在库上（撞键翻返回值 + 条件 UPDATE），与读-判-写时序无关。四个接入方（`TradeRepository.schedulePayTimeout`、`ErrandRepository.schedulePayTimeout/scheduleLockTimeout`、`ReviewTaskPoolStore.claim`）各一行 `timeoutRepository.register(TimeoutTaskEntity.newWaiting(...))`，原 outbox upsert 与 afterCommit 挂钩消失；outbox 的「同键复位 PENDING」语义由三分支接管（「先 cancel 再复位」纪律随通道消失，`cancelAsync` 本就无生产调用方，一并删除）。

**删注册通道**：`timeout_register_outbox` DDL（schema.sql）、`TimeoutRegisterOutboxStore`、`TimeoutRegisterOutboxSender`（含 `@XxlJob("timeoutRegisterOutboxScan")`）、`TimeoutRegisterOutboxMapper.xml`、`ITimeoutRegisterOutboxDao`/PO、`TimeoutTaskRegisterPort`/`ITimeoutTaskRegisterPort`、xxl-job seed 行、`application.yml` 的 `timeout-center.register.*` 配置块、bs-infrastructure 的 `timeout-center-api` 与 dubbo 依赖（port 已删后全模块零 Dubbo 引用）。`grep timeoutRegister|timeout_register_outbox` 生产代码零命中。

**删旧链路死代码**：`ProductTimeoutScanJob`、`ProductPayTimeoutConsumer`、`productTimeoutScan` seed 行，连带孤儿链一并删净——`ProductOrderCase.scanPayTimeouts`、`IProductOrderService/TradeRepository/Dao` 的 `listPayTimeoutOrderIds`/`selectPayTimeoutOrderIds`（+ mapper XML）、`ProductOrderTimeoutMessage` valobj、`PRODUCT_PAY_TIMEOUT_TOPIC` 常量（生产者早已不存在，全链无存活引用）。

**同事务验收（新 `TimeoutInTransactionRegistrationIntegrationTest`）**：提交即可见（HTTP 下单/发布后立刻能查到 WAITING 行、action_time ≈ +15min）；业务事务回滚超时行不落库；同一 `TransactionTemplate` 里业务行与超时行同生同死（提交侧双行、`setRollbackOnly` 侧双无）；三分支各有用例（在途不改期 / 终态复活刷新 action_time）。4 个接入点的 action_time 取值、复合 bizId（`{orderId}:{lockSeq}`、`{taskId}:{claimSeq}`）、计时起点不变——`ErrandLockTimeout`/`ReviewTaskLease`/`ErrandDraftFlow` 的注册断言已改查 `timeout_task`（广播 COUNT 合并；删掉假端口与即时注册轮询断言）。

**坑记录（重要）**：基类 `CommunityApiTestBase` 原本只 TRUNCATE 业务表——**业务表 TRUNCATE 会重置自增 id，而 `timeout_task` 残留行不清**，下一用例的同 biz_id 注册被三分支幂等（在途不改期）静默吸收，计数/时间窗断言被陈旧行污染（首轮联调 4 个假失败全源于此）。已改为每用例用裸 MySQL 连接 TRUNCATE 16 张分表（物理名经 SS 连接查不到，复用票 02 的裸连处方）。另：`ConsumeHandlerVirtualThreadIntegrationTest` 的消费者容器计数 15→14（`ProductPayTimeoutConsumer` 下线）。

**与票面的偏差**：bs-trigger 的 `timeout-center-api` 依赖与 `TimeoutTaskCallbackDubboService` **保留**——Dubbo 回调边的删除是票 05 的明确工作项，现在删会让旧中心在途任务的回调无处落地、并打破回调向集成测试；root pom 的 dependencyManagement 条目同理待票 05 收。`sql.md` 已同步（29 张业务表口径）；`docs/db/*` 与 `docs/api/errand.md` 的 outbox 表述已最小同步，CONTEXT.md 词条与 arch doc 整体改写留给票 09。评审判断题记录：三个业务仓储注入他域 `ITimeoutRepository` 是同事务设计的直接后果（跨域许可建议在 backend/CLAUDE.md §1/§3 补记，随票 09 文档收口处理）；跑腿侧 `errandTimeoutScan`/`ErrandPayTimeoutConsumer` 旧链是 MQ 延迟消息的域内兜底（map Notes 8 明确不收编），保留。

## What to build

**接入方改直插**（把现在的 outbox upsert 换成事务内直插 `timeout_task`）：

| 接入方 | 现注册点 | 现仓储方法 |
|---|---|---|
| `PRODUCT_ORDER_PAY_TIMEOUT`（+15min） | `TradeRepository.createOrderWithLock` 内 | `TradeRepository.schedulePayTimeout:376-379` |
| `ERRAND_PAY_TIMEOUT`（+15min） | `createWithPayTimeout:353`、`submitDraftWithPayTimeout:405` | `ErrandRepository.schedulePayTimeout:313-315` |
| `ERRAND_LOCK_TIMEOUT`（+5min，`bizId={orderId}:{lockSeq}`） | `grabWithLockTimeout:368` | `ErrandRepository.scheduleLockTimeout:318-321` |
| `REVIEW_LEASE_TIMEOUT`（+5min，`bizId={taskId}:{claimSeq}`） | `ReviewTaskPoolStore.claim:136-162` | 同方法 `:159-160` |

幂等分支在仓储内实现为**三分支**：新插入 / 在途同键**幂等成功不改期** / 终态**复活刷新 `action_time`**；原「不同 group 冲突失败 + 告警」随 `callback_group` 消失。

**删注册通道**：`timeout_register_outbox` 表（`schema.sql:172-185`）+ `TimeoutRegisterOutboxStore` + `TimeoutRegisterOutboxSender`（含 afterCommit 挂钩与 `@XxlJob("timeoutRegisterOutboxScan")`）+ `TimeoutRegisterOutboxMapper.xml` + `TimeoutTaskRegisterPort` / `ITimeoutTaskRegisterPort` + `better-school/sql/xxl-job-tasks.sql` 里那行 seed + 两个 pom 里的 `timeout-center-api` 依赖。

**删旧链路死代码**：`ProductTimeoutScanJob`（`@XxlJob("productTimeoutScan")`）+ `ProductPayTimeoutConsumer`（RocketMQ）+ 其 seed 行。

## 验收

- **同事务**：集成测试钉「业务事务回滚后超时行不落库、提交后同事务可见」；同一 `TransactionTemplate` 里业务行与超时行同生同死。
- `better_school` 里不再有 `timeout_register_outbox`；`grep -r timeoutRegister` 无生产命中。
- 4 个接入点的注册语义不变（`action_time` 取值、复合 `bizId`、计时起点——见 arch doc §8 的两张接入方表）。
- 全量集成测试绿。

## 注意

- 现在 `TimeoutRegisterOutboxStore` 的 upsert 带「同键复位」（`ON DUPLICATE KEY UPDATE state='PENDING'`），那是 outbox 的表达；改直插后**同键复位语义由中心侧三分支接管**，不要顺手把它丢了。
- arch doc §8 记的「残留窗口」（注册在途 vs 取消竞态）与本票的「先 cancel 再复位」纪律**随同事务消失**——`TimeoutRegisterOutboxSender.cancelAsync` 本就没有生产调用方，可一并删。
