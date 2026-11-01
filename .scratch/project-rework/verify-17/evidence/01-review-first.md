# 冒烟项 1：发帖 / 评论先审后发（敏感词初筛 → 人工）

**判定：PASS**（62/62 断言，其中项 1 占 17 条；0 FAIL）

- 脚本：[`smoke_01_08_review.py`](../smoke_01_08_review.py)（号段 `137000171xx`，可重复执行：连跑两次全绿）
- 运行记录：[`01-08-run.txt`](01-08-run.txt)；机读汇总：[`01-08-summary.json`](01-08-summary.json)
- 前置：`sensitive_word` 种子 8 条（`代考 / 代课 / 作弊 / 赌博 / 枪手 / 办证 / 刷单 / 跑分`）→ [`01-sensitive-words.json`](01-sensitive-words.json)

## 证明链

**帖子**
1. 正文命中敏感词 → 同步初筛拒绝：`code=500` + 文案「内容包含违规词汇，请修改后重试」，且**库里查不到这一行**（按本次运行的唯一标记查 0 行）
   → [`01-dirty-post-response.json`](01-dirty-post-response.json) / [`01-dirty-post-db.json`](01-dirty-post-db.json)
2. 标题命中同样被拒且不落库 → [`01-dirty-title-response.json`](01-dirty-title-response.json)
3. 干净帖落库即 `state=PENDING_REVIEW` → [`01-clean-post-response.json`](01-clean-post-response.json) / [`01-clean-post-db.json`](01-clean-post-db.json)
4. 此时 `content_review` **还没有裁决行**（待审 = 只挂队列，裁决行由项 8 落）→ [`01-clean-post-review-rows.json`](01-clean-post-review-rows.json)
5. 待审帖对外不可见：访客/旁人 feed 里没有、旁人看详情 `404`、访客看详情 `404`；作者在「我的」列表可见 → [`01-pending-not-visible.json`](01-pending-not-visible.json)

**评论**
6. 评论命中敏感词 → 同样 `code=500` + 同一文案，评论**不落库**（该帖评论数不变）→ [`01-dirty-comment-response.json`](01-dirty-comment-response.json) / [`01-dirty-comment-db.json`](01-dirty-comment-db.json)
7. 干净评论落库即 `state=PENDING_REVIEW`（两条）→ [`01-clean-comment-response.json`](01-clean-comment-response.json) / [`01-clean-comment-db.json`](01-clean-comment-db.json)
8. 作者本人可见自己的待审评论，旁人/访客 `total=0` → [`01-comment-visibility.json`](01-comment-visibility.json)

## 结论

「同步敏感词初筛 → 人工」这条审核收敛口径（map 决策 5）在全栈上成立：
初筛是**拒写**（不落库、不改状态），干净内容进 `PENDING_REVIEW` 并挂人工队列，待审期对外不可见。
未发现业务缺陷。
