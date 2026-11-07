# 内容治理表（moderation：review_task / content_review / content_report / sensitive_word）

> 业务：审核工单派发、审核动作留痕、举报单流转、敏感词库；归属模块：moderation
> DDL 唯一事实源：`sql/schema.sql`（票 05 全库重写）；已删 `agent_review_log`（旧审核流水，不再有这张表）

# review_task（审核工单，在途派单凭据）

> 票 06 落库。与 `content_review` 的「只插入审计/历史裁决」语义严格分离：工单单生命周期、可变（领单/回收/裁决），
> 裁决结论落在 `content_review`，工单只有一个终态 `DONE`；**不承载裁决与可见性**——对外可见与否由业务行状态流转决定

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键（纯内部键；同刻入池 FIFO tie-break 用物理 id ASC——task_id 已 UUID 化，不再代表入池先后，uuid-biz-id 票 06）|
|task_id|varchar(36)|NOT NULL, UNIQUE(`uk_task_id`)|工单业务 ID（UUID 字符串，对外唯一标识）：应用层 insert 前由 `IIdGenerator` 发号随行一次写入；工单详情按它寻址：`GET /api/moderation/tasks/{taskId}`；租约复合 bizId 首段也是它（`{taskId}:{claimSeq}`）|
|target_type|varchar(20)|NOT NULL, `uk_target_round` 首列|内容类型 POST/COMMENT/PRODUCT/ERRAND（`ReviewTargetTypeVO` 可审值；USER/MESSAGE 仅举报用不入池）|
|target_id|varchar(36)|NOT NULL|内容业务 ID（post_id/comment_id/product_id/errand_order_id，UUID 字符串）|
|review_round|int|NOT NULL, DEFAULT 1, `uk_target_round` 第三列|审核轮次，与 `content_review.review_round` 同源（COMMENT/ERRAND 恒 1）|
|author_id|varchar(36)|NOT NULL|作者用户 ID（冗余，工单详情/历史聚合免回查工单表）|
|source|varchar(20)|NOT NULL, DEFAULT 'MANUAL'|入池来源 MANUAL/KEYWORD/RANDOM（当前仅 MANUAL，其余扩展点）|
|claim_state|varchar(20)|NOT NULL, DEFAULT 'PENDING'|工单态 PENDING(在池)/CLAIMED(租约中)/DONE(已裁决)。**不叫 status**（业务表已占 state）|
|claim_by|varchar(36)|NULL|当前持单人（审核员用户 ID）|
|claim_seq|int|NOT NULL, DEFAULT 0|领单轮次（每次 claim +1；租约复合 bizId `{id}:{claimSeq}` 与回调守卫）|
|lease_expire_at|datetime|NULL|租约到期时刻（claim 时 = NOW()+5min）|
|recycle_count|int|NOT NULL, DEFAULT 0|回收次数（≥3 置 escalated + 告警）|
|escalated|tinyint(1)|NOT NULL, DEFAULT 0|需人工干预标记|
|entered_at|datetime|NOT NULL|进入待审时刻（FIFO 排序/等待时长锚点；**回收不改**，等待时长继续累加）。取值：POST/PRODUCT 直取业务表 `review_entered_at`，COMMENT 取发布时刻、ERRAND 取支付成功时刻|
|decided_at|datetime|NULL|裁决时刻（DONE 时写）|
|create_time / update_time|datetime|NOT NULL|时间戳|

## 索引
- `uk_target_round (target_type, target_id, review_round)`：同一轮同一内容只开一张在途工单（`INSERT IGNORE` 幂等入池）
- `idx_queue (claim_state, entered_at)`：跨类型 FIFO 取候选
- `idx_queue_type (claim_state, target_type, entered_at)`：按类型过滤 FIFO
- `idx_lease (claim_state, lease_expire_at)`：租约惰性/扫描回收

## 注意
- 入池点＝各域进入待审的**同一事务**（帖子发布/草稿提交、商品提交/草稿提交/在售改内容回审/重新上架、评论发布、跑腿支付成功），经 `ReviewTaskPoolStore`（各域仓储事务内调用）
- 同轮重提＝新轮次＝新工单行；入池先关同内容旧轮遗留工单（旧工单停 DONE），裁决/撤回/删除等退出待审路径同事务出池
- **裁决强绑定（票 09）**：裁决只能由持有人对自己手上的工单做出——`POST /api/moderation/tasks/{taskId}/decide` 断言 `claim_by=我`，非持有人/已被回收（回收清空 claim_by）403「请先领单 / 工单已被回收」；业务行切换 + 审核记录 + 工单置 DONE（持有人双守卫 `closeByHolder`，与裁决同事务原子）；关单**保留 `claim_by`**（持有快照），已 DONE 工单同一动作重放整体幂等成功零副作用
- **遗留入口收口（票 09）**：`POST /api/moderation/review` 不再直接裁决——按「类型 + 内容 id + 当前登录用户」反查其持有的在途工单（`claim_by=我 且 claim_state='CLAIMED'`），命中转强绑定裁决，未命中 403「请先领单」，无绕过路径；管理端 `POST /api/admin/reviews/decide` 按票 08 口径保留
- 存量 `content_review` 不回填成工单（历史裁决＝已完成，不该入池）
- 素材（内容/配图/悬赏/评论上下文）**不进工单列**，工单详情接口按类型实时直查业务表

# content_review（审核动作记录，只插入）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键：纯内部键，永不对外|
|review_id|varchar(36)|NOT NULL, UNIQUE(`uk_review_id`)|审核记录业务 ID（UUID 字符串，uuid-biz-id 票 06 起）：应用层 insert 前由 `IIdGenerator` 发号随行一次写入|
|target_type|varchar(20)|NOT NULL, `uk_target_action_round` 首列|审核对象 POST/COMMENT/ERRAND/PRODUCT|
|target_id|varchar(36)|NOT NULL|审核对象业务 ID（各业务表的业务键，非物理 id）|
|author_id|varchar(36)|NOT NULL|作者用户 ID（冗余；对齐 `content_report.author_id`，按作者聚合历史裁决单表可查、免反查业务表，票 06/07）|
|review_round|int|NOT NULL, DEFAULT 1|审核轮次 = 同一目标第 N 次进入待审：POST/PRODUCT 取自业务表当前 `review_round`；COMMENT/ERRAND 无再审出口恒为 1|
|action|varchar(20)|NOT NULL|动作 APPROVE/REJECT|
|source|varchar(20)|NOT NULL, DEFAULT 'MANUAL'|触发来源 MANUAL/KEYWORD/RANDOM（KEYWORD=敏感词命中转审、RANDOM=随机抽查；当前仅 MANUAL，其余为扩展点）|
|operator_id|varchar(36)|NOT NULL|裁决人用户 ID（审核员本人）|
|reason|varchar(200)|NULL|驳回原因/备注（拒绝理由，对外仅作者可见）|
|reason_code|varchar(50)|NULL|拒绝理由枚举 code（RejectReasonCode；APPROVE/枚举化前历史数据为 NULL）|
|create_time|datetime|NOT NULL|审核时间|
|update_time|datetime|NOT NULL|更新时间（只插不改，实际恒等于 create_time）|

## 索引
- `uk_target_action_round (target_type, target_id, action, review_round)`：幂等键
- `idx_create (create_time)`

## 注意
- 只插入审计/历史裁决：同一内容历轮裁决按 `(target_type, target_id)` 查（工单详情聚合，票 07），同一作者历史按 `author_id` 单表直查免回查工单表
- 写入走 `IReviewService.recordReview` → `INSERT IGNORE`，撞 `uk_target_action_round` = 该轮已记录过（重放裁决自然幂等跳过）；轮次由业务表行锚定，重复裁决业务行轮次不变 → 只插一行
- 同一目标可被多次审核（商品转草稿重提、下架重发各成一轮），历史裁决留痕完整；拒绝理由按最新一次展示（`selectRejectReason` ORDER BY 物理自增 id DESC LIMIT 1）
- 业务 ID `review_id` 承担对外标识（uuid-biz-id 票 06 起）；物理 `id` 只作「最新一轮」排序等内部用途
- 资金动作不在此表：跑腿拒绝退款走 wallet 流水（uk_order_type 幂等）

# content_report（举报单）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键（纯内部键，永不对外）|
|report_id|varchar(36)|NOT NULL, UNIQUE(`uk_report_id`)|举报单业务 ID（UUID 字符串，uuid-biz-id 票 06 起）：应用层 insert 前由 `IIdGenerator` 发号随行一次写入；信用分 REPORT_CONFIRMED 流水 `ref_id` 引用它；对外按它寻址|
|target_type|varchar(20)|NOT NULL, `uk_reporter_target` 第二列|举报对象 POST/COMMENT/USER/MESSAGE（MESSAGE = 1v1 会话零审核的举报兜底）|
|target_id|varchar(36)|NOT NULL|举报对象业务 ID（USER/POST/COMMENT = user_id/内容 id UUID；MESSAGE = `message.msg_id` UUID 字符串，票 04）|
|author_id|varchar(36)|NOT NULL|被举报人用户 ID（冗余，免举报时回查业务表）|
|reporter_id|varchar(36)|NOT NULL, `uk_reporter_target` 首列|举报人用户 ID|
|reason|varchar(200)|NOT NULL|举报理由|
|content_snapshot|varchar(1000)|NULL|举报时内容摘要（证据）|
|status|varchar(20)|NOT NULL, DEFAULT 'PENDING'|状态 PENDING/PROCESSED(核实)/IGNORED(不实)。**本表状态列仍叫 `status`，未随票 05 改名 `state`**|
|handler_id|varchar(36)|NULL|处理人用户 ID（审核员）|
|handled_at|datetime|NULL|处理时间|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间（处理时刷新）|

## 索引
- `uk_reporter_target (reporter_id, target_type, target_id)`：同一人对同一目标只能举报一次
- `idx_status (status, create_time)`：按状态分页
- `idx_target (target_type, target_id)`：按被举报对象回查

## 注意
- 提交走 `INSERT IGNORE`，撞 `uk_reporter_target` = 重复举报，不插新行也不报错
- 流转是条件 UPDATE：`PENDING → PROCESSED` / `PENDING → IGNORED`（带 `status='PENDING'` 守卫），处理后不可再改判
- 对外按业务 ID `report_id` 寻址（uuid-biz-id 票 06 起，物理 `id` 不再对外）

# sensitive_word（敏感词库）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键|
|word|varchar(50)|UNIQUE(`uk_word`), NOT NULL|敏感词（原文精确匹配）|
|enabled|tinyint(1)|NOT NULL, DEFAULT 1|是否启用（1 启用 / 0 停用；只有启用词进匹配）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

## 索引
- `uk_word (word)`：词唯一（新增走 `INSERT IGNORE`，撞键 = 已存在）

## 注意
- `schema.sql` 种子 8 行（代考/代课/作弊/赌博/枪手/办证/刷单/跑分，`INSERT IGNORE` 防重复执行冲突），上线前按需扩充
- 词库启动时把 `enabled=1` 的词全量加载进内存（`KeywordScreenServiceImpl`），命中在内存 contains 判定、不逐次查库；后台增/删词后同进程内即时刷新，多实例一致性后置（本期单体）
- 测试**从不 truncate 本表**（种子行是初筛用例的前置数据），别把它加进清库白名单

## 关联
- review_task.target_id / content_review.target_id / content_report.target_id 均指向**业务表的业务 ID**（`post.post_id`、`post_comment.comment_id`、`product.product_id`、`errand_order.errand_order_id`、`message.msg_id`、`bs_user.user_id`），非物理 `id`
- review_task（claim_state=DONE）⇔ 本轮已落 content_review 行（同事务，互为充分）；工单不承载可见性，对外可见与否由业务行状态流转决定
- content_report（target_type=MESSAGE）是 chat 的举报兜底；content_review / review_task 不记录 MESSAGE
- 拒绝理由回显：仓储按 target_id 集单表查 `content_review` 的 `action='REJECT'` 行，Java 取每 target 最新一轮
- 敏感词命中转审是扩展点（content_review.source=KEYWORD）

## 谁在写
- review_task：各域进入待审的同事务开单（`ReviewTaskPoolStore.enter`）；领单/回收/裁决关单（票 08/09）
- content_review：审核裁决（人工审核 case，`recordReview`，带 author_id 冗余）
- content_report：用户举报（创建）+ 审核员核实/忽略（状态流转）
- sensitive_word：`schema.sql` 种子 + 后台词库维护（增/删/启停）
