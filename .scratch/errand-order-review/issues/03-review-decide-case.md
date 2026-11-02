Status: resolved
Type: task
Blocked by: 02

# 03: 统一裁决用例 decide()——通过发布 / 拒绝打回退款

**What to build:** 抽出 case 层独立裁决用例 `decide(targetType, targetId, approve, reason, operatorId)`，由管理员登录态调用。跑腿订单裁决：通过 → 条件 UPDATE 转 PUBLISHED（幂等，复用既有 recordReview + uk 防重）+ `AUDIT_RESULT` 通知作者；拒绝 → reason 必填，转 REJECTED 终态 + 解冻退款（流水幂等）+ 通知作者（含不通过理由）+ 审核记录落 `content_review.reason`。现有 `ReviewCase` 三类内容（POST/COMMENT/ERRAND）的裁决收敛走此用例或转发，管理端既有审核接口行为不变。

**Status:** done

**后端落地**：`ReviewCase.decide()` 为统一裁决入口（原 `review()` 转发，既有 `/api/moderation/review` 行为不变）；新增 `POST /api/admin/reviews/decide`（仅 ADMIN，`AdminReviewController` + `IAdminReviewService`）。Errand 域：`reviewApproveOrder` → PENDING_REVIEW→PUBLISHED（已发布幂等跳过）；`reviewRejectOrder` → PENDING_REVIEW→REJECTED 终态，返回 `ErrandReviewResult(publisherId, reward)` 供 case 层解冻退款（流水先行 + uk 幂等）。跑腿拒绝 reason 必填（400）；POST/COMMENT 拒绝沿用默认文案兜底。通过通知文案改为"现已对外可见"。

- [x] 通过：PENDING_REVIEW → PUBLISHED，对外可见，作者收到通知
- [x] 拒绝：无 reason 被拒；有 reason 时 → REJECTED + 解冻流水 + 通知含理由
- [x] 重复裁决幂等（状态条件翻转 + uk 防重），退款不重复
- [x] 非管理员调用被拒
- [x] 既有 POST/COMMENT 人工审核行为无回归
