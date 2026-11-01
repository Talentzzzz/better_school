# 01: DDL 收口——列表职责迁往 room_member（room 删三列、room_member 增四列 + 存量回填）

**What to build:** 会话列表的排序键与预览三件套从会话维度（room 行）迁到用户维度（room_member 行），room_member 增 `last_seq_copy` 使未读数可在用户维度就地可算。完成后：列表所需数据全部在用户维度行上（与 OpenIM Conversation 行同构），room 回归纯取号器 + 会话真相；存量库经割接脚本迁移后新旧行为等价，现有集成测试全绿。本票为宽改票（expand–contract 在票内完成：先加新列并存 → 读写迁移 → 删旧列），以「现有测试全绿」为闸门。

**Blocked by:** None (can start immediately).

**Status:** resolved

- [x] room 删 `last_msg_type/last_msg_preview/last_msg_at` 三列（DDL + 存量割接脚本，非幂等，头注释含执行序；新库建全）
- [x] room_member 增 `last_seq_copy/last_msg_type/last_msg_preview/last_msg_at` 四列，`idx_user` 换为 `idx_user_msg (user_id, last_msg_at)`
- [x] 存量回填：从 message + room 现值一次性回填 room_member 四列
- [x] 未读数算式切换为 `last_seq_copy − last_read_seq`（本票先保证副本有值，消费端维护归 02；本票内由回填与既有写路径过渡保证值正确；markRead 目标同步推到 `last_seq_copy`，与未读算式同源）
- [x] 现有 chat 集成测试全绿（发送/列表/未读/补拉/撤回/弹回行为不变）
