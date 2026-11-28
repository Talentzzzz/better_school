# 计划：通知落库改事件驱动（业务事务只写 outbox，通知域消费者统一落库）

> 状态：已执行（2026-11-16，静态验收通过；未启动环境、未跑集成测试、前端零改动）。执行窗口不需要看到本文档所属对话——本计划自包含。
> **约束：改完后不启动环境、不运行集成测试；测试代码只写不跑。前端零改动。**
>
> **后续变更（2026-11-27）**：投递第二跳已折叠——`bs-notify-chat` / `bs-notify-push` 两条 outbox 与 `NotifyChatConsumer` / `NotifyPushConsumer` 已删除，
> `NotifyEventConsumer` 落库后直接调 `NotificationCase.deliver / push`。本文档中关于“写投递 outbox、再经消费者投递”的段落为历史方案，当前口径见
> [`backend/better-school/docs/api/notify-channels.md`](../../backend/better-school/docs/api/notify-channels.md)。

## 1. 背景与目标

### 现状（改造前）

`notification` 表的写入发生在**业务事务内**：`ErrandOrderCase` / `CreditCase` / `PostLikeCase` / `CommentLikeCase` / `MarketingNoticeCase` 调 domain 层 `INotificationService.sendToUser / sendInteraction / sendSystem`，`NotificationServiceImpl.insertWithSeq` → `NotificationRepository.doInsertOne`（取 seq + 落库 + 按类型通道写投递 outbox）。

问题：业务与通知耦合。将来通知域独立成服务时，业务服务无法直写通知库。

### 目标（改造后）

```
业务事务：业务写 + outbox 写一行「通知事件」（同事务，原子）
    ↓ 提交后 outbox sender 发 MQ
NotifyEventConsumer（通知域唯一写入口）
    ├─ 落 notification 表（幂等）
    └─ 按类型通道路由：CHAT → bs-notify-chat outbox（系统消息链路）
                      BANNER → bs-notify-push outbox（横幅链路）
                      NONE → 到此为止
```

**不做的事（方案边界，刻意保守）**：事件体仍携带完整通知文案（title/content/refType/refId/dedupKey），即"文案由业务点渲染"保持不变（方案 A）。"事件只带事实、消费者渲染文案"（方案 B）不在本次范围，留作后续演进。本计划的分歧点只有**落库时机**：从事务内 → 事件消费时。

### 参考先例

营销域已经是"事件 → 消费落通知"形态：营销服务发 `bs-lottery-drawn` / `bs-points-changed`，单体 `MarketingNoticeCase` 消费落库。本次把单体内部的通知也统一成这个形状。

## 2. 关键设计决策（照此执行，不要重新发明）

1. **新 topic**：`Constants.NOTIFY_EVENT_TOPIC = "bs-notify-event"`，消费组 `bs-notify-event-group`。单一 topic + 事件体自带 type，不按业务域拆 topic。
2. **事件体** `NotificationEventMessage`（新文件，放 domain notification valobj 包）：生产点**预生成** `notificationId`（UUID）随事件携带——它是消费端幂等的第一锚点（`uk_notification_id`），`dedupKey`（可空）是第二锚点。
3. **幂等**：MQ 至少一次送达，消费者会重试。`NotificationRepository.doInsertOne` 需要新增「按 notificationId 幂等」前置检查（现只有 dedupKey 检查），否则无 dedupKey 的事件（信用分变动这类 `sendToUser` 场景）重试会插重。
4. **投递路由整体搬走**：`NotificationRepository.doInsertOne` 里的 `saveDeliveryEvents` 删除——落库方法回归"只落库"；通道投递（CHAT/BANNER outbox）由 `NotifyEventConsumer` 在落库成功后执行。
5. **生产入口**：domain `INotificationService` 删除 `sendToUser / sendInteraction / sendSystem`，新增 `void publish(NotificationEventMessage event)`；实现 = 事务模板内 `outboxMessageStore.save(NOTIFY_EVENT_TOPIC, event)`（形态照抄 `NotificationRepository.saveBroadcastMessage`——`transactionTemplate.executeWithoutResult` 包 outbox 写，因为 `OutboxMessageStore` 的 afterCommit 钩子需要活事务）。
6. **消费者失败语义**：`NotifyEventConsumer.onMessage` 失败向上抛（broker 重试）——通知落库是持久化义务，不是加速路径；重试安全由 notificationId + dedupKey 幂等兜底。这与 `NotifyPushConsumer`（恒成功）/`NotifyChatConsumer`（抛出重试）的分工一致。
7. **广播链路不动**：`broadcast` → `NOTIFICATION_BROADCAST_TOPIC` → `NotificationBroadcastConsumer.insertBroadcast` 本身就是"事件消费落库"，落库后 ANNOUNCEMENT 是 NONE 通道，无需投递。改造后它落库路径不变（只是 doInsertOne 不再带投递）。
8. **前端零改动**：通知中心/未读/横幅/系统消息全部接口与行为不变。

## 3. 改动清单（逐文件）

以下路径均相对 `Z:/better_school/backend/better-school/`。

### 3.1 新增文件

**`bs-types/src/main/java/com/betterschool/types/common/Constants.java`**（编辑）
```java
/** 通知事件 topic：业务事务内写 outbox，通知域消费者统一落 notification + 按通道投递（事件驱动改造） */
public final static String NOTIFY_EVENT_TOPIC = "bs-notify-event";
```

**`bs-domain/src/main/java/com/betterschool/domain/notification/model/valobj/NotificationEventMessage.java`**（新建）
```java
/**
 * 通知事件（业务事务内写 outbox → 通知域消费者统一落库 + 按通道投递）：
 * notificationId 由生产点预生成（消费端幂等第一锚点，uk_notification_id），
 * dedupKey 可空（互动/系统通知的生产点语义键，uk_dedup 第二锚点）。
 * 文案由生产点渲染（方案 A）；scope/type 沿用现有枚举名
 */
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class NotificationEventMessage {
    private String notificationId;   // 生产点预生成 UUID
    private String userId;           // 收件人
    private String scope;            // NotificationScopeEnum.name()
    private String type;             // NotificationTypeEnum.name()
    private String senderId;         // 触发者，可空
    private String title;
    private String content;
    private String refType;          // 可空
    private String refId;            // 可空
    private String dedupKey;         // 可空
}
```

**`bs-trigger/src/main/java/com/betterschool/trigger/listener/NotifyEventConsumer.java`**（新建）
```java
/**
 * 通知事件消费方（通知域唯一写入口）：落 notification（幂等）→ 按 NotificationTypeEnum.ChannelEnum
 * 写投递 outbox（CHAT→bs-notify-chat / BANNER→bs-notify-push / NONE→止）。
 * 失败向上抛由 broker 重试；幂等靠 notificationId（uk_notification_id）+ dedupKey（uk_dedup）
 */
@Slf4j @Component @RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.NOTIFY_EVENT_TOPIC, consumerGroup = "bs-notify-event-group")
public class NotifyEventConsumer implements RocketMQListener<String> {
    // 依赖：domain INotificationService（落库）、INotificationRepository（写投递 outbox）、ObjectMapper
    // 流程：
    //  1. 反序列化 NotificationEventMessage
    //  2. 组装 NotificationEntity（notificationId 预填！不能让 doInsertOne 现生成，否则幂等锚丢失）
    //     —— 参照 NotificationRepository.insertBroadcast 的组装方式（NotificationEntity.create 系列工厂
    //        + 手工 setNotificationId / setBroadcastMsgId 同形状）
    //  3. notificationRepository.insertOne(entity)（doInsertOne 内 seq 回填到 entity）
    //  4. 按 NotificationTypeEnum.valueOf(msg.type).getChannel() 路由：
    //     CHAT   → outboxMessageStore.save(Constants.NOTIFY_CHAT_TOPIC, new NotifyChatMessage(notificationId, userId, type, title, content, refType, refId))
    //     BANNER → outboxMessageStore.save(Constants.NOTIFY_PUSH_TOPIC, new NotifyPushMessage(userId, scope, entity.getSeq(), type, title, content, refType, refId))
    //     NONE   → log.info 落库完成
    //  5. catch 解析失败重抛；落库/投递异常也重抛（重试安全）
}
```
注意第 3 步落库被幂等命中（notificationId 或 dedupKey 已存在）时，`doInsertOne` 会提前 return，`entity.seq` 不会被回填——此时 **BANNER 投递跳过**（重复事件不重复推横幅，前端补拉兜底；这正好是期望行为）。落库返回前用「本次是否真正插入」判断：给 `INotificationRepository` / `NotificationServiceImpl` 加一个返回 boolean 的落库方法（见 3.2），或让 `doInsertOne` 返回 boolean（向上改签名）。**推荐：`insertOne` 改为返回 boolean（真正插入 = true），`NotifyEventConsumer` 仅在 true 时投递。**

### 3.2 修改文件

**`bs-domain/src/main/java/com/betterschool/domain/notification/service/INotificationService.java`**（domain 层，不是 api 层的同名接口）
- 删除 `sendToUser` / `sendInteraction` / `sendSystem` 三个方法声明
- 新增 `void publish(NotificationEventMessage event);`
- 其余方法（list/count/unread/markRead/insertBroadcast/broadcast）不动

**`bs-domain/src/main/java/com/betterschool/domain/notification/service/NotificationServiceImpl.java`**
- 删三个 send 方法
- 实现 `publish`：`notificationRepository.saveEvent(event)`（透传，事务在仓储层收）

**`bs-domain/src/main/java/com/betterschool/domain/notification/adapter/repository/INotificationRepository.java`**
- 新增 `boolean saveEvent(NotificationEventMessage event);`（真正插入返回 true）
- `insertOne` 签名改为返回 `boolean`（或新增重载；调用点同步改）

**`bs-infrastructure/src/main/java/com/betterschool/infrastructure/adapter/repository/notification/NotificationRepository.java`**
- `doInsertOne` 返回改为 boolean：dedupKey 命中 → false；insertIgnore 0 行 → false；真正插入 → true。**删除 `saveDeliveryEvents` 方法及其调用**（投递路由整体搬去 `NotifyEventConsumer`）
- 新增 `saveEvent`：`transactionTemplate.executeWithoutResult(...)` 内 `outboxMessageStore.save(Constants.NOTIFY_EVENT_TOPIC, event)`（形态照抄 `saveBroadcastMessage`），返回值语义：outbox 写恒成功即 true（消费端才做落库幂等）
- `insertOne`（旧签名）现在只剩 `insertBroadcast` 在用（走 doInsertOne）——广播是 NONE 通道，落库即止，不需要 boolean 返回值，保持 void 调用即可（用返回值忽略）
- 新增 import：`NotificationEventMessage`、`Constants.NOTIFY_EVENT_TOPIC`（Constants 已 import）

**`bs-infrastructure/src/main/java/com/betterschool/infrastructure/dao/notification/INotificationDao.java`**（可选加固）
- 如需「按 notificationId 幂等」显式前置检查（等价于 insertIgnore 靠 uk_notification_id 吸收——注意普通 `insert` 撞 uk 会**抛异常**而不是忽略！），则新增 `int existsNotificationId(String notificationId)` + Mapper XML 对应 `<select>`（仿 `existsDedupKey`）。**推荐加上**：无 dedupKey 的事件重试时普通 insert 撞 `uk_notification_id` 会抛 DuplicateKeyException → 消费者当作失败无限重试。所以 `doInsertOne` 逻辑：`dedupKey 命中 → return false`；`existsNotificationId 命中 → return false`；无 dedupKey 走 `insert`；有 dedupKey 走 `insertIgnore`。
- Mapper XML：`bs-app/src/main/resources/mybatis/mapper/NotificationMapper.xml` 补 existsNotificationId。

### 3.3 生产点替换（call sites，逐个改）

全部从 `notificationService.sendXxx(...)` 换成 `notificationService.publish(NotificationEventMessage.builder()...)`。**dedupKey 原样携带**（sendInteraction/sendSystem 的最后一个参数），sendToUser 场景 dedupKey 传 null（幂等靠 notificationId）。notificationId 每个调用点 `UUID.randomUUID().toString()` 预生成。

| 文件 | 行（约） | 现调用 | 改造要点 |
|---|---|---|---|
| `bs-case/.../usecase/credit/CreditCase.java` | 46 / 54 / 61 | `sendToUser(...)` ×3（信用分变动/封禁/解封） | publish；dedupKey=null；补 import NotificationEventMessage |
| `bs-case/.../usecase/community/PostLikeCase.java` | 32 | `sendInteraction(...)` | publish；dedupKey 原样 |
| `bs-case/.../usecase/community/CommentLikeCase.java` | 31 | `sendInteraction(...)` | publish；dedupKey 原样 |
| `bs-case/.../usecase/errand/ErrandOrderCase.java` | 205（handleLockTimeout）/ 237（notifyPaid） | `sendSystem(...)` ×2 | publish；dedupKey 原样（含 lockSeq 的那个）。注意 handleLockTimeout 现在把通知包在 try-catch 里「通知失败不让释放报失败」——改成 publish 后 catch 保留（outbox 写失败只记日志，主效应是释放） |
| `bs-case/.../usecase/marketing/MarketingNoticeCase.java` | 42 / 65 | `sendSystem(...)` ×2（LOTTERY_WON / POINTS_CHANGED） | publish。它是消费者，改后营销事件消费 → publish 事件 → NotifyEventConsumer 落库，多一跳但写入口统一。**注意**：`MARKETING:DRAWN:<recordId>` / `MARKETING:POINTS:<reasonType>:<refId>` 的 dedupKey 原样携带 |
| `bs-trigger/.../listener/NotificationBroadcastConsumer.java` | — | 调 `insertBroadcast` | **不改**（广播链路自洽） |

搜索兜底：改完后全局 `grep "sendToUser\|sendInteraction\|sendSystem"` 应只剩 domain 接口历史注释（删除）与测试（见 3.4）。

### 3.4 测试改造（只写不跑）

| 测试文件 | 影响 | 改法 |
|---|---|---|
| `bs-app/src/test/java/com/betterschool/integration/chat/NotifyChannelIntegrationTest.java` | 直接调 `sendSystem/sendInteraction` 的断言全部失效 | 重写：生产点（如 `errandOrderCase.notifyPaid(orderId)` 或直接 `notificationService.publish(...)`）→ 断言 `outbox_message` 有 `destination=bs-notify-event` 行 → 注入 `NotifyEventConsumer` 调 `onMessage(payload)` → 断言 notification 表落行 + `bs-notify-chat`/`bs-notify-push` outbox 出现 → 再调 `NotifyChatConsumer.onMessage` 断系统消息落库（沿用现有断言）。幂等用例：同一事件 `onMessage` 两次，notification 表仍 1 行、投递 outbox 仍 1 条 |
| `bs-app/src/test/java/com/betterschool/integration/chat/NotificationReadModelIntegrationTest.java` | 多处直接调 `sendSystem` 落库 | 每处替换为 `publish` + `notifyEventConsumer.onMessage(...)`（可提一个私有 helper `deliver(NotificationEventMessage)`） |
| `bs-app/src/test/java/com/betterschool/integration/errand/ErrandLockTimeoutIntegrationTest.java` | 走 HTTP 超时链路后断言 notification 表 | 锁单超时由 XXL 回调驱动，`handleLockTimeout` 改为 publish 事件后，断言前需先消费 `outboxPayloads(Constants.NOTIFY_EVENT_TOPIC)`（可用 `outboxPayloads` 同款 SQL 手写 helper）。或在断言通知处改为「先消费再断言」 |
| `bs-app/src/test/java/com/betterschool/integration/errand/ErrandFullChainIntegrationTest.java` | `errandPaidConsumer.onMessage(payload)` 后断言"支付成功"通知 | notifyPaid 改 publish 后，消费链路变两级：先 ErrandPaidConsumer → 再取 `bs-notify-event` 的 outbox payload 喂 `NotifyEventConsumer` → 再断言 notification |
| `bs-app/src/test/java/com/betterschool/integration/marketing/MarketingNoticeIntegrationTest.java` | 消费营销事件后直接断言 notification | 中间加一级：`MarketingNoticeCase` 现在 publish 事件，测试里 `lotteryDrawnConsumer.onMessage` 后要再消费 `bs-notify-event` outbox（喂 NotifyEventConsumer）再断言 |
| `bs-app/src/test/java/com/betterschool/integration/errand/ErrandOrderPushIntegrationTest.java` | 本次新增，测 ORDER 帧 | **不受影响**（ORDER 推送与通知事件是两条链路），跑通即可不动 |
| `bs-infrastructure/src/test/...`（如有 NotificationRepository 单测） | doInsertOne 签名变 | 按 boolean 返回值调整断言 |

通用 helper 建议：在 `CommunityApiTestBase` 加
```java
@Autowired protected NotifyEventConsumer notifyEventConsumer;
protected void consumeNotifyEvents() {
    for (String payload : queryForList("SELECT payload FROM outbox_message WHERE destination='bs-notify-event' AND state=0 ORDER BY id", String.class))
        notifyEventConsumer.onMessage(payload);
}
```
（注意：`outboxPayloads` 在 ChatWsIntegrationTest 是私有的，基类没有——放基类最省。）

### 3.5 文档更新

- `backend/better-school/docs/api/notify-channels.md`：链路图头部加一级「通知事件（bs-notify-event）→ NotifyEventConsumer 落库 + 通道路由」；「落库+投递」一节的文件指向改为 NotifyEventConsumer；补一段「落库时机=事件消费时，幂等锚=notificationId+dedupKey」的设计说明（对应用户讨论结论：可靠性锚点从"同事务"换成"outbox 至少一次 + 消费端幂等"）
- `backend/better-school/docs/db/outbox.md`：destination 取值补 `bs-notify-event`
- `backend/better-school/docs/db/chat.md` 的 notification 节：补一行"谁在写：通知域消费者 NotifyEventConsumer（事件驱动），业务侧只发事件不直写"

## 4. 执行顺序建议

1. Constants + NotificationEventMessage + INotificationDao.existsNotificationId + Mapper XML
2. INotificationRepository / NotificationRepository（doInsertOne→boolean、saveEvent、删 saveDeliveryEvents）+ domain INotificationService（删 send*、加 publish）+ NotificationServiceImpl
3. 新建 NotifyEventConsumer
4. 替换 5 个文件的调用点（3.3 表格逐行）
5. 全局 grep `sendToUser|sendInteraction|sendSystem` 清零（domain 测试代码除外）
6. 测试改造（3.4 表格逐个）
7. 文档（3.5）

## 5. 验收标准（静态检查即可，不跑环境）

- [ ] `grep -r "sendToUser\|sendInteraction\|sendSystem" backend/better-school --include=*.java` 主代码（src/main）零命中
- [ ] `NotificationRepository.doInsertOne` 不再调用 outbox（落库纯化）；`NOTIFY_CHAT_TOPIC/NOTIFY_PUSH_TOPIC` 的唯一写点在 `NotifyEventConsumer`
- [ ] 所有事件载荷都预生成 notificationId；有语义幂等键的场景 dedupKey 原样保留（对照 3.3 表格）
- [ ] `NotifyEventConsumer` 消费失败重抛（broker 重试）；重复消费不重复落库、不重复投递
- [ ] 前端零 diff
- [ ] 编译通过（dev 环境有 mvn 的话 `mvn -o clean package -DskipTests`；没有则至少保证 imports/签名静态自查）——**不运行任何集成测试、不启动 docker/服务**

## 6. 风险与注意

1. **幂等双锚缺一不可**：只靠 dedupKey 会在"无键事件重试"时插重；只靠 notificationId 会丢"业务语义去重"（如同一人对同一帖子反复赞/取消再赞——dedupKey 命中即吞掉，这是现有语义，必须保留）。两层都留。
2. **`insertIgnore` 只该用于带 dedupKey 的行**：无 dedupKey 行走普通 `insert`，靠 `existsNotificationId` 前置检查防重试撞 uk 抛异常。
3. **BANNER 通道的 seq**：来自 doInsertOne 内取号回填；幂等命中（未真正插入）时没有 seq，跳过投递是正确行为——重复事件不该重复弹横幅。
4. **两级 MQ 的测试断言时机**：集成测试里"业务动作 → 通知落库"之间必须手动喂 `NotifyEventConsumer`，别漏（3.4 已列清单）。
5. **营销链路多一跳**：营销事件消费 → publish → notify-event 消费。多一跳是刻意的（统一写入口），不要"优化"回直调。
