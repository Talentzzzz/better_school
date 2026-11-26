# outbox 域表（mq_outbox）

> 业务：事务性本地消息表（RocketMQ 最终一致）；归属模块：infrastructure（`dao` 根包：`IMqOutboxDao` + `dao/po/MqOutbox`）
> DDL 唯一事实源：`sql/schema.sql`（票 05 全库重写；monolith-restructure 票 07 合库后**全库唯一一份**，形状取合并前 marketing 侧超集——单体侧旧形状退役）。**`delay_outbox_message` / `DelayOutboxMessageStore` / `DelayOutboxMessageSender` 整条延迟 outbox 链路已删除，无此表、无此代码**
> **原来的超时注册意图表 / `TimeoutRegisterOutboxStore` / `TimeoutRegisterOutboxSender` 整条注册 outbox 链路已删除（超时内嵌化票 03）**：注册改为业务事务内直插 `timeout_task` 分表（16 张，见 schema.sql），三分支幂等在 `TimeoutTaskStore.register`。用法见 [`backend/docs/architecture/timeout.md`](../../../docs/architecture/timeout.md)

# mq_outbox（本地消息表 outbox）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理自增，标记/扫描按它寻址（分配序 = 提交序）|
|msg_id|varchar(64)|UNIQUE(`uk_msg_id`), NOT NULL|消息 ID（幂等/去重）：由 `MqOutbox.pending(...)` 生成 UUID；发帖返利两段式用确定性键（领取任务 `post-rebate-claim:{postId}`、入账任务 `post-rebate-credit:{postId}`，撞 `uk_msg_id` 即说明任务已落过），其余场景仍为随机 UUID，消费端一律按它幂等。**注意本列是 UUID 字符串，与 chat `message.msg_id`（同为 UUID 字符串的业务 ID）只是同名不同列**|
|destination|varchar(64)|NOT NULL|消息目的地（**原 `topic` 列**）：`topic` 或 `topic:tag` 语法，由业务端自行解析，取值见 `bs-types` 的 `Constants.*_TOPIC`（如 `bs-search-sync`、`bs-post-rebate-claim`、`bs-post-rebate-credit`、`bs-chat-push`、`bs-notify-event`（通知事件：业务事务内写，NotifyEventConsumer 消费统一落 notification + 按通道投递）、`bs-notify-chat`、`bs-notify-push`、`bs-order-push`、`bs-lottery-drawn`、`bs-points-changed`）|
|payload|text|NOT NULL|消息内容 JSON（落库与发送同一份）|
|delay_level|tinyint|NOT NULL, DEFAULT 0|RocketMQ 延迟等级；0=不延迟，1=1 秒（约 1.0–1.1s）——marketing 侧超集列，合并前单体侧无此列|
|delay_ms|int|NULL|毫秒级相对延迟（RocketMQ 定时消息）；NULL=走 `delay_level`——marketing 侧超集列（库存合并定时投递在用）|
|state|tinyint|NOT NULL, DEFAULT 0|0 待发送 / 1 已发送 / 2 发送失败（重试耗尽）。**本列是 tinyint 数字码（`OutboxStatus`），不是别处的 varchar 状态名**|
|retry_count|int|NOT NULL, DEFAULT 0|已重试次数；达上限（当前 5 次）后置 state=2，不再重发|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间（状态标记时刷新）|

## 索引
- `uk_msg_id (msg_id)`：消息唯一键（发送/消费幂等锚点）
- `idx_state (state)`：扫描兜底与积压观测走这条

## 注意
- **谁在写**：各域 repository 在**业务事务内**直接用 `IMqOutboxDao.insert(MqOutbox.pending(...))` 插行（插行即拿到本地事务原子性）；**禁止在事务外写**——`afterCommit` 挂钩靠事务存在才成立，回滚即无行（无幽灵消息）。无事务的旁路（对账告警）单行 insert 自带隐式事务，插完立即同步发送
- **谁在读/改**：各仓储自己——事务提交后在 `afterCommit` 里用 `RocketMQTemplate.syncSend` 同步发送一次，broker 返回 `SEND_OK` 后 `markSentByMsgId`；失败同步累计 `retry_count`，达上限置 state=2
- **发帖返利领取任务**：post 仓储在发帖 / 提交草稿事务内插入（PENDING，不在事务内发送）；领取成功后由 case 调 post 仓储按确定性 msgId 标记已发送（SENT），只有同步领取抛异常时才留 PENDING 交 `outboxScan` 重放到 `bs-post-rebate-claim`
- **发帖返利入账任务**：返利仓储在领取事务内插入（与 claim 行同事务），事务提交后立即同步发送到 `bs-post-rebate-credit`；发送失败留 PENDING 交 `outboxScan`
- **兜底扫描**：`trigger/job/OutboxScanJob` 的 `@XxlJob("outboxScan")` cron `0/30 * * * * ?` 重发全部 state=0 行，覆盖「提交后进程崩溃 / broker 宕机」；多实例需加扫描锁，重复发送由消费端按 `msg_id` 幂等兜底
- 消费侧按 `msg_id` 幂等，因此重发安全；本表只有 0/1/2 三态，无软删列
- 集成测试每用例 TRUNCATE 本表（`PostApiTestBase` 的表隔离清单）——断言待发消息要么按 `destination + state=0` 查，要么在清库前查
