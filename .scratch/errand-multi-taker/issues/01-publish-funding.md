# 01: 多人单发布与资金口径

**What to build:** 发布者在跑腿发布页可选「需要人数」（2~10，不选即单人单）并设定**每人报酬**，支付总额 = 每人报酬 × 所需人数，走既有先付后审链路（同步初筛 → 冻结总额 → 人工审核 → PUBLISHED）；订单详情页带出「当前/所需人数」展示。spec：`.scratch/errand-multi-taker/spec.md`。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [ ] 发布请求带所需人数与每人报酬；人数缺省 1 时行为与现有单人单完全一致（回归）
- [ ] 人数边界校验：多人单限 2~10，越界创建失败
- [ ] 支付冻结金额为总额（每人报酬 × 人数），流水与钱包账目可观察验证
- [ ] 组队 deadline = 支付时间 + time_limit_hours（沿用现有计算，不变）
- [ ] 详情接口/详情页带出所需人数与当前人数（组队期初始为 0 或 1 人时正确显示 x/y）
- [ ] 增量 ALTER 迁移脚本供联调库（init.sql 原地更新，同 005/006 先例）
- [ ] 集成测试：发布校验、总额冻结、详情带出人数

## Comments

**资金口径**：`errand_order.reward` 语义定为**每人报酬**（data model 未新增报酬列，只加 `required_takers`，与 spec 一致）；支付总额由 `ErrandOrderEntity.getTotalReward()` = reward × requiredTakers 统一给出——冻结、退款（取消/被拒/超时）全部改走该口径，单人单 total == reward 故零回归。`ErrandPayResult.reward` 改名 `amount` 避免"这是总额还是每人"的歧义。确认/仲裁的入账口径同改 total（单人单等价；多人单在票 01 不可达，票 05 会拆成 per-taker income）。

**当前人数**：`requiredTakers > 1` 时恒为 0——进团链路属票 02，票 01 无人可进团，返回值是真实值而非占位；单人单由 `taker_id` 判定（0/1）。票 02 落地成员表后此方法改由成员数统计。

**多人单拒绝直接抢单**：`grabOrder` 对 `requiredTakers > 1` 前置拒绝。理由：`grab` 是单人语义（写 `taker_id` + TAKEN），若不拦会在票 02 之前把多人单拖进单人链路，产生"一人抢走多人单"的错误状态（且会顺带触发单人单的结算路径）。前端详情页对多人单隐藏「立即抢单」，改为展示「组队中 0/3 人，人满自动成团」，票 02 替换为进团入口。

**未做**：订单成员表（票 02）、组队期退团/散团（票 02）、成团建群（票 03）。

**验收**：全量后端测试 264 绿（新增 `ErrandMultiTakerPublishIntegrationTest` 6 例 + `ErrandMultiTakerMigrationTest` 1 例），前端构建绿。双端 UI 冒烟（新账号注册→发布 3 人单，每人 20）：发布页人数 chips 与「每人 ¥20 × 3 人，支付总额 ¥60」实时联动；详情页「每人报酬 ¥20 / 0/3 人 / 立即支付 ¥60」；点支付后库内 `frozen_amount=60.00` + FROZEN 流水 60.00 实证；游客视角已发布态显示「0/3 人（还差 3 人成团）」且无抢单按钮；市集卡片「每人 ¥20」。
