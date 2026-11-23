# outbox 域表（outbox_message）

> 业务：事务性本地消息表（RocketMQ 最终一致）；归属模块：outbox（infrastructure）
> DDL 唯一事实源：`sql/schema.sql`（票 05 全库重写；monolith-restructure 票 07 合库后**全库唯一一份**，形状取合并前 marketing 侧超集——单体侧旧形状退役）。**`delay_outbox_message` / `DelayOutboxMessageStore` / `DelayOutboxMessageSender` 整条延迟 outbox 链路已删除，无此表、无此代码**
> **原来的超时注册意图表 / `TimeoutRegisterOutboxStore` / `TimeoutRegisterOutboxSender` 整条注册 outbox 链路已删除（超时内嵌化票 03）**：注册改为业务事务内直插 `timeout_task` 分表（16 张，见 schema.sql），三分支幂等在 `TimeoutRepository.register`。用法见 [`backend/docs/architecture/timeout.md`](../../../docs/architecture/timeout.md)

# outbox_message（本地消息表 outbox）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理自增，标记/扫描按它寻址（分配序 = 提交序）|
|msg_id|varchar(64)|UNIQUE(`uk_msg_id`), NOT NULL|消息 ID（幂等/去重）：由 `OutboxMessageStore` 生成 UUID，消费端按它幂等。**注意本列是 UUID 字符串，与 chat `message.msg_id`（同为 UUID 字符串的业务 ID）只是同名不同列**|
|destination|varchar(64)|NOT NULL|消息目的地（**原 `topic` 列**）：`topic` 或 `topic:tag` 语法，由业务端自行解析，取值见 `bs-types` 的 `Constants.*_TOPIC`（如 `bs-search-sync`、`bs-chat-push`、`bs-notify-event`（通知事件：业务事务内写，NotifyEventConsumer 消费统一落 notification + 按通道投递）、`bs-notify-chat`、`bs-notify-push`、`bs-order-push`、`bs-lottery-drawn`、`bs-points-changed`）|
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
- **谁在写**：各域 repository 在**业务事务内**经 `OutboxMessageStore.save(destination, payload)` 插行（插行即拿到本地事务原子性）；**禁止在事务外写**——`afterCommit` 挂钩靠事务存在才成立，回滚即无行（无幽灵消息）
- **谁在读/改**：只有 `OutboxMessageSender`：事务提交后即时 `asyncSend` 一次（降延迟），失败按下发重试计数决定 `retry_count+1` 或置 state=2；状态标记走高并发设计（RocketMQ 回调线程只入内存队列，专用标记线程批量 update）
- **兜底扫描**：`@XxlJob("outboxScan")` cron `0/30 * * * * ?` 重发全部 state=0 行，覆盖「提交后进程崩溃 / broker 宕机」；多实例需加扫描锁，重复发送由消费端按 `msg_id` 幂等兜底
- 消费侧按 `msg_id` 幂等，因此重发安全；本表只有 0/1/2 三态，无软删列
- 集成测试每用例 TRUNCATE 本表（`CommunityApiTestBase` 的表隔离清单）——断言待发消息要么按 `destination + state=0` 查，要么在清库前查
