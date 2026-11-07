# 内容治理模块接口

审核队列 / 审核动作 / 审核长连接 / 举报 / 敏感词。base `/api/moderation`；统一裁决另走 `/api/admin`。

## 审核 WS 长连接（票 10，spec §5.3/§5.4）

### POST /api/moderation/ws-ticket
签发审核 WS 连接票据（60s 一次性短时凭证，换取 `/ws/review` 握手资格；契约形状与 chat 票据一致，前端审核端复用既有长连接客户端骨架）。Auth: AUDITOR/ADMIN。
- 成功: `{ ticket }`
- 失败常见: 非审核员/管理员 403 / 未登录 401

### WS /ws/review?ticket={ticket}
审核端长连接端点（派单信号 + presence；**独立端点，不复用 /ws/chat**——端点常量与帧类型各归各的协议类）。Auth: 握手时点一次性票据换身份，**叠加角色过滤**：票据换出 userId 后回查角色，非 AUDITOR/ADMIN 401（堵普通用户拿 chat 票据跨端点握手）。
- 心跳口径与 chat 相同（CONTEXT.md「心跳」词条）：客户端 25s 发 `PING`、服务端以任意入站帧刷新活跃时间、60s 无入站判死清理（close GOING_AWAY）；容器层 90s 空闲兜底
- 帧信封（JSON 文本帧）：`{type, payload?, ts}`；C→S 仅 `PING`（其余类型静默忽略），S→C `PONG`/`REVIEW_SIGNAL`/`KICK`
- 互踢：单设备，同账号新连接接管时旧连接先收 `KICK`（payload `{reason:"SESSION_TAKEN_OVER"}`）再被 close(4001)，客户端停止自动重连
- presence：在线集合落 Redis Hash `bs:review:presence`（field=userId、value=最近活跃毫秒）——连接建立写入、任意入站帧刷新、断开删除（仅当被关闭连接仍是该用户当前连接，被踢旧连接迟到 close 不误删）；可跨实例共享、可查在线人数、重启可恢复（崩溃残留靠活跃时刻新鲜度过滤，不做后台清扫）；选 Redis 理由是可查与跨实例，**不是拿它做锁**

### 派单信号 REVIEW_SIGNAL（票 11，spec §5.3「信号粒度」「离线积累」「降级」）
- payload：`{ "backlog": N }`——**粗粒度总信号**：「有新工单」+ 当前积压总数（工单池 PENDING 行数，推前现算）；**不按内容类型推**，类型计数走 `GET /tasks/counts`
- 触发时机两条：① **入池广播**——新工单入池事务提交后向全部在线审核员广播（真实开新工单才推；回滚不推、同轮幂等重入不推）；② **上线补推**——审核员连接建立即推当前积压数（离线期间不推、不丢单——池是 DB 持久化的，无人在线静默积压，上线后信号与池中实际一致）
- 语义边界：推送只负责唤醒，**不指定收件人、不做分配、不引入任何锁**；公平约束（一次一条 + 先到先审 + 类型分区）全在拉取侧（claim）。推送失败不重试（漏推合法，客户端兜底：上线补推 + 计数条刷新）

## 统一裁决

### POST /api/admin/reviews/decide
统一裁决入口（人工 source 恒为 MANUAL：`decide(targetType, targetId, approve, reasonCode, supplement, operatorId)`）。
- Auth: 仅 ADMIN（`POST /api/moderation/review` 为既有审核员入口，AUDITOR/ADMIN，内部转发同一用例，行为不变）
- Body: `targetType`（POST/COMMENT/ERRAND/PRODUCT）, `targetId`, `approve`（true=通过 / false=拒绝）, `reasonCode`（拒绝理由枚举 code，见下）, `supplement`（可选补充说明）
- 拒绝理由统一枚举 `RejectReasonCode`（bs-types，全站同口径）：POLITICAL 涉政有害 / PORNOGRAPHY 色情低俗 / AD_PROMOTION 广告导流 / PROHIBITED_ITEM 违禁品交易 / FRAUD 诈骗导流 / HARASSMENT 人身攻击 / INFRINGEMENT 侵权 / OTHER 其他
- 落库口径：`content_review.reason` 统一为"枚举文案：补充说明"（无补充为纯枚举文案），补充说明超长截断，受 varchar(200) 约束；作者端 rejectReason / 通知透传该文案
- 通过: 内容转对外可见（POST→NORMAL / COMMENT→NORMAL / ERRAND→PUBLISHED / PRODUCT→ON_SALE（无资金环节，落搜索同步））+ `AUDIT_RESULT` 通知作者（评论通过不发 AUDIT_RESULT，改为发互动通知 POST_COMMENTED/COMMENT_REPLIED，票 03）；通过时 reasonCode/supplement 可空
- 拒绝: COMMENT 打回（终态，**reasonCode 必填且须为合法枚举**）；ERRAND 转 REJECTED 终态 + 冻结悬赏全额解冻退款；PRODUCT 转 REJECTED（无资金环节）；POST 转 REJECTED 终态（迁移 011 起对齐商品，不同步搜索）——四者通知含理由且 **reasonCode 必填**（缺失或非法报 400）
- 幂等: 重复裁决状态条件翻转 + 流水 uk 防重；审核记录（`content_review`，INSERT IGNORE）按业务行 `review_round` 落轮次，重放不重复；同一目标多次审核（重提/重发）各轮各留一行
- 失败常见: 非管理员 403 / 未登录 401 / 对象不存在 404 / 终态后改判 500

## 审核队列（工单池）

### GET /api/moderation/queue?targetType&page&pageSize
读 `review_task` 工单池（claim_state=PENDING + 类型过滤 + entered_at 先到先审；targetType 缺省 = 跨类型全局 FIFO）。条目带 `review_round`/`entered_at`；ERRAND/PRODUCT/POST 带 `images`，PRODUCT 的 `reward` 为价格。Auth: AUDITOR/ADMIN。

### GET /api/moderation/tasks/counts
各类型积压计数（工单池 PENDING 行数，四类型缺省补 0；驱动审核端计数条与派单信号）。Auth: AUDITOR/ADMIN。

## 派单可观测与积压告警（票 11，spec §9）
观测 job `reviewDispatchObservation`（xxl-job，cron 0 * * * * ?，注册默认不自动触发）每分钟落 INFO 一条：
- **在线审核员数**：presence 快照按 60s 心跳判死阈值过滤（崩溃残留不计）
- **队列深度（按类型）**：工单池 PENDING 计数（`GET /tasks/counts` 同口径）
- **平均等待时长 / 最老待审等待时长**：PENDING 行 entered_at 起算（回收不改 entered_at、持续累加）
- **租约超时率**：Redis 累计计数 `bs:review:metric:claim-count` / `bs:review:metric:lease-timeout-count`（领单提交成功 +1；租约超时回收含惰性兜底 +1；主动放回不计；Redis 重启丢数 = 少计 = 观测低估，可接受）

积压告警两条都**只 WARN、不做自动扩容/自动派给离线者**：
- 队列深度 ≥ `review.backlog-warn-threshold`（默认 50）
- 最老待审等待时长 ≥ `review.oldest-wait-warn-minutes`（默认 30 分钟）

### GET /api/moderation/tasks/{taskId}
工单详情聚合（spec §5.1：一屏裁决上下文；Auth: AUDITOR/ADMIN）：
- 通用列：`taskId`/`targetType`/`targetId`/`reviewRound`/`claimState`（PENDING/CLAIMED/DONE）/`enteredAt`/`decidedAt` + 内容 `content`/`images`/`authorId`/`authorNickname`/`authorAvatar`/`status`（业务行当前状态）
- 类型专属：ERRAND `reward`=悬赏（PRODUCT 沿用队列口径 `reward`=价格）；COMMENT `contextPostId`/`contextPostTitle`（所属帖子）与 `contextParentId`/`contextParentContent`（父评论，根评论为 null）
- 历史裁决：`targetReviews`（同一内容历轮，review_round 升序，含 action/reason/reasonCode/operatorId/createdAt）+ `authorReviews`（同一作者历史，content_review 单表 author_id 直查免查工单表，id 降序最新 20 条）
- 纯只读聚合，不改变任何业务行状态与对外可见性（工单不承载可见性）；工单/内容不存在 404
- 失败常见: 非审核员 403 / 工单不存在 404

### GET /api/moderation/queue/{targetType}/{targetId}
审核对象详情（审核员看全文；ERRAND/PRODUCT 带 `images`，复用看图裁决）。Auth: AUDITOR/ADMIN。

## 既有审核动作（转发统一用例）

### POST /api/moderation/review
`targetType`, `targetId`, `action`（APPROVE/REJECT）, `reasonCode`（枚举，拒绝必填）, `supplement`（可选）。Auth: AUDITOR/ADMIN。行为与 decide 一致（拒绝落库同为"枚举文案：补充说明"）。

## 举报（content_report）

### POST /api/moderation/reports
提交举报（需登录）。Body: `targetType`（POST/COMMENT/USER/MESSAGE）, `targetId`, `reason`。一人一内容一单（`uk_reporter_target` INSERT IGNORE，重复提交返回 data=null 不重复入单）。
- USER：不能举报自己；POST/COMMENT：不能举报自己的内容，快照存内容原文（≤500 字）
- MESSAGE（chat 零审核兜底，票 05）：`targetId` = 消息全局主键；举报者须为会话成员（含被移除，与历史可读口径一致）；不能举报自己的消息；SYSTEM 消息不可举报；已撤回消息可举报（追责留痕）；快照 TEXT/IMAGE 存载荷原文、CARD 存 ref 标识（`CARD:POST#123`）

### POST /api/moderation/reports/{reportId}/process
处理举报（Auth: AUDITOR/ADMIN）。Body: `action`（PROCESSED/IGNORED）。
- PROCESSED：帖子/评论下架 + 扣 10 信用分（流水 uk 幂等，一内容一罚）；**MESSAGE = chat 强制撤回**（`recalled_by=AUDITOR` 无窗口，已撤回幂等跳过），不走扣分流水；双方 `REPORT_RESULT` 通知
- IGNORED：仅通知举报人不成立

## 管理面：敏感词与审核员（Auth: 仅 ADMIN）

### GET /api/moderation/admin/words
敏感词分页（`?page=1&pageSize=10`，pageSize ≤ 50）。返回 `data.list = [{id, word, ...}]`。

### POST /api/moderation/admin/words
新增敏感词。Body: `word`。落库即生效（同步初筛下一次请求即按新词表检查）。

### DELETE /api/moderation/admin/words/{wordId}
删除敏感词。

### POST /api/moderation/admin/auditors
指派审核员。Body: `userId`（目标账号须存在）。

### DELETE /api/moderation/admin/auditors
撤销审核员。Body: `userId`。
