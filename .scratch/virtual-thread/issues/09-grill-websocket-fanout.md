# 09 grilling：长连接 / WebSocket 推送扇出裁决

Type: grilling
Status: resolved
Blocked by: 03

## Question

WebSocket 两个点要不要 / 能不能虚拟线程化，待[票 03](03-research-jdk21-pinning-inventory.md) 的 pinning 结论到齐后拍板：

1. **握手线程**：`ChatWsHandshakeInterceptor.java:35` 在握手线程上做阻塞 Redis `GETDEL`（换连接票据）。这属不属于「并发上限由平台线程数决定」？Tomcat 的握手线程池是什么规模
2. **推送发送路径**：`ChatWsPusher.java:30` 用 `ConcurrentWebSocketSessionDecorator`（5s 发送上限 / 512KB 缓冲），从 MQ 消费线程串行发给会话内每个成员。扇出并行化的收益与**语义风险**——同一会话内消息顺序！消息序号 seq 单调（见 `CONTEXT.md`「消息序号」），并发发送会不会乱序
3. **pinning 是否致命**：票 03 给的 decorator 内部锁 / Tomcat WS 端点锁，在虚拟线程下 pin 的时长与并发量级——是「pin 了无害」还是「必须绕」
4. **与票 06 对齐**：4 个走 WS 发送的消费者的线程模型裁决不能与本节打架

结论落 spec 的「长连接」节。

## Answer

**一句话：三处全部「不改代码」——扇出保持串行（上限问题已随[票 06](06-grill-rocketmq-consumer-model.md) 消费池 VT 化消失，并行化只买单消息尾延迟、还要赔一套有界机制）；握手 GETDEL 属「并发上限由平台线程数决定」的形态但已被[票 05](05-grill-framework-flip-and-backpressure.md) 的开关吸收；pinning 落「无害」档、不新增并发上限，兜底 = decorator 5s/512KB + sweep + 容器 idle。**（2026-09-21，用户「都按你推荐的」）

### ① 握手线程（票面问 1）：已被票 05 吸收，无独立改造项

事实链：`beforeHandshake`（`ChatWsHandshakeInterceptor.java:28-43`）在处理 HTTP Upgrade 请求的 Tomcat handler 线程上跑（默认 `maxThreads=200` 平台线程，全仓零 `server.tomcat.*` 配置）——这正是[票 05](05-grill-framework-flip-and-backpressure.md) 落地面**唯一真实生效**的点（Tomcat Connector `setExecutor(VirtualThreadExecutor)`），翻开关后握手自动变 VT。`wsTicketPort.take`（`WsTicketPort.java:36`）走 Redisson `getAndDelete` = `CompletableFuture.get`（AQS park），**不 pin**（票 03 §0 行 6）。

故票面问 1 的答案：**是**——这属于「并发上限由平台线程数决定」的形态（现状 200 条平台线程确实在握手这条 3s+ 的阻塞调用上排队），但**无独立改造项**，spec 记「已被票 05 开关吸收」。

附带隐患（spec 记**一条备注**、不设改造项）：全仓**零 Redisson 超时配置**（`application.yml` 无 `redisson:` 段、无 `RedissonConfig`），默认 `timeout=3000ms` + `retryAttempts=3` × `retryInterval=1500ms` ⇒ 单次 GETDEL 最坏阻塞 ~10s+。属 Redis 客户端配置面、与线程模型无关；VT 化后也不再占平台线程。

### ② 扇出并行化（票面问 2）：保持串行，不改造

先修票面：**3 个**走 WS 发送的消费者（非 4 个，[票 06](06-grill-rocketmq-consumer-model.md) 事实自核 1）——`ChatPushConsumer` / `ChatReadPushConsumer` / `ChatRecallPushConsumer`；`NotificationBroadcastConsumer` 不走 WS。它们的消费池已随票 06 整体换 VT。

**裁决：`ChatWsPushService.broadcast`（:59-72，全仓唯一 WS 扇出点）保持串行 for 循环（查库 listRoomUsers → 内存注册表取 session → 逐成员同步 `sendMessage`），不并行化。**判据按选点规则判读精化①的「上限由谁决定」：

- 票 06 之后扇出宿主已是消费侧虚拟线程——单条广播串行占住的是**一条 VT**，不占任何平台线程上限；其余消息由其余 VT 处理。「消除扇出串行化」这个选点触发条件指向的是「扇出发生在平台线程上、串行直接吃掉并发上限」的形态（如超时链路 5 处手写扇出），这里**不成立**。
- 并行化的剩余收益只有**单消息尾延迟**：同一广播里一个慢客户端卡住写（decorator 5s 观察窗内），排它后面的成员被拖延。这是尾延迟问题，不是上限问题。
- 并行化的代价：全局并发 = 消费并发 × 扇出宽度（群上限 500），无信号量即无界；慢客户端阻塞写吃载体（补偿型，池 cap 256）⇒ 要么破票 05/06「不加新信号量」口径，要么引入一套新的有界池机制。收益/代价不换。

⚠️ **[票 06](06-grill-rocketmq-consumer-model.md) 的倾向（「并行化不破顺序语义」）成立且被采纳**——顺序从来不是反对手感的理由：基线是同会话内消息顺序**今天就不保证**（票 06 约束 3：20 个并发消费线程本可乱序处理同一队列，客户端靠 `(roomId, seq)` 判重 + 补拉合流，见 `CONTEXT.md`「消息序号」「增量补拉」词条）；且同一条消息推给不同成员之间无先后问题（每用户单端：`ChatConnectionRegistry.register` put 覆盖 + 互踢 kick，每广播每成员至多 1 帧）。**否掉并行化的是上限/收益判据，不是顺序**——票面问 2 的「并发发送会不会乱序」按票 06 约束 3 以现状为基线记，不当新风险、不把顺序升级为硬保证（那属 map Out of scope 第 5 条的语义变更）。

### ③ pinning 致命性（票面问 3）：落「pin 了无害」档，「必须绕」= 否

三段事实（票 03 §2.2，一手 jar 字节码双扫）：

1. Spring `ConcurrentWebSocketSessionDecorator` **不 pin**——全类零 monitor，`flushLock`/`closeLock` 是 `ReentrantLock.tryLock()`（拿不到立刻返回进缓冲重试，不是在锁上等）；
2. Tomcat `WsRemoteEndpointImplBase` **不 pin**——socket 写在 `messagePartLock` 之外（源码注释明写「Actual write has to be outside sync block」）；
3. 唯一会等的：慢客户端（TCP 发送缓冲写满）触发 Tomcat NIO 阻塞写 `synchronized(writeLock){ writeLock.wait(timeout) }`——**补偿型**：载体被占但 ForkJoinPool 扩池补偿，吞吐不垮；每个卡住的会话吃一条载体，池 cap `max(parallelism, 256)`，**JFR 看不见这类事件**（`jdk.VirtualThreadPinned` 是「park 时无法卸载」的子集）。

致命性判定（判据：类别 × 并发量 × 时长，票 03 §3）：需要 **256 个并发卡住的写同时存在**第 257 个才开始排队，而收敛机制现成——decorator 5s/512KB 越限主动 `close(SESSION_NOT_RELIABLE)`（**负反馈：越慢的会话越先被断**，卡住的写攒不到 256）+ `ChatSessionRegistry` sweep 60s 判死 + 容器 `maxSessionIdleTimeout` 90s，三层兜底。⇒ **pin 了无害**，不构成「必须绕」。

**配套裁决：spec 定口径「不新增并发上限/信号量」**——慢客户端兜底 = decorator 5s/512KB 断开 + sweep 判死 + 容器 idle，全为既有机制，本 effort 不加任何新防线。（sweep 守护线程 `chat-ws-session-sweep` 与心跳应答：并发度 1、工作主体非阻塞 I/O，按选点规则「不用」，spec 记一句即可。）

### ④ 与票 06 对齐（票面问 4）：三条约束照单接收

1. **约束 1（扇出若并行化必须有界）**：裁决不并行化 ⇒ 本票不触发；文字保留进可回收触发条件（见下）。
2. **约束 2（不得改变「漏推合法、补拉兜底」语义）**：不动——`ChatWsPusher.push` 吞异常 warn（:36-38）、`broadcast` 只推 `inRoom()` 且在线的成员、推送失败不重试（消费者永不 RECONSUME_LATER），全部保持。
3. **约束 3（顺序基线 = 今天就不保证）**：照抄进 spec「长连接」节，与 ② 的判读一致。

### 可回收触发条件

1. **引入扇出并行化或下行有状态回放** ⇒ 票 06 约束 1 复活（并行度必须有界），并按 256 载体分母重判慢客户端阻塞写的承压面。
2. **握手 P99 恶化归因到 Redisson 默认重试叠加（~10s+）** ⇒ 配 `timeout`/`retryAttempts` 即可，配置面单点改动，与本 effort 无耦合。

### 转下游（[票 11](11-task-write-spec.md) 落 spec「长连接」节的口径）

- 裁决三条：**握手不设改造项**（已被票 05 开关吸收 + Redisson 超时备注一条）；**扇出保持串行不设改造项**（否决理由 = 上限已吸收 + 尾延迟收益不抵有界机制代价，顺序倾向照记）；**不新增并发上限/信号量**（兜底三层既有机制点名）。
- spec 只写**裁决**不搬实现步骤（与票 06 落稿口径同）；票面问 2 的「消息序号 seq 单调」以 `CONTEXT.md`「消息序号」「投递序号」词条为准，WS 下行带 seq、客户端判重合流是既有语义，本票不改。
