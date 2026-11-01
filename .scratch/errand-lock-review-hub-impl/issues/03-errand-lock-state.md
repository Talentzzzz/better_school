# 03: 锁单状态机（抢单 / 确认接单 / 放弃）

**Spec:** `.scratch/errand-lock-review-hub/spec.md` §2.1、§2.2（迁移矩阵与三个交叉点裁决）、§2.3「锁单的状态位」「放弃路径」

**What to build:** 把「抢 ≠ 开工」落成状态机。接单方抢单不再直接进入可开工态，而是先拿到 `LOCKED`——写 `taker_id`、`lock_seq+1`、条件 UPDATE 保证先到先得原子性（`state='PUBLISHED' AND taker_id IS NULL AND deadline > NOW()`）；期间他人不可抢。接单方核对信息后自己点「确认接单」→ `CONFIRMED`（离开 `LOCKED` 后不改 `lock_seq`，陈旧回调天然打不中）；觉得不合适可**放弃** → 回到 `PUBLISHED`，他人可再抢，放弃是设计内的正常动作——**不扣信用分、不动「接单取消」幂等位、不清 `lock_seq`**，但落一条独立事件留痕。`CONFIRMED` 之后的「接单方取消」仍是原路径（扣分），两条路分事件、不可混。

三个交叉点按 spec 裁决落地：锁单期**发布方可以取消**（已列入取消白名单，退款，接单方不扣分）；锁单期**不进 `DISPUTED`**（还没开工，无可争议事实）；**执行时限到点而单在 `LOCKED`** 时，超时扫描的适用状态集与条件 UPDATE 两处都补 `LOCKED`（否则锁单中的单到期无人收口）。两端「我的订单」与作者主页展示口径补 `LOCKED`（显示「锁单中」）；搜索侧零改动——`LOCKED` 从不进索引。

本票**不含** 5 分钟自动释放（见票 04）：落地后到票 04 前，锁单靠「确认接单 / 主动放弃 / 发布方取消」三条边收口。

**Blocked by:** 01

**Status:** resolved（commit 01d3e95）

> ⚠️ 票 01 过渡态注记（code-review 产出）：票 01 的 `grab` 事件类型是机械改名后的 `CONFIRMED`（`PUBLISHED→CONFIRMED`）。本票落地 `PUBLISHED→LOCKED` 时，**必须把抢单事件类型改为 `LOCKED`**（spec §2.3 事件改名表）——否则「抢单」事件与新 `LOCKED→CONFIRMED` 的「确认接单」事件 `CONFIRMED` 永久撞名。同步核对 `ErrandMapper.grab`/`ErrandRepository.grab` 与 `docs/db/errand.md` 事件清单。

- [x] 并发抢单仅一人成功；条件 UPDATE 命中 0 行时给「手慢了」业务错
- [x] 抢单后状态为 `LOCKED`、`taker_id` 已写、`lock_seq` 已 +1；「我接的单」立即可见
- [x] `LOCKED→CONFIRMED`（仅持单人）与 `LOCKED→PUBLISHED`（仅持单人，事件 `LOCK_ABANDONED`，`reason_type=TAKER_ABANDON`）各自条件 UPDATE 守卫
- [x] 放弃**不扣信用分**、不置 `taker_canceled`、不改 `lock_seq`；`CONFIRMED` 后的接单方取消仍扣分（原路径不变）
- [x] 发布方可在 `LOCKED` 取消（退款、不扣接单方分）；`PICKED_UP`/`DELIVERED` 仍不可取消
- [x] `LOCKED` 不可发起争议；`DISPUTED` 前态集合不含 `LOCKED`
- [x] 执行时限扫描（超时扫描的状态集 + 条件 UPDATE）都包含 `LOCKED`，锁单中的过期单会被取消
- [x] 两端「我的订单」与作者主页跑腿 tab 含 `LOCKED` 且文案为「锁单中」；搜索侧零改动
- [x] 集成测试覆盖：并发抢单、放弃后他人可抢、锁单期发布方取消、锁单中被 deadline 扫描取消

## Answer

落地要点（commit `01d3e95`）：

- **状态机**：`grab` 条件 UPDATE `PUBLISHED→LOCKED`（`state + taker_id IS NULL + deadline>NOW()` 三重校验；同 UPDATE `lock_seq+1`），事件 `LOCKED`；新增 `acceptLockedById`（`LOCKED→CONFIRMED`，`taker_id=? AND state='LOCKED'`，事件 `CONFIRMED`）与 `abandonLockedById`（`LOCKED→PUBLISHED` 清 `taker_id`，事件 `LOCK_ABANDONED`+`TAKER_ABANDON`）。三者都走「条件 UPDATE 自证 + 0 行抛业务错」。
- **接口**：`POST /api/errand/orders/{orderId}/accept`、`POST /api/errand/orders/{orderId}/abandon`（domain `ErrandOrderServiceImpl.acceptLockedOrder/abandonLockedOrder`，共用私有守卫 `requireLockedTaker`）。
- **三个交叉点**：`cancelByPublisher`（SQL + domain 前置判定）白名单补 `LOCKED`；`DISPUTED` 前态集合保持 `CONFIRMED/PICKED_UP/DELIVERED`（不含 LOCKED）；`selectTimeoutOrderIds`、`markTimeout`、`handleOrderTimeout` 适用状态集补 `LOCKED`。
- **扣分口径（判断项，已在提交说明与文档写明）**：`ErrandTimeoutResult.taken` 更名为 `committed`，LOCKED 超时**不扣**接单人信用分。依据 spec §4.4「信用分只记『已承诺后违约』」＋票 01「抢 ≠ 开工」；票面 item 7 只要求「会被取消」。若裁定应为「扣分」，只需把 `handleOrderTimeout` 的 `committed` 判据改回 `status != PUBLISHED`。
- **对外可见**：LOCKED 不落搜索同步（已发布单的搜索文档维持旧 `PUBLISHED` 态）、不进公开列表；两端「我的订单」与作者主页 tab 因可选状态过滤天然含 `LOCKED`，无需改 SQL；前端 `ERRAND_STATUS_TEXT.LOCKED='锁单中'` 票 01 已备。
- **前端最小接线**：`acceptErrandOrder`/`abandonErrandOrder` API + 详情页锁单态操作区（持单人「确认接单/放弃」、发布方「取消订单」、旁人提示）+ 抢单成功文案改为「抢单成功，请确认接单」。
- **两轴复查修正**：
  - 文档纠错：`docs/api/errand.md` 抢单失败分流改为与实现一致（抢自己的单 400 / 非 PUBLISHED 404 / 超时与并发 500），原稿误写「已被抢（含已锁单）→ 500」；`docs/db/errand.md` 的 `taker_id` 清空口径改为「放弃 / 接单方取消时清空，发布方取消与超时保留 taker_id」。
  - `taken`→`committed` 改名（消除 LOCKED 有 `taker_id` 却 `taken=false` 的名实不符）；DAO/XML 的 `acceptLockedById`/`abandonLockedById` 与仓储同名（对齐 `pickupById` 先例）；抽 `requireLockedTaker` 消重。
  - **未采纳项**：`grab`/`acceptLockedById`/`abandonLockedById` 的「条件 UPDATE + 落事件」未包编程式事务——与仓内 `pickupById`/`deliverById`/`cancelByTaker`/`markTimeout` 等 8 处既有同形写法一致（本仓只在「状态 + outbox 多写」处上事务），故不在本票单独偏离；如需系统性收口应另开票覆盖全部同形方法。
- **验证**：`mvn test` 247 例全绿（新增 9 例）；`npm run build`（vue-tsc + vite）通过。⚠️ 集成测试连常驻共享测试库，**两条 Maven 测试进程不可并发**（会互相清表导致假红）。
