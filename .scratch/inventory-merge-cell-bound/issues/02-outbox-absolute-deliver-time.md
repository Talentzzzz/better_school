# 票 02 · outbox 支持毫秒级相对延迟

Type: task
Status: resolved

Blocked by: 01

## Question

把 outbox 从「只支持延迟级别」扩成「也支持毫秒级相对延迟」，让库存合并消息能在「发出后 2 秒」投递。

> 票 01 已把原设计的「绝对投递时刻」改成「相对延迟」：`syncSendDelayTimeMills` 就是相对语义，且发送必然在 `insert` 之后，天然带 0~1s 余量。**不要再引入 `deliver_time_ms` 绝对时刻那一套，时区坑整条消失。**

范围（只改通用件，不碰库存域逻辑）：

1. `sql/schema.sql`：`outbox_message` 加 `delay_ms INT NULL COMMENT '毫秒级相对延迟（RocketMQ 定时消息）；NULL=走 delay_level'`。
2. `OutboxMessageStore`：新增 `save(destination, payload, int delayLevel, Integer delayMs)` 重载；`delayLevel` 与 `delayMs` 互斥（有 `delayMs` 时 `delay_level = 0`）。`afterCommit` 异步发送的语义不变。
3. `OutboxMessageSender.sendAsync`：三分支——
   - `delayMs != null` → 底层 producer 直发：`msg.setDelayTimeMs(delayMs)` + `producer.send(msg, callback)`；
   - 否则 `delayLevel > 0` → 现有 5 参 `asyncSend(..., delayLevel)`；
   - 否则 → 现有 2 参 `asyncSend`。
   **第一条是票 01 留下的待验证点**：`setDelayTimeMs` 在 client 5.3.2 的 `Message` 上是否可用、异步发送是否真按定时投递。先写一个最小验证再落分支。
4. `outboxScan` 重发：只捞 `update_time` 超过 60s 的行，那时「2 秒后」早已过期 → **直接走普通 `send`**，不再定时（否则会变成「立刻又要等 2 秒」）。分支上写注释说明。
5. 单测：覆盖三分支与 `uk_msg_id` 幂等。

约束：`delay_level` 列保留不动——积分 / 抽奖 / 发货事件仍用它，不能被拖慢 1 秒（ADR-0011 的既有约束）。

## Answer

### 1. 票 01 留下的验证点：通道在，且确实按定时投递

**可用性（client 5.3.2，javap）**：`Message.setDelayTimeMs(long)` 存在，字节码就是
`putProperty("TIMER_DELAY_MS", value)`；`setDeliverTimeMs` / `setDelayTimeSec` / `setDelayTimeLevel` 同族。
异步路径用 `producer.send(msg, callback)`（`MQProducer` 接口自带），不经过模板的同步重载。

**实机**（5.5.0 broker 单起一个隔离容器，client 5.3.2，`producer.send(msg, callback)`，14:0x）：

| 模式 | n | 实测（到达 − 发送时刻） |
| --- | --- | --- |
| `setDelayTimeMs(2000)` 第一轮 | 10 | 2282–2286 ms |
| `setDelayTimeMs(2000)` 第二轮 | 10 | 3025–3031 ms |
| `setDelayTimeLevel(1)` | 5 | 1072–1122 ms |
| 不延迟 | 3 | 57–66 ms |

- **没有一条早到**（全部 ≥ 2000ms），最坏晚 ~1.03s ⇒ 与票 01 的「时间轮按秒槽对齐、槽过完才排空」一致；
  实际延迟落 **2.28~3.03s**。票 03 的 `2000` 不用加保险值，票 05 的 2.0~3.2s 上界留得住。
- 探针第一版测出来三种模式齐刷刷 21s——那是消费者**首次拉取的 ~20s 路由退避**，不是投递延迟。
  改成「topic 先建 + 消费者起好再发」之后才得到上表。**票 05 量时延必须照这个顺序**，否则量到的是消费侧。

### 2. 改了什么（只动通用件，库存域一行没碰）

| 位置 | 改动 |
| --- | --- |
| `marketing/sql/schema.sql`、`sql.md` | `outbox_message` 加 `delay_ms INT NULL COMMENT '毫秒级相对延迟（RocketMQ 定时消息）；NULL=走 delay_level'` |
| `OutboxPO` | 加 `delayMs` |
| `OutboxMapper.xml` | `baseColumns` / `insert` 带上 `delay_ms` |
| `OutboxMessageStore` | 新增 `save(destination, payload, int delayLevel, Integer delayMs)`；`delay_level` 与 `delay_ms` 互斥（有 `delayMs` 时级别落 0）；`afterCommit` 语义不变 |
| `OutboxMessageSender` | `sendAsync` 三分支：`delayMs`→producer 直发 `setDelayTimeMs`；否则 `delayLevel>0`→五参 `asyncSend`；否则两参。`scanPending` 改为 `dispatch(po, delayExpired=true)`，只有 `delay_ms` 行落回普通发送 |

### 3. 两个判断，写下来免得后面重新吵

1. **互斥做成「抛异常」而不是「静默归一化」**：票面只要求「有 `delayMs` 时 `delay_level = 0`」，实现多挡了一手——
   两个都传就 `IllegalArgumentException`。理由是那种调用没有正确答案（落哪个、发送侧听哪个全靠猜），
   落库时炸掉比让 `scanPending` 猜便宜。正常的调用形态只有 `save(topic, msg, 0, 2000)` 一种。
2. **扫描重发只让 `delay_ms` 行失去定时**：`delay_level` 行仍按列里的级别重发——ADR-0011 把「级别随消息
   持久化」写成了那一列存在的理由，1 秒档重发再等 1 秒无伤；把它也改成直发等于替 ADR-0011 做决定，不在本票。

### 4. 测试

- 单测 10 个（`marketing.unit.outbox`）：三分支 + 「两列同时非空时 `delayMs` 优先」+ 扫描重发的两种延迟行为
  + 落库互斥/归一化。
- 集成 1 个（`RaffleShippingOutboxIntegrationTest.sameMsgId_isAbsorbedByUniqueKey`）：`uk_msg_id` 撞键
  （`DataIntegrityViolationException`、表里仍一行）、`delay_ms` 随行往返、扫描前回读 `delayMs` 不丢。
- 全量 `mvn -o test`：**129 tests 绿**（基线 118 + 本票 11）。
- 顺手在 `backend/docs/testing.md` 补了一条：mock 单测放 `marketing.unit.*`，与「集成测试只断言外部行为」分开，
  否则新目录没有文档依据。

### 5. 后面几票要接的

- 票 03：`save(topic, msg, 0, Constants.MERGE_DELIVER_DELAY_MILLIS)`；合并消息从此不再用 `delay_level`。
- 票 05：断言 2.0~3.2s；照 §1 的顺序量（先建 topic、先起消费者）。
- 票 06：`inventory.md` 触发段、`CONTEXT.md` 的「超时任务」词条、ADR-0011 顶部 supersede 行都还没动。

### 6. 环境备注（和本票结论无关，但会绊住下一票）

跑这一票时 Docker Desktop 的 VM 掉过一次（`apiproxy: no route to host 192.168.65.7:2376`，引擎整体 500），
重启才恢复；另外**宿主机 C: 已用 95.2%**，dev broker 的 store 是 bind mount 到 C:，RocketMQ 因此判
`disk is full`（`CODE 14`）拒写——用 dev broker 联调前先把盘清到 90% 以下，或把 store 挪到容器内路径。

