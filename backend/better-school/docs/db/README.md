# 库表总览

营销库总览在根 `backend/docs/db/README.md`（票 06 起积分、票 07 起抽奖，两域随 `backend/marketing/` 落位为同一个库 `bs_marketing`；票 09 起只余这两域）。

**DDL 唯一来源**：`sql/schema.sql`（业务表见下方清单，另含 16 张 `timeout_task_00..15` 超时分表——票 05 按根 `sql.md` 重建、超时中心内嵌化票 02 增分表）。`sql/init-dev.sql` / `sql/init-test.sql` 只建库与 `USE`，**必须与 `schema.sql` 按序拼接喂给同一个连接**才建得出表；票 05 起不再有增量迁移脚本（`sql/migrations/` 与迁移测试已整体删除），改表 = 直接改 `schema.sql` + 重建库。**物理表数 = 40 张业务表 + 16 张超时分表 = 56**（原第 30 张注册意图表随内嵌化票 03 删除；此前漏登 12 张——`review_task`、两轮 feed 索引/浏览族 9 张（feed-index-uv / market-feed-uv 票 01）、评论计数与热评索引 2 张（comment-count-narrow-table 票 01/02）——本次随 `sql.md` 一并补齐；除 `review_task` 外这些表尚无独立小节，语义以 `sql.md` 对应表节为准）。

| 库 | 归属 | DDL 来源 | 表文档 |
| --- | --- | --- | --- |
| `better_school` | 单体（`backend/better-school/bs-*`） | `sql/init-dev.sql` + `sql/schema.sql` | [user](user.md) · [community](community.md) · [community_comment](community_comment.md) · [trade](trade.md) · [errand](errand.md) · [chat](chat.md) · [moderation](moderation.md) · [outbox](outbox.md) · [timeout](timeout.md) |

## 表清单（业务表，另见 timeout.md 的 16 张分表）

|表名|归属文档|表说明|
|----|--------|------|
|bs_user|[user](user.md)|用户表|
|virtual_wallet|[user](user.md)|虚拟钱包表（一人一行）|
|wallet_detail|[user](user.md)|钱包流水表（跑腿 + 商品共用）|
|user_credit_detail|[user](user.md)|信用分流水表|
|post|[community](community.md)|帖子表|
|post_like|[community](community.md)|帖子点赞关系表|
|post_like_count|[community](community.md)|帖子点赞计数表（专用窄行，异步刷盘）|
|post_feed_index|[community](community.md)|帖子 feed 排序投影（一帖一行 1:1 索引，hot/time 排序键）|
|user_view_history|[community](community.md)|帖子浏览明细（UV 事实源，一人一帖一行）|
|post_view_count|[community](community.md)|帖子浏览 UV 加速列（精确值 = 明细行数）|
|post_comment|[community_comment](community_comment.md)|帖子评论表|
|post_comment_count|[community_comment](community_comment.md)|帖子评论计数表（专用窄行，同步维护）|
|post_comment_like_count|[community_comment](community_comment.md)|评论点赞计数表（专用窄行，计数真源）|
|post_comment_like|[community_comment](community_comment.md)|评论点赞表|
|post_comment_feed_index|[community_comment](community_comment.md)|根评论热评排序投影（只服务「最热」序）|
|product|[trade](trade.md)|二手商品表|
|product_favorite|[trade](trade.md)|商品收藏表|
|product_order|[trade](trade.md)|二手商品托管订单表|
|product_order_event|[trade](trade.md)|商品订单状态流转记录（只插不改）|
|product_feed_index|[trade](trade.md)|商品推荐排序投影（一商品一行）|
|product_view_history|[trade](trade.md)|商品浏览明细（UV 事实源）|
|product_view_count|[trade](trade.md)|商品浏览 UV 加速列|
|errand_order|[errand](errand.md)|跑腿订单表|
|errand_order_event|[errand](errand.md)|跑腿订单状态流转记录（只插不改）|
|errand_feed_index|[errand](errand.md)|跑腿大厅排序投影（一单一行）|
|errand_view_history|[errand](errand.md)|跑腿浏览明细（UV 事实源）|
|errand_view_count|[errand](errand.md)|跑腿浏览 UV 加速列|
|room|[chat](chat.md)|会话表（1v1）|
|room_member|[chat](chat.md)|会话成员状态（每会话恒两行）|
|message|[chat](chat.md)|消息内容表（内容中心，只写一份）|
|timeline|[chat](chat.md)|时间线（写扩散收件箱，只插不改）|
|user_seq|[chat](chat.md)|投递序号计数器（每用户一行）|
|notification|[chat](chat.md)|站内通知表（scope + 水位线已读模型）|
|notification_read|[chat](chat.md)|通知已读水位表（每用户每范围一行）|
|notification_seq|[chat](chat.md)|通知收件序号计数器表（每用户每范围一行）|
|content_report|[moderation](moderation.md)|举报单表|
|content_review|[moderation](moderation.md)|审核动作记录表（只插不改）|
|review_task|[moderation](moderation.md)|审核工单表（在途派单凭据，裁决结论落 content_review）|
|sensitive_word|[moderation](moderation.md)|敏感词库表|
|outbox_message|[outbox](outbox.md)|本地消息表（outbox）|
|timeout_task_00..15 ×16|[timeout](timeout.md)|超时任务分表（内嵌化票 02；逻辑表 `timeout_task`，ShardingSphere hint 路由）|

## 全库口径（票 05 起）

- **时间戳**：每表以 `create_time` / `update_time` 收尾，**不带 DB 默认值**（应用写入），全库无 `created_at` / `updated_at`。**唯一例外**：`timeout_task_00..15` 的 `created_at` / `updated_at`（按原样自原独立超时库迁入，改名要动 16 份 DDL + mapper XML + 存量迁移，收益只有命名整齐）。
- **状态列**：统一叫 `state`（`bs_user` / `post` / `post_comment` / `product` / `errand_order` / `product_order` / `message` / `outbox_message`）。**例外**：`content_report` 的状态列仍叫 `status`（PENDING/PROCESSED/IGNORED）。同名不同型注意：业务表 `state` 是 varchar 枚举名，`outbox_message.state` 是 tinyint 数字码（0/1/2）。
- **主键**：`id` 恒为无业务含义的物理自增；对外与跨表引用一律用业务 ID 列并配 `uk_*` 唯一键（`user_id` / `post_id` / `comment_id` / `product_id` / `product_order_id` / `errand_order_id` / `room_id` / `msg_id` / `notification_id` / `wallet_id`）。写入口径：业务列先插 0，同事务 `UPDATE t SET biz_col = id WHERE id = ?` 回写。**例外**：各域 feed 索引 / 浏览明细 / 浏览计数 / 评论热评索引投影表不设 `id`，主键直接用业务键（清单见 `sql.md` 的主键口径例外段）。
- **开发/测试库名隔离**：单体 `better_school` / `better_school_test`（测试栈 host 端口 +10000）。
- 建库/重建命令见 [`../../../docker-compose.yml`](../../../docker-compose.yml) 头注释 1，或直接 `backend/scripts/test-env.sh up`；改了 `schema.sql` 必须 `scripts/test-env.sh reset-test-db`，否则测试跑在过期 schema 上出假绿。
- **无业务 ID 列的表**：明细/流水/计数/事件/关系类表（`post_like`、`post_comment_like`、`post_like_count`、`post_comment_count`、`post_comment_like_count`、`product_favorite`、`product_order_event`、`errand_order_event`、`room_member`、`timeline`、`user_seq`、`notification_read`、`notification_seq`、`wallet_detail`、`user_credit_detail`、`content_report`、`content_review`、`sensitive_word`）靠自身 `uk_*`（或 `id`）寻址，不另设业务列。
