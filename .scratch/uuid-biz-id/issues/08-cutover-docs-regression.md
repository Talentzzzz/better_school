# 08: 收口（UUID 断言 + 割接脚本退役 + 文档口径 + 全量回归）

**What to build:** 全仓库 ID 改造的最终验收与文档收口：关键链路补「返回 id 可被 UUID.fromString 解析且用该字符串引用成功」的形态断言；删除/改写已退役的存量割接回填脚本（room-list-dim-cutover.sql 等按 BIGINT 体系书写的脚本）；docs/api 的 id 字段类型改 string、示例值改 UUID，docs/db、sql.md、CONTEXT.md（含「热度」词条确定序新口径）、backend/CLAUDE.md 相关约定与代码对齐。这是唯一承诺全量绿的收口票。

**Blocked by:** 03, 04, 05, 06, 07

**Status:** ready-for-agent

> **票 04 注记（2026-11-01）**：`ChatMapper.xml` 的 room/message 列口径注释仍标注「（sql.md）」，而 sql.md 的 chat 段还是 `room_id/msg_id BIGINT` + 「业务 ID 回写」旧口径——本票收口时 sql.md 的 chat 五表段（room/direct_room_pair/room_member/message/timeline）须按 VARCHAR(36) + 应用层发号新口径重写。另：front 的 chat 详情页存在 `Number(route.params.roomId)` 硬编码与 `/chat/:roomId(\\d+)` 数字路由正则（front 整体 userId 也仍按 number 定型），front 适配不在本票范围，收口时须确认已另立 front 适配票。

> **票 05 注记（2026-11-02）**：sql.md 的 trade 段（product/product_favorite/product_feed_index/errand_feed_index/两对 view_history/view_count/errand_order(+event)/product_order(+event)/wallet_detail.order_id/user_credit_detail.ref_id）仍是 BIGINT + 「业务 ID 回写」旧口径，须按 VARCHAR(36) + IIdGenerator 发号重写；product_feed_index/errand_feed_index 收口时补「热度档 = view_count DESC, create_time DESC, 业务 id DESC 三段收敛键」口径与新增 create_time 列说明。front 的市集页（MarketView 等）如存在按数字解析 product/errand id 的硬编码，随 front 适配批次一并收口。

> **票 06 注记（2026-11-02）**：sql.md 辅助域段仍是旧口径——notification.notification_id / virtual_wallet.wallet_id / wallet_detail.wallet_id 标 BIGINT、content_review/review_task/content_report 三表还缺 review_id/task_id/report_id 业务列与 uk（收口时按 VARCHAR(36) + 应用层 IIdGenerator 发号重写，并补「物理自增 id 保留纯内部键，领单候选/队列 tie-break 用物理 id ASC」口径）。front-review 手抄类型 `src/types/review.ts`（ReviewTaskClaim.taskId、ReviewTaskDetail.taskId、ReviewHistoryItem.reviewId）与举报单 id 的 number 定型需随 front 适配批次同步为 string。票 06 集成测试因「不跑全量」豁免未执行，本票全量回归时重点复跑 moderation 四类 + ChatRecallReport + CommentDeleteSemantics + 通知读模型 + 超时端到端。

- [ ] 注册、发帖、评论、发消息、下单、抽奖等新建资源返回的 id 均可被 UUID.fromString 解析，且后续请求用该字符串 id 引用成功（集成断言）
- [ ] 退役割接脚本删除或按新类型体系重写，仓库内无 BIGINT 业务键残留（schema 全文检查）
- [ ] docs/api、docs/db、sql.md、CONTEXT.md、backend/CLAUDE.md 与代码一致
- [ ] better-school 全量（~410 用例）与 marketing 全量全绿
