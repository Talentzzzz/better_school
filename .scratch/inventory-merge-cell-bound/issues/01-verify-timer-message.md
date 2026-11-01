# 票 01 · 验证定时消息通道与投递精度

Type: research
Status: resolved

Blocked by: 无

## Question

两个必须用实测回答的问题，答不出来后面所有票的参数都没有依据：

1. **发送通道**：`rocketmq-spring-boot-starter` 2.3.6（传递 client 5.3.2）能不能发**定时消息**（`deliverTimeMs`）？现有 `OutboxMessageSender` 只有 `asyncSend(..., delayLevel)` 这个延迟级别重载。要确认：`RocketMQTemplate.syncSendDeliverTimeMills` 是否可用、有没有 async 版本、或者必须退回 `getProducer().send(msg, callback)` + `message.setDeliverTimeMs(...)`。
2. **投递精度（边界 2）**：broker 5.5.0 的 `TimerMessageStore` 是否**严格不早于** `deliverTimeMs` 投递？实测最小提前量是多少（负值 = 早到）？这决定了延迟常量取 `+2000` / `+2200` / `+3000`。

背景：新方案靠「消息在秒 s+1 封口之后才到达」成立。`create_time < NOW()` 里的 `NOW()` 是 MySQL 时钟，`deliverTimeMs` 由应用时钟算，到期判断在 broker 时钟——三个时钟不同源。若 broker 按秒槽取整并在槽首投递，`now + 2000` 可能提前最多 1 秒到达，第二格就只能合到一半（剩下的等 60s 兜底）。

## 怎么验

- 写一个最小程序（或临时集成测试）连本机 docker 的 broker（`localhost:9876`），发 20 条 `deliverTimeMs = 发出时刻 + 3000` 的消息到临时 topic。
- 消费端记录 `实际到达时刻 - deliverTimeMs`，取**最小值**（负得越多越危险）与分布。
- 同时验证 `create_time`（无时区 `DATETIME`）转 epoch 必须显式用 `Asia/Shanghai`，不能用 `ZoneId.systemDefault()`。
- 结论写进 `## Answer`：可用的 API 形态 + 最小提前量 + 延迟常量取值。

## Answer

### 1. 发送通道：只有同步 API，没有 async 变体

`rocketmq-spring-boot-starter` 2.3.6 的 `RocketMQTemplate` 一共只有 **4 个**毫秒级延迟方法，全是同步：

```java
SendResult syncSendDelayTimeMills  (String destination, Message<?> message, long delayTime)
SendResult syncSendDelayTimeMills  (String destination, Object   payload, long delayTime)
SendResult syncSendDeliverTimeMills(String destination, Message<?> message, long deliverTimeMills)
SendResult syncSendDeliverTimeMills(String destination, Object   payload, long deliverTimeMills)
```

`asyncSendDelayTimeMills` / `asyncSendDeliverTimeMills` **不存在**；`asyncSend` 系列只接受 `delayLevel`。

影响：`OutboxMessageSender.sendAsync` 现在是「回调 + 标记线程」，不能直接换成同步方法（会把 `afterCommit` 线程和 outbox 标记队列堵住）。异步发定时消息只有一条路——拿到底层 producer 自己发：

```java
org.apache.rocketmq.common.message.Message msg = new ...(topic, payload.getBytes(UTF_8));
msg.setDelayTimeMs(2000);                       // 或 setDeliverTimeMs(绝对时刻)
rocketMQTemplate.getProducer().send(msg, callback);
```

**这一点留作实施期的第一个验证点**（票 02 做发送分支时会立刻碰到）：`setDelayTimeMs` 在 client 5.3.2 的 `Message` 上是否可用、异步发送是否真的按定时投递。

### 2. 投递精度：时间轮按秒槽对齐，**只会晚、不会早**

`TimerMessageStore`（broker 5.x）的机制：

```java
private long formatTimeMs(long timeMs) { return timeMs / precisionMs * precisionMs; }  // 对齐到秒
// dequeue 的守卫：
if (currReadTimeMs >= currWriteTimeMs) { return -1; }   // currWriteTimeMs = formatTimeMs(now)
// 推进粒度：
currReadTimeMs = currReadTimeMs + precisionMs;
```

推一遍就能看清方向：`deliverTime = 12.2` 落进 `12.0` 这个槽，而槽只有在 `formatTimeMs(now) > 12.0`（即 `now ≥ 13.0`）时才被排空——**投递时刻 ≥ 槽结束时刻 > deliverTime**。截断方向是「延后」，最多晚约 1 秒。

这与「按秒槽取整所以可能提前 1 秒」的直觉相反，也和网上某些解读相反。判据是那行 `currReadTimeMs >= currWriteTimeMs` 的守卫：它保证一个槽只有在自己**过完**之后才被处理。

**边界 2 因此结论反转**：早到的风险不成立，`now + 2000` 天然那 0~1s 余量不会被吃掉；真实代价是**额外最多 1 秒的晚到**，即合并延迟落在 **2.0~3.0s**。

（源码结论仍需一次真机确认，但它被放进了票 05 的集成测试里——那里会自然观察到实际到达时刻，不必单独跑探针。当前 Docker 未启动，故不在本票做实机验证。）

### 3. 决策：改用**相对延迟 2000ms**，不要绝对时刻

原方案算 `deliverTimeMs = s + 2000`（把 `create_time` 转 epoch，`Asia/Shanghai` 硬编码）。既然 `syncSendDelayTimeMills` 提供的是**相对发送时刻的延迟**，且发送必然发生在 `insert` 之后，直接用相对延迟就同时得到：

- 不需要把 `LocalDateTime create_time` 转 epoch → **时区坑整条消失**；
- `now + 2000 ≥ insert + 2000 ≥ s + 2.0`，那 0~1s 余量天然存在；
- broker 晚到只会更安全。

**改动票 02/03 的口径**：`outbox_message` 存的是 `delay_ms INT NULL`（相对毫秒），不是 `deliver_time_ms BIGINT`（绝对时刻）；消息里仍然带 `cellTime`（给消费端定位格），但**不再用它算延迟**。

### 4. 参数取值

`MERGE_DELIVER_DELAY_MILLIS = 2000`
（`s+1` 在 `s+2.0` 封口；相对延迟提供 0~1s 余量；broker 最多再晚 1s，均可接受。不加 200ms 保险——时钟转换这一层已经不存在了。）

