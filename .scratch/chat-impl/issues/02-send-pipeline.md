---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by: 01
---

# chat 落地票 02：消息发送主链路（REST + 幂等 + 扇出 + outbox）

## Scope

依据 spec §2 发送链路、§4 接口清单、§6 零审核，重写发送用例。

1. `POST /api/chat/rooms/direct`：建/取 1v1 会话，`uk_direct_pair` 幂等返回既有 room；`POST /api/chat/rooms/{id}/messages`：发送（body `{clientMsgId, msgType, content?, refType?, refId?}`），200 返回完整消息体（roomId/seq/消息字段）。
2. 事务内：撞 `uk(room_id, client_msg_id)` 回查返回原消息（幂等）→ room 取 seq（UPDATE last_seq=last_seq+1）→ message INSERT → user_seq 取号 → timeline 多值 INSERT 扇出（同时清空扇出目标 hidden_at 弹回）→ room.last_msg 三列冗余（预览规则 spec §1）→ outbox 事件（MSG 类型，载荷=完整消息体）。
3. 类型校验：TEXT ≤500（单一常量收敛）、IMAGE url 本站上传域前缀、CARD 存在且未删/未拒（spec §1 各状态口径）、SEALED 会话拒绝发言、非成员拒绝。
4. **chat 零审核**：拆除 `MessageSendCase` 敏感词初筛调用（仅此一处，其他域不动）。

## 验收要点

- 重复提交同 client_msg_id 收敛同一条消息（同 seq，无重复行）；集成测试覆盖撞 uk 两时序（已落库/未落库）。
- 扇出行数=成员数（1v1=2）；hidden_at 弹回；last_msg 预览四类型标签正确。
- moderation 拆除后帖/评/商品/跑腿审核测试不回归。

## Answer

2026-09-10 落地收口，全量 224 绿（bs-app 218 + 各模块）。

1. **domain**：`IChatSendService`/`ChatSendServiceImpl`（`service/send/`）——发送主链路事务内完成：撞 uk 回查幂等收敛（两时序：已落库快路径 / 并发未落库 `DuplicateKeyException` 回滚后回查）→ `increaseLastSeq` 取号 → message INSERT → 逐成员 `increaseUserSeq` 取号 → timeline 扇出（`clearHidden` 弹回）→ `updateRoomLastMsg` 冗余 → outbox MSG 事件；`createDirectRoom` 校验目标存在 + 不能与自己，`uk_direct_pair` 幂等返回既有 room（并发撞键回查兜底）。载荷校验：TEXT ≤500 常量、IMAGE JSON url 须 `minio.public-url` 前缀、CARD content 恒空 + 三域状态校验（post 非 DELETED/REJECTED/DRAFT、product 非 DELETED/REJECTED、errand 非 REJECTED/CANCELLED）、SYSTEM 用户侧拒绝。CARD/用户校验为只读跨域注入（规范允许例外）。
2. **api/trigger**：`IChatService` + 4 DTO，`ChatController` 两端点 `POST /rooms/direct`、`POST /rooms/{id}/messages`（200 返回完整消息体含 roomId/seq/发送者昵称头像）。
3. **推送事件**：`IChatRepository.savePushMessage` → `OutboxMessageStore`，新 topic `Constants.CHAT_PUSH_TOPIC = "bs-chat-push"`，载荷 `ChatMessagePushMessage`（全量消息体 + 发送者昵称头像，(roomId, seq) 判重键）；消费者归票 04。
4. **评审修正两处**：① `ChatMapper.listRoomUsers` 补 `ORDER BY id`（review 发现：无序遍历成员做 user_seq 行锁取号，并发同会话发送锁序不定有死锁风险）；② 幂等快路径前移到 SEALED/载荷校验之前（spec 异常表「撞 uk 返回原消息」——重发收敛不受封盘/卡片失效影响；成员校验仍在前防非成员借幂等路径探测消息）。
5. **零审核确认**：`MessageSendCase` 票 01 已删，全库 grep 无 chat→敏感词引用；帖/评/商品/跑腿审核路径零改动，moderation 测试无回归。
6. **测试**：`ChatSendPipelineIntegrationTest` 7 用例（建/取幂等双向收敛+自己/不存在拒绝、五表+outbox 落地、已落库撞 uk 收敛、并发未落库时序——独立事务持 uk 锁 + 定时释放、hidden_at 弹回、last_msg 预览 TEXT/IMAGE/CARD 三类型 + 外站图片/已删帖卡片拒绝且不占 seq、SEALED/非成员/被移除/超长/SYSTEM 拒绝且失败不占号）；docs/api/chat.md 新建。

给后续票的备忘：SYSTEM 消息预览分支现为 `content` 直通占位，票 06 落系统消息载荷时须按「渲染文案」语义校准 `previewOf`；MSG 推送载荷字段见 `ChatMessagePushMessage`，票 04 消费端按 (roomId, seq) 判重并排除发送者本人。

## Comments
