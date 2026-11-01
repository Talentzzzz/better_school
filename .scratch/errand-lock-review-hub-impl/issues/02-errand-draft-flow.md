# 02: 草稿链路（创建 / 编辑 / 软删 / 提交）

**Spec:** `.scratch/errand-lock-review-hub/spec.md` §2.1、§2.3「草稿的行为边界」

**What to build:** 发布方可以先把跑腿单存成草稿、慢慢改、再提交进支付。草稿是**服务端落行**的私有态（不是纯前端 localStorage），可编辑全部内容字段（类型/描述/取送地点/悬赏/时限/配图，上限 6 张），可软删；不付款、不对外、不起支付计时。两条创建入口并存：草稿入口落 `DRAFT`，直接发布入口保持现状（落 `PENDING_PAYMENT`）——草稿是可选路径、不是必经。

「提交」是一次状态迁移：校验必填 + 复用既有同步敏感词初筛，命中即失败并**停留草稿**；通过后转 `PENDING_PAYMENT` 并**同事务**注册 15 分钟支付超时。删除是软删 `DELETED`（全站删除统一软删的同一口径），本期 `DELETED` 仅从 `DRAFT` 可达，且彻底从公开列表/详情/搜索口径中消失。草稿态**不做**同步初筛（保存与提交分开），也不设自动清理。前端只做走通流程所需的最小接线。

**Blocked by:** 01

**Status:** resolved（commit ff6c864）

- [x] 草稿创建入口落 `DRAFT` 行；直接发布入口行为不变（落 `PENDING_PAYMENT`）
- [x] 草稿可编辑全部内容字段；`publisherId` 与状态位不可改；编辑不产生状态迁移
- [x] 删除 = 软删 `DELETED`（仅作者、仅 `DRAFT`）；`DELETED` 单从公开列表/详情/我的列表排除
- [x] 提交：必填校验 + 同步初筛（命中则停留 `DRAFT` 并报错）通过后 → `PENDING_PAYMENT`，且 15 分钟支付超时任务与状态迁移**同事务**注册
- [x] 草稿态不注册支付超时（支付钟只在提交后起算）
- [x] 集成测试覆盖「草稿 → 编辑 → 提交 → 支付 → 待审」全链路，以及「软删后不可见」

## Answer

落地要点（commit `ff6c864`）：

- **落位**：`POST /api/errand/orders/draft`、`PUT /api/errand/orders/{orderId}/draft`、`POST /api/errand/orders/{orderId}/submit`、`DELETE /api/errand/orders/{orderId}`；case 层 `ErrandOrderCase.createDraft/updateDraft/submitDraft/deleteDraft`，domain `ErrandOrderServiceImpl`，仓储原子用例 `saveDraft / updateDraft / submitDraftWithPayTimeout / softDeleteDraft`（事务落仓储，`submitDraft` 与 `schedulePayTimeout` 同事务）。
- **schema**：`errand_order` 的 `type/description/pickup_location/delivery_location/reward/time_limit_hours` 改 `NULL`（草稿允许缺省，提交时必填）；`ErrandOrderEntity.timeLimitHours` 由 `int` 改 `Integer`，映射处 type 空守卫。
- **可见性**：`orderDetail` — DELETED 对所有人 404、DRAFT/PENDING_REVIEW 仅作者；`selectPage/count` 排除 `DRAFT/DELETED`；`selectMyPublished/selectMyTaken`（及 count）排除 `DELETED`。搜索侧零改动（`PUBLIC_ERRAND_STATUSES` 本就不含新两态）。
- **事件**：`SUBMITTED`（DRAFT→PENDING_PAYMENT）、`DELETED`（DRAFT→DELETED，actor PUBLISHER）。
- **两轴复查修正**：
  - 待支付兜底扫描锚点由 `create_time` 改为 `update_time`（进入待支付时刻）——否则「早存晚提交」的草稿会被扫描按创建时刻立刻误判 15 分钟超时（spec §2.3「草稿态不起支付钟，提交后同事务注册」）。新增回归用例「早创建晚提交的草稿不被待支付兜底扫描误杀」。
  - `updateDraft` 条件 UPDATE 补 `publisher_id` 守卫（与其它迁移「条件 UPDATE 自证」口径一致）。
  - case 层 `updateDraft` 参数序统一为 `(orderId, userId, …)`；`IErrandService.deleteOrder` 更名 `deleteDraft`（名实相符）。
- **判过未并入选项**：提交增设 `creditAccountService.ensureOperable`（票面只列「必填 + 初筛」）。理由：直接发布本就查信用分，提交即进支付，不加则「唯一准入闸门是信用分」可被草稿路径绕过；product 先例（`TradePublishCase.submitProductDraft`）同构。
- **规格张力（保留）**：草稿 DTO `reward` 仍带 `@DecimalMin("10")`（沿用发布下限），故草稿无法暂存 <10 的中间值；与 product 草稿 DTO 用真实下限同口径。
- **前端（最小接线）**：API 四函数 + 发布页「存草稿」+ 详情页草稿态「提交并去支付 / 删除草稿」；`formatMoney` 容空（我的-跑腿列表会渲染 reward 为空的草稿行）。草稿「编辑」的 UI 入口与草稿箱视觉细案留给 spec §9 另开 effort（`updateErrandDraft` 已就绪）。
- **验证**：`mvn test` 238 例全绿（新增 8 例）；`npm run build`（vue-tsc + vite）通过。
- **注**：工作区另有一处与本票无关的未提交改动（跑腿时限新增长周期选项：`ErrandTimeLimitVO`、`front/src/types/api.ts`、发布页选项），保持原样未并入本提交。
