# 08: 收口（UUID 断言 + 割接脚本退役 + 文档口径 + 全量回归）

**What to build:** 全仓库 ID 改造的最终验收与文档收口：关键链路补「返回 id 可被 UUID.fromString 解析且用该字符串引用成功」的形态断言；删除/改写已退役的存量割接回填脚本（room-list-dim-cutover.sql 等按 BIGINT 体系书写的脚本）；docs/api 的 id 字段类型改 string、示例值改 UUID，docs/db、sql.md、CONTEXT.md（含「热度」词条确定序新口径）、backend/CLAUDE.md 相关约定与代码对齐。这是唯一承诺全量绿的收口票。

**Blocked by:** 03, 04, 05, 06, 07

**Status:** ready-for-agent

> **票 04 注记（2026-11-01）**：`ChatMapper.xml` 的 room/message 列口径注释仍标注「（sql.md）」，而 sql.md 的 chat 段还是 `room_id/msg_id BIGINT` + 「业务 ID 回写」旧口径——本票收口时 sql.md 的 chat 五表段（room/direct_room_pair/room_member/message/timeline）须按 VARCHAR(36) + 应用层发号新口径重写。另：front 的 chat 详情页存在 `Number(route.params.roomId)` 硬编码与 `/chat/:roomId(\\d+)` 数字路由正则（front 整体 userId 也仍按 number 定型），front 适配不在本票范围，收口时须确认已另立 front 适配票。

- [ ] 注册、发帖、评论、发消息、下单、抽奖等新建资源返回的 id 均可被 UUID.fromString 解析，且后续请求用该字符串 id 引用成功（集成断言）
- [ ] 退役割接脚本删除或按新类型体系重写，仓库内无 BIGINT 业务键残留（schema 全文检查）
- [ ] docs/api、docs/db、sql.md、CONTEXT.md、backend/CLAUDE.md 与代码一致
- [ ] better-school 全量（~410 用例）与 marketing 全量全绿
