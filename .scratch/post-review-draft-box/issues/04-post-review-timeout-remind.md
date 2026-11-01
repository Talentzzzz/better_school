# 04: 帖子 24h 待审超时提醒

**What to build:** 帖子进入待审超过 24 小时未被裁决时，系统提醒管理员与审核员（按目标+轮次去重，不重复轰炸）；只提醒、不自动通过也不自动拒绝。模式对齐商品/跑腿的超时扫描：xxl-job 注册扫描任务，以进入待审时间锚点判定超时。

**Blocked by:** 01: 帖子审核新语义——先审后发 + 同步初筛报错 + 审核轮次

**Status:** ready-for-human

- [x] 待审超 24h 的帖子触发提醒，收件人为管理员+审核员
- [x] 同一帖子同一轮次只提醒一次（dedup 幂等）；重提后新一轮可再次提醒
- [x] 刚进待审（未超 24h）与已裁决的帖子不触发
- [x] 任务在 xxl-job 任务表中注册（默认不自动触发）；集成测试对齐 TradeTakedownExpireRemind 先例

**实现备注（2026-09-08）：**
- 链路对齐商品/跑腿先例：`PostReviewTimeoutScanJob`（@XxlJob postReviewTimeoutScan）→ `PostPublishCase.remindPostReviewTimeouts()`（收件人 ADMIN+AUDITOR 去重，逐收件人吞异常下轮补发）→ `PostServiceImpl.listReviewTimeoutPosts()`（review_entered_at ≤ now-24h 的 PENDING_REVIEW，批次 100）→ `PostMapper.selectReviewTimeoutPosts`
- 与先例的差异点：dedup_key 含审核轮次（`ORDER_EVENT:REVIEW_TIMEOUT:POST:{postId}:{round}:{reviewerId}`）——商品/跑腿的 key 不含轮次，重提后不会再提醒；帖子按验收项 2 要求轮次随行带出（`PostReviewTimeoutVO`），扫描 SQL 只取 id + review_round
- 通知类型沿用先例口径 ORDER_EVENT（票 03 的 AUDIT_PENDING 是评论消费方进审提醒，与本扫描先例不同轨）
- 无新迁移（011 已备好 review_round/review_entered_at）；`xxl-job-tasks.sql` 追加任务块 trigger_status=0，联调库执行方式见文件头注释
- 集成测试 `PostReviewTimeoutRemindIntegrationTest`：两用例覆盖超时提醒/同轮次去重/未超时与已裁决不触发/撤回重提轮次 +1 后可再次提醒/不改状态
