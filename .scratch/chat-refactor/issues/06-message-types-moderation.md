---
Labels: wayfinder:grilling
Type: grilling
Status: resolved
Blocked by:
---

# 票 06：消息类型与举报

## Question

消息类型与消息体结构（审核口径已在[票 05](05-group-rules.md)定死：chat 全路径零审核 + 举报兜底）：

- 图片：上传链路复用现有文件上传？大小/格式限制
- 业务卡片（商品/帖子/跑腿）：消息体结构——content 存 JSON、独立 ref 字段、还是混合？卡片引用的对象被删/下架后消息怎么渲染
- 各类型的发送前校验与大小限制
- 举报落地（票 05 已定规则）：`content_report` 扩 target_type=MESSAGE 的 DDL 与校验、强制撤回复用撤回机制的实现点、处理幂等（消息已被发送者自行撤回）

➡️ 产出：消息类型清单 + 每类的消息体结构 + 举报/强制撤回的落地设计。

## Answer

（2026-09-09 grilling 结论，一轮 + 收尾三小项，[定稿文档](../research/06-消息类型与举报.md)）

1. **类型清单**：TEXT / IMAGE / CARD / SYSTEM 四类定死；撤回是消息状态不是类型，已读/踢人走事件不走消息。
2. **消息体结构（混合）**：`msg_type` 枚举 + `content` 载荷（TEXT 纯文本 ≤500；IMAGE 存 JSON `{url,width,height}`；SYSTEM 存 JSON `{event,...}` + 服务端渲染文案）+ CARD 独立可空 `ref_type`/`ref_id` 列；否决全 JSON 与全独立列。DDL 归票 02。
3. **卡片纯实时渲染**（用户定向，快照方案否决）：渲染时按 ref 实时查，失效显示「内容已失效」；发送校验=对象存在且未被删除/拒绝（PENDING_REVIEW、SOLD 可发，errand 排除 REJECTED/CANCELLED）；CARD 的 content 存空，last_msg 预览用类型标签。
4. **图片**：复用 `/api/files/upload`（jpg/png/webp ≤5MB）链路不动；单条单图（多图拆条）；URL 校验本站上传域前缀；宽高客户端上报可缺省。
5. **文本**：≤500 收敛为单一常量（旧版 DTO 与 DB 双硬编码）。
6. **撤回**：发送者 2 分钟窗口，1v1 与群一致；message 加 `status`（NORMAL/RECALLED）+ `recalled_at` + `recalled_by`（USER/AUDITOR）；content 库内保留（取证），读路径与下发按状态脱敏为「已撤回」空壳；RECALL 轻推定位（票 04 协议）。
7. **举报**：已撤回消息也可举报（追责留痕，处理时幂等）；复用 `uk_reporter_target`；举报者须为会话成员；snapshot 存 content（CARD 存 ref 标识）；AUDITOR 处理 = 强制撤回走 chat 系统侧内部撤回 API（`recalled_by=AUDITOR`、无窗口限制），已自行撤回则只落结论；MESSAGE 分支不绑扣分。
