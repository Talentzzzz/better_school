# 项 4 — 1v1 私信

**判定：PASS**（脚本 `smoke_03_04_errand_chat.py` 项 04 共 24 条断言全过；连续跑 3 次均 0 FAIL，可重复执行）
**证据脚本**：[`smoke_03_04_errand_chat.py`](../smoke_03_04_errand_chat.py)
**号段**：A = `13700017301`（userId 6），B（对家）= `13700017303`（userId 12），非成员探针 = `13700017302`（userId 7）

## 1. 发送 seam（明确说明）

私信**全部走 REST**，没有用 WS：

- 建会话 `POST /api/chat/rooms/direct`；发消息 `POST /api/chat/rooms/{roomId}/messages`；
  读历史 `GET /api/chat/rooms/{roomId}/messages`；补拉 `GET /api/chat/timeline?afterDeliverySeq=`；已读 `POST /api/chat/rooms/{roomId}/read`。
- 这与 chat 集成测试同一 seam：`ChatReadPathIntegrationTest` / `ChatSendPipelineIntegrationTest` / `ChatRecallReportIntegrationTest`
  全部用 `postJson("/api/chat/rooms/{id}/messages", ...)` 发送，**不需要 WS 票据握手**。
- `POST /api/chat/ws-ticket` 单独探了一次（拿到非空票据，说明握手端点可用），但本次发送链路**未使用**它——
  即「HTTP 发送路径对 WS 无依赖」这一点被顺带证明。

## 2. 断言结果（roomId=1）

| 断言 | 实测 |
| --- | --- |
| 建会话返回 roomId | 1 |
| `room` 行数（按 `user_min/user_max` 这一对人） | 1（`uk_direct_pair`） |
| 对家反向再建一次 | 幂等回同一 roomId=1，`room` 行数仍 1 |
| `room_member` 行数与成员 | 恰 2 行 = {6, 12} |
| A→B / B→A 各发一条（`clientMsgId` 带时间戳，可重复跑） | 返回 seq=5 / seq=6，`fromUserId` 分别 6 / 12，seq 差 1 |
| `message` 表持久化 | seq 5/6 各一行，`state=NORMAL`、内容与 `client_msg_id` 与请求一致 |
| `room` 冗余列 | `last_seq=6`、`last_msg_type=TEXT`、`last_msg_preview` = 最后一条原文 |
| B 用 API 回读历史 | `GET /rooms/1/messages`（B 的 token）返回 seq 5 与 6，含 `content`/`fromUserId`/`fromNickname` |
| 成员接口 | `memberCount=2`（含各自 `lastReadSeq`） |
| 写扩散 `timeline` | 每个 seq 双方各一行（共 4 行），`uk_user_delivery` 的 `delivery_seq` = 5/6 单调 |
| 补拉游标 | `GET /api/chat/timeline?afterDeliverySeq=4`（B 视角）返回这两条，证明增量游标口径 |
| 已读推进 | B 读前 `last_read_seq=4 < last_seq=6`，会话列表 `unread=2`；`POST /rooms/1/read` 返回 6；库内 `room_member.last_read_seq` 6；再拉列表 `unread=0` |
| 非成员隔离 | 接单方（userId 7）拉该会话历史 → 业务码 **403**（反证成员恰两人） |

## 3. 证据文件（`.scratch/project-rework/verify-17/evidence/`）

| 文件 | 内容 |
| --- | --- |
| `04-direct-message.json` | userA/userB/outsider、roomId、`room` 行、`room_member` 两行（含 `last_read_seq`）、两条 `message` 行、4 行 `timeline`、已读前后水位线、A/B 发送响应、历史接口返回的 seq 集合、非成员 403 码 |
| `03-04-summary.json` | 总体 `passed=57 / failed=[]`（03A+03B+04 合计） |

## 4. 失败项与判定

**无失败项**，故无「编排问题 vs 业务缺陷」判定需要下。首轮唯一的 FAIL 是本脚本自身缺陷（04 段用 `h.login()` 拉未注册的对家号 →
404「用户不存在，请先注册」），已改为 `h.register()`（对已注册号自动回落登录）后三轮全绿——**脚本缺陷，非编排问题、非业务缺陷**。
