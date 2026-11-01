Status: resolved
Type: task
Blocked by: 01, 02, 03

# 05: 用户侧前端——配图上传、审核状态展示、待审取消、拒绝理由

**What to build:** 用户侧前端适配新链路。发布页：订单表单支持配图（≤6 张，走 01 的上传接口，可复用/改造 `ImageUrlPicker.vue`）；初筛命中的业务错误有明确提示。订单详情/列表：展示"待支付 / 已付款待审 / 已发布 / 审核未通过"等状态；REJECTED 时对作者展示不通过理由（详情接口 `rejectReason` 字段）；已付款待审状态提供"取消"按钮（调取消接口，解冻退款）。后端配合：订单详情响应对作者在 REJECTED 时带出 rejectReason（从 `content_review.reason` 读，他人不带）。

**Status:** done

- [x] 详情接口：作者 + REJECTED → 带 rejectReason；他人/其他状态不带
- [x] 发布页可传图、限 6 张，初筛命中提示明确
- [x] 待审状态可取消且前端状态随之更新
- [x] 被拒订单能看到理由；vue-tsc 通过

## Answer

实现要点（票 05）：

- **后端 rejectReason**：moderation 域 `IReviewService.rejectReason` → `IModerationRepository.findRejectReason`（content_review action=REJECT，uk_target_action 下至多一行）→ case 层 `ErrandOrderCase.errandRejectReason`；可见性判定（作者 + REJECTED）在 `ErrandController.orderDetail`，DTO 字段 `@JsonInclude(NON_NULL)` 保证"他人不带"是字面缺字段（bs-api 补 jackson-annotations 依赖）。
- **前端**：类型加 `REJECTED` / `rejectReason`；详情页状态文案（人工审核中/审核未通过）、作者理由卡、配图网格展示、待审期"取消并退款"按钮（确认语含全额退款提示）；`MyErrandView` 状态文案同步。发布页传图/限 6 张/初筛报错 toast 已随票 01 就位，未再动。
- **测试**：`ErrandRejectReasonIntegrationTest` 三用例（作者带出 / 他人缺字段 / 非 REJECTED 缺字段）；vue-tsc 通过；后端全量 suite 通过。
