# 社区帖子表

> 业务：校园墙帖子 + 帖子点赞；归属模块：post

|表名|说明|
|----|----|
|post|帖子主表（内容 + 状态机）|
|post_rebate_claim_task|发帖返利领取任务（域内最终一致任务表，订单兼 outbox；不走 MQ）|
|post_like|帖子点赞关系（明细，uk_post_user 幂等）|
|post_like_count|帖子点赞计数窄表（点赞计数真源，进程内合并同步落库）|

**主键口径（uuid-biz-id 起）**：`id` 是纯内部物理自增，业务 ID 为 `post_id`（`uk_post_id`）；业务 ID 是应用层发号的 UUID 字符串（`VARCHAR(36)`，`UUID.randomUUID()` 在 insert 前生成、随行一次写入），与物理 `id` 永不同值、物理 id 永不对外。存量数据不迁移（删库重建），历史「插 0 回写」割接脚本已退役。

# post

> 业务：校园墙帖子（内容 + 状态机）；归属模块：post

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键，不对外|
|post_id|varchar(36)|UNIQUE(uk_post_id), NOT NULL|业务 ID（UUID 字符串）：跨表引用（post_comment / post_like / post_like_count）与接口出参用它|
|user_id|varchar(36)|NOT NULL, INDEX(idx_user)|发布者用户 ID|
|title|varchar(100)|NULL|标题（可选）|
|content|text|NULL|正文；草稿可空，提交时校验必填|
|images|varchar(1000)|NULL|图片 URL 列表（JSON 数组，MinIO）|
|cover|varchar(500)|NULL|独立封面 URL：可传相册外的独立上传图；展示口径统一为「独立封面 ?? 相册第一张」（读时兜底，不落回退值），均空即无封面帖|
|state|varchar(20)|NOT NULL, DEFAULT 'NORMAL'|NORMAL/DELETED/DRAFT；发布即对外可见：发布落库即 NORMAL，未发布进 DRAFT（原 `status` 列改名）|
|expire_at|datetime|NULL|过期归档时间（满 30 天下架，XXL-Job 后置）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

> 已删除的列：`category`（帖子分类，feed-index-uv 票 06 退役、票 07 删列——墙内容发现 = 时间线 + 搜索）、`view_count`（浏览切 UV 口径后退役，同票删列——真相源 `user_view_history`、加速列 `post_view_count`）与 `review_round` / `review_entered_at`（审核功能整体移除后删列——发布即对外可见，无审核轮次）。存量库不迁移（uuid-biz-id：删库重建），历史删列脚本已退役。

## 索引
- `uk_post_id (post_id)`
- `idx_user (user_id)`：我的帖子

> 已删除的索引：`idx_category_created (category, create_time)`（分类列表，随 `category` 列在 feed-index-uv 票 07 清理）。

## 关联
- 1:N → post_comment（帖子评论）、post_like（点赞明细）、post_like_count（点赞计数，1:1 语义）
- `like_count` / `comment_count` **不是本表列**：读路径由仓储按 id 集批量回查 `post_like_count` / `post_comment_count`（三窄表各一次单表 IN 查询）后 `COALESCE(..., 0)` 投影进 PO（SQL 层零 JOIN）
- 评论侧的点赞数与热评序不在本文件内：`post_comment` 行不存 `like_count`（comment-count-narrow-table 票 04 已删列），列表/详情的评论 `likeCount` 由 `post_comment_like_count` 按页内 id 集批量回查填充（缺行补 0）、「最热」档的顺序来自 `post_comment_feed_index`（漂移由 `commentFeedIndexRebuildJob` 清空重灌兜底）——见 [post_comment](post_comment.md)

## 注意
- 匿名字段已移除：所有内容实名展示
- 删除是软删（`state='DELETED'`，条件 UPDATE），列表/详情查询均排除
- 写状态一律条件 UPDATE（`WHERE post_id = ? AND state = ?`），不要无条件 UPDATE
- 发布即对外可见：发布落库即 NORMAL 并同事务落搜索同步消息，同时落一条 `PENDING` 领取任务（`post_rebate_claim_task`，与帖子行同事务）；同步领取成功后由 case 把该任务置 `DONE`，领取异常才留 `PENDING` 交 `postRebateClaimScan` 重放
- 草稿箱：DRAFT 仅作者可见（「我的发帖」含草稿态）；提交条件 UPDATE（DRAFT→NORMAL）并刷新 `expire_at`
- 封面 `cover` 独立列：发布/草稿可指定（不传存 NULL）；列表与详情展示走「独立封面 ?? 相册第一张」，编辑清空 cover 后自动跟随相册

# post_rebate_claim_task

> 业务：发帖返利领取任务（域内最终一致任务表，订单兼 outbox）；归属模块：post（ADR-0017）

**不是 MQ outbox**：本表与 `mq_outbox` 是两张不同的表。`mq_outbox` 负责跨进程投递；本表负责「这条帖子的返利领取还没做完」，由 post 域自己的 `postRebateClaimScan` 进程内重放，不经过 broker。形态对齐 `draw_count_purchase_order` / `timeout_task`。

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理自增（扫描抢占 / 死信按 id 条件更新）|
|post_id|varchar(36)|UNIQUE(uk_post_id), NOT NULL|帖子业务 ID：同一帖子只落一条任务|
|user_id|varchar(36)|NOT NULL|发帖用户 ID|
|period_key|varchar(16)|NOT NULL|返利周期键（`yyyyMMdd`，Asia/Shanghai）：发帖事务内按数据库 `update_time` 定死，重放沿用，避免跨零点串天|
|state|tinyint|NOT NULL, DEFAULT 0|0 待领取 / 1 已完成（领取成功、明确拒绝或无返利）/ 2 死信（重试耗尽）|
|retry_count|int|NOT NULL, DEFAULT 0|重试次数（扫描抢占时递增）|
|next_retry_at|datetime|NULL|下次重试时间（指数退避 1/2/4/8/16 分钟）；NULL = 立即可扫|
|finish_time|datetime|NULL|领取完成时间|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

## 索引
- `uk_post_id (post_id)`：同一帖子只落一条，重放插行幂等
- `idx_state_next_retry (state, next_retry_at)`：扫描 `WHERE state=0 AND (next_retry_at IS NULL OR next_retry_at <= NOW())`

## 流程
1. **发帖 / 提交草稿事务**：post 仓储插入本行（`PENDING`，与帖子行同生同死），不发送任何消息
2. **同步领取**：事务提交后请求线程调 rebate 域领取；正常返回（`CLAIMED` / `DENIED` / `NO_REBATE`）后置 `DONE`，异常则留 `PENDING`，接口返回「发放中」
3. **扫描收敛**：`postRebateClaimScan`（cron `0/30`）捞到点的 `PENDING` 行，抢占后进程内调 `replayClaim`；成功置 `DONE`，失败按 `next_retry_at` 退避，`retry_count` 达 5 置 `DEAD`
4. **幂等**：领取按 `(user_id, action_type, ref_type, ref_id)` 唯一键幂等，重复重放零副作用

# post_like

> 业务：帖子点赞关系（明细，事实源）；归属模块：post

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键|
|post_id|varchar(36)|NOT NULL, INDEX(uk_post_user 首列)|帖子业务 ID|
|user_id|varchar(36)|NOT NULL, INDEX(idx_user)|点赞用户 ID|
|create_time|datetime|NOT NULL|点赞时间|
|update_time|datetime|NOT NULL|更新时间|

## 注意
- `uk_post_user (post_id, user_id)`：一人一帖一条，点赞 `INSERT IGNORE` 幂等，取消点赞 `DELETE` 该行
- `idx_user (user_id)`：供「我的赞过」反查（配合 `bs_user.show_liked` 隐私开关做他视查询）
- 只存关系不存计数：计数在 post_like_count

# post_like_count

> 业务：帖子点赞计数窄行（展示值与计数真源，**精确值**）；归属模块：post

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键|
|post_id|varchar(36)|UNIQUE(uk_post), NOT NULL|帖子业务 ID（一帖一行）|
|like_count|int|NOT NULL, DEFAULT 0|点赞数（可负增量，但刷盘语句带 `GREATEST(0, ...)` 兜底不为负）|
|create_time|datetime|NOT NULL|首次建行时间|
|update_time|datetime|NOT NULL|最近刷盘时间|

## 注意
- **写路径**：点赞 / 取消赞走进程内合并（`CountMerger`，5ms 窗口）：请求等落库结果，**接口返回成功时本表计数已经精确**。一组一事务：`INSERT IGNORE` 关系行 + 一条 `INSERT ... ON DUPLICATE KEY UPDATE like_count = like_count + N`（N = 本组真正插入的行数）。没有 MQ、没有 msgId 去重、不做校准——关系行是事实源，计数按影响行数推进即恒等（`.scratch/hot-row-merge-l2w256/spec.md` §3.1）。**评论赞同走这台合并器、但落 `post_comment_like_count`**（形状同构，见 [post_comment](post_comment.md)）
- **精确性**：计数 == `COUNT(post_like WHERE post_id = ?)`，不是近似值；无行 = 计数 0，读侧恒 `COALESCE` 兜底
- 窄行设计目的：点赞高频写不锁 `post` 内容/状态行；纯浏览不写本表——浏览走 fire-and-forget 合并，UV 口径落 `user_view_history` + `post_view_count`（feed-index-uv 票 02，允许丢，只记丢弃数）
- 计数变更**不再失效帖子详情缓存**：详情缓存不存三个计数（票 05 收口后由读路径每次按主键取数）
