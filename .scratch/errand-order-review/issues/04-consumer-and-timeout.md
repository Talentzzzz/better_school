Status: resolved
Type: task
Blocked by: 02

# 04: 支付消息消费者（进入审核通知）+ 24h 超时提醒

**What to build:** 新增 RocketMQ 消费者（topic `bs-errand-paid`，载荷订单 id）：给作者发"订单已进入人工审核"站内通知（通知类型实现时定，复用或新增），重复消费不重复通知（dedup_key 幂等）；consumer 位置即未来 agent 自动裁决的接入点，本票只发通知不做裁决。超时提醒：在现有 `ErrandTimeoutScanJob` 加一条规则——PENDING_REVIEW 超 24h 未裁决 → 给管理员发站内提醒（每订单只提醒一次，用通知 dedup_key 或订单标记控制），绝不自动通过、绝不自动退款。

**Status:** done

- [x] 支付后作者收到一条"进入人工审核"通知，重复消费只一条
- [x] 待审超 24h：管理员收到提醒，且同一订单不重复提醒
- [x] 提醒不改变订单状态；通知写入失败不影响主流程

## Answer

实现要点（票 04）：

- **消费者**：新增 `ErrandPaidConsumer`（topic `bs-errand-paid`，group `bs-errand-paid-group`，载荷 `ErrandPaidMessage{orderId}`），解析后调 `ErrandOrderCase.notifyPaidReview(orderId)`；该消费位置即未来 agent 自动裁决接入点，本票只发通知不裁决。订单不存在的消息静默忽略。
- **作者通知**：类型复用 `ORDER_EVENT`（订单动态），内容"已支付成功，进入人工审核，审核通过后将对外可见"；dedup_key = `ORDER_EVENT:ERRAND_PAID:{orderId}`，走通知表 `uk_dedup` 唯一键 INSERT IGNORE，重复消费只一条。为此新增 `INoticeService.sendSystem`（系统通知、无触发者、带 dedupKey）+ `NotificationEntity.createSystem`，与既有 `sendInteraction`（互动通知）并列。
- **24h 提醒**：`ErrandOrderServiceImpl.listPendingReviewTimeoutOrderIds`——`status='PENDING_REVIEW' AND updated_at <= now-24h`（PENDING_REVIEW 由支付条件 UPDATE 翻转而来，updated_at 即进入待审时间），挂 `ErrandOrderCase.scanTimeouts` 第三段循环。收件人 = `IUserRepository.listUserIdsByRole(ADMIN)` + `AUDITOR`（两者都是审核队列操作者，票面写"管理员"，实现扩到审核员——若只发 ADMIN，审核员经手的队列无人被提醒）；dedup_key 含收件人 id（`ORDER_EVENT:REVIEW_TIMEOUT:{orderId}:{reviewerId}`），每订单每收件人只一条。
- **绝不自动裁决**：提醒只落 notification 表，不触碰 errand_order 状态（测试固化）。
- **通知失败语义**：MQ 路径（作者通知）——载荷解析失败才吞（重试也不会成功的坏消息），业务失败上抛由 broker 自动重试（dedup_key 幂等，重试不重复落），主流程即订单状态/资金不在消费链路内；扫描路径（24h 提醒）无 broker 兜底——逐收件人吞异常不影响扫描，下轮扫描自动补发（dedup_key 防重复）。
- **测试**：`ErrandPaidNotifyIntegrationTest` 4 例（消费通知+重复消费只一条 / 不存在订单静默 / 超 24h 提醒管理员+审核员+重复扫描不重复+未超时不提醒+状态不变 / 非待审状态不提醒）。后端全量 70/70 绿。
