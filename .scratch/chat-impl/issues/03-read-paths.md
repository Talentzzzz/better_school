---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by: 01
---

# chat 落地票 03：会话读路径（列表/历史/补拉/水位线/隐藏/成员/票据）

## Scope

依据 spec §2 已读时序与补拉口径、§4 接口清单，实现读路径 REST。

1. `GET /api/chat/rooms`：会话列表（排除 hidden/left_at 非空；1v1 拼对方 userId+昵称+头像，群拼群名；unread=last_seq−水位线；含 last_msg 三列与 SEALED 态），按 last_msg_at 排序分页。
2. `GET /api/chat/rooms/{id}/messages`：`?beforeSeq=&limit=`（默认 50）倒序分页，撤回消息脱敏「已撤回」空壳（content 不出服务端）。
3. `GET /api/chat/timeline`：`?afterDeliverySeq=&limit=` 默认 200 硬顶 500，返回消息全量+roomId/seq/deliverySeq；不足一页即追平。
4. `POST /api/chat/rooms/{id}/read`：仅 `last_read_seq < room.last_seq` 才 UPDATE + 提交后投递 READ outbox 事件（载荷 `{roomId, userId, readSeq}`）；重复打开幂等无噪音。
5. `POST /api/chat/rooms/{id}/hide`：置 hidden_at。
6. `GET /api/chat/rooms/{id}/members`：当前成员（left_at 为空）含 last_read_seq 与成员数；非成员拒绝。
7. `POST /api/chat/ws-ticket`：60s 一次性票据（Redis/内存均可，原子取删）。
8. 旧 threads 端点与 DTO 删除（`ThreadResponseDTO` 等）。

## 验收要点

- 未读数 O(1) 无 COUNT；补拉分页边界（0=全量、limit 硬顶、循环拉语义）集成测试。
- 水位线幂等：重复调 /read 不产生第二个 READ 事件；打开即读不等补拉的时序由前端票 09 消费。

## Comments
