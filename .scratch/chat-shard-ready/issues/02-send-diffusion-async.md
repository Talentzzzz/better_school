# 02: 发消息链路拆分——事务缩为 room 片 + outbox 异步扩散消费端

**What to build:** 发消息从「单库大事务」拆为「room 片本地事务（取号 + message 插行 + outbox 行）+ outbox→RocketMQ 异步扩散」，扩散消费端为每个收件人的 user 片单片事务（timeline INSERT + room_member 副本 UPDATE + user_seq ODKU）。完成后：发送方确认只依赖 room 片事务；两端列表副本由消费链路单一写入方维护；`delivery_seq` 游标连续、`uk_user_delivery` 吸收重投；客户端零改动，对外行为不变。

**Blocked by:** 01。

**Status:** ready-for-agent

- [ ] 发消息事务收窄：取号 + message + outbox 行同事务；`uk_room_client` 幂等与重试收敛语义不变
- [ ] 扩散消费端：按 user_id 分队列保序；每收件人单片事务 = timeline INSERT + room_member UPDATE（`last_seq_copy = GREATEST(...)`、刷预览三件套、清 `hidden_at` 弹回）+ user_seq ODKU 取 delivery_seq
- [ ] 新增 `awaitDiffusion` 有界轮询测试助手（等消费完成，超时即败）
- [ ] 集成测试（HTTP/WS 缝，沿用 chat-impl 票 07 风格）：发送成功后两端列表副本推进、未读正确、预览正确、断线补拉游标连续
- [ ] 重投幂等用例：消费失败重投后不重不漏
- [ ] 回归：推送判重 `(room_id, seq)`、多端已读共享、弹回语义与重构前等价
