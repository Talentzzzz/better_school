# 02: 预览去副本——读时按 message 真相现算（B 方案）

**What to build:** 会话列表预览不再落副本：user_room 删除 `last_msg_type`/`last_msg_preview` 两列；发送事务里 user_room×2 只刷 `last_msg_time`；撤回（用户自撤/审核强制）路径不再有任何预览维护写。列表读路径新增一段——按 `(room_id, room.last_seq)` 批量点查 message 最新一条，渲染规则落应用/领域拼装层：`RECALLED`→[已撤回]、`IMAGE`→[图片]、`CARD`→按 `ref_type` 出 [商品]/[帖子]/[跑腿]（不查标题）、`TEXT`→原文截 200。用户可见行为不变（列表排序、未读、预览文案、弹回），唯一行为增强：预览永远等于 message 真相、撤回即时反映。

**Blocked by:** 01（表拆分与改名落地，user_room 名实就位）

**Status:** ready-for-agent

- [ ] schema.sql：user_room 删预览两列，表注释同步「预览读时现算、message 唯一真相」
- [ ] 写路径：发送只刷 `last_msg_time`×2；撤回路径预览写全部移除（写路径净减一处）
- [ ] 读路径：三段查变四段——新增 message 按 (room_id, last_seq) 批量点查 + 拼装层渲染；SQL 层零 JOIN 不破坏；渲染逻辑有归属（不散在 controller/SQL）
- [ ] 集成测试：翻新后新增断言「撤回不经过任何预览写路径、列表读时直接呈现 [已撤回]」；既有预览相关断言语义不变
- [ ] 交付闸门（不起环境）：compile + test-compile + 纯单测全绿；集成测试翻新到位不实跑，票面标注同票 01
