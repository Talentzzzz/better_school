Status: resolved
Type: task
Blocked by: 01

# 02: 状态机反转——创建硬拦截 + 支付即转待审 + 待审可取消 + REJECTED 终态

**What to build:** 按 ADR-0003 反转跑腿订单状态机。创建链路：同步初筛只覆盖标题+描述，命中抛业务异常"内容包含违规词汇，请修改后重试"、订单不落库（替换现状的"命中转 PENDING_REVIEW"）；通过则创建为 PENDING_PAYMENT 并落 images。支付链路：支付事务内条件 UPDATE 转 PENDING_REVIEW（已付款待审）+ 同事务写 outbox 消息（topic 如 `bs-errand-paid`，载荷订单 id），沿用 OutboxMessageSender 模式。新增 REJECTED 终态。待审期取消：作者可从 PENDING_REVIEW 取消，解冻退款（复用 unfreeze 流水幂等）。既有 `ErrandTimeoutScanJob` 逻辑随之调整：PENDING_REVIEW 不再走"免付款待审"的旧超时语义（移除/改造），PENDING_PAYMENT 超时取消不变；存量迁移：处理库里可能残留的旧 PENDING_REVIEW 数据（增量脚本约定）。

**Status:** done

- [x] 初筛命中：返回业务错误，库中无新行
- [x] 支付成功：状态 PENDING_REVIEW，outbox 有对应行，资金冻结
- [x] 待审取消：状态 CANCELLED，解冻流水幂等
- [x] REJECTED 终态入枚举（本票只占位定义，拒绝编排归 03）
- [x] 超时扫描对 PENDING_REVIEW 不再按旧语义取消/放行

## Answer

实现要点（票 02）：

- **创建**：`ErrandOrderCase.createOrder` 初筛命中抛 `BusinessException(BUSINESS_ERROR, "内容包含违规词汇，请修改后重试")`，不落库；`ErrandOrderEntity.create` 去掉 `initialStatus` 参数，恒为 PENDING_PAYMENT，支付超时延迟消息无条件随单落库（原条件分支消失）。
- **支付**：`ErrandOrderServiceImpl.payOrder` 幂等判定从 PUBLISHED 改为 PENDING_REVIEW；域内事务 = `payById` 条件 UPDATE（PENDING_PAYMENT→PENDING_REVIEW，ErrandMapper.xml）+ `saveErrandPaidMessage`（新端口方法，topic `bs-errand-paid`，载荷 `ErrandPaidMessage{orderId}`，走 OutboxMessageStore）+ 订单超时延迟消息。**搜索同步消息从支付链路移除**（支付后不再公开可搜），转 PUBLISHED 时机补落 `bs-search-sync` 归票 03。冻结仍由 case 层在域事务后调用（uk_order_type 流水幂等，重试安全）。
- **取消**：`cancelByPublisher` 与 ErrandMapper `cancelByPublisher` 的状态白名单加 PENDING_REVIEW；`needRefund` 既有判定自动覆盖（待审→解冻，复用 UNFROZEN 流水幂等）。
- **超时扫描**：`handlePayTimeout`/`handleOrderTimeout` 本就按状态过滤，PENDING_REVIEW 天然不受旧语义影响（测试固化）；24h 提醒归票 04。
- **REJECTED**：`ErrandStatusVO` 新增终态 + 状态机注释重写；旧审核编排（reviewApprove→PENDING_PAYMENT / reviewReject→CANCELLED 无退款）未动，Javadoc 已标注"票 03 落地前勿在主链路调用"（评审确认的中间态风险，map.md 记录）。
- **存量迁移**：`sql/migrations/006_errand_pay_before_review.sql`——旧语义 PENDING_REVIEW 行（未支付、无冻结）统一转 CANCELLED + status 列注释更新；init.sql 原地同步。迁移测试 `ErrandPayBeforeReviewMigrationTest`（JDBC-only，旧 schema 内联重建）。
- **测试**：`ErrandPayBeforeReviewIntegrationTest` 9 例（初筛不落库/创建待支付/支付转待审+outbox+冻结/支付幂等/待审取消解冻/取消重试幂等/待支付取消无退款/扫描不动待审/REJECTED 枚举）。后端全量 59/59 绿；code-review 双轴复核后修复 3 处文档/风格问题。
