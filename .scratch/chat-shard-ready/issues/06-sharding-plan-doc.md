# 06: 分库预案文档重写（最终一致形态定稿）

**What to build:** 分库预案文档反映 01–05 已落地的代码形态：片键表、写路径（发消息 = room 片本地事务 + outbox 异步扩散，建会话 = pair 先行 + room 懒物化 + room_member 异步建）、读路径（列表/未读/补拉 user 片单分片、历史按会话键点路由、已读回执跨片精确点查、msg_id 经 timeline 路由）。完成后：分库日只剩 sharding 配置 + 数据回填，不再有设计工作。预案必须废除「发消息单事务」假设（业界零跨片强一致先例）。

**Blocked by:** 01、02、03、04、05。

**Status:** ready-for-agent

> **uuid-biz-id 票 04 注记（2026-11-01）**：room_id/msg_id 已翻转为 UUID 字符串（`VARCHAR(36)`），room_member/timeline/direct_room_pair 的引用键同步。本票预案与自检中的「片键哈希」一律按**字符串哈希**口径书写（`user_id`/`user_min`/`room_id`/`msg_id` 均为字符串取哈希路由），不得沿用数值取模或 `Long.hashCode` 假设；票面其余 BIGINT 假设一并按 String 体系执行。
> **chat-member-table-refactor 票 03 注记（2026-11-07）**：成员表双维度拆分后表名/口径更新——用户维度 `user_room`（user_id 片）、会话维度名册 `room_member`（room_id 片）；`direct_room_pair` 即 user_pair_room。片键表：`room_id` → room/message/**room_member(名册)**；`user_id` → **user_room**/timeline/user_seq；`user_min` → user_pair_room。读路径清单：列表/未读/补拉 user 片单分片（预览按 (room_id, room.last_seq) 点查 message 现算）、历史键点路由、已读回执跨片精确点查（直查对方 user_room 行）、msg_id 经 timeline 路由。`peer_id 盖章`记为**作废**（名册结构性替代）；`peer_read_seq` 副本**降级**为触发点清单后备开关、不落列。建会话分库形态：两行名册 + 两行 user_room 消费端异步建。详见 spec §3.5（已按此口径改写）。

- [ ] 片键表：`room_id` → room/message/room_member(名册)（发消息 = 唯一强一致事务）；`user_id` → user_room/timeline/user_seq（消费端写，最终一致）；`user_min` → user_pair_room
- [ ] 建会话分库形态：pair 事务先行（幂等裁决）→ room 行首条消息 INSERT...ODKU 懒物化 → 两行名册 room_member + 两行 user_room 消费端异步建
- [ ] 读路径形态与触发点预留清单（列表/未读/补拉 user 片单分片、历史键点路由、已读回执跨片点查对方 user_room、msg_id 经 timeline 路由；作废项注记：peer_id 盖章作废、peer_read_seq 降级后备开关不落列；ES 检索投影等触发点保留）
- [ ] 与本仓库 timeout_task 分表先例（dbCount=1 硬约束、逻辑表名纪律）的口径衔接
- [ ] 出处：调研报告 §10 结论与野火/微信源码级对照（`.scratch/chat-refactor/research/05-业界IM存储方案对比与库表设计评估.md`）
