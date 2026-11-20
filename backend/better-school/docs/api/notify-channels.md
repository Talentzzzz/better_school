# 通知投递通道（notification 呈现路由）

> 通知落库（`notification` 表）是唯一真相源：通知中心列表、未读水位线、dedup 幂等全部不变。
> 在落库之上按通知类型路由到两条**呈现通道**，路由定义在 `NotificationTypeVO.Channel`。

## 链路总览（事件驱动，业务侧不直写通知库）

```
业务事务：业务写 + outbox 写一行「通知事件」bs-notify-event（NotificationEventMessage，同事务原子）
    ↓ 提交后 outbox sender 发 MQ
NotifyEventConsumer（通知域唯一写入口）
    ├─ 落 notification 表（幂等：notificationId uk_notification_id + dedupKey uk_dedup 双锚）
    └─ 按类型通道路由：CHAT → bs-notify-chat outbox（系统消息链路）
                      BANNER → bs-notify-push outbox（横幅链路）
                      NONE → 到此为止
```

- **落库时机 = 事件消费时**：业务点只 `INotificationService.publish(NotificationEventMessage)`，
  业务事务内只写 outbox 一行；可靠性锚点从「通知与业务同事务」换成「outbox 至少一次 + 消费端幂等」
  （消费失败向上抛由 broker 重试，重试安全由 notificationId + dedupKey 双锚收敛）。
- 事件体仍携带完整通知文案（title/content/refType/refId/dedupKey），即"文案由业务点渲染"（方案 A）；
  `notificationId` 由生产点预生成 UUID 随事件携带（消费端幂等第一锚点）。
- 幂等命中（未真正插入）时没有 seq，BANNER 投递跳过——重复事件不重复弹横幅，前端补拉兜底。
- 广播链路自洽不变：`broadcast` → `bs-notification-broadcast` → `NotificationBroadcastConsumer` 落库
  （ANNOUNCEMENT 是 NONE 通道，落库即止）。

## 通道路由表

| 类型 | 通道 | 理由 |
|---|---|---|
| ORDER_EVENT 订单动态 | CHAT 系统消息 | 需要留档、可跳转订单 |
| CREDIT_CHANGE 信用分变动/封禁/解封 | CHAT 系统消息 | 需要留档 |
| POST_LIKED / COMMENT_LIKED / POST_COMMENTED / COMMENT_REPLIED | BANNER 弹一下 | 高频低价值，瞬态提醒 |
| LOTTERY_WON / POINTS_CHANGED | NONE 不推送 | 只落通知中心，进页面可见 |
| ANNOUNCEMENT 公告 | NONE（广播落库） | 全用户落 notification，行为不变 |

## CHAT 通道：系统消息（聊天链路）

- 发送者 = 系统助手固定账号 `Constants.SYSTEM_USER_ID`（schema.sql 种子行，phone 占位不参与登录）。
- `NotifyEventConsumer` 落库成功后写 outbox `bs-notify-chat`（`NotifyChatMessage`）→ `NotifyChatConsumer` →
  `SystemMessageCase.deliver`：懒创建「系统助手」1v1 会话（`uk_pair` 幂等）+ 以 `MsgTypeVO.SYSTEM`
  发消息（content = JSON 载荷，`client_msg_id = NOTIFY:<notificationId>` 幂等）。
- 免费复用聊天链路的全部能力：在线走 WS MSG 帧实时冒泡、离线 timeline 补拉兜底、未读走会话水位线。
- 守卫：用户不可主动与系统账号建会话、不可向系统会话发消息（`ChatSendServiceImpl` 双守卫，403）；
  前端识别 SYSTEM 消息后隐藏输入区。

## BANNER 通道：顶部横幅（瞬态）

- `NotifyEventConsumer` 落库成功后写 outbox `bs-notify-push`（`NotifyPushMessage`）→ `NotifyPushConsumer`（反序列化 + 调 case）→
  `NotifyPushCase` 组帧经 `IWsPushPort` 由 infra WS 推送适配器推 WS `NOTIF` 帧（只推在线者，漏推合法）。
- 「保证弹过」（小红书口径）：前端按 `(userId, scope)` 在 localStorage 记 seq 水位
  （`bs:notify-cursor:<userId>:<scope>`），WS 重连/上线时把 `seq > 水位` 的积压通知逐条重放横幅；
  首次登录该账号（无水位）静默对齐不回放历史存量。NOTIF 帧就地推进水位。
- 前端未读角标：NOTIF 帧到达对应 scope 未读 +1，进通知页 markRead 后以服务端口径重拉对齐。

## ORDER 帧：订单状态实时刷新

- 跑腿订单每个状态迁移点成功后由域服务写 outbox `bs-order-push`（`ErrandOrderPushEvent{orderId,status}`）→
  `OrderPushConsumer`（反序列化 + 调 case）→ `ErrandOrderCase.pushStatus` 决定订单双方投递 →
  经 `IWsPushPort` 由 infra WS 推送适配器推 WS `ORDER` 帧给发布方+接单方在线者。
- 详情页收到帧（匹配当前 orderId）即重拉详情；推送是加速路径，漏推由详情页拉取/通知中心兜底。

## 关键文件

| 层 | 文件 |
|---|---|
| 事件定义 | `bs-domain/.../notification/model/valobj/NotificationEventMessage.java` |
| 事件发布 | `bs-domain/.../notification/service/INotificationService.java`（publish，业务点唯一生产入口） |
| 事件消费+落库+通道路由 | `bs-trigger/.../listener/NotifyEventConsumer.java`（通知域唯一写入口） |
| 路由定义 | `bs-domain/.../notification/model/valobj/NotificationTypeVO.java`（Channel 枚举） |
| 系统消息投递 | `bs-case/.../usecase/notification/SystemMessageCase.java` |
| 通道消费方 | `bs-trigger/.../listener/NotifyChatConsumer.java` / `NotifyPushConsumer.java` / `OrderPushConsumer.java`（后两者均为反序列化 + 调 case） |
| 推送决策 | `bs-case/.../usecase/notification/NotifyPushCase.java`（NOTIF 组帧）/ `usecase/errand/ErrandOrderCase.java`（pushStatus 双方投递决策） |
| WS 投递端口 | `bs-domain/.../chat/adapter/port/IWsPushPort.java`（deliver 单一入口）→ `bs-infrastructure/.../adapter/port/chat/ws/ChatWsPushAdapter.java` |
| WS 帧 | `bs-domain/.../chat/model/valobj/ChatWsFrameType.java` 增 NOTIF / ORDER |
| 前端 | `stores/notices.ts`（横幅队列/水位补放/未读）+ `components/NotifBanner.vue` + `App.vue` 挂载 |
