# 04: outbox 延迟级别（共享件）

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §2.4（加列）、§4.3（延迟级别与 outbox）、§12（共用件波及）

**What to build:** 让 outbox 消息能**随行持久化一个延迟级别**，首次异步发送与扫描重发都按这一列走。
默认 `0`（不延迟），本轮唯一的 `1` 是库存合并 topic；points / raffle 的消息必须一个字节都不变。
这是「扫描兜底重发不能把 1 秒后合并变成立刻合并」的唯一保证。

**Blocked by:** 01

**Status:** resolved

**预算：≈70k / 200k。** 只碰共享 outbox 一处（PO / mapper / store / sender）加两条回归断言。
若五参 `asyncSend` 的重载在 client 版本上对不上，停下来核对依赖版本（client 5.3.2），不要自己造延迟实现。

## 验收

- [x] 营销库 `outbox_message` 加 `delay_level TINYINT NOT NULL DEFAULT 0`
      （注释写清 `0=不延迟 / 1=1 秒`；单体工程的 outbox 不动）
- [x] `OutboxPO.delayLevel`；`OutboxMapper.xml` 的 `insert` 与 `baseColumns` 带上 `delay_level`，
      `selectPendingList` 读回它
- [x] `OutboxMessageStore.save(destination, payload, delayLevel)` 写列；保留两参重载（默认 0）
- [x] `OutboxMessageSender.sendAsync(OutboxPO)` 按 `po.getDelayLevel()` 选路：
      `0` 走原两参 `asyncSend`；`1` 走
      `asyncSend(destination, MessageBuilder.withPayload(payload).build(), callback, timeout, 1)` 五参重载
- [x] `scanPending`（outboxScan）重发时沿用同一列，不做 topic → 级别的映射表
- [x] points / raffle 的既有调用全部落到 `delay_level = 0`；`RaffleShippingOutboxIntegrationTest`
      的两条断言（回滚后没有第二条事件 / 重试耗尽进死信）**原样绿**
- [x] 新用例：写入一条库存合并 topic 的消息 → outbox 行 `delay_level = 1`；同场景的中奖事件仍为 0
- [x] 重建测试库（列变更），全量集成测试绿

## 实现要点

（2026-10-17 落地。只动营销工程的共享 outbox 一处，points / raffle 的调用与消息字节未变。）

**五参重载的实际签名**（rocketmq-spring-boot 2.3.6 → client 5.3.2，`javap` 核对）
- `RocketMQTemplate.asyncSend(String destination, Message<?> message, SendCallback callback,
  long timeout, int delayLevel)` 确实存在；**没有** Object payload 的延迟重载。
- `MessageBuilder.withPayload(payload).build()` 先把 JSON 字符串包成 Spring `Message<String>` 再发；
  `timeout` 取 `rocketMQTemplate.getProducer().getSendMsgTimeout()`（沿用
  `rocketmq.producer.send-message-timeout`：测试 1000ms、生产默认 3s），与两参重载内部用的超时一致。

**sender 选路**
- `sendAsync(OutboxPO)` 只读 `po.getDelayLevel()`（null 视为 0）：
  `0` 走原两参 `asyncSend(destination, payload, callback)`（points / raffle 一个字都不变）；
  `> 0` 走五参重载。**不按 destination 查表**，所以共享 sender 不会把别的域拖慢 1 秒。
- `scanPending` 调的是同一个 `sendAsync(OutboxPO)`；`selectPendingList` 的 `baseColumns` 已含
  `delay_level`，重发自然沿用行里的级别，不需要 topic → 级别映射（合并消息的 SENT 会晚约 1s，可接受）。

**DDL / store**
- `outbox_message.delay_level TINYINT NOT NULL DEFAULT 0` 加在 `payload` 之后；注释按 spec §2.4 原文。
  只改营销 `schema.sql` 与根 `sql.md` §2，单体 outbox 未动；比对脚本 20 张表逐表逐列全等。
- `OutboxPO.delayLevel`（`Integer`）；`OutboxMapper.xml` 的 `insert` / `baseColumns` 带上 `delay_level`。
- `OutboxMessageStore.save(destination, payload, delayLevel)` 写列；保留 `save(destination, payload)`
  委托 `delayLevel = 0`，既有 points / raffle 调用零改动。

**测试**
- `RaffleShippingOutboxIntegrationTest` 新增一例：同场景先抽奖，再在一个事务里用三参 `save` 写一条
  `bs-inventory-merge`（载荷三字段），断言中奖事件 `delay_level = 0`、合并消息 `= 1`；
  把该行推回 10 分钟前再调 `scanPending`，断言 `retry_count + 1` 且 `delay_level` 仍为 1。
  （测试栈无 broker，「晚 1 秒到」不可观察；按票面只断言列。）
- 本票不引入库存消息类型 / 常量（`InventoryMergeMessage`、`INVENTORY_MERGE_TOPIC` 留给 09），
  测试里用字面量 destination。

**验证证据**
- 两库按 spec §3 重建（各 20 张表，`outbox_message.delay_level` 为
  `tinyint NOT NULL DEFAULT 0`）。
- `mvn -o test`（`backend/marketing`）：**92 tests, 0 failures, 0 errors, 0 skipped**（91 + 1 新用例）。

## 注意

- **共享 sender 不能把 points / raffle 拖慢 1 秒**：级别只由消息自带的列决定，不许按 destination 查表。
- 测试栈没有 broker：`sendAsync` 必然失败并被 outbox 兜住，所以本票的可观察形态是
  「outbox 行 + `delay_level`」，不是「消息真的晚 1 秒到」。测延迟等级只断言列。
- 本票只让能力可用；**谁传 1 在 09**，本票不要引入库存 message 类型。
