# 冒烟项 8：后台审核

**判定：PASS**（62/62 断言，其中项 8 占 45 条；0 FAIL）

- 脚本：[`smoke_01_08_review.py`](../smoke_01_08_review.py)（ADMIN 由 `h.promote_admin` 提权后重登，角色在 JWT 里）
- 运行记录：[`01-08-run.txt`](01-08-run.txt)；机读汇总：[`01-08-summary.json`](01-08-summary.json)

## 帖子（POST 目标）

| 断言 | 证据 |
| --- | --- |
| ADMIN 可拉审核队列（`GET /api/moderation/queue?targetType=POST`），详情带 `authorId` 与正文 | [`08-queue-before.json`](08-queue-before.json) / [`08-queue-detail.json`](08-queue-detail.json) |
| 通过 → `state=NORMAL`、公开 feed 可见（访客可打开详情）、移出队列 | [`08-approve-response.json`](08-approve-response.json) / [`08-approve-db-and-public.json`](08-approve-db-and-public.json) |
| `content_review` 落 APPROVE 裁决行恰 1 行，`source=MANUAL`、`operator_id`=ADMIN 本人、带 `review_round` | 同上 |
| 作者收到 `AUDIT_RESULT` 通知行且文案含「通过审核」（走真实 MQ） | 同上 |
| 驳回（带 `reasonCode`）→ `state=REJECTED`、公开 feed 不可见、旁人看详情 `404`、**作者可见且带出拒绝理由** | [`08-reject-response.json`](08-reject-response.json) / [`08-reject-db-and-visibility.json`](08-reject-db-and-visibility.json) |
| `content_review` 落 REJECT 行：`reason` = 枚举文案、`reason_code` 落库、`operator_id`=ADMIN | 同上 |
| 作者收到含拒绝理由的 `AUDIT_RESULT` 通知 | 同上 |
| **缺 `reasonCode` 的驳回被 400 拦下，且裁决未生效（仍 `PENDING_REVIEW`）** | [`08-reject-missing-reason.json`](08-reject-missing-reason.json) |

## 评论（COMMENT 目标）

同构走一遍：队列 `total=3` → 缺理由驳回被 400 拦下 → 驳回转 `REJECTED` + REJECT 裁决行 + 作者通知含理由
→ 通过转 `NORMAL` + 对外可见（访客 `total=1`）+ APPROVE 裁决行 → 两条都移出队列。

证据：[`08-comment-queue-before.json`](08-comment-queue-before.json) / [`08-comment-reject.json`](08-comment-reject.json) / [`08-comment-approve.json`](08-comment-approve.json)

## 结论

审核面（队列 / 通过 / 驳回 / 缺理由拦截 / 裁决行 / 通知 / 可见性）在 POST 与 COMMENT 两类目标上全部成立，
与「人工审核 + `content_review` 仅 MANUAL」的口径一致。未发现业务缺陷。
