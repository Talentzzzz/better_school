# Spec：跑腿锁单 + 统一审核中台

_Status: **accepted** — 定稿 2026-10-14（票 01–08 决策汇总，票 09 收口产出）。本 spec 是**设计定稿，不含实现本身**；由它派生实现票即可开发。_
_决策出处：[map](map.md) · [01 状态机](issues/01-grill-errand-state-machine.md) · [02 锁单超时](issues/02-grill-lock-timeout.md) · [03 防占单](issues/03-grill-abuse-rules.md) · [04 实时设施盘点](research/04-realtime-presence-inventory.md) · [05 派单方案](issues/05-grill-dispatch-model.md) · [06 工单模型](issues/06-grill-review-task-model.md) · [07 原型](issues/07-prototype-review-console.md) · [08 工程形态](issues/08-grill-review-console-deploy.md)。_
_收口规则：**编号更大的票为准**。本 spec 已按此裁决一处反转——**票 03 反转票 02 的「锁单释放边不发通知」**，改为释放后给接单方发一条**结果通知**（仍**不做**提前预警，见 §3/§4）。其余决策按原票落稿。_

---

## 1. 目标与范围

本 spec 定义两件事的完整落地设计：

1. **跑腿订单状态机扩展**：新增 `草稿` / `锁单` 两态、`已确认` 收编现有 `TAKEN`，配 5 分钟锁单超时（接超时中心）与防恶意占单规则。
2. **统一审核中台**：独立前端工程 `front-review/`，同屏审多种内容（帖子 / 评论 / 商品 / 跑腿），工单按「推信号 + 拉工单」派给在线审核员并带租约回收。

### 1.1 覆盖

- 跑腿订单的**新状态机**（枚举、迁移矩阵、字段变更、术语）与其**存量迁移口径**（§2）。
- **锁单 5 分钟超时**接入既有超时中心的完整契约（§3）。
- **防恶意占单**规则、参数与数据落点（§4）。
- **统一审核中台**的数据模型、派单方案、工程形态与部署、原型结论（§5）。
- `CONTEXT.md` 词条清单、ADR 判定、超时中心文档回填点、可观测与运维口径（§6–§9）。

### 1.2 不覆盖

- **实现本身**：本 effort 只出设计；`front-review/` 建立与原型平移、主工程替换删除、各域入池点、状态机代码改造等均由本 spec 派生实现票承接。
- 举报裁决 / 申诉机制、审核中台自建账号体系 / SSO、超时中心旧链路下线、跑腿执行时限接入超时中心——见 **§10 Out of scope**。
- **多人单（组队）不适用**：`.scratch/errand-multi-taker/spec.md` 的在线实现已随 `project-rework` 整体下线（`required_takers`/成员表/系统群已删，`backend/better-school/docs/db/errand.md` 记「恒单人单」）。本 effort 的「先抢后确认」**只对单人单生效、不按 `required_takers` 分叉**；该孤立 spec 的状态机**不被继承**（多人单若要恢复，属另一 effort）。

---

## 2. 跑腿订单状态机

### 2.1 定稿状态链与状态图

目标链条：`草稿 → 待支付 → 已支付待审核 → 已发布 → 锁单 → 已确认 → 取货 → 送达 → 完成`（终态另有 `CANCELLED / DISPUTED / REJECTED / DELETED`）。

```
                     ┌───────────── 发布方「删除」（软删）────────────► DELETED（终态）
                     │
DRAFT ──提交/必填+同步初筛──► PENDING_PAYMENT ──支付(冻结)──► PENDING_REVIEW ──审核通过──► PUBLISHED
  ▲                                │15min 支付超时(SYSTEM)            │审核拒绝+退款                │
  │                                ▼                                  ▼                            │ 抢单（先到先得）
  └─(不做)                       CANCELLED                          REJECTED（终态）                 ▼
                                                                                            ┌──► LOCKED ◄──┐
                                                                                            │  确认接单     │ 放弃 / 5min 锁单超时
                                                                                            ▼              │
                                          （以上 5 态均可被发布方取消）──────────────────► CONFIRMED ────┘
                                                                                            │ 确认取货
                                                                                            ▼
                                                                                        PICKED_UP ──确认送达──► DELIVERED ──确认完成/24h 自动──► COMPLETED（终态）
                                   CONFIRMED/PICKED_UP/DELIVERED 任一方发起争议 ──► DISPUTED ──仲裁──► CANCELLED / COMPLETED
```

要点：

- `草稿` 是**新增态**：可编辑全字段、可删（软删 `DELETED`）、不付款、不对外；`待支付 / 已支付待审核 / 已发布` 沿用现有 `PENDING_PAYMENT / PENDING_REVIEW / PUBLISHED`。
- `锁单` 是**新增态**（**抢 ≠ 开工**）：抢到即锁，他人不可抢；`已确认` = 现 `TAKEN` **改名**，其后链路不变。
- 「确认」由**接单方**核对信息后自己点；**不合适可放弃**，放弃回 `已发布`（他人可抢）。
- **付款/审核位置**沿用 **ADR-0003 先付后审**（不反转）。

### 2.2 完整迁移矩阵（票 01）

守卫一律条件 UPDATE，0 行按既有约定分流（「必须失败」抛业务错 / 「可重入」静默返回）。

| from | → to | 触发者 / 触发 | 前置条件与幂等守卫 | 事件 |
|---|---|---|---|---|
| DRAFT | PENDING_PAYMENT | 发布方「提交」 | `state='DRAFT' AND publisher_id=?`；必填 + 同步初筛；同事务注册 15min 支付超时 | `SUBMITTED` |
| DRAFT | DELETED | 发布方「删除」 | `state='DRAFT' AND publisher_id=?` | `DELETED` |
| PENDING_PAYMENT | PENDING_REVIEW | 支付成功（冻结） | `state='PENDING_PAYMENT'`；重试已待审则幂等返回 | `PAID` |
| PENDING_PAYMENT | CANCELLED | 15min 支付超时（SYSTEM） | `state='PENDING_PAYMENT'` | `PAY_TIMEOUT` |
| PENDING_REVIEW | PUBLISHED | 审核通过 | `state='PENDING_REVIEW'` | `REVIEW_APPROVED` |
| PENDING_REVIEW | REJECTED | 审核拒绝 + 解冻退款 | `state='PENDING_REVIEW'`；退款流水 uk 幂等 | `REVIEW_REJECTED` |
| PENDING_PAYMENT / PENDING_REVIEW / PUBLISHED / **LOCKED** / CONFIRMED | CANCELLED | 发布方取消 | `state IN (上列 5 态) AND publisher_id=?`（**取货前**可取消；`PICKED_UP`/`DELIVERED` 不可）；待支付无退款、其余解冻退款 | `CANCELLED` |
| PUBLISHED | **LOCKED** | 接单方「抢单」 | `state='PUBLISHED' AND taker_id IS NULL AND deadline>NOW()`；写 `taker_id` + `lock_seq=lock_seq+1`；同事务注册 5min 锁单超时（`bizId={orderId}:{lockSeq}`）；前置防占单冷却判定（§4） | `LOCKED` |
| PUBLISHED / **LOCKED** / CONFIRMED / PICKED_UP | CANCELLED | 执行时限到（`deadline`，SYSTEM） | `state IN (上列 4 态) AND deadline<=NOW()` | `TIMEOUT` |
| LOCKED | CONFIRMED | 接单方「确认接单」 | `state='LOCKED' AND taker_id=?`（**不改 `lock_seq`**：离开 `LOCKED` 后陈旧回调天然打不中） | `CONFIRMED` |
| LOCKED | PUBLISHED | 接单方「放弃」 | `state='LOCKED' AND taker_id=?`；清 `taker_id`；**不扣分** | `LOCK_ABANDONED` |
| LOCKED | PUBLISHED | 锁单超时 5min（SYSTEM） | `state='LOCKED' AND lock_seq=?`（**轮次守卫**，挡住陈旧任务）；清 `taker_id` | `LOCK_TIMEOUT` |
| CONFIRMED | PICKED_UP | 接单方「确认取货」 | `taker_id=? AND state='CONFIRMED'` | `PICKED_UP` |
| CONFIRMED | PUBLISHED | 接单方取消 | `taker_id=? AND state='CONFIRMED' AND taker_canceled=0`；置 `taker_canceled=1`；**扣信用分** | `TAKER_CANCELLED` |
| CONFIRMED / PICKED_UP / DELIVERED | DISPUTED | 双方之一发起争议 | `state IN ('CONFIRMED','PICKED_UP','DELIVERED')` 且为双方之一 | `DISPUTED` |
| PICKED_UP | DELIVERED | 接单方「确认送达」 | `taker_id=? AND state='PICKED_UP'` | `DELIVERED` |
| DELIVERED | COMPLETED | 发布方「确认完成」 | `publisher_id=? AND state='DELIVERED'` | `COMPLETED` |
| DELIVERED | COMPLETED | 24h 自动（SYSTEM） | `state='DELIVERED'`（`autoCompleteById` **无** `publisher_id`） | `AUTO_COMPLETED` |
| DISPUTED | CANCELLED / COMPLETED | 仲裁裁决 | `state='DISPUTED'` | `ARBITRATED` |
| COMPLETED / CANCELLED / REJECTED / DELETED | — | **终态，无出边** | — | — |

**三个交叉点裁决**：

- **(a) 锁单期发布方可取消**：可以——`LOCKED` 已列入发布方取消白名单（退款；接单方**不扣分**，不是他的错）。
- **(b) 锁单期不进 `DISPUTED`**：`DISPUTED` 语义是「已开工/已交付的履约纠纷」，`LOCKED` 还没开工，无可争议事实（接单方不满意→放弃，发布方不满意→取消）。
- **(c) 执行时限到点而单在 `LOCKED`**：`markTimeout` 与 `listTimeoutOrderIds` 两个方法的适用状态集**都补 `LOCKED`**（否则锁单中的订单到期无人收口）；`grab` 已要求 `deadline>NOW()`，锁不到过期单。若锁单释放发生在订单 `deadline` 已过之后，释放回 `PUBLISHED` 的订单会被 deadline 扫描立即取消（无害）。

### 2.3 枚举与字段变更

**枚举**：新增 `DRAFT`、`LOCKED`、`DELETED`；`TAKEN` **改名 `CONFIRMED`**。最终 `ErrandStatusVO` = `DRAFT, PENDING_PAYMENT, PENDING_REVIEW, PUBLISHED, LOCKED, CONFIRMED, PICKED_UP, DELIVERED, COMPLETED, CANCELLED, DISPUTED, REJECTED, DELETED`（**13 值**）。

理由：本 effort 核心命题是「抢 ≠ 开工」，保留 `TAKEN` 字面量＝在代码里留一句谎；改名触点（后端枚举 / Mapper 条件 UPDATE / Repository / 前端序列化与详情页）**本来就要动**，且 DB 重建制、无 seed ⇒ 无数据迁移负担。

**中文文案**（`front/src/utils/errand.ts` 的 `ERRAND_STATUS_TEXT`）：`DRAFT`→「草稿」、`LOCKED`→「锁单中」、`CONFIRMED`→「已确认」、`PUBLISHED` 维持「待接单」；`DELETED` **不对外展示**（列表/详情一律排除，无需文案）。

**事件撞名修正**（旧事件指「发布方确认完成」，与新的 `CONFIRMED` 撞车）：

| 语义 | 旧 | 新 |
|---|---|---|
| `PUBLISHED→LOCKED`（抢单） | `TAKEN` | **`LOCKED`** |
| `LOCKED→CONFIRMED`（确认接单） | —（新） | **`CONFIRMED`** |
| `DELIVERED→COMPLETED`（发布方确认完成） | `CONFIRMED` | **`COMPLETED`**（自动完成仍 `AUTO_COMPLETED`） |

**字段变更**（`errand_order`）：

- 新增 **`lock_seq INT NOT NULL DEFAULT 0`**（每次抢单 `+1`）：既是超时中心 `bizId={orderId}:{lockSeq}` 的**轮次**，也是回调守卫的比对值。见 §3。
- **不加时间列**：前端倒计时由抢单时刻（`grab` 的 `update_time`）+ 5min 派生；「进锁时刻」由 `errand_order_event` 留痕。**不**复用 `deadline`（那是执行时限、被订单超时扫描占用，`lock_seq` 与它正交）。
- 状态列现名 **`state`**（`status` 已废）。

**草稿的行为边界**（票 01）：

- **服务端落 `DRAFT` 行**（与商品/帖子「草稿箱」同构；不用纯前端 localStorage）。
- **两条创建入口**（对齐商品 `POST /products/draft` vs `POST /products`）：`POST /api/errand/orders/draft` → `DRAFT`；`POST /api/errand/orders` → 直接 `PENDING_PAYMENT`（＝现行为）。草稿是**可选入口**，不是必经。
- **可编辑**全部内容字段（type/description/pickup/delivery/reward/timeLimitHours/images）；`publisherId` 与状态位不可改。编辑**不是**状态迁移。
- **删除＝软删 `DELETED`**（`WHERE state='DRAFT' AND publisher_id=?`）：全站删除统一软删（post/comment/product 均落 `state='DELETED'`），跑腿不做全站唯一例外。`DELETED` 本期**仅从 `DRAFT` 可达**。
- **不设自动清理**（对齐商品/帖子草稿箱；堆积风险标为开放项，§9）。
- **`DRAFT→PENDING_PAYMENT`**：触发＝用户点「提交」；校验＝必填 + 复用同步初筛 `moderationScreenService.screen(description)`（命中即失败、停留草稿）；通过后过渡并**同事务注册 15 分钟支付超时**。草稿态**不**起支付钟。
- **images 落库**（复用同列，上限 6）；仍不做图片同步初筛（与现状一致）。
- **`REJECTED→DRAFT` 不做**：跑腿 `REJECTED` 是资金终态（已退款），复活会引入「已退款单重付」语义；用户重发新单即可。

**锁单的状态位**（票 01）：

- **`taker_id` 锁单时即写**（沿用现 `grab` 形态，改成 `WHERE state='PUBLISHED' AND taker_id IS NULL` 的 `PUBLISHED→LOCKED` 条件 UPDATE）——先到先得原子性白拿，且「我接的单」立即可见。
- **`LOCKED` 进两端「我的订单」**：后端 `selectMyPublished`/`selectMyTaken` 的 `state` 是可选过滤（null=全部），**天然包含**，无需改 SQL；前端 `ERRAND_STATUS_TEXT` 补 `LOCKED`。
- **对外可见口径**：**锁单期间不落搜索同步**——沿用现 `grab`（`PUBLISHED→TAKEN` 不落 SearchSync）的先例，已发布单的搜索文档**维持旧 `PUBLISHED` 态、仍可见**；`LOCKED` 因此**从不进入搜索索引**，`SearchServiceImpl.PUBLIC_ERRAND_STATUSES`（`PUBLISHED/TAKEN/PICKED_UP/DELIVERED`，写时过滤）**不改、搜索侧零改动**。公开列表 `GET /api/errand/orders` 因默认 `state='PUBLISHED'` 天然不含 `LOCKED`；作者主页硬编码 `state IN ('PUBLISHED','TAKEN')` 加 `LOCKED`（展示「锁单中」）；`TAKEN` 逐处换 `CONFIRMED`。

**放弃路径**（票 01）：

- **`LOCKED→PUBLISHED`（放弃）**：独立迁移 + 独立事件 `LOCK_ABANDONED`（`reason_type=TAKER_ABANDON`）；**不扣信用分、不动 `taker_canceled`**，清 `taker_id`（不改 `lock_seq`）。
- **`CONFIRMED→PUBLISHED`（即原 `TAKEN→PUBLISHED`）**：现有 `cancelByTaker`（**扣信用分** + `taker_canceled=1` + 事件 `TAKER_CANCELLED`）**原样保留**。两者是**分事件**的两条路：锁单期放弃是设计内的正常动作（map 明说「不合适可放弃」），不落在「接单取消扣分」路径上；若扣分，用户会先确认再弃单，问题往后推、更糟。
- 留痕：**要**（`errand_order_event` append-only）。分开 `event_type` 的副作用好处：防占单（§4）按 `LOCK_ABANDONED` 计数封禁，不误伤真实取消。
- 锁单超时任务**无需撤销**：放弃后状态已是 `PUBLISHED`，5 分钟到点回调命中 0 行＝幂等吞掉（超时中心契约本就如此）。

### 2.4 存量迁移

- **目前无存量、从头开始 ⇒ 本仓库零迁移工作**：不新建编号脚本、不回填。`TAKEN` 改名纯代码层生效，`PUBLISHED` 无存量需补 `LOCKED` 语义。
- **新增列 `lock_seq`**（及 `state` 注释、新枚举）**随 `backend/better-school/sql/schema.sql` 一并加、重建库生效**——本仓无增量迁移机制（`sql/migrations/` 已随全库重写删除，`schema.sql` 是唯一 DDL 来源；§2.4 与 §5.1 同口径）。
- 若未来恢复编号迁移机制，从历史最大 **028** 续号（非 006/007）。
- 面对真实生产库的**假设性**回填（本期不产出）：`UPDATE errand_order SET state='CONFIRMED' WHERE state='TAKEN'`；`PUBLISHED` 不动。

---

## 3. 锁单超时（接入既有超时中心，不自建扫描 job）

### 3.1 结论

| # | 项 | 裁决 |
|---|---|---|
| 1 | taskType / 归属 | 新增常量 **`ERRAND_LOCK_TIMEOUT`** 落 `bs-types` `Constants`（紧邻 `ERRAND_PAY_TIMEOUT`）；`callback_group` **沿用 `${spring.application.name}`**，不改。 |
| 2 | 注册点 / payload | **抢单事务内**（`PUBLISHED→LOCKED` 条件 UPDATE **同事务**）写 `timeout_register_outbox`，`action_time = 锁单时刻 + 5min`，同一 UPDATE 顺带 `lock_seq=lock_seq+1`。**不需要专用 payload**：超时注册边没有 payload 位（中心回调 DTO 只有 `taskType/bizId/actionTime`，outbox DDL 也无 payload 列）；唯一需携带的定位信息 = **复合 `bizId`**。 |
| 3 | 回调处理 | `applyCloseAction` 增 `case Constants.ERRAND_LOCK_TIMEOUT`；**bizId 是复合键**，需拆 `orderId:lockSeq`（不能走现有 `Long.parseLong` 的 `parseBizId`）→ `ErrandOrderCase.handleLockTimeout` → 条件 UPDATE `LOCKED→PUBLISHED`（清 `taker_id`），**未命中即幂等成功**；**同事务落 `errand_order_event`**（`LOCK_TIMEOUT`, `LOCKED→PUBLISHED`, actor `SYSTEM`）。**滥用计数 / 信用分不做**（归 §4）；**结果通知做**（见下）。 |
| 4 | 取消边 | **不调 `cancel`**：条件 UPDATE + 轮次守卫使多余回调零副作用（超时中心 spec §4.2 本就定「取消是可选优化，接入方不取消也正确」）；`cancelAsync` 是 best-effort、带失败与竞态面。记「可选优化，本期不做」。 |
| 5 | 计时起点 | **从「锁单时刻」起算**（5min 是 taker 的确认窗口，从抢到手那刻开始）；与 `ERRAND_PAY_TIMEOUT`（从**创建**时刻）**口径不同，必须写明**（读者易误以为对称）。 |
| 6 | 轮次区分 | **不复用 `uk(task_type,biz_id)` 同键复位语义；按锁单轮次区分**：`bizId={orderId}:{lockSeq}`。 |
| 7 | 中心侧动不动 | **不改中心代码**（属**新增接入方**；中心通用模型 `task_type+biz_id+回调` 本就支持，复合 bizId 在 spec §3.2 已有先例 `userId:couponId`）；**只回填文档**（§8）。 |

### 3.2 为什么必须复合 bizId（本票最重要裁决，独立于多人单）

超时中心 spec §3.4：同 `(task_type, biz_id)` **在途**（WAITING/RETRYING）时再次注册 = **幂等成功应答、不改期**；只有终态才复活并刷新 `action_time`。若 `bizId` 直接用 `orderId`，锁单超时的正常路径会踩：

1. A 抢单（t0）→ 注册 `LOCK(orderId)`，action = t0+5。
2. A 放弃（t1）→ 回 `PUBLISHED`；中心任务若未取消，仍 `WAITING(t0+5)`。
3. B 抢单（t2 < t0+5）→ 同键再注册 → 中心**幂等成功、不改期** → B 的锁仍挂在 **t0+5** 到点 → **B 的确认窗口被压缩**（可能不足 5min）。
4. 若放弃边补调 `cancelAsync`（best-effort、异步），与 B 的注册异步竞态：注册先落地、取消后落地 → **B 的锁永久无计时、卡死**（比「早释放」严重）。

⇒ 给每次锁单一个**独立 bizId**（`{orderId}:{lockSeq}`），使每次注册都是**新键插入**（携带正确的 `action_time`），陈旧任务只影响旧键；回调再以**轮次守卫**（条件 UPDATE 带 `lock_seq`）挡住陈旧任务。取消彻底退化为可选、竞态面消失。

### 3.3 定稿接入设计

**参数**：`LOCK_TIMEOUT_MINUTES = 5`（落 `ErrandOrderServiceImpl` 常量，与 `PAY_TIMEOUT_MINUTES` 并列）。

**注册（抢单事务内）**：

```sql
UPDATE errand_order
   SET state='LOCKED', taker_id=?, lock_seq=lock_seq+1, update_time=NOW()
 WHERE errand_order_id=? AND state='PUBLISHED' AND deadline > NOW();   -- 复用 grab 现条件
-- 同事务写 timeout_register_outbox：
--   task_type='ERRAND_LOCK_TIMEOUT'
--   biz_id='{orderId}:{lock_seq}'
--   callback_group=${spring.application.name}
--   action_time=NOW()+5min
```

走既有 `TimeoutRegisterOutboxStore.save` 形态（提交后即时发 + 30s 扫描兜底）；复合 bizId 使 `uk(type,biz_id)` 不再撞键，走纯插入。

**回调（释放）**：

```sql
-- 中心按 callback_group 回调 → applyCloseAction 拆 bizId='{orderId}:{lockSeq}'
-- → ErrandOrderCase.handleLockTimeout(orderId, lockSeq) → 域 + 仓储条件 UPDATE：
UPDATE errand_order SET state='PUBLISHED', taker_id=NULL, update_time=NOW()
 WHERE errand_order_id=? AND state='LOCKED' AND lock_seq=?;             -- 轮次守卫
-- 同事务落 errand_order_event（LOCK_TIMEOUT / LOCKED→PUBLISHED / SYSTEM）
```

命中 0 行（已确认 / 已放弃并重抢（新轮次）/ 已取消）＝**幂等成功、零副作用**。

**幂等**：完全落在「状态机条件 UPDATE」既有口径上（业务方负责不重）；`lock_seq` 守卫额外挡住**陈旧轮次**——这是单靠状态守卫挡不住的。

**通知**：⚠️ **票 03 反转票 02**——释放后给**接单方**发一条 `ORDER_EVENT` 结果通知（`dedupKey="ORDER_EVENT:ERRAND_LOCK_TIMEOUT:{orderId}:{lockSeq}"`，带 `lockSeq`，多轮释放各发各的）；**不做**提前预警（现有 `ORDER_EVENT` 是拉取式，5min 窗口触达不了眼前；前端倒计时已是主要手段）。实现约束：释放会清 `taker_id`，故域方法 `handleLockTimeout` 必须**在清空前读出并返回被释放的接单方 id**。

**中心零代码改动**；属**新增接入方**。

---

## 4. 防占单

### 4.1 规则清单

1. 接单方抢单 → `LOCKED`（写 `taker_id`、`lock_seq+1`），他人不可抢；5 分钟后超时自动释放回 `PUBLISHED`。
2. 接单方可**主动放弃**（`LOCKED→PUBLISHED`），**不扣信用分**；落 `LOCK_ABANDONED` 事件（actor = 接单方）。
3. 每次**主动放弃**在 Redis 上计 1（ZSET，滑动 24h）；**超时未确认不计**。
4. 滑动 24h 内主动放弃 **≥3 次** → 置**冷却 2h**（`SET NX EX`），并**重置该用户计数窗口**；冷却触发打 WARN 日志。
5. 冷却期内 `grabOrder` 直接拒（`FORBIDDEN` + 文案）；冷却期天然含「该单」，无需同单规则。
6. 超时释放后给接单方发一条 `ORDER_EVENT` 结果通知（`dedupKey` 带 `lockSeq`）。
7. 状态迁移幂等一律靠条件 UPDATE；抢单原子性**不依赖 Redis 锁**。
8. **不做**：提前预警、超时计数、扣信用分、同单 N 次上限、发布方补偿、管理侧榜单。

### 4.2 计数口径与存储

- **落点：不新增表 / 列。** 复用 `errand_order_event`（append-only，**事实源**）；查询 `actor_id=? AND event_type='LOCK_ABANDONED' AND create_time >= NOW()-INTERVAL 24 HOUR`。抢单是**低频**动作，不需要计数表的写放大优化，也不该造「第二个真相」。→ `schema.sql` 给该表加索引 **`idx_actor_type_time(actor_id, event_type, create_time)`**（现仅 `idx_order`），重建库生效。
- **窗口：滑动 24h**，非自然日（自然日的「23:59 放弃 3 次 + 00:01 放弃 3 次」可跨零点刷新逃逸）。
- **只计「主动放弃」（`LOCK_ABANDONED`）**；**超时未确认（`LOCK_TIMEOUT`）不计入触发**——超时是非可控的（掉线/离开/没看到），惩罚不公。事件照记（分开），为运营/调参保留观察面。
  - ⚠️ **显式约束**：若将来要把超时纳入计数，**必须让 `LOCK_TIMEOUT` 事件带上 `actor_id=taker`**——当前该事件 actor 是 `SYSTEM`、`actor_id` 为 NULL，**从事件表数不出来**。

### 4.3 阈值与冷却

| 项 | 默认值 |
|---|---|
| 阈值 | 滑动 24h 内 **≥3 次**主动放弃 |
| 冷却 | **2 小时** |
| 适用范围 | **全员统一，不按订单类型（`type`）分** |
| 触发副作用 | **重置该用户计数窗口** |
| 冷却写 | `SET NX EX 7200`（幂等，不叠加延长） |
| 判定点 | `grabOrder` 前置（条件 UPDATE 之前） |

- **为什么「触发即重置窗口」**：若不清空，冷却结束（t2+2h）时窗口内那 3 次仍在 ⇒ 立刻再触发 ⇒ 循环到 24h 滑出，**实际封禁 ≈22h**，与「轻惩罚」的设计直接冲突；重置把「罚过了」和「重新起算」绑定。
- **为什么按类型不分**：滥用主体是「人」不是「某类单」；按类型分会让滥用者换类型逃逸。
- **拒绝**：`throw new BusinessException(ResponseCode.FORBIDDEN, "因近期多次放弃接单，您已被限制接单，请于 HH:mm 后再试")`。⚠️ 与 `<60` 闸的 `BUSINESS_ERROR(500)` 不一致是**有意的**（403 = 权限类拒绝；500 会污染客户端/监控的错误语义）。

**同一订单限制**：(a) 冷却期内不可再抢该单——**自动成立**（冷却 key 是 user 维度、禁全量抢单）；(b) 「同一订单最多被同一人抢 N 次」——**不做**，记为开放项（与冷却口径重叠；若实测出现「同一发布方被同一人反复占」，备选「放弃过的单，本发布周期内不可再抢」）。

### 4.4 信用分联动

**裁决：`LOCKED→PUBLISHED`（放弃）不扣信用分，维持票 01（本票确认而非反转）。**

- 惩罚载体选**限权（冷却）**而非**扣分**：锁单放弃是低代价高频小动作，而信用分**只减不增**且 `-20` 一档——扣 3 次即低于 60 直接禁接单，量刑与过错严重不成比例；冷却（2h 不可抢）才是「等长于过错」的惩罚。
- 现存两个 -20（`ERRAND_TAKER_CANCEL` / `ERRAND_TIMEOUT`）针对的都是**已承诺后违约**（产生对方真实损失）；票 01 定「抢 ≠ 开工」，锁单期放弃**未产生承诺**，不在这条口径上。
- ⇒ **口径正交**：**信用分只记违约，冷却只记滥用**，两套互不触发。

### 4.5 并发、幂等与 Redis 落点

**状态迁移互斥一律走条件 UPDATE，Redis 不参与状态互斥**：

| 路径 | 守卫 | 0 行分流 |
|---|---|---|
| 抢单 `PUBLISHED→LOCKED` | `state='PUBLISHED' AND deadline>NOW()`（同 UPDATE `lock_seq+1`） | 抛业务错「手慢了…」（既有） |
| 确认 `LOCKED→CONFIRMED` | `state='LOCKED' AND taker_id=?` | 抛业务错 |
| 放弃 `LOCKED→PUBLISHED` | `state='LOCKED' AND taker_id=?` | 抛业务错 |
| 超时释放 `LOCKED→PUBLISHED` | `state='LOCKED' AND lock_seq=?`（轮次守卫） | **幂等成功** |

四条 UPDATE 在同一 `state` 列上互斥于 DB 行锁，任意交错至多一条成功。**抢单本身无幂等键**（重复点第二次必然 0 行）。

**Redis（用户准入）用在「冷却计数与判定」这条热路径**：

| 用途 | 结构 | Key | 语义 |
|---|---|---|---|
| 滥用计数 | ZSET | `bs:errand:grab-abandon:{userId}` | member `{orderId}:{lockSeq}`、score = 放弃时刻 → **精确滑动 24h**（`ZREMRANGEBYSCORE .. now-24h` + `ZCARD`） |
| 冷却标记 | String | `bs:errand:grab-cooldown:{userId}` | 值 = 到期时刻；`SET NX EX 7200` |

- **写点**：放弃事务**提交后**写 Redis（对齐既有 `afterCommit` 风格）；`grab` 前置读 Redis 判定。
- **口径**：`errand_order_event` 仍是**事实源/审计**，Redis 是**判定加速层**——故 `idx_actor_type_time` **照加**；**Redis 不可用/降级时回落查事件表**（ZSET 不存在 ⇒ 该窗口 0 次 ⇒ 放行；只有 Redis 报错才回落 SQL）。
- **已知取舍**：Redis 写失败或数据丢失 ⇒ **少计 ⇒ 宽恕**（限流不是资金，宽恕方向安全）；不引入对账补偿。
- **为什么冷却判断放在 `grab` 前置读、不塞进 UPDATE 条件**：计数是**跨订单**的、不是这一行的属性；且冷却只是限流不是资金，乐观判断不产生正确性问题。

### 4.6 参数默认值

| 参数 | 默认 | 落点 |
|---|---|---|
| 滥用窗口 | 24h（滑动） | `ErrandOrderServiceImpl` 常量（与 `PAY_TIMEOUT_MINUTES` / `LOCK_TIMEOUT_MINUTES` 并列） |
| 滥用阈值 | 3 次 | 同上 |
| 冷却时长 | 2h | 同上 |
| 锁单超时 | 5min | §3 已定 |
| Redis key 前缀 | `bs:errand:grab-abandon:` / `bs:errand:grab-cooldown:` | `bs-types` `Constants`（对齐 `bs:chat:ws-ticket:` 先例） |

**数据落点汇总**：**不新增表、不新增列**。唯一 DDL 改动＝`errand_order_event` 加索引 `idx_actor_type_time(actor_id, event_type, create_time)`（改 `schema.sql` + 重建库）＋ §2 的 `lock_seq` 列。Redis 两个 key 均带 TTL/时间裁剪，无常驻数据。**无新事件类型**（`LOCK_ABANDONED`/`LOCK_TIMEOUT` 票 01 已定）；冷却触发**不落事件**，只落 WARN 日志。

---

## 5. 统一审核中台

### 5.1 数据模型（票 06）

**裁决：新增独立 `review_task` 表**（在途工单），`content_review` **保持「只插入审计」语义**（历史裁决，仅加一冗余列 `author_id`）。理由：裁决（多轮，1 行/次）与工单（单生命周期，可变）是 1:N 不可共存；本仓无迁移机制、加表≈改表零成本。

**类型口径**：工单 `target_type` 复用 `ReviewTargetTypeVO` 的 **4 个可审值**（`POST/COMMENT/PRODUCT/ERRAND`；`USER/MESSAGE` 仅举报用，不入池）。**求购(WANTED) 已随产品整体删除，本 effort 不做**——未来若建求购域，加一个枚举值 + 一条 detail 分支即可。

```sql
-- 新增：审核工单（在途派单凭据；与 content_review 的历史裁决语义严格分离）
CREATE TABLE IF NOT EXISTS review_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    target_type     VARCHAR(20)  NOT NULL COMMENT '内容类型 POST/COMMENT/PRODUCT/ERRAND（ReviewTargetTypeVO 可审值）',
    target_id       BIGINT       NOT NULL COMMENT '内容业务 ID（post_id/comment_id/product_id/errand_order_id）',
    review_round    INT          NOT NULL DEFAULT 1 COMMENT '审核轮次，与 content_review.review_round 同源（COMMENT/ERRAND 恒 1）',
    author_id       BIGINT       NOT NULL COMMENT '作者用户 ID（冗余，工单详情/历史聚合免 join）',
    source          VARCHAR(20)  NOT NULL DEFAULT 'MANUAL' COMMENT '入池来源 MANUAL/KEYWORD/RANDOM（ReviewSourceVO）',
    claim_state     VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '工单态 PENDING(在池)/CLAIMED(租约中)/DONE(已裁决)；勿与业务表 state 混用',
    claim_by        BIGINT       NULL COMMENT '当前持单人（审核员用户 ID）',
    claim_seq       INT          NOT NULL DEFAULT 0 COMMENT '领单轮次（每次 claim +1；租约复合 bizId {id}:{claimSeq} 与回调守卫）',
    lease_expire_at DATETIME     NULL COMMENT '租约到期时刻（claim 时 = NOW()+5min）',
    recycle_count   INT          NOT NULL DEFAULT 0 COMMENT '回收次数（≥3 置 escalated + 告警）',
    escalated       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '需人工干预标记（recycle_count≥3）',
    entered_at      DATETIME     NOT NULL COMMENT '进入待审时刻（FIFO 排序/等待时长锚点；POST/PRODUCT 取 review_entered_at，COMMENT 取 create_time，ERRAND 取支付时刻；回收不改）',
    decided_at      DATETIME     NULL COMMENT '裁决时刻（DONE 时写）',
    create_time     DATETIME     NOT NULL COMMENT '创建时间',
    update_time     DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_target_round (target_type, target_id, review_round) COMMENT '同一轮同一内容只开一张在途工单（INSERT IGNORE 幂等入池）',
    KEY idx_queue (claim_state, entered_at) COMMENT 'FIFO 取候选（跨类型）',
    KEY idx_queue_type (claim_state, target_type, entered_at) COMMENT 'FIFO 取候选（按类型过滤）',
    KEY idx_lease (claim_state, lease_expire_at) COMMENT '租约惰性/扫描回收'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '审核工单（在途派单；不承载裁决与可见性）';

-- 改：content_review 加冗余作者列（供按作者聚合历史裁决；语义仍为「只插入审计」）
ALTER TABLE content_review
    ADD COLUMN author_id BIGINT NOT NULL COMMENT '作者用户 ID（冗余；对齐 content_report.author_id）' AFTER target_id;
```

> ⚠️ **真实部署注意**：若目标库 `content_review` 已有历史行，`ADD COLUMN ... NOT NULL` 在 MySQL 严格模式下会失败/落 0。稳妥法：先加 `NULL` 列 → 按 `target_type/target_id` 反查业务表回填 → 再改 `NOT NULL`。**本仓无存量 ⇒ 直接 `NOT NULL` 即可**。

**多类型统一**：详情取数＝**中台聚合直查**（延续现有 `selectReviewDetail` 的 `<choose>` 直查形态；单体同库，不为各域造 RPC）。素材差异用「通用列 + 类型专属上下文」表达——通用（`content`/`images`/`authorId/authorNickname`/`entered_at`/`reviewRound`）；类型专属（跑腿 `reward`、评论上下文）**不进 `review_task` 列**（工单只存定位与派单态，素材实时直查）。

**索引与查询**：

- uk `(target_type,target_id,review_round)`＝同轮只开一张，`INSERT IGNORE` 幂等入池。
- 排队 `(claim_state, entered_at)`＋`(claim_state, target_type, entered_at)`；租约 `(claim_state, lease_expire_at)`。
- **claim 走条件 UPDATE 且命中 PK**（`WHERE id=? AND claim_state='PENDING'`），**不引 `SELECT ... FOR UPDATE SKIP LOCKED`**。
- **排序键 `entered_at` 而非 `create_time`**：回收不改 `entered_at`（等待时长继续累加）；取值＝**「业务行进入待审的时刻」**——⚠️ 各表字段不齐：`post`/`product` 有 `review_entered_at` 列可直接取；`post_comment`/`errand_order` 无此列，分别取 `create_time`（评论发布即待审）与**支付成功时刻**。

**迁移与入池**：

- 存量 `content_review` **不回填**成工单（历史裁决＝已完成，不该入池）。
- 池来源＝业务表 `state='PENDING_REVIEW'` 行 ⇒ **入池点＝各域进入待审的同事务**（`INSERT IGNORE`，4 触点：`post` 发布/草稿提交、`product` 提交/草稿提交/在售改内容回审/重新上架（`review_round+1` 时开新工单）、`post_comment` 发布、`errand_order` 支付成功）＋**一次性回填脚本**兜存量（真实部署用；**本仓无存量 ⇒ 可选**）。
- **兜底（可选，防漏池）**：claim/队列读取时对「业务行 `PENDING_REVIEW` 但无在途工单」的行补插 `INSERT IGNORE`（或低频 xxl-job 对账）——本期**列为可选加固项**，不阻塞主链路。
- 队列读取口径切换：`GET /api/moderation/queue` 改为**读 `review_task`**（`claim_state='PENDING'` + 类型过滤 + FIFO）。
- `review_task.review_round` 与 `content_review.review_round` **同源**；「同一内容重提」＝新轮次＝**新工单行**（旧工单停在 `DONE`）。
- **无编号迁移脚本**：改 `schema.sql` + 重建库；若要恢复编号从 **028** 续。

**历史裁决展示**：数据源＝`content_review`（补 `author_id` 后**单表化**）——该作者历史 `WHERE author_id=?`；同一内容历轮 `WHERE target_type=? AND target_id=?`。工单详情 `review_task.author_id` 冗余免 join。**风险标记**（原型 `riskFlags` 占位）**不落列、派生**，本期列为开放项。

### 5.2 工单状态机与裁决路径

```
             claim（CAS 条件 UPDATE，claim_seq+1，租约 5min）
  PENDING ───────────────────────────────────────────────► CLAIMED
     ▲                                                        │
     │  回收（租约超时回调 / 主动放回；recycle_count+1）          │ decide（同事务）
     └────────────────────────────────────────────────────────┤
                                                              ▼
                                                            DONE（终态，无出边）
```

- **`claim_state`**：`PENDING`（在池、无人持）· `CLAIMED`（已领、租约中）· `DONE`（已裁决）。**只有一个终态 `DONE`**——裁决结论（`APPROVE/REJECT`）落在 `content_review`，工单不必再分通过态/拒绝态。**不叫 `status`**（业务表已占 `state`）。
- **与 `content_review` 口径对齐**：`PENDING` ⇔ 业务行 `state='PENDING_REVIEW'` **且**本 `review_round` 尚无 `content_review` 行；`DONE` ⇔ 本轮**已落** `APPROVE/REJECT` 行（**同事务**）——即 `claim_state='DONE'` 与 `uk_target_action_round` 命中**互为充分**。「`PENDING/PASS/REJECT`」是**裁决侧**动作枚举（`ReviewActionVO`），**不是**工单态。
- **「先审后发」作者本人可见**不体现在工单层：可见性由**业务域**决定（业务行 `state='PENDING_REVIEW'` 时作者可见、对外不可见）；`review_task` **只是派单凭据**，`DONE` 只表示「已裁决」，对外可见与否由业务行流转决定。**工单不引入任何新的可见性规则**。
- **`recycle_count` 与升级**：每次回收 `+1`；`≥3` 置 `escalated=1` 并告警。

**裁决写入——强绑定（唯一内核沿用 `ReviewCase.decide`）**：

```
decideTask(taskId, approve, reasonCode, supplement, operatorId):
  ① 载入 task；断言 task.claim_by == operatorId 且 claim_state == 'CLAIMED'
       —— 不满足 → 抛错（403/409「请先领单 / 工单已被回收」）
  ② 分发：switch(task.targetType) → 复用既有 approve()/reject() 分支
       （域状态流转条件 UPDATE + content_review INSERT IGNORE + 跨域资金动作 + 通知）
  ③ 关单（同事务）：
       UPDATE review_task SET claim_state='DONE', decided_at=NOW(), update_time=NOW()
        WHERE id=? AND claim_state='CLAIMED' AND claim_by=?       -- 持单人守卫
```

- **原子性**：单体同库，①–③ 在**同一 Spring 事务**（业务行已切 + 裁决已记 + 工单已关三者的原子）。
- **幂等（重试语义）**：重放时 ② 域方法命中 0 行、`recordReview` 撞 uk 跳过、③ 命中 0 行（已 `DONE`）——**整体幂等成功、零副作用**（同既有 `handlePayTimeout` 口径）。
- **遗留入口收口**：现 `POST /api/moderation/review` **不保留绕过路径**——改为按 `(targetType,targetId,当前登录用户)` **反查其持有的在途工单**（`claim_by=我 且 claim_state='CLAIMED'`），命中则转 `decideTask`，未命中 → 409「请先领单」。

### 5.3 派单方案（推信号 + 拉工单，票 05）

**presence**

- 审核端持 WS 长连，**25s** 发 PING、服务端以任意入站帧刷新活跃时间、**60s** 无入站判死清理（复用 `CONTEXT.md` 已定的「心跳」口径，不新造参数）。
- 在线集合落 **Redis**：`RMap<userId, lastSeenTs>`（连接建立写入、心跳刷新、断开删除）或 `RSet` 在线 id；**只收 AUDITOR/ADMIN**（角色过滤是新逻辑）。选 Redis 理由：跨实例共享、**可查**（在线人数可观测）、重启可恢复。

**推送通道**

- **泛化新建 `/ws/review`**：把 chat 的连接层骨架（一次性票据鉴权、握手拦截、连接注册表、心跳判死、互踢 close 4001、`ConcurrentWebSocketSessionDecorator` 并发写保护）抽出，新增审核帧类型（如 `REVIEW_SIGNAL`）；**不复用 `/ws/chat`**（端点常量与帧类型写死 chat 语义）。前端 `chatWs.ts` 可复用骨架，泛化票据接口、拆出 chat 专属 handler。
- **信号粒度**：只推**粗粒度总信号**（「有新工单」+ 当前积压数），**不按内容类型推**——推送只负责唤醒、不指定给谁、不锁任何东西；「只擅审某类」的偏好在**拉取侧**表达。
- **离线积累**：审核员上线（presence 由离线转在线）时**立即补推**「积压 N 条」。

**claim（原子取一件）**

```sql
-- ① 选候选（FIFO，可带类型过滤）
SELECT id FROM review_task
 WHERE claim_state='PENDING' [AND target_type=?]
 ORDER BY entered_at ASC LIMIT 1;
-- ② 认领（影响行数=1 者赢）
UPDATE review_task
   SET claim_by=?, claim_state='CLAIMED', claim_seq=claim_seq+1,
       lease_expire_at=NOW()+INTERVAL 5 MINUTE, update_time=NOW()
 WHERE id=? AND claim_state='PENDING';
-- 同事务写 timeout_register_outbox：task_type='REVIEW_LEASE_TIMEOUT', biz_id='{id}:{claim_seq}', action_time=now+5min
```

- 取件顺序 **FIFO**，类型只作**过滤器**、不做优先级插队；**一次只取一条**（不预取）；**不引 `SKIP LOCKED`**、**不把 Redis 锁放关键路径**（DB 权威，Redis 只做信号/计数）。

**租约与回收（复用超时中心）**

- **复用既有超时中心**，不为回收新建扫描 job。租约 = 一个「到点收口」动作，与中心通用模型严丝合缝。
- **注册（claim 事务内）**：`task_type='REVIEW_LEASE_TIMEOUT'`、`biz_id='{reviewTaskId}:{claimSeq}'`、`action_time=领单时刻+5min`。**必须复合 bizId**（理由同 §3.2：只用 `taskId` 会让「回收→再领单」撞上未取消的旧任务、被静默忽略 → **租约永久失效**）。
- **回调（回收）**：`applyCloseAction` 增 `case Constants.REVIEW_LEASE_TIMEOUT`；拆 `taskId:claimSeq`（不能走 `parseBizId`）→ 审核侧用例 → 条件 UPDATE：

  ```sql
  UPDATE review_task SET claim_state='PENDING', claim_by=NULL, lease_expire_at=NULL,
         recycle_count=recycle_count+1, update_time=NOW()
   WHERE id=? AND claim_state='CLAIMED' AND claim_seq=?;             -- 轮次守卫
  ```

  命中 0 行（已裁决/已放回/已按新轮次重领）＝**幂等成功、零副作用**。
- **不调 cancel**（轮次守卫已使陈旧回调零副作用；`cancelAsync` 是 best-effort 且带竞态面）。记「可选优化，本期不做」。
- **惰性兜底**：claim 前顺带 `UPDATE ... SET claim_state='PENDING' WHERE claim_state='CLAIMED' AND lease_expire_at < NOW()`（一行，几乎免费），防中心回调延迟/丢失导致工单卡在 `CLAIMED`。
- **主动放回**：支持（审不了 → `CLAIMED→PENDING`，可选放回原因）。
- **参数**：`LEASE_MINUTES = 5`。**中心零代码改动**，属**新增接入方**。

**公平与均衡**：保留三条**轻量约束，全落 claim 时**——① 每人手里最多 1 条（=一次一条）；② FIFO 防饥饿；③ 类型分区放拉取侧。推送保持「只唤醒、不管分配」。

**降级（无人在线/积压）**：池 **DB 持久化**，无人在线静默积压、上线补推、不上锁、不丢单。**积压告警**沿用命名先例 **`review.backlog-warn-threshold`**（队列深度）+「最老待审等待时长」阈值，两条都只告警，不做自动扩容/自动派给离线者。

**派单池来源**：**全量待审**（人工为唯一裁决者）。`ReviewSourceVO` 的 `MANUAL/KEYWORD/RANDOM` 扩展点保留（常规待审/敏感词命中转审/抽检），本期只写 `MANUAL`。

### 5.4 工程形态与部署（票 08）

**工程形态**

- 落点 **仓库根 `front-review/`**（与 `front/` 并列），技术栈同 `front/`（Vue 3 + Vite + TypeScript + SCSS、手写组件、无 UI 库）；独立 `package.json` / `tsconfig*.json` / `vite.config.ts`。
- 由 `.scratch/errand-lock-review-hub/prototype/` **整体平移**（`git mv`，源码 + 配置 + README）；**不带** `node_modules/`、`dist/`、`*.tsbuildinfo`。`.gitignore` 追加与 `front/` 同款三行。
- 信息架构（承票 07）：顶部**信号条** + **类型计数条**（只读）+ 主区**单条聚焦** + 右栏**状态/事件流**；**仅桌面**（最小宽度 ~1200px）。

**与主工程的关系（替换口径）**

- **删**（主工程）：`front/src/router/index.ts` 的 `/admin/review` 路由；`ProfileView.vue` 的审核工作台入口卡（含 `canReview`）；`views/admin/ReviewWorkbenchView.vue`；`api/moderation.ts` 中仅供该视图使用的 `listReviewQueue`/`decideReview`（仅当确认无他处引用）。
- **留**（后端）：`POST /api/admin/reviews/decide`（前端零引用，不动）；`/api/moderation/**` 其余接口。
- **替换是单向门**（干净、单一口径），回滚依赖 git 历史；**并存方案否决**（两处口径漂移）。
- ⚠️ **替换的实际实施不在本 effort 范围**（本 spec 只定口径，实施归派生实现票）。

**后端接口归属**

- **域**：moderation（不新建域）。**工单 API**（新控制器，落 `bs-trigger/http`，前缀 `/api/moderation/tasks`）：

  | 方法 | 路径 | 用途 |
  |---|---|---|
  | `GET` | `/api/moderation/tasks/counts` | 各类型积压计数（驱动类型计数条 + 派单信号 payload 的积压数） |
  | `POST` | `/api/moderation/tasks/claim` | 拉一条（原子指派 + 起租约；body 可选 `targetType` 过滤） |
  | `POST` | `/api/moderation/tasks/{taskId}/release` | 主动放回（审不了） |
  | `POST` | `/api/moderation/tasks/{taskId}/decide` | 裁决（**内部调 `ReviewCase.decide`**，同事务工单置 `DONE`） |
  | `GET` | `/api/moderation/tasks/{taskId}` | 单条详情（内容/作者/历史裁决聚合——取数口径归 §5.1） |

- **WS**：`/ws/review`（派单信号 + presence）。既有 `GET /api/moderation/queue`、`POST /api/moderation/review` **保留**（向后兼容）。

**鉴权**：沿用既有 JWT（`localStorage.bs_token`）+ `Authorization: Bearer`；客户端路由守卫（非 AUDITOR/ADMIN → 跳主站登录）+ 服务端 `requireAuditor`（工单 API 全量继承）。**不新建账号体系/SSO**。

**部署与开发环境**

- 开发：`cd front-review && npm run dev` → **5174**；`vite.config.ts` 配 proxy `/api`、`/ws/review` → `http://localhost:8080`（ws: true）；主后端照常 8080。
- 生产：独立构建 `npm run build` → 静态产物发到**主站同一域名的 `/review/` 路径**（内网），反向代理 `/api`、`/ws/review` 到主后端。**仅内网可达**。独立域名/端口不是首选（会引入 CORS + 登录态搬运）。

**构建/发版/类型同步**：不进 CI（本仓无设施）；两工程各自 `npm run build`，**独立版本、解耦发版**；共享类型（`RejectReasonCode` 等）**手抄 + 后端 Java 枚举为唯一事实源 + 两侧注释互指**；**不引共享包/workspaces**（记为技术债）。**仅桌面 Web**。

### 5.5 原型结论（票 07）

- **已交付可运行原型**：`.scratch/errand-lock-review-hub/prototype/`（Vue 3 + Vite + TS + SCSS，`vue-tsc` 类型检查 + 构建通过）。全离线可跑（配图用内联 SVG 占位）。
- **信息架构成形**：**顶部信号条 + 类型计数条（只读）+ 主区单条聚焦工单（内容/作者/历史裁决 + `RejectReasonCode` 8 枚举理由 + 补充说明）+ 右栏状态/事件流**；「推信号 → 拉一条 → 租约倒计时 → 审完可再拉 → 租约到点自动回收 + 离线不推/上线补推」交互全部跑通（原型参数：租约 90s、一次一条、FIFO）。
- **多类型＝4 类**（帖/评/商/跑腿）——随求购整体删除同步收敛（原「五类」为过时愿景）。
- 原型暴露并已收敛的设计点：一次一条（不预取）、FIFO（不做类型挑选）、回收后**等待时长继续累加**、租约时长真实取值＝票 05 的参数（90s 仅演示）。

---

## 6. 术语（`CONTEXT.md`）

`CONTEXT.md`「跑腿订单」「审核与信用」等节新增/修订如下词条（体例遵循现有词条：名称 + 定义 + `_Avoid_:` 反例）。

**跑腿订单（新增 6 条）**

| 词条 | 定义（摘要） | `_Avoid_` |
|---|---|---|
| **草稿** | 跑腿订单的私有未提交态，可编辑全字段、可删（软删）、不付款、不对外；提交后进待支付。 | 草稿箱（那是商品/帖子的多来源停靠区；跑腿只有一个草稿态，不复用该词条） |
| **锁单** | 接单方抢单成功后的 5 分钟持有态，**非开工**；期间他人不可抢，超时或放弃均回落已发布。 | 占单、锁定、预接单 |
| **已确认** | 接单方核对信息后确认开工的态（**收编**原 `TAKEN`）；其后取货/送达链路不变。 | 已接单（旧 `TAKEN` 文案，语义已改） |
| **放弃** | 锁单期接单方主动退回（`锁单→已发布`），**不扣信用分**；与 `CONFIRMED` 后的「取消」（扣分）区分。 | 取消（那是已确认后的动作）、退单 |
| **占单** | 抢单后未确认即离手（主动放弃或超时释放），把名额占住使他人抢不到的行为；其中**主动放弃**计入滥用计数，**超时未确认**不计。 | 恶意抢单（"抢单"本身是正常动作，被否定的是"占了不做"）、刷单 |
| **接单冷却** | 因滑动 24h 内主动放弃达 3 次，用户在 2h 内被禁止抢任何订单的限权状态；**不扣信用分**，与信用分正交。 | 封禁（那是 `BANNED`，持久态）、限流（本规则无请求频率含义） |

**跑腿订单（修订 2 处）**：「跑腿订单」词条的状态链改为新链条；「同步初筛」补一句「跑腿草稿保存不做初筛，提交才做」。

**统一审核中台（新增 6 条）**

| 词条 | 定义（摘要） | `_Avoid_` |
|---|---|---|
| **审核工单** | 在途派单凭据，区别于 `content_review`（历史裁决）：单生命周期、可变（领单/回收/裁决）。 | 审核记录、审核队列（队列是取候选的视图，工单是持久化实体） |
| **派单信号** | 推送，只唤醒（广播「有新工单」，不指定给谁、不锁单）。 | 指派、抢单推送 |
| **领单** | claim，原子指派（条件 UPDATE，一次一条、FIFO）。 | 抢审、接单 |
| **租约** | 领单后的持有窗口（5min），到点未裁决自动回收。 | 锁、占用 |
| **放回** | 审核员主动退回（审不了），`CLAIMED→PENDING`。 | 放弃、退回 |
| **回收** | 租约超时或放回使工单回到池中（`recycle_count+1`）。 | 释放、超时下架 |

**复用已入册词条**：「心跳」「连接票据」（presence 与 WS 复用其口径）。**`CONTEXT.md` 不新增工程/部署类词条**（工程形态/部署属实现细节，非领域词汇——票 08 判定）。

---

## 7. ADR 判定

本 effort 落 **2 篇 ADR**（编号取 `0008`、`0009`；`0005–0007` 均曾占用后删除，**不复用退役号**）：

1. **`0008` — 跑腿状态机扩展：草稿 / 锁单 / 已确认**（票 01 定）。
   - 够格：记录不可逆语义决策（**「抢 ≠ 开工」**）＋被否决选项（保留 `TAKEN` 字面量、多人单全局反转先抢后确认）＋与 **ADR-0003（先付后审）的关系**：**ADR-0003 继续有效**，新 ADR 在其**两端**各插态（前插 `草稿`、后插 `锁单`），不推翻。
2. **`0009` — 统一审核中台：独立工程 + 「推信号 + 拉工单」派单模型**（票 08 合并定）。
   - 够格：涵盖三件同一决策簇的系统性决策——① **独立审核端工程**（`front-review/`）；② **「推信号 + 拉工单」派单模型**（票 05 标候选）；③ **替换旧 `/admin/review` 工作台**。三者是「审核中台的产品形态」，拆开写会碎。

**不单开 ADR**：锁单超时接入、防占单规则（读 ADR-0008 即可理解其上下文）、工单数据模型（`review_task` 属实现选型）——均归入两篇主 ADR 的 Consequences 或对应 spec 章节。

---

## 8. 对接影响（超时中心文档回填点）

**⚠️ 前提**：本 effort 对超时中心**零代码改动**——两个新 taskType 都是**新增接入方**（中心通用模型 `task_type + biz_id + 回调` 本就支持，复合 bizId 在 spec §3.2 已有先例 `userId:couponId`）。仅需**文档回填**：

- **`.scratch/timeout-center/spec.md`**：
  - §8「首个接入方」补两行 —— 跑腿锁单超时（taskType `ERRAND_LOCK_TIMEOUT`、注册点＝抢单、回调点＝释放、复合 bizId `{orderId}:{lockSeq}`）；审核租约超时（taskType `REVIEW_LEASE_TIMEOUT`、注册点＝claim、回调点＝回收、复合 bizId `{taskId}:{claimSeq}`）。
  - **界定 §10 Out of scope** 那句「跑腿执行时限、组队截止等**非支付超时类型的实际接入**」——它是指该 spec 自身不落具体设计，**不排斥**后续新增接入方；锁单超时与审核租约由本 effort 引入，可回填为该 spec 的后续接入示例。
  - 可选：§3.4 旁注「同一逻辑计时器反复重启（如跑腿锁单轮次）应使用**不同 bizId**，不要依赖同键复位」。
- **`backend/docs/architecture/timeout-center.md`**：§8/§9 的接入方清单（或 §4 两条边）补「跑腿锁单超时」「审核租约超时」两条，各记 taskType / 注册点 / 回调点 / 复合 bizId 形状 / 计时起点。
- **不动**：`backend/timeout-center/` 任何代码；`sql.md`（除 §2 的 `lock_seq` 列、§4 的事件表索引外无新表）。

---

## 9. 可观测与运维

**本期做**

- **防占单**：冷却触发时打一条 `WARN` 日志（`userId` + 窗口内计数 + 到期时刻），成本 ≈ 0，供排查/调参。
- **审核派单**：**在线审核员数 / 队列深度（按类型）/ 平均等待时长 / 租约超时率**；积压告警 `review.backlog-warn-threshold`（队列深度）+「最老待审等待时长」阈值。
- **超时中心侧**：沿用既有 outbox 积压告警（`timeout-center.register.backlog-warn-threshold`）；两个新 taskType 复用中心死信管理面。

**开放项（另议，不阻塞落地）**

- **审核员工作量视图**（每人处理量 / 平均耗时 / 超时率）——拉取模型的副产品，属 map 的 `Not yet specified`。
- **管理侧「占单滥用」榜单**——本期触发冷却的用户量 ≈ 0，榜单没有读者；事实数据已在 `errand_order_event`，运营需要时可 SQL 自查。
- **跑腿侧用户端 UI 细案**（草稿箱、锁单倒计时、确认/放弃的页面级设计）——本期 spec 只定行为口径，视觉细案可能另开 effort。
- **草稿堆积清理**、**终态软删能力**（是否给 `CANCELLED`/`REJECTED`/`COMPLETED` 软删）、**工单风险标记形态**、**同单 N 次上限**、**入池兜底对账 job**。
- **监控面板接入 / 管理接口鉴权**（超时中心 spec §9 既有开放项）。

---

## 10. Out of scope

> 与 `map.md` 的 Out of scope 逐条对齐并定稿。

- **举报裁决 / 申诉机制**：后续 effort。
- **审核中台自建账号体系 / SSO**：本期沿用既有角色门控（ADMIN/AUDITOR），不新造登录设施。
- **超时中心旧链路下线**（`delay_outbox_message` / `ProductPayTimeoutConsumer` / `productTimeoutScan`）：属 `.scratch/timeout-center/spec.md §10` 的迁移 effort，将来一次性下线、不留双跑。
- **跑腿执行时限（送达 deadline）接入超时中心**：超时中心 spec 已明确排除；本期只接「锁单超时」这一新类型。
- **实现本身**：本 effort 只出 spec；`front-review/` 建立与原型平移、主工程替换删除、各域入池点、状态机代码改造等由 spec 派生实现票承接。
- **多人单（组队）**：其在线实现已下线，本 effort 不继承其状态机、不恢复多人单（§1.2）。

---

_本 spec 关闭即本 effort 的地图终点。_
