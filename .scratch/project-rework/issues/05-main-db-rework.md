# 票 05 · 主体库照 sql.md 重构（better_school 29 张）

Type: task
Status: resolved
Blocked by: 02, 03, 04

## Question

以根目录 [`sql.md`](../../sql.md) 的 better_school 库（29 张）为准改造主体服务库与代码行为：

- **删表**：want_to_buy、errand_order_member、agent_review_log、errand_evaluation（跑腿评价随本轮下线）、user_daily_active、delay_outbox_message。
- **改名**：room_user→room_member、wallet_transaction→wallet_detail、user_credit_log→user_credit_detail（PO/mapper/代码全改）。
- **新增**：post_like_count / post_comment_count 计数窄表 + **计数行为改造**——点赞事务 = INSERT 关系表（uk 幂等）+ 投递计数消息（走 outbox_message），消费端按窗口聚合 ±1 合并成一条 UPDATE 落窄表；浏览量同模式；product_order_event / errand_order_event 事件流水表 + 订单状态流转处落事件。
- **DDL 落地**：backend/better-school/sql/schema.sql 重写为 sql.md 版本（create_time/update_time 口径、业务列+uk 口径全库对齐）；init-dev/init-test 同步。
- 本地开发库直接重建，不写迁移脚本。
- 验收：schema.sql 与 sql.md 逐表一致；点赞/浏览计数走 MQ 聚合落窄表；订单事件落表；相关测试改绿。

## Answer

已收口。主体服务库按 sql.md 重写为 29 张表并全链落地：DDL、PO/Entity/mapper/仓储/域服务/job/consumer、集成测试与文档全部对齐新口径。

### 验收对照

| 票面验收 | 结果 | 证据 |
| --- | --- | --- |
| schema.sql 与 sql.md 逐表一致 | ✅ | `.scratch/project-rework/cmp_schema.py`：29 张表名一致，27 张逐列（名/类型/NOT NULL/默认值/键/顺序）全等，2 张（`post_like_count`、`room.state` COMMENT）仅注释措辞不同 |
| 点赞/浏览计数走 MQ 聚合落窄表 | ✅ | `PostCountAggregator` + `PostCountConsumer`；新增 `PostCountAggregationIntegrationTest`（3 用例） |
| 订单事件落表 | ✅ | `errand_order_event` / `product_order_event`；`ErrandFullChainIntegrationTest`、`TradeFullChainIntegrationTest` 各补事件流水断言 |
| 相关测试改绿 | ✅ | 全量 `mvn -o test` 230 测试通过（独占测试库跑；详见下方「测试环境注意事项」） |
| 文档同步 | ✅ | `docs/db/*` 全量重写（`cmp_db_docs.py` 校验 29/29 收录、字段集合与顺序与线上库一致）、`docs/api/*`、`CONTEXT.md`、`sql.md`、`backend/CLAUDE.md` |

### 施工要点

- **删表**：6 张全链删除（PO/DAO/mapper/entity/仓储/域服务/job/consumer 一并清），跑腿评价随本轮下线。
- **改名**：3 张表 + 类名（`RoomUserPO→RoomMemberPO` 等）+ `IChatRepository` 方法名（`insertRoomUsers→insertRoomMembers` 等）。
- **计数分级**：点赞/浏览走 outbox 计数消息 + 内存窗口（200ms）合并刷窄表，属**近似值**；评论数走 `post_comment_count` **同事务同步** upsert，是精确值。`post` 行不再存这两列，查询侧 LEFT JOIN 窄表 + COALESCE 投影。
- **事件流水**：`ErrandRepository` 15 处、`ProductOrderRepository` 5 处条件 UPDATE 成功分支各落一条事件，无遗漏（两轴 review 独立复核确认）。
- **不写迁移**：`sql/migrations/*`（28 个）与全部 `*MigrationTest.java`、`MigrationTestBase.java` 删除；本地开发库已 drop 重建（29 张表校验通过）。

### ⚠️ 三处 sql.md 回写（本文档是库表事实源，按 map.md 允许「票内定并回写」）

1. **决策 A：`room` 补回 `user_min`/`user_max`/`source`/`initiator_id` + `uk_direct_pair`。**
   理由：这 4 列撑着两条已定行为——1v1 查重（`uk_direct_pair`，并发建会话撞键幂等回既有）与「会话来源 / 对家角色」标签（`CONTEXT.md` 词条 + 市集消息页 + `ChatRoomSourceIntegrationTest`）。去掉需另设计防重方案。**当时问过用户未得明确答复，选了可逆的加法。** 若后续要求严格照原 sql.md，需一并删这 4 列、`RoomSourceVO`/`RoomPeerRoleVO`、`ChatController.parseRoomSource`、`ChatQueryServiceImpl.peerRoleOf`、前端标签与 `ChatRoomSourceIntegrationTest`。

2. **决策 B：`post`/`product` 补回 `review_round` + `review_entered_at`。**
   三条理由缺一不可：① `content_review.review_round` 列注释写的是「第 N 次**进入待审**」，而某轮没有裁决就结束（撤回→改→重提）时，「进入次数」与「1+COUNT(裁决行数)」不等价，推导口径下轮次不 +1；② 帖子 24h 待审提醒的 `dedup_key` 嵌了轮次，轮次不 +1 ⇒ 撤回重提后再也不会提醒（原行为是「重提后新一轮须可再次提醒」）；③ sql.md 里 post.state 注释声称提醒走 `timeout_register_outbox` 注册 AUDIT_REMIND，**该机制从未实现**（现实是轮询扫描），故必须要一个可靠的进入待审时间——而 `update_time` 会被浏览量聚合器刷新（待审期被作者看一眼就重置 24h）。顺带订正了 `post.state` 注释里未实现的 AUDIT_REMIND 说法。

3. **消息级幂等键澄清（review 发现，本轮补记）：`post_like_count` 说明节原写「以 post_like 的 uk `(post_id, user_id)` 为消息幂等键」，与实现不符。**
   实现是生产端生成 `msgId`（UUID，随载荷走），消费端按它环形去重。**代码是对的**：`like → unlike → like` 是合法序列，按关系表 uk 去重会把第三次点赞误判为重投而丢增量；outbox 重发复用同一 payload ⇒ 同 `msgId`，天然被吸收。已改 sql.md 与 schema.sql 注释，让文档与实现一致。

### 本轮顺带修掉的缺陷

| 缺陷 | 影响 |
| --- | --- |
| `IProductDao` 缺 `bindBizId` 声明 | 主代码编译失败 |
| `ErrandRepository.save` / `PostCommentRepository.saveComment` 用插入前恒 null 的实体 ID 当业务 ID | `createOrder` 返回 null；支付超时注册到空 bizId |
| `PostCountAggregator` viewDelta 重复累加 + 与 `buffers.remove` 之间的丢增量竞态 | 浏览量翻倍；窗口增量静默丢失 |
| `TimeoutRegisterOutboxMapper.selectByKey` 占位符 `#{type}` 与 DAO `@Param("taskType")` 不匹配 | **每次登记超时任务都抛 BindingException**（下单/支付直接 500） |
| `ModerationMapper` 队列/详情 `state` 未别名成 PO 的 `status` 字段 | 审核队列 `status` 恒 null（`ErrandReviewQueueIntegrationTest` 抓到） |
| `ModerationMapper`/`PostMapper` 用物理 `id` 关联 `content_review.target_id` | 审核记录关联错目标 |
| 审核轮次口径被上一轮做坏（派生式） | 撤回重提不再提醒；见决策 B |
| `ModerationMapper.selectReviewRound` 派生式死查询 | 与「读真实列」口径矛盾，已删 |

另：`from_state` 语义订正——原先发布方取消（4 前态）、争议（3 前态）、超时（3 前态）、商品买家取消（2 前态）都写 NULL，与「NULL = 创建事件」冲突，事件流分不清「创建」与「从 TAKEN 取消」。现改为在条件 UPDATE **之前**读业务行 state 落事件，NULL 只留给创建事件。

### 测试

- 新增 `PostCountAggregationIntegrationTest`（3 用例）：outbox 落 ±1 计数消息 → 直喂 `PostCountConsumer` → 轮询断言窄表/`view_count`；同 `msgId` 重投被去重。测试栈无 broker，故在「生产侧落 outbox」与「消费侧入口」的接缝上各断言一半拼成端到端。
- `ErrandFullChainIntegrationTest` +2 处事件断言（通过链 `CREATED→PAID→REVIEW_APPROVED`、拒绝链含 `reason` 列）；`TradeFullChainIntegrationTest` 新增商品订单事件流水用例（`CREATED→PAID→DELIVERED→COMPLETED` 与取消分支 `reason_type`）。
- 回退 8 个文件里按派生轮次写的断言（`PostDraftBox`、`PostReviewFirstReview`、`PostReviewTimeoutRemind`、`TradeRejectDraftAndEdit`、`TradeReviewDecide`、`TradeTakedownExpireRemind`、`TradeFullChain`、`JobFanoutVirtualThread`）。
- 修 `CommunityApiTestBase.postCommentCountOf`：窄表无行时应为 0 而非抛 EmptyResultDataAccess。

**测试环境注意事项（下一次跑全量的坑）**：集成测试共用 `better_school_test` 一个库，而 `CommunityApiTestBase` 用 `TRUNCATE`（DDL）清表。**同时跑两个 `mvn test` 必然互相污染**——TRUNCATE 重置自增 id 后两个进程会造出同一批 user id，症状是「手机号已被注册」「用户不存在」「信用分低于 60」，以及 MySQL `error code 1412 Table definition has changed`（DDL 撞在途事务）。本轮排查中一度误判为代码缺陷，实为并发跑测试。跑全量务必独占测试库。

### 连带影响（不在本票范围）

- **跑腿评价整体下线**：`errand_evaluation` 表、评价 DTO/接口/域服务全删；`CONTEXT.md` 的「信用分」词条已去掉「获得好评加分」，因 `CreditReasonTypeVO` 现无任何加分类型（所有 `dailyCap` 均为 null）。
- **前端 `condition`（成色）已无后端支撑**：`sql.md` 的 product 无该列，DTO 与 `ProductConditionVO` 已删；前端 `front/src/types/api.ts`、`api/trade.ts`、`ProductPublishView.vue`、`ProductCard.vue`、`ProductDetailView.vue` 仍在发/显示成色，归**票 09** 清理（Spring 忽略未知字段，不报错，只是卡片标签渲染为空）。

### 复核工具（入库当工具，可重复跑）

- `.scratch/project-rework/cmp_schema.py`：schema.sql ↔ sql.md 逐表逐列比对
- `.scratch/project-rework/cmp_mapper_params.py`：DAO `@Param` ↔ mapper `#{}` 占位符比对（抓出上面那条 BindingException）
- `.scratch/project-rework/cmp_api_routes.py`：Controller 路由 ↔ docs/api 路径覆盖比对
- `.scratch/project-rework/cmp_db_docs.py`：docs/db 表/列 ↔ 线上库比对
- `.scratch/project-rework/dev_schema_*.tsv`：开发库 schema 导出（三个脚本的输入基线）

### code-review 结论（两轴独立）

- **Standards**：无硬性违规；判断项已处理或记录——`PostCountAggregator` 放在 `adapter/repository/post` 下与 §3「该包 = 实现 domain 仓储接口」的措辞不符（它是单例聚合器不是仓储，位置沿用同域就近原则）；`a.b().c()` 类长的 id 绑定样板在 10 处重复（`INSERT` 0 → `bindBizId` → `setBizId(getId())`），是当前口径的固有成本；`NotificationEntity.setId(业务ID)` 与 `CreditDetailEntity.id`（物理 id）两个 id 空间混用，已在文档标注。
- **Spec**：验收项全部落实，无漏落状态分支；两处 sql.md 回写内部自洽。发现的偏差点即上面的第 3 条幂等键澄清与 `from_state` 语义，均已在代码/文档侧订正。

> **交接文档**：[`05-handoff.md`](05-handoff.md) —— 记录了本轮中段的环境命令、已完成清单、两处 sql.md 回写决策与踩坑清单（原文保留，其「未完成」清单均已完成）。
