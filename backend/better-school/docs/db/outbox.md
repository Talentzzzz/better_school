# outbox 域表（mq_outbox）

> 业务：**跨进程投递**的事务性本地消息表（RocketMQ 最终一致）；归属模块：infrastructure（`dao` 根包：`IMqOutboxDao` + `dao/po/MqOutbox`）
> **本表只管「把字节送到 broker 的 topic」**。outbox 有两种，是两张不同的表（ADR-0017）：域内最终一致任务（「这个本地动作还没做完」）用**域自己的任务表** + 域自己的扫描 job 进程内重放，不进本表、不过 MQ——例如发帖返利领取任务落 `post_rebate_claim_task`（见 [post](post.md)），商品支付收敛任务落 `wallet_pay_task`（见 [user](user.md)，与支付单 PAID / 平台代收入账同事务），抽奖次数购买单落 `draw_count_purchase_order`，超时任务落 `timeout_task` 分表
> DDL 唯一事实源：`sql/schema.sql`（票 05 全库重写；monolith-restructure 票 07 合库后**全库唯一一份**，形状取合并前 marketing 侧超集——单体侧旧形状退役）。**`delay_outbox_message` / `DelayOutboxMessageStore` / `DelayOutboxMessageSender` 整条延迟 outbox 链路已删除，无此表、无此代码**
> **原来的超时注册意图表 / `TimeoutRegisterOutboxStore` / `TimeoutRegisterOutboxSender` 整条注册 outbox 链路已删除（超时内嵌化票 03）**：注册改为业务事务内直插 `timeout_task` 分表（16 张，见 schema.sql），三分支幂等在 `TimeoutTaskStore.register`。用法见 [`backend/docs/architecture/timeout.md`](../../../docs/architecture/timeout.md)

# mq_outbox（本地消息表 outbox）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理自增，标记/扫描按它寻址（分配序 = 提交序）|
|msg_id|varchar(64)|UNIQUE(`uk_msg_id`), NOT NULL|消息 ID（幂等/去重）：由 `MqOutbox.pending(...)` 生成 UUID；发帖返利**入账**任务用确定性键 `post-rebate-credit:{orderId}`（撞 `uk_msg_id` 即说明任务已落过），其余场景仍为随机 UUID，消费端一律按它幂等。**注意本列是 UUID 字符串，与 chat `message.msg_id`（同为 UUID 字符串的业务 ID）只是同名不同列**|
|destination|varchar(64)|NOT NULL|消息目的地（**原 `topic` 列**）：`topic` 或 `topic:tag` 语法，由业务端自行解析，取值见 `bs-types` 的 `Constants.*_TOPIC`（如 `bs-search-sync`、`bs-post-rebate-credit`、`bs-post-liked`、`bs-comment-liked`、`bs-notify-event`（通知事件：业务事务内写，NotifyEventConsumer 消费统一落 notification 并按通道直接投递——CHAT 调系统消息投递、BANNER 直接推 WS，不再有第二跳投递 topic）、`bs-order-push`、`bs-lottery-drawn`、`bs-points-changed`）。**chat 的 MSG/READ/RECALL 实时推送不走本表**：写事务提交后直接投递 WS，漏推由客户端 `(roomId, seq)` 补拉兜底|
|payload|text|NOT NULL|消息内容 JSON（落库与发送同一份）|
|delay_level|tinyint|NOT NULL, DEFAULT 0|RocketMQ 延迟等级；0=不延迟，1=1 秒（约 1.0–1.1s）——marketing 侧超集列，合并前单体侧无此列|
|delay_ms|int|NULL|毫秒级相对延迟（RocketMQ 定时消息）；NULL=走 `delay_level`——marketing 侧超集列（库存合并定时投递在用）|
|state|tinyint|NOT NULL, DEFAULT 0|0 待发送 / 1 已发送 / 2 发送失败（重试耗尽）。**本列是 tinyint 数字码（`OutboxStatusEnum`），不是别处的 varchar 状态名**|
|retry_count|int|NOT NULL, DEFAULT 0|已重试次数；达上限（当前 5 次）后置 state=2，不再重发|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间（状态标记时刷新）|

## 索引
- `uk_msg_id (msg_id)`：消息唯一键（发送/消费幂等锚点）
- `idx_state (state)`：扫描兜底与积压观测走这条

## 注意
- **谁在写**：各域 repository 在**业务事务内**直接用 `IMqOutboxDao.insert(MqOutbox.pending(...))` 插行（插行即拿到本地事务原子性），**事务内只插不发**；回滚即无行（无幽灵消息）。无事务的旁路（对账告警）单行 insert 自带隐式事务，插完立即同步发送
- **谁在读/改**：事务边界在仓储方法内（`TransactionTemplate.execute` 返回、提交完成）——仓储在**事务外**用 `RocketMQTemplate.syncSend` 同步发送一次，broker 返回 `SEND_OK` 后 `markSentByMsgId`；失败同步累计 `retry_count`，达上限置 state=2。**禁止用 `TransactionSynchronization.afterCommit` 挂钩发送，也禁止在事务内提前发送**；若仓储方法并入调用方外层事务（REQUIRED 合流），不在方法返回时发送，留 PENDING 交 `outboxScan` 在事务结束后兜底
- **发帖返利领取任务不在本表**（ADR-0017）：它是域内最终一致任务，落 `post_rebate_claim_task`（与帖子行同事务），领取失败由 `trigger/job/PostRebateClaimScanJob` 的 `postRebateClaimScan` 进程内重放，不经过 broker
- **支付回调任务不在本表**（ADR-0017 / ADR-0018）：它是钱包域的域内最终一致 outbox，落 `wallet_pay_task`（与支付单 PAID、买家 `PRODUCT_PAY` + 平台 `PLATFORM_IN` 双边流水、买家余额扣减 + 平台余额入账同一支付事务），商户回调失败由 `trigger/job/ProductPayTaskScanJob` 的 `productPayTaskScan` 进程内重放，不经过 broker
- **发帖返利入账任务**：返利仓储在领取事务内插入（与 claim 行同事务），事务提交后立即同步发送到 `bs-post-rebate-credit`；发送失败留 PENDING 交 `outboxScan`
- **兜底扫描**：`trigger/job/OutboxScanJob` 的 `@XxlJob("outboxScan")` cron `0/30 * * * * ?` 重发本表全部 state=0 行，覆盖「提交后进程崩溃 / broker 宕机」；多实例需加扫描锁，重复发送由消费端按 `msg_id` 幂等兜底。**域内收敛任务不归它管**：各域自己的扫描 job（`postRebateClaimScan` / `drawCountPurchaseScan` / `timeoutTaskScan` 等）负责
- **历史 topic 清理（2026-11-27）**：`bs-notify-chat` / `bs-notify-push` 已随投递第二跳折叠下线（通知域改为落库后直接投递）；升级前存量 PENDING 行直接删除或标 SENT 即可，已无消费者、重发无意义
- 消费侧按 `msg_id` 幂等，因此重发安全；本表只有 0/1/2 三态，无软删列
- 集成测试每用例 TRUNCATE 本表（`PostApiTestBase` 的表隔离清单）——断言待发消息要么按 `destination + state=0` 查，要么在清库前查
