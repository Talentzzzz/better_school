# 03: 撤回与已读的副本统一走消费链路

**What to build:** 撤回（用户自撤 + 审核强制撤回）后，两端 room_member 的 `last_msg_preview` 刷为「[已撤回]」，且该副本更新与撤回通知同走 outbox 消费链路（room_member 副本字段的单一写入方原则落地）；已读水位线推进语义不变（打开会话推到 `last_seq_copy`）。完成后：撤回在列表上与详情页语义一致；room_member 副本无第二个写入方。

**Blocked by:** 01、02。

**Status:** ready-for-agent

- [ ] 票内前置确认：现行撤回推送是否已走 outbox——未走则补链路，已走则在同一消费点刷新两端预览
- [ ] 撤回后两端 `last_msg_preview` = 「[已撤回]」（用户自撤与 AUDITOR 强撤同语义）
- [ ] 已读推进目标改为 `last_seq_copy`，未读清零与角标行为回归
- [ ] 集成测试：撤回 → 对方列表预览变化（awaitDiffusion 后断言）；并发撤回与已读无互踩
