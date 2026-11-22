Status: ready-for-agent
Type: task
Blocked by:

# 04 告警通路：outbox 事件 → 单体消费 → 通知中心

## 目标

打通对账告警的完整通路，复用现有营销通知链路（outbox → RocketMQ → 单体 consumer → `INotificationService.publish`）。本票与票 01/02 并行：**端口接口也在本票定义**（域层），票 03 合入后按此接口注入调用。

## 上下文指针

- spec：`.scratch/draw-recon-round/spec.md`（Implementation Decisions 告警节；Out of Scope：不接词典外新渠道）
- 探索报告：`.scratch/draw-recon-round/exploration.md` §3 全节（链路三段、`OutboxMessageStore.save` 语义、`MarketingNoticeCase.notifyLotteryDrawn` 先例、`NotificationTypeVO` 位置）
- 域层端口接口（**本票产出**）：接口放 `marketing-domain` raffle 域（照 `IInventoryDeductPort` 的声明位置惯例），签名带 roundNo、checkType、scopeKey、bizId、期望/实际快照摘要、告警文案；票 03 将按此注入调用，Answer 里必须写清最终签名。

## 交付内容

1. **marketing 侧消息类型**：`marketing-types` 新建告警事件消息（照 `LotteryDrawnMessage` 形态：roundNo、checkType、scopeKey、bizId、expect/actual 摘要、告警文案），topic 常量进 marketing `Constants`（命名如 `bs-marketing-recon-alert`，照 `LOTTERY_DRAWN_TOPIC` 惯例）。
2. **端口实现**：`marketing-infrastructure` 实现上述端口接口——经 `OutboxMessageStore.save(topic, message)` 落 outbox。注意 `save` 要求业务事务包裹（afterCommit 首发）；对账服务无事务模板，实现里确认 `save` 在无活动事务时的行为，若不允许则把"插 outbox 行"做成仓储一个原子写（单行 insert 自带事务）——在 Answer 说明你选的方案与理由。
3. **单体侧 consumer**：`backend/better-school/bs-trigger/.../listener/` 新建 consumer（照 `LotteryDrawnConsumer` 形态：独立 consumerGroup），消费告警事件 → 调用 case 层。
4. **case 层落通知**：`MarketingNoticeCase` 扩方法（或新建 `MarketingReconAlertCase`，判断依据：方法体量与内聚）——`notificationService.publish`，`dedupKey` 形如 `MARKETING:RECON:<roundNo>:<checkType>:<bizId>`（天然幂等，重复点名不重复打扰）、`scope=BOTTOM_SYSTEM`、`refType=MARKETING`。
5. **通知类型**：`NotificationTypeVO` 营销枚举追加对账告警值（照 LOTTERY_WON 形态，带语义注释）。

## 约束

- 不接词典外的新通知渠道（spec Out of Scope）。
- 单体侧改动文件与当前主工作区未提交的 WS 重构文件**零交集**；提交时只提交本票文件。

## 验收

- 两个工程（`backend/marketing/` 与 `backend/better-school/`）`mvn compile` 通过；不写测试、不跑测试。

## Answer

（完成后在此追加要点）
