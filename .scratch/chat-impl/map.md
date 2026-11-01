---
Labels: wayfinder:map
---

# chat 领域重构落地（wayfinder map）

## Destination

按 [chat-refactor spec](../chat-refactor/spec.md)（唯一需求来源，票 01–08 决策已收口）完成 chat 领域重构的全部编码落地：DDL、后端 room/message/timeline 模型 + WS 连接层、前端 WS 化改造。设计阶段见 [chat-refactor map](../chat-refactor/map.md)。

## Notes

- 需求问题一律查 spec 对应章节；本 effort 不再重开设计讨论，发现 spec 缺口回 chat-refactor 补票。
- 后端模块布局：bs-api（DTO/接口）/ bs-case / bs-domain / bs-infrastructure / bs-trigger（http/listener）。outbox→MQ 消费者骨架同构参照 agent-review 既有实现。
- 测试基类事实：RocketMQ namesrv 必须有、broker 刻意不启；WS 测试用真客户端直连 RANDOM_PORT + 直调 ChatPushConsumer（spec §9）。
- chat 全路径零审核（只拆 MessageSendCase 敏感词一处）；通知域一行不动。

## Decisions so far

- 票 01（resolved，fcb6053）：DDL 定稿并入 init.sql + chat 持久化层与领域模型重建
- 票 02：消息发送主链路（幂等/取号/扇出/outbox/last_msg/moderation 拆除）
- 票 03：会话读路径（列表/历史 seq 分页/补拉/水位线/隐藏/成员/票据）
- 票 04：WS 连接层与推送链路
- 票 05：撤回与举报落地
- 票 06（resolved）：系统群领域层（建群/进出/封盘 + SYSTEM 消息，扇出核心抽 ChatMessageFanoutService 与票 02 共用）
- 票 07（resolved）：WS 集成测试与异常路径收口（真客户端 + 直调消费者 + 注入时钟，10 用例）
- 票 08（resolved）：前端 WS 客户端与 rooms store（utils/chatWs 状态机 + chatRooms 判重合流 + 三定时器全拆）
- 票 09（resolved）：前端会话列表与详情页重写（分页/发送状态机/撤回/卡片/已读回执，loadOlder 判重缺口 review 修正）
- 票 10（resolved）：前端成员列表页/卡片入口/全链路收尾（ChatMembersView + 卡片发送入口 + 造数验收清单全过）

## Not yet specified

（无——设计遗留项已在 chat-refactor spec §8/§10 清零或记为演进口）

## Out of scope

（同 chat-refactor spec §0：跑腿群绑定、多端同步、通知域、数据迁移）
