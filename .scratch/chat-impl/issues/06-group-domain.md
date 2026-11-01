---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by: 02
---

# chat 落地票 06：系统群领域层（建群/成员进出/封盘 + SYSTEM 消息）

## Scope

依据 spec §5 群聊规则。纯领域层 service API（如 `ChatGroupSystemApi`），无 HTTP 端点无前端入口，集成测试当调用方。

1. `createGroup(name, memberIds)`：room type=GROUP、name 系统传入；成员 ≤500 可配置（room_user COUNT 校验，不落 DB 约束）；建群即投 `GROUP_CREATED` SYSTEM 消息。
2. 成员进出：加入（投 `MEMBER_JOINED`）、移除（room_user 置 left_at 留痕，此后停止扇出、不出会话列表；投 `MEMBER_REMOVED`，被移除者不收此消息）；投递对象=事件时刻成员集合；满员拒绝加入。
3. 封盘 `sealGroup(roomId)`：room status=SEALED（软删不可逆，无重开）；禁发言禁成员变更；投 `SEALED` SYSTEM 消息；历史全员只读可见。
4. SYSTEM 消息走票 02 发送链路：占 seq、计未读、正常扇出；content=JSON `{event,...}` + 服务端渲染文案（快照式落库，spec §1 event 枚举）；from_user_id 为 NULL，不受 client_msg_id 约束。

## 验收要点

- 集成测试覆盖四事件各一条：SYSTEM 消息落库渲染文案正确、扇出集合正确（移除事件不含被移除者）。
- 封盘后发送/加人被拒，历史消息读路径不变；left_at 成员不再收后续消息。

## Comments

（2026-09-10 resolved，全量 247 绿）落地决策留档：

- **扇出核心抽取**：票 02 发送链路的落库段（取 seq→message→timeline 扇出→clearHidden→last_msg→outbox）抽为 `ChatMessageFanoutService.deliver`（send 包内组件，非 IXxxService），用户消息与 SYSTEM 共用；`ChatSendServiceImpl.doSend` 改为委托。SYSTEM 预览顺带修正为取 JSON `text` 字段（spec §1「SYSTEM→渲染文案」，票 02 时无 SYSTEM 实消息，改动无波及）。
- **SYSTEM 载荷**：content=JSON `{"event","text"}`，键常量收口在 `SystemEventVO.PAYLOAD_KEY_*`；from_user_id/client_msg_id 均 NULL；渲染文案快照落库（昵称定格不回溯）。
- **并发口径**：成员进出/封盘先 `selectRoomForUpdate` 锁 room 行再校验计数（满员 COUNT 锁内读才可靠）；发送事务内同样锁后重检 SEALED——封盘与并发发送竞态时发送让位，杜绝晚于 SEALED 消息的用户消息。
- **rejoin（票面未明说，工程补充）**：被移除成员重新加入清 left_at（+hidden_at）复用留痕行（uk_room_user 不另起新行），再投 MEMBER_JOINED 恢复收流。
- **1v1 守卫（票面未明说，防御性补充）**：addMember/removeMember/sealGroup 均拒绝 DIRECT 会话（封盘是群聊终态语义，spec §5）。
- **幂等**：已在会重复加入 / 重复移除 / 重复封盘一律静默不占 seq 不重复投事件；markSealed 条件 UPDATE（status=ACTIVE 守卫）与撤回状态机同款。
- 成员 ≤500 上限：`chat.group.max-members`（默认 500），锁内 COUNT（left_at IS NULL 口径，留痕行不计入）。
