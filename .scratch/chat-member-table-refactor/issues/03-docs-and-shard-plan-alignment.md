# 03: 文档与分库预案对齐

**What to build:** 全部文档口径从「单张 room_member 混装」翻到「user_room（用户维度）+ room_member 名册（会话维度）+ 预览读时现算」终形：sql.md chat 段逐字重排；docs/db/chat.md 重写（room_member 段拆为两张表、message 段补预览渲染规则归属、未读数算式改 read_seq、索引说明更新）；docs/db README 表数与主键/无业务 ID 列口径核对（两张表均无业务 ID 列）；CONTEXT.md 相关词条（会话列表/未读/已读回执）对齐。同时改写 chat-shard-ready 分库预案（票 06）：片键表更新为 `room_id` → room/message/room_member(名册)、`user_id` → user_room/timeline/user_seq；读路径清单改为「列表/未读/补拉 user 片单分片、历史键点路由、已读回执跨片精确点查（直查对方 user_room 行）、预览 message 点查现算」；`peer_id 盖章`记为作废（名册结构性替代）、`peer_read_seq` 降级为触发点清单后备开关不落列；建会话分库形态补「两行名册 + 两行 user_room 异步建」。

**Blocked by:** 02（代码终形定稿后文档才不会写两遍）

**Status:** resolved（纯文档票，无编译闸门；script 校验 sql.md 七张 chat DDL 与 schema.sql 逐字一致；验收 grep 达成——余下命中均为注记性提及/Avoid 词条/notification_read 同名列；捎带清扫 docs/api/chat.md 旧口径与 docs/db README 表数 40→42 历史漏登）

- [x] sql.md chat 段按 schema.sql 终形逐字重排（user_room / room_member 名册 / user_pair_room / message 预览注记）
- [x] docs/db/chat.md 重写两表条目 + message 段渲染规则迁注 + 索引/未读算式更新；docs/db README 口径核对
- [x] CONTEXT.md 词条对齐（未读数算式、已读回执、会话列表查询面）
- [x] chat-shard-ready 票 06 预案按上述口径改写（片键表/读路径/建会话形态/作废项注记）
- [x] 纯文档票：无编译闸门，验收 = 文档间互相引用无残留旧名（room_member 旧语义、last_read_seq、last_msg_at、peer_read_seq 列、peer_id 盖章）
