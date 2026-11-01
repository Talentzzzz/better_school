# 通知拆域 + chat 域改造

Type: task
Status: resolved
Blocked by:

## Question

一次做完两件事（都动 chat 的包结构，分开做会改两遍）。

**1. 通知升为独立域**

`chat/service/notice/*`、`INotificationRepository`、`NotificationEntity`/`NotificationScopeVO`/`NotificationTypeVO`/`NotificationBroadcastMessage` 迁到 `domain/notification/{service,adapter/repository,model}`；`INoticeService` → `INotificationService`；`dao/chat/INotificationDao` 与 `NotificationPO` 迁到 `dao/notification/`，`NotificationMapper.xml` 的 namespace 跟着改。**表 `notification`/`notification_seq`/`notification_read` 与 HTTP 路径 `/api/chat/notifications` 都不动。**

- `NotificationRepository:26` 是 infra 层唯一跨域仓储引用（`IUserRepository.listAllUserIds`，广播在一个事务里逐用户落行）：改成 notification 域自己的 port，实现放 `bs-infrastructure/.../adapter/port/notification/`。
- 改 import：10 个 case 类（CommentLike / CommentReview / PostLike / PostPublish / Credit / ErrandOrder / MarketingNotice / Report / Review / TradePublish）、`NotificationController`、`NotificationBroadcastConsumer`（顺手改成经 service，与 case 层口径一致）、`NotificationReadModelIntegrationTest`、`MarketingNoticeIntegrationTest`。

**2. chat 域改造**

- 跨域只读（`ChatSendServiceImpl:47-50` 的 user/post/product/errand、`ChatQueryServiceImpl:40`、`ChatMessageFanoutService:37` 的 user）→ chat 自有 port：用户（存在性 + 批量昵称头像）、卡片引用（帖子/商品/跑腿是否失效）。**判据留在 chat 侧，port 只取状态。**
- 4 组事务按「原样搬」搬成 `IChatRepository` 方法（仓储内 `TransactionTemplate`，REQUIRED）：`ChatSendServiceImpl:76` 建会话+成员、`:119` 委托 `ChatMessageFanoutService.deliver` 的取号+落消息+扇出时间线+清 hidden+回 last_msg+outbox、`ChatReadServiceImpl:41` 水位线+READ outbox、`ChatRecallServiceImpl:65` 撤回+RECALL outbox。
- 撞 `uk_client_msg` 的两条时序（幂等快路径 / 回滚后回查收敛）行为不变，回查走同一个 `IChatRepository`。

判据：bs 侧 `mvn -o clean package -DskipTests` 过；`ChatSendPipelineIntegrationTest`、`ChatPersistenceIntegrationTest`、`NotificationReadModelIntegrationTest`、`MarketingNoticeIntegrationTest` 绿；`domain/chat` 不 import 他域仓储、不出现 `TransactionTemplate`；`bs-infrastructure` 下不再有仓储实现注入他域仓储。

## Answer

两件事一次做完（都动 chat 的包结构）。

**1. 通知独立成域（`domain/notification`）**

- `model/entity` + `model/valobj` + `service` + `adapter/repository` 全套迁入；`INoticeService`/`NoticeServiceImpl` → `INotificationService`/`NotificationServiceImpl`（`api.INotificationService` 是另一层的同名契约，`NotificationController` 用全限定名注入域服务，先例 `SearchController`）。
- `dao/chat/INotificationDao` 与 `dao/chat/po/NotificationPO` → `dao/notification/`（`@MapperScan("com.betterschool.infrastructure.dao")` 全覆盖，无需改扫描）；`NotificationMapper.xml` 的 namespace 与 `insert`/`insertIgnore`/`selectPage` 三处 `parameterType`/`resultType` 跟着改。
- infra 唯一跨域仓储引用（`NotificationRepository → IUserRepository`）改为通知域自己的 port：`IBroadcastRecipientPort.listAllUserIds`，实现放 `bs-infrastructure/.../adapter/port/notification/BroadcastRecipientPort`（跨域只读伸手点落在 `adapter/port`，不是 `adapter/repository`）。
- 通知侧事务同规约收进仓储：`insertOne`（取号+落库必须同事务）与 `saveBroadcastMessage`（`OutboxMessageStore` 用 `TransactionSynchronization` 提交后投递，必须有活事务）在实现内用 `TransactionTemplate`（默认 REQUIRED）包住；`NotificationServiceImpl` 不再注入事务模板。`insertBroadcast` 内部调私有的无事务 `doInsertOne`，避免同一用例自套两层事务（原先靠 REQUIRED 合流，语义不变）。
- 改 import：10 个 case 类（CommentLike / CommentReview / PostLike / PostPublish / Credit / ErrandOrder / MarketingNotice / Report / Review / TradePublish）+ `NotificationController` + `NotificationBroadcastConsumer`（顺手改成经 `INotificationService.insertBroadcast`，与 case 层口径一致）+ `NotificationReadModelIntegrationTest`。`MarketingNoticeIntegrationTest` 票面列了但它没有引用任何被迁类型（只打两个消费者 + jdbcTemplate 查表），无需改源码，用例照跑绿。
- 表 `notification`/`notification_seq`/`notification_read` 与 HTTP 路径 `/api/chat/notifications` 未动（`NotificationController` 的 `@RequestMapping("/api/chat")` 原样）。

**2. chat 域改造**

- 跨域只读 → chat 自有 port：`IUserQueryPort`（`findById` 覆盖存在性 + `listByIds` 批量昵称头像，返回域内 `ChatUserVO`，user 域实体不出 infra）、`ICardRefQueryPort`（`findPostStatus`/`findProductStatus`/`findErrandStatus`，只取状态、不存在返回 null）。「哪些状态算已失效」「用户不存在」的判据仍在 `ChatSendServiceImpl.validateCardContent` / `createDirectRoom`。实现 `bs-infrastructure/.../adapter/port/chat/{UserQueryPort,CardRefQueryPort}`，帖子状态走 post 域自己的读路径（详情缓存口径不变）。
- 4 组事务按「原样搬」成 `IChatRepository` 原子用例（实现内 `TransactionTemplate`，默认 REQUIRED）：`createDirectRoom`（建会话 + 两名成员）、`deliverMessage`（取号 → 落消息 → timeline 逐成员扇出取号 → 清 hidden → 回 last_msg → MSG outbox；预览文案与推送载荷由域服务备好，仓储只补事务内生成的消息 ID/序号/时间）、`advanceReadWatermark`（水位线 + READ outbox，仅守卫 UPDATE 实际前进才写）、`recallMessageWithEvent`（撤回 + 预览替换 + RECALL outbox，条件 UPDATE 落空即幂等静默）。原语方法全部保留（读路径与测试造数照用）。
- `ChatSendServiceImpl` / `ChatReadServiceImpl` / `ChatRecallServiceImpl` 删除 `TransactionTemplate` 注入；`ChatMessageFanoutService` 只剩组装（构造消息、裁预览、备推送载荷）。发送者资料反查从「事务内」挪到「事务外」，只读、结果一致。
- 撞 `uk_client_msg` 的两条时序不变：幂等快路径仍在载荷校验之前；`DuplicateKeyException` 回滚后回查仍走同一个 `IChatRepository.findMessageByClientMsgId`。

**判据结果**

- `mvn -o clean package -DskipTests`：BUILD SUCCESS（bs 七个模块全过）。
- 必测四类全绿：`ChatSendPipelineIntegrationTest`(7) / `ChatPersistenceIntegrationTest`(4) / `NotificationReadModelIntegrationTest`(4) / `MarketingNoticeIntegrationTest`(6)；另跑 `mvn -o test -pl bs-app -am` 全量 230 项、0 失败 0 错误。
- `domain/chat` 无他域 `adapter.repository` import、无 `TransactionTemplate`（grep 0 命中）。
- `bs-infrastructure/**/adapter/repository/**` 无任何注入他域仓储者（跨域只读只在 `adapter/port/**`）。

**遗留（不在本票）**

- `backend/CLAUDE.md:33` 的「只读数据访问可在域内注入其他域仓储接口」例外已与新 port 规约不符 —— 按 map 决议 7 归票 06 改文档，本票不动。
- `NotificationReadModelIntegrationTest` 仍在 `integration/chat` 包下（票面只要求改 import，未要求迁包）。
- `ICardRefQueryPort` 直接返回 post/product/errand 三域的状态枚举（chat 侧据此判失效）。这样「判据留在 chat 侧」且不复制一份会漂移的状态词表；代价是 `domain/chat` 引了三个他域 `model.valobj`（票面判据只禁他域 `adapter.repository` import）。
