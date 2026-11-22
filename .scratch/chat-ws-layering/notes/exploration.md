# Chat WS 推送链路探查笔记（只读探查，供重构 spec 拆票用）

探查日期：2026-11-20。Repo root: `Z:/better_school`，后端 Maven 多模块 `Z:/better_school/backend/better-school`。

## 1. 文件清单（绝对路径 + 一句话角色）

### 1.1 trigger 层 WS 推送机器（spec 要求迁往 infrastructure 的部分）

| 文件 | 角色 |
|---|---|
| `Z:/better_school/backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/ws/WsPushRouter.java` | 推送路由器：本地注册表命中即推 → `IWsRoutePort.lookupEndpoint` 查 Redis 路由 → 非 `selfEndpoint` 则经 `IWsPushBroadcastPort.publish` 定向发布到目标实例频道；subscriber=0/发布失败/指向自己但本地 miss → `revokeByEndpoint` 清理路由后放弃（尽力而为） |
| `Z:/better_school/backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/ws/ChatConnectionRegistry.java` | chat 连接注册表：继承 `AbstractSingleSessionConnectionRegistry`，`kickSession` = `pusher.kick(oldSession)`（KICK 帧 + close 4001）。互踢骨架的 chat 绑定 |
| `Z:/better_school/backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/ws/ChatSessionRegistry.java` | chat 会话存活注册表：继承 `AbstractHeartbeatSessionRegistry`，判死回调 = `connectionRegistry.unregister` + `pusher.closeQuietly(GOING_AWAY)`；清扫线程名 `chat-ws-session-sweep` |
| `Z:/better_school/backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/ws/ChatWsPusher.java` | chat 帧写出器：继承 `AbstractWsJsonPusher<ChatWsFrame>`，绑定 kickFrame（KICK + `{reason: SESSION_TAKEN_OVER}`）与帧类型名 |
| `Z:/better_school/backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/ws/WsPushSubscriber.java` | 本实例推送频道订阅器：`InitializingBean/DisposableBean`，启动订阅 `bs:ws-push:{selfEndpoint}`（`enabled()` 才订阅），收到广播后本机注册表二次确认，miss → `revokeByEndpoint` 清理自己名下条目 |
| `Z:/better_school/backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/ws/WsPushBroadcast.java` | 跨实例广播消息体 record `(userId, ChatWsFrame)`，JSON 文本负载 |
| `Z:/better_school/backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/ws/WsRouteProperties.java` | 路由配置：`@Value("${better-school.ws.route.self-endpoint:}")`，留空 = 单机形态（`enabled()==false`） |
| `Z:/better_school/backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/ws/ChatWsPushService.java` | WS 推送服务（MSG/READ/RECALL 落地侧）：**注入了 `IChatRepository`**（trigger 直接摸仓储！）按 `chatRepository.listRoomMembers(roomId)` 扇出，MSG/READ 排除发起者（excludeUserId），RECALL 不排除；逐成员 `wsPushRouter.deliver`。**这就是要上提进 case 的「成员扇出决策逻辑」** |

### 1.2 support 骨架抽象类（4 个，`ws/support` 包，也要迁 infra）

| 文件 | 角色 |
|---|---|
| `...\trigger\ws\support\AbstractWsTicketHandshakeInterceptor.java` | 票据握手拦截骨架：解析 query `ticket` → 抽象 `authenticateUserId(ticket)`（null 则 401 拒绝）→ 写入 `WsHandshakeAttributes.ATTR_USER_ID` |
| `...\trigger\ws\support\AbstractWsJsonPusher.java` | 帧写出器骨架：`decorate` = `ConcurrentWebSocketSessionDecorator`（sendTimeLimit 5s / bufferSizeLimit 512KB，并发写保护）；`push` JSON 序列化 + 失败吞掉；`kick` = KICK 帧 + close(4001)；`closeQuietly`；抽象 `kickFrame()` / `frameTypeName()` |
| `...\trigger\ws\support\AbstractSingleSessionConnectionRegistry.java` | 单设备连接注册表骨架：`ConcurrentHashMap<userId, session>`；`register` put 占位并互踢旧连接（抽象 `kickSession`）；`unregister` 两参 `remove(userId, session)` 防迟到 close 误删；`get`；`snapshot()`（注释说给"审核派单信号广播"票 11 用——**当前无调用方，疑似预留/死代码**） |
| `...\trigger\ws\support\AbstractHeartbeatSessionRegistry.java` | 心跳判死骨架：`track/touch/untrack`（任意入站帧刷活跃时间），30s 周期清扫，60s 无入站判死（`DEAD_THRESHOLD_MS=60_000` public 供测试），判死回调抽象 `onSessionDead`；`sweep(now, threshold)` 时钟可注入 |

support 包另有 2 个共享常量类（同样属连接层骨架，迁移时需跟随）：
- `...\trigger\ws\support\WsCloseCodes.java`：`CLOSE_CODE_TAKEN_OVER=4001`、`KICK_REASON_SESSION_TAKEN_OVER`
- `...\trigger\ws\support\WsHandshakeAttributes.java`：`ATTR_USER_ID="wsUserId"`、`ATTR_DECORATED_SESSION="wsDecoratedSession"`

### 1.3 trigger 层 WS 连接处理（spec 要求留在 trigger 的部分）

| 文件 | 角色 |
|---|---|
| `...\trigger\ws\ChatWsHandler.java` | WS 连接处理器（spec §3）：建连 = decorate → `sessionRegistry.track` → `connectionRegistry.register`（占位即互踢）→ `wsRoutePort.register(userId, token=端点|sessionId)`；入站帧 = touch + renewRoute + PING→PONG；断连 = untrack → 两参 unregister → token CAS revoke。**注入 IWsRoutePort（路由注册/续期/注销应属"连接注册端口"职责）** |
| `...\trigger\ws\ChatWsHandshakeInterceptor.java` | chat 握手拦截：继承骨架，`authenticateUserId` = `IWsTicketPort.take(ticket)` |
| `...\trigger\ws\ChatWsProtocol.java` | 端点常量：`ENDPOINT="/ws/chat"`，属性键/关闭码常量委托 support 类 |
| `...\trigger\ws\ChatWsFrame.java` | 协议信封 `{type, roomId?, seq?, payload?, ts}` |
| `...\trigger\ws\ChatWsFrameType.java` | 帧类型枚举：PING/PONG/MSG/READ/RECALL/NOTIF/ORDER/KICK |

### 1.4 领域端口（bs-domain，chat）

| 文件 | 角色 | infra 实现 | 使用方 |
|---|---|---|---|
| `Z:/better_school/backend/better-school/bs-domain/src/main/java/com/betterschool/domain/chat/adapter/port/IWsRoutePort.java` | WS 连接路由表端口：`register/renew/revoke/revokeByEndpoint/lookupEndpoint`，token=端点\|sessionId | `bs-infrastructure\...\adapter\port\chat\WsRoutePort.java`（Redisson RBucket，`bs:ws-route:{userId}`，TTL 90s，revoke 用 compareAndSet(token,null) 原子删） | ChatWsHandler、WsPushRouter、WsPushSubscriber |
| `Z:/better_school/backend/better-school/bs-domain/src/main/java/com/betterschool/domain/chat/adapter/port/IWsPushBroadcastPort.java` | 跨实例推送 pub/sub 定向频道端口：`publish/subscribe/unsubscribe`（subscribe 返回 listenerId） | `bs-infrastructure\...\adapter\port\chat\WsPushBroadcastPort.java`（Redisson RTopic，`bs:ws-push:{endpoint}`，StringCodec 原样 JSON） | WsPushRouter、WsPushSubscriber |
| `Z:/better_school/backend/better-school/bs-domain/src/main/java/com/betterschool/domain/chat/adapter/port/IWsTicketPort.java` | WS 一次性票据端口：`issue/take`（getAndDelete 原子取删） | `bs-infrastructure\...\adapter\port\chat\WsTicketPort.java`（`bs:ws-ticket:{ticket}`，TTL 60s） | ChatWsHandshakeInterceptor（take）、`bs-trigger\...\http\ChatController.java`（issue，POST /ws-ticket） |

同目录其他 chat 端口：`ICardRefQueryPort.java`、`IUserQueryPort.java`；chat 仓储：`bs-domain\...\chat\adapter\repository\IChatRepository.java`（infra 实现 `bs-infrastructure\...\adapter\repository\chat\ChatRepository.java`）。

### 1.5 推送 MQ 消费者（bs-trigger\listener，全部 `RocketMQListener<String>`，try/catch 吞错不重试，推送=加速路径）

| 文件 | topic / group | 现状业务逻辑 |
|---|---|---|
| `ChatPushConsumer.java` | `bs-chat-push` / bs-chat-push-group | 反序列化 `ChatMessagePushEvent` → `ChatWsPushService.pushMessage`。**已算薄**（反序列化+调 service），fanout 在 service 里 |
| `ChatReadPushConsumer.java` | `bs-chat-read` / bs-chat-read-group | 反序列化 `ChatReadPushEvent` → `pushRead`（排除 reader 本人，逻辑在 service） |
| `ChatRecallPushConsumer.java` | `bs-chat-recall` / bs-chat-recall-group | 反序列化 `ChatRecallPushEvent` → `pushRecall`（全员，不排除） |
| `NotifyPushConsumer.java` | `bs-notify-push` / bs-notify-push-group | 反序列化 `NotifyPushMessage` → **消费者内部自己组 NOTIF 帧**（scope/seq/type/title/content/refType/refId Map payload）→ 直接 `wsPushRouter.deliver(userId, frame)`。单收件人，无扇出/排除。**绕过了 ChatWsPushService，直接依赖 WsPushRouter**；注释里还残留旧方案字样「HTTP 转发」 |
| `OrderPushConsumer.java` | `bs-order-push` / bs-order-push-group | 反序列化 `ErrandOrderPushEvent` → **`errandOrderCase.loadOrder(orderId)`** 取双方 → publisherId + takerId 各 `wsPushRouter.deliver` 一条 ORDER 帧（`{orderId,status}`）。草稿态不推的注释口径实际由「双方为空」体现（DELETED/DRAFT 无双方）。**同时依赖 case + WsPushRouter，消费者含投递对象决策** |
| 相关但不含 WS 推送 | | `NotifyEventConsumer.java`（通知域唯一写入口：落 notification + 按通道写 outbox，含 `savePushDelivery(NotifyPushMessage)`/`saveChatDelivery`）、`NotifyChatConsumer.java`（→ `SystemMessageCase.deliver`，落库类，失败上抛重试）、`NotificationBroadcastConsumer.java`（公告落库） |

### 1.6 WS 端点注册与配置

- `Z:/better_school/backend/better-school/bs-app/src/main/java/com/betterschool/config/WebSocketConfig.java`：`@EnableWebSocket`，`registry.addHandler(chatWsHandler, ChatWsProtocol.ENDPOINT).addInterceptors(chatWsHandshakeInterceptor)`；`ServletServerContainerFactoryBean` 容器空闲 90s 兜底。
- `Z:/better_school/backend/better-school/bs-app/src/main/java/com/betterschool/config/SecurityConfig.java:50`：`/ws/chat` 在 Spring Security 白名单 permitAll。
- `Z:/better_school/backend/better-school/bs-app/src/main/resources/application.yml:90-97`：`better-school.ws.route.self-endpoint: ""`（留空=单机形态），注释说明多实例用法。
- `Z:/better_school/backend/better-school/bs-types/src/main/java/com/betterschool/types/common/Constants.java`：`WS_TICKET_KEY="bs:ws-ticket:"`（TTL 60s）、`WS_ROUTE_KEY="bs:ws-route:"`（TTL 90s）、`WS_PUSH_CHANNEL_PREFIX="bs:ws-push:"`；MQ topic：`CHAT_PUSH_TOPIC="bs-chat-push"`、`CHAT_READ_TOPIC="bs-chat-read"`、`CHAT_RECALL_TOPIC="bs-chat-recall"`、`NOTIFY_PUSH_TOPIC="bs-notify-push"`、`ORDER_PUSH_TOPIC="bs-order-push"`、`NOTIFY_EVENT_TOPIC`/`NOTIFY_CHAT_TOPIC`。
- MQ 监听容器：`bs-app\...\config\RocketMQConsumerConfig.java` + `VirtualThreadRocketMQListenerContainerRegistrar.java`（虚拟线程接管全部 listener 容器，与 WS 无耦合）。

### 1.7 域事件 VO（消费反序列化目标，bs-domain）

`bs-domain\...\chat\model\valobj\ChatMessagePushEvent.java`、`ChatReadPushEvent.java`、`ChatRecallPushEvent.java`；`bs-domain\...\notification\model\valobj\NotifyPushMessage.java`、`NotifyChatMessage.java`；`bs-domain\...\errand\model\valobj\ErrandOrderPushEvent.java`。

## 2. 当前依赖图

### 2.1 模块依赖（pom）

```
bs-types   → （无内部依赖）
bs-api     → bs-types
bs-domain  → bs-types
bs-case    → bs-domain
bs-infrastructure → bs-domain          （端口实现层，注意：不依赖 trigger）
bs-trigger → bs-api, bs-domain, bs-case（不依赖 infrastructure —— 靠 app 装配时 Spring 注入实现）
bs-app     → bs-trigger, bs-infrastructure, bs-case, bs-api, bs-domain, bs-types（组装根）
```

### 2.2 类级调用图（chat 推送链）

```
MQ topic bs-chat-push/bs-chat-read/bs-chat-recall
  → ChatPushConsumer / ChatReadPushConsumer / ChatRecallPushConsumer   [trigger.listener]
      → ChatWsPushService                                             [trigger.ws]
          ├── IChatRepository.listRoomMembers(roomId)                 [domain 仓储，trigger 直接注入]
          └── WsPushRouter.deliver(userId, ChatWsFrame)               [trigger.ws]
                ├── ChatConnectionRegistry.get（本地命中）
                ├── ChatWsPusher.push（并发写保护装饰 session）
                ├── IWsRoutePort.lookupEndpoint / revokeByEndpoint     [infra WsRoutePort: Redis bs:ws-route:{uid}]
                └── IWsPushBroadcastPort.publish                       [infra WsPushBroadcastPort: Redisson RTopic bs:ws-push:{endpoint}]

MQ topic bs-notify-push → NotifyPushConsumer → WsPushRouter.deliver（直接，不走 ChatWsPushService）
MQ topic bs-order-push  → OrderPushConsumer → ErrandOrderCase.loadOrder + WsPushRouter.deliver（直接）

WS 连接生命周期：
  POST /ws-ticket (ChatController → IWsTicketPort.issue)
  → 握手 ChatWsHandshakeInterceptor（→ IWsTicketPort.take）
  → ChatWsHandler.afterConnectionEstablished
        ChatWsPusher.decorate → ChatSessionRegistry.track → ChatConnectionRegistry.register（占位即互踢：旧连接 KICK + close 4001）
        → IWsRoutePort.register(userId, token=endpoint|sessionId)
  → handleTextMessage：sessionRegistry.touch + IWsRoutePort.renew + PING→PONG
  → afterConnectionClosed：untrack → 两参 unregister → IWsRoutePort.revoke（token CAS）
  → 心跳判死：ChatSessionRegistry.sweep（30s 周期，60s 阈值）→ connectionRegistry.unregister + closeQuietly(GOING_AWAY)
  → 跨实例：WsPushSubscriber 订阅 bs:ws-push:{selfEndpoint}，收到帧本地二次确认后推，miss 则 revokeByEndpoint
```

### 2.3 关键点

- `IChatRepository` 在 trigger 的注入点**只有一处**：`ChatWsPushService`（用了 `listRoomMembers`）。这是 spec 要求把扇出决策上提到 case 的直接动因（trigger 不应摸仓储）。
- 连接 handler 今日注册/注销 session 是**直接调用本地注册表**（`ChatSessionRegistry.track` / `ChatConnectionRegistry.register/unregister`），路由表部分走 `IWsRoutePort`。spec 的「连接注册端口」要把这两件事统一到端口后面。
- `WsPushRouter`/`WsPushSubscriber` 是两个直接消费两个 domain 端口（IWsRoutePort、IWsPushBroadcastPort）+ infra 骨架的"交付机器"，整体应随端口下沉 infra；新端口 `IWsPushPort` 是它们对外的唯一入口（deliver(userId, frame) 语义）。
- `NotifyPushConsumer`/`OrderPushConsumer` 目前**跨过 service 直连 WsPushRouter**，是薄化改造的主要对象。

## 3. 既有测试

| 路径 | 内容 |
|---|---|
| `Z:/better_school/backend/better-school/bs-app/src/test/java/com/betterschool/unit/chat/ChatConnectionRegistryTest.java` | 连接注册表互斥规则纯单测（票 04）：新连接不踢、同账号接管踢旧（verify pusher.kick）、两参注销防迟到 close 误删、未知注销返回 false、关后重连。mock `ChatWsPusher`，直接 `new ChatConnectionRegistry(pusher)`。**引用 trigger 内部类，spec 要求迁 infra 不改内容** |
| `Z:/better_school/backend/better-school/bs-app/src/test/java/com/betterschool/unit/chat/ChatSessionRegistryTest.java` | 会话存活注册表纯单测：touch 刷新推迟判死、61s 判死 → closeQuietly(GOING_AWAY)+unregister、untrack 后不再清扫、重复清扫幂等。mock pusher/connectionRegistry，注入时钟 `sweep(now, threshold)` |
| `Z:/better_school/backend/better-school/bs-app/src/test/java/com/betterschool/integration/chat/ChatWsIntegrationTest.java` | chat WS 全链路集成测试（票 07）：真 `StandardWebSocketClient` 连 RANDOM_PORT；**推送触发不过 MQ，直接 `@Resource` 注入 ChatPushConsumer/ChatReadPushConsumer/ChatRecallPushConsumer 调 `onMessage`**。断言：握手 401（未知/过期/无票据）、票据一次性、互踢 KICK+close(4001)、PING→PONG、`sessionRegistry.sweep` 手动判死、MSG 排除发送者+判重键、READ 水位推 reader 之外、断线不补推、RECALL 轻推全员 |
| `Z:/better_school/backend/better-school/bs-app/src/test/java/com/betterschool/integration/chat/NotifyChannelIntegrationTest.java` | 直调 `NotifyEventConsumer.onMessage` + `NotifyChatConsumer.onMessage`（消费者直调模式范例）；断言 outbox 落 `bs-notify-push`/`bs-notify-chat` payload、幂等不重复、系统消息落库 |
| `Z:/better_school/backend/better-school/bs-app/src/test/java/com/betterschool/integration/errand/ErrandOrderPushIntegrationTest.java` | ORDER 帧写侧测试：状态迁移逐点断言 outbox `bs-order-push` 有 payload（**不直接调 OrderPushConsumer，也不碰 WsPushRouter**） |
| `Z:/better_school/backend/better-school/bs-app/src/test/java/com/betterschool/integration/support/CommunityApiTestBase.java` | 集成测试基座：`@SpringBootTest(RANDOM_PORT)` + `@ActiveProfiles("test")`，全上下文装配（真 Redis/MySQL），提供 registerAndLogin、redissonClient、outboxPayloads 等 |
| `Z:/better_school/backend/better-school/bs-app/src/test/java/com/betterschool/integration/chat/ChatSendPipelineIntegrationTest.java` / `ChatReadPathIntegrationTest.java` / `ChatRecallReportIntegrationTest.java` / `ChatPersistenceIntegrationTest.java` / `ChatRoomSourceIntegrationTest.java` | chat 其余链路集成测试（发送管线/已读/撤回/持久化/会话来源），与 WS 推送链有 outbox 事件衔接 |

注意：**目前没有 WsPushRouter / WsPushSubscriber / WsRoutePort / WsPushBroadcastPort 的任何单测**（infra 侧仅 merge/tx 有测试）。多实例路由只被 docs 覆盖，无测试背书。

## 4. case 层约定（bs-case，`com.betterschool.usecase.<域>`）

- 包结构：`usecase\errand\ErrandOrderCase`、`usecase\notification\SystemMessageCase`、`usecase\community\*Case`、`usecase\trade\*Case`、`usecase\timeout\*Case`。命名 `XxxCase`。
- 风格：`@Slf4j @Service @RequiredArgsConstructor`，构造注入 domain service 接口 / domain 端口；**跨域组合编排，本层不做事务**（各域服务各自事务原子，一致性靠幂等+重试，见 ErrandOrderCase 类注释）。
- 范例（新推送 case 可对标）：`SystemMessageCase` —— 单一公开方法 `deliver(NotifyChatMessage)`，消费侧落地：前置守卫（系统账号缺失跳过）、域服务调用、幂等锚（client_msg_id=NOTIFY:<id>）、结构化日志带业务 ID。
- case 依赖 domain 的 service 接口（如 IChatSendService）与端口（如 IUserQueryPort），**不依赖 trigger**。
- 消费者调 case 的既有模式：`NotifyChatConsumer → SystemMessageCase.deliver`（失败上抛 broker 重试）与 `OrderPushConsumer → ErrandOrderCase.loadOrder`（读模型查询）。

## 5. infra 约定（bs-infrastructure，`com.betterschool.infrastructure.adapter.*`）

- 端口实现命名：领域端口 `IWsRoutePort` → `WsRoutePort`（去 I 前缀，同名），包 `adapter\port\chat\`；仓储实现在 `adapter\repository\chat\`。统一 `@Component @RequiredArgsConstructor`，构造注入 `RedissonClient`。
- Redis 使用模式：Redisson `RBucket`（键前缀来自 `bs-types` Constants，TTL `Duration.ofSeconds`；revoke 用 `compareAndSet(token, null)` 原子删）、`RTopic`（`getTopic(prefix+endpoint, StringCodec.INSTANCE)` 原样 JSON 字符串，避免全局 codec 对 Object payload 类型注入）、票据 `getAndDelete`。
- 注释风格：说明为什么这么做（原子性理由、竞态后果有界化、fire-and-forget 语义）。
- `WsPushBroadcastPort`/`WsRoutePort` 已经在 infra —— 迁移时主体是把 trigger 的 router/subscriber/骨架搬过来并新建 `IWsPushPort` 领域端口 + infra 适配（把 WsPushRouter/WsPushSubscriber 组合收进一个适配器）。

## 6. 文档

### docs/api/chat.md「WS 长连接（票 04，spec §3）」节（L66-88）

- `GET /ws/chat?ticket=`：裸 WebSocket，先 POST /ws-ticket 换 60s 一次性票据，拦截器原子取删，401 拒绝；连接期内不再鉴权；`/ws/chat` 在 Security 白名单。
- 连接注册表 Map<userId, session>：单设备互踢（KICK + close 4001，两参注销防误删）；心跳 25s PING / 60s 判死（close 1001）；服务端无业务路由无 ACK。
- **多实例推送路由段（L76）**：握手注册 `bs:ws-route:{userId}=端点|sessionId`（TTL 90s，入站续期，CAS 注销）；每实例订阅 `bs:ws-push:{端点标识}`；推送消费方经 **WsPushRouter**——本地优先→路由不是自己则定向发布（fire-and-forget，目标实例二次确认）；订阅者 0/本地 miss/发布失败清理路由后放弃；`better-school.ws.route.self-endpoint` 留空=单机形态。
- 推送失败不重试，漏推合法，`GET /timeline` 补拉 + `(roomId, seq)` 判重合流。
- 协议信封表：PING/PONG/MSG（排除发送者）/READ（payload={userId,readSeq}）/RECALL/ORDER/KICK + NOTIF（见 notify-channels.md）。
- L18/L54/L64：READ/MSG/RECALL outbox destination 与载荷定义，「WS 推送归票 04」。
- **重构后需更新**：L76 的 WsPushRouter 叙述（类将迁 infra/换成 IWsPushPort 表述）、L73 连接注册表归属。

### docs/api/notify-channels.md

- BANNER 通道（L47-54）：`bs-notify-push` → `NotifyPushConsumer` → 「复用 ChatConnectionRegistry 推 WS NOTIF 帧」（表述已过时：实际经 WsPushRouter.deliver）。
- ORDER 帧（L56-60）：`bs-order-push` → `OrderPushConsumer` → WS ORDER 帧推双方。
- 关键文件表（L62-73）列出 NotifyEventConsumer/NotifyChatConsumer/NotifyPushConsumer/OrderPushConsumer/ChatWsFrameType —— 消费者薄化后路径语义要改。

### CONTEXT.md 词汇表（Z:/better_school/CONTEXT.md）

- **连接票据**（L119-121）：60s 一次性凭证，REST 签发，JWT 不进 URL。
- **心跳**（L123-125）：25s PING/PONG，任意入站帧刷新活跃，超时判死。
- **互踢**（L127-129）：新连接接管注册表，旧连接 KICK + close 4001，停止自动重连。
- **消息类型/撤回/投递序号/增量补拉/发送幂等**（L107-141）：与推送判重键 `(roomId, seq)`、漏推合法口径相关。

### 其他提及

- `docs/db/outbox.md:13`：destination 取值枚举含 bs-chat-push/bs-notify-push/bs-order-push/bs-notify-chat/bs-notify-event。
- `docs/api/chat.md` L27：`POST /ws-ticket` 归票 04。

## 7. 意外发现 / 注意事项

1. **`snapshot()` 无调用方**：`AbstractSingleSessionConnectionRegistry.snapshot()`（注释说给"审核派单信号广播（票 11）遍历推送"用）当前全仓无调用。迁移时可保留或标注预留。
2. **ReviewWsProtocol 已不存在**：`WsHandshakeAttributes`/`WsCloseCodes` 注释称「chat 与 review 两端点共用」「ChatWsProtocol / ReviewWsProtocol」，但仓库里没有任何 review WS 端点类（可能已删或未落地）——迁移注释时措辞要改。
3. **NotifyPushConsumer 注释陈旧**：写「经 WsPushRouter 投递（本地优先 → Redis 路由 → **HTTP 转发**）」，实际方案已是 pub/sub 定向频道，无 HTTP 转发；OrderPushConsumer 同样残留该句。薄化时顺带修正。
4. **notify-channels.md L50 表述失真**：「NotifyPushConsumer 复用 ChatConnectionRegistry 推 WS NOTIF 帧」——实际是 WsPushRouter.deliver（含跨实例）。
5. **消费者直调是既有测试惯例**：ChatWsIntegrationTest 与 NotifyChannelIntegrationTest 都直接注入 consumer 调 `onMessage` 绕过 MQ——消费者薄化为「反序列化+调 case」后这些测试只需替换被调类，模式可延续。
6. **没有任何多实例路由测试**：WsPushRouter/WsPushSubscriber/WsRoutePort/WsPushBroadcastPort 零测试覆盖；WxRoute 的 CAS/清理语义只靠注释与文档。拆票时值得补 infra 层单测（Redisson 可 mock 或 testcontainers）。
7. **配置属性唯一入口**：`better-school.ws.route.self-endpoint`（仅此一项，无其他 `better-school.ws.route.*` 键）由 trigger 的 `WsRouteProperties` 读取——迁 infra 时配置类应随迁。
8. **trigger→domain 仓储直连仅此一处**：全 trigger 只有 `ChatWsPushService` 注入 `IChatRepository`，重构后 trigger 将完全不摸 chat 仓储（ChatController 注入的是 IWsTicketPort 端口，合规）。
9. **OrderPushConsumer 已有 case 依赖**：`ErrandOrderCase.loadOrder`（L230 委托 `errandOrderService.loadOrder`）——「双方投递决策上提到 case」可自然落在既有 ErrandOrderCase 或新 case 方法。
10. **消息模型不对称**：NotifyPushMessage 的 refType/refId 在消费侧做 null→"" 归一化（NotifyPushConsumer L45-46）——薄化后该组帧逻辑应随 case/infra 收口，别丢。
11. **MQ 消费失败语义分两派**：推送类消费者（chat/notify/order push）吞错不重试（加速路径）；落库类（NotifyEvent/NotifyChat/Broadcast）上抛 broker 重试（持久化义务）——新薄消费者必须保持推送派吞错语义。
