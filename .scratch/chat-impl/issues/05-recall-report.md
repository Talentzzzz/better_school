---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by: 02
---

# chat 落地票 05：撤回与举报落地

## Scope

依据 spec §1 撤回语义、§6 举报与强制撤回。

1. `POST /api/chat/rooms/{id}/messages/{seq}/recall`：发送者 2 分钟窗口（常量）、1v1 与群一致、仅本人；置 status=RECALLED + recalled_at + recalled_by=USER；content 库内保留、读路径与下发脱敏（票 03 读路径已实现脱敏口径，本票保证撤回后即时生效）；成功后投递 RECALL outbox 事件（轻推 roomId+seq）。
2. chat 系统侧**内部撤回 API**（领域层 service，无 HTTP、无窗口限制）：`recalled_by=AUDITOR`；消息已 RECALLED 则幂等跳过。
3. 举报落地：content_report `targetType=MESSAGE` 入口（举报者须为会话成员校验、一人一消息一单复用 uk_reporter_target、已撤回消息可举报）；`content_snapshot` 存消息 content（CARD 存 ref 标识如 `CARD:PRODUCT#123`）。
4. `ReportCase` 增加 MESSAGE 分支：调内部撤回 API → 落 PROCESSED/IGNORED 同既有状态机；**不走扣分流水**；举报人/被举报人通知沿用既有 `REPORT_RESULT`（notification 域不动）。

## 验收要点

- 超 2 分钟撤回被拒；非本人被拒；撤回后占 seq 不动水位线计入未读。
- 强制撤回无窗口限制、对已自行撤回消息幂等；一举报者一消息一单。
- 撤回后 GET messages/last_msg 均为「已撤回」口径，content 不出服务端。

## Comments
