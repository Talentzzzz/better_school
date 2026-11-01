# 02: 理由口径全站枚举化（人工侧先行）

**What to build:** 拒绝理由从自由文本收口为枚举：8 个 reason_code（POLITICAL / PORNOGRAPHY / AD_PROMOTION / PROHIBITED_ITEM / FRAUD / HARASSMENT / INFRINGEMENT / OTHER）进后端共享类型，作为全站（人工与后续 agent）统一口径。人工审核 API 改为"枚举点选 + 可选补充说明"，落库统一"枚举文案：补充说明"格式（受 reason varchar(200) 约束）；审核前端改下拉点选；作者端各内容 rejectReason 透传展示不变。这是后续 agent 票的地基——agent 复用同一枚举。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 人工拒绝帖子/评论/商品/求购/跑腿时 reason 必填且为合法枚举 code，落库文案为"枚举文案：补充说明"格式且 ≤200 字
- [x] 审核前端拒绝操作为枚举点选 + 可选补充说明输入框
- [x] 作者端详情/列表的拒绝理由展示行为不变（仍读最新 REJECT 记录）
- [x] 通过操作不受影响（理由可空）
- [x] 集成测试覆盖枚举校验（非法 code 拒绝）与落库格式

## Answer

- **枚举落点**：`bs-types` 新增 `RejectReasonCode`（`types/enums/RejectReasonCode.java`），8 code + 中文文案（涉政有害/色情低俗/广告导流/违禁品交易/诈骗导流/人身攻击/侵权/其他）+ `from(code)`；人工与 agent（票 03+）全站共用。
- **用例收口**：`ReviewCase.decide` 签名扩为 `(targetType, targetId, approve, reasonCode, supplement, operatorId)`，`review(...)` 同步；拒绝理由在用例层校验（缺失或非法枚举报 400"拒绝理由必填且为合法枚举 code"）并格式化落库 `枚举文案：补充说明`（无补充为纯枚举文案；补充超长截断保证总长 ≤200，受 `content_review.reason` varchar(200) 约束）。原 `requireRejectReason` 移除，格式化即校验。
- **API 变更**：`ReviewRequestDTO`（`/api/moderation/review`）与 `ReviewDecideRequestDTO`（`/api/admin/reviews/decide`）的 `reason` 字段替换为 `reasonCode` + `supplement`；通过操作两者可空，行为不变。`backend/docs/api/moderation.md` 已同步。
- **前端**：`ReviewWorkbenchView` 拒绝面板改 8 枚举 chip 点选（必选，未选提交 toast"请点选不通过理由"）+ 可选补充说明输入框（≤200）；`decideReview` 传 `reasonCode`/`supplement`；`types/api.ts` 新增 `RejectReasonCode` 联合类型。vue-tsc 通过，浏览器端到端验证：点选"广告导流"+补充 → 落库"广告导流：含校外兼职导流二维码，删除后可重新发布"，作者端透传展示。
- **作者端展示不变**：各内容 rejectReason 仍读最新 REJECT 记录 reason 透传，既有断言改为"其他：xxx"前缀格式。
- **测试**：新增 `RejectReasonEnumIntegrationTest` 5 例（非法 code 拦截不落记录 / 枚举+补充落库格式 / 仅枚举落库纯文案 / 超长截断 ≤200 / 通过不受影响）；既有 12 处 HTTP 请求体与 12 处理由断言全量同步，全量集成测试绿。
