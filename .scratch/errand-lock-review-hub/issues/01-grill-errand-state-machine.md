# 01 grilling：跑腿新状态机定稿（草稿 / 锁单 / 已确认 / 放弃）

Type: grilling
Status: resolved
Blocked by: 无

## Question

把图绘期定的目标链条落成**可实现的域模型**：`草稿 → 待支付 → 已支付待审核 → 已发布 → 锁单 → 已确认 → 取货 → 送达 → 完成`，现有 `ErrandStatusVO` 为 `PENDING_PAYMENT, PENDING_REVIEW, PUBLISHED, TAKEN, PICKED_UP, DELIVERED, COMPLETED, CANCELLED, DISPUTED, REJECTED`。要裁决：

1. **枚举口径**：`草稿`/`锁单`/`已确认` 是新增 `DRAFT`/`LOCKED` 并把 `TAKEN` **改名** `CONFIRMED`，还是保留 `TAKEN` 字面量、只改中文文案与语义？改名牵动存量数据迁移与前后端契约，改名与否的代价要讲清。
2. **`草稿` 态的行为边界**：可编辑哪些字段、可删除、是否有限期自动清理、**草稿→待支付**的触发（用户点「提交」）与校验（回到现有同步初筛？）；草稿是否占用「同时进行中的单」配额；草稿要不要落 `images`。
3. **`锁单` 的状态位**：`taker_id` 是否在锁单时写入、`locked_at` 字段、`LOCKED` 是否出现在「我的订单」两端的列表口径。
4. **`放弃` 路径**：`锁单 → 已发布` 是否复用现有 `cancel/退回` 用例（现有「接单方仅 TAKEN 可退 → 回 PUBLISHED」是否就是这条路）；放弃要不要留痕（`errand_order_event`）。
5. **状态的完整迁移矩阵**：逐条列 `(from, to, 触发者, 前置条件, 幂等守卫)`，含与 `CANCELLED / DISPUTED / REJECTED` 的交叉（锁单期发布方能不能取消？锁单期能不能进仲裁？）。
6. **与多人单（组队）的兼容**：`.scratch/errand-multi-taker/spec.md` Q4 定的是「成团自动开始、**不设手动确认**」，组队期 = `PUBLISHED`（人数 < 所需人数派生）。「先抢后确认」是否**全局反转**这条（多人单也走锁单→确认），还是**只对单人单**生效、多人单维持成团即开始？这条决定状态机要不要按 `required_takers` 分叉。⚠️ 若全局反转，多人单的组队/成团语义要重写，价码要先摊开。
7. **存量数据迁移**：现有 `TAKEN` 行（已抢单、进行中）在改名/新语义下怎么落；是否有 `PUBLISHED` 行需要补 `LOCKED` 语义；增量脚本编号（现有迁移已到 `006`，见 `sql/migrations/`）。
8. **术语落 `CONTEXT.md`**：跑腿订单一节新增/修订词条（草稿、待支付、已支付待审核、已发布、锁单、已确认、放弃），以及是否需要一篇 ADR（新状态机 vs ADR-0003 的关系）。

**产出**：定稿的状态机 + 迁移矩阵 + 术语/ADR 清单，写进 `spec.md` 对应章节。

## Answer

**resolved（2026-10-13，grilling 两轮）。** 下列为定稿；编号更大的票若反转，以后票为准。产出为**设计定稿，不含实现**。

### 0. 开工前事实校正（改变了票内 1/6/7 三问的框架）

- **无增量迁移机制**：`sql/migrations/` 已随票 05（主体库重构）整体删除，`backend/better-school/sql/schema.sql` 是**唯一 DDL 来源**；改表＝改 `schema.sql` + 重建库。票面「迁移已到 006」为过时线索（历史最大编号实为 **028**，且 025 缺号）。跑腿状态列现名 **`state`**（`status` 已废）。
- **多人单（组队）已整体下线**：`.scratch/project-rework/issues/04-remove-multi-taker-and-group-chat.md`（resolved）已删 `required_takers` / `errand_order_member` / `room_id` / 系统群；`backend/better-school/docs/db/errand.md:3` 记「恒单人单」。`.scratch/errand-multi-taker/spec.md` 的 Q4 已成**孤立 spec**（其在线实现已不存在）。
- 其余支撑事实：`ErrandStatusVO` 为纯枚举（对外以 `.name()` 序列化，中文文案在 `front/src/utils/errand.ts`）；跑腿表**无** `locked_at` / `paidAt` / `publishedAt` 列，仅 `create_time/update_time/deadline`；幂等统一＝条件 UPDATE，事件表 `errand_order_event` append-only；**「同时进行中的单」配额当前不存在**（唯一准入闸门是信用分）。

### 1. 枚举口径（票内 1）— 改名

新增 `DRAFT`、`LOCKED`、`DELETED`；`TAKEN` **改名 `CONFIRMED`**。

最终 `ErrandStatusVO` = `DRAFT, PENDING_PAYMENT, PENDING_REVIEW, PUBLISHED, LOCKED, CONFIRMED, PICKED_UP, DELIVERED, COMPLETED, CANCELLED, DISPUTED, REJECTED, DELETED`（13 值）。

理由：本 effort 核心命题是「抢 ≠ 开工」，保留 `TAKEN` 字面量＝在代码里留一句谎；改名触点（`ErrandStatusVO`、`ErrandMapper.xml` 全部条件 UPDATE、`ErrandRepository`、前端 `errand.ts`/`types/api.ts`/`ErrandDetailView.vue`/`MyOrdersView.vue`、`backend/better-school/docs/db/errand.md`/`backend/better-school/docs/api/errand.md`、`CONTEXT.md`）**本来就要动**；DB 重建制、无 seed ⇒ 无数据迁移负担。留旧字面量是零收益技术债。

中文文案（`front/src/utils/errand.ts` 的 `ERRAND_STATUS_TEXT`）：`DRAFT`→「草稿」、`LOCKED`→「锁单中」、`CONFIRMED`→「已确认」、`PUBLISHED` 维持「待接单」；`DELETED` **不对外展示**（列表/详情一律排除，无需文案）。

### 2. 草稿的行为边界（票内 2）

- **服务端落 `DRAFT` 行**（与商品/帖子「草稿箱」同构；不用纯前端 localStorage——那会让草稿脱离状态机、也装不下配图）。
- **两条创建入口**（对齐商品 `POST /products/draft` vs `POST /products`）：`POST /api/errand/orders/draft` → `DRAFT`；`POST /api/errand/orders` → 直接 `PENDING_PAYMENT`（＝现行为）。**草稿是可选入口，不是必经**。
- **可编辑**全部内容字段（type/description/pickup/delivery/reward/timeLimitHours/images）；`publisherId` 与状态位不可改。编辑**不是**状态迁移。
- **删除＝软删 `DELETED`**（`WHERE state='DRAFT' AND publisher_id=?`）。⚠️ **撤回原「物理删除」推荐**：全站删除统一软删（post/comment/product 均落 `state='DELETED'`；`backend/better-school/docs/db/community_comment.md:51` 明写「物理 DELETE 已废弃」），跑腿不该是全站唯一例外——一致性 > 局部理由（无资金/无外见/无事件）。
- **不设自动清理**（对齐商品/帖子草稿箱，不为跑腿单另造机制；堆积风险标为开放项）。
- **`DRAFT→PENDING_PAYMENT`**：触发＝用户点「提交」；校验＝必填 + 复用现有同步初筛 `moderationScreenService.screen(description)`（命中即失败、停留草稿）；通过后过渡并**同事务注册 15 分钟支付超时**（＝现 `createOrder` 行为）。草稿态**不**起支付钟。
- **images 落库**（复用同列，上限 6）；仍不做同步初筛（与现状一致，只初筛 description）。
- **`REJECTED→DRAFT` 不做**：跑腿 REJECTED 是资金终态（已退款），复活会引入「已退款单重付」语义；用户重发新单即可。
- **`DELETED` 本期仅从 `DRAFT` 可达**；其余终态（`CANCELLED`/`REJECTED`/`COMPLETED`）常驻列表，不提供用户删除（开放项：是否给终态软删能力，另议）。
- 「同时进行中的单」配额不存在 ⇒ 此问消解为「无此项」。

### 3. 锁单的状态位（票内 3）

- **`taker_id` 锁单时即写**（沿用现 `grab` 形态，改成 `WHERE state='PUBLISHED' AND taker_id IS NULL` 的 `PUBLISHED→LOCKED` 条件 UPDATE）——先到先得原子性白拿，且「我接的单」立即可见；不要等确认才写。
- **新增 `lock_seq INT NOT NULL DEFAULT 0`**（每次抢单 `+1`）——⚠️ **依票 02（resolved，编号更大）定稿**：超时中心用 `bizId={orderId}:{lockSeq}` 区分锁单轮次（否则「放弃→重抢」在 `uk(task_type,biz_id)`「同键复位」语义下会压缩甚至**永久卡死**新锁），`lock_seq` 既是轮次、也是回调守卫的比对值。**不加时间列**（撤回原「加 `lock_expire_at`」推荐）：前端倒计时由抢单时刻（`grab` 的 `update_time`）+ 5min 派生。也不单独加 `locked_at`（进锁时刻由 `errand_order_event` 留痕）。为什么不复用 `deadline`：那是**执行时限**、被订单超时扫描占用，混用会把「锁单 5 分钟」与「送达时限」搅在一起；`lock_seq` 与它正交。
- **`LOCKED` 进两端「我的订单」**：发布方要看到（倒计时、别人抢不了），接单方也要看到（可确认/放弃倒计时）。后端 `selectMyPublished`/`selectMyTaken` 的 `state` 是可选过滤（null=全部），**天然包含**，无需改 SQL；前端 `ERRAND_STATUS_TEXT` 补 `LOCKED`。
- **对外可见口径（票 02 明确交由票 01 裁定）**：**锁单期间不落搜索同步**——沿用现 `grab`（`PUBLISHED→TAKEN` 不落 SearchSync）的先例，已发布单的搜索文档**维持旧 `PUBLISHED` 态、仍可见**；`LOCKED` 因此**从不进入搜索索引**，`SearchServiceImpl.PUBLIC_ERRAND_STATUSES`（现 `PUBLISHED/TAKEN/PICKED_UP/DELIVERED`，**写时**过滤）**不改、搜索侧零改动**。⚠️ **撤回原两条推荐**：（原）「`LOCKED` 不可见不可搜」会要求 grab/释放/超时三处落搜索同步；（原）「搜索可见集加 `LOCKED`」与「不落同步」自相矛盾——没人会在 `LOCKED` 态写文档，加了也是空操作。公开列表 `GET /api/errand/orders` 因默认 `state='PUBLISHED'` **天然不含** `LOCKED`（无需改）；作者主页硬编码 `state IN ('PUBLISHED','TAKEN')` 加 `LOCKED`（展示「锁单中」）；`TAKEN` 逐处换 `CONFIRMED`。

### 4. 放弃路径（票内 4）

- **`LOCKED→PUBLISHED`（放弃）**：复用「接单方退回」用例骨架，但落成**独立迁移 + 独立事件 `LOCK_ABANDONED`**（`reason_type=TAKER_ABANDON`）；**不扣信用分、不动 `taker_canceled`**，清 `taker_id`（不改 `lock_seq`）。
- **`CONFIRMED→PUBLISHED`（即原 `TAKEN→PUBLISHED`）**：现有 `cancelByTaker`（**扣信用分** + `taker_canceled=1` + 事件 `TAKER_CANCELLED`）**原样保留**。
- **关键裁决**：锁单期放弃**不落在「接单取消扣分」路径上**（即**不扣分**）——这是设计内的正常动作（map 明说「不合适可放弃」）；若扣分，用户会先「确认」再实际弃单，问题往后推、更糟。**「超时未确认 vs 主动放弃」的区分、滥用计数、是否额外惩罚归票 03**（同票 02 口径：通知/滥用/信用分不在本票定）。
- **留痕：要**（`errand_order_event` append-only，对齐 CONTEXT「状态流转记录」）。分开 event_type 的副作用好处：票 03 防占单可按 `LOCK_ABANDONED` 次数封禁，不误伤真实取消。
- **锁单超时任务无需撤销**：放弃后状态已是 `PUBLISHED`，5 分钟到点的回调 `LOCKED→PUBLISHED` 命中 0 行＝幂等吞掉（超时中心契约本就如此）。

### 5. 迁移矩阵（票内 5）

守卫一律条件 UPDATE，0 行按既有约定分流（「必须失败」抛业务错／「可重入」静默返回）。

| from | → to | 触发者 / 触发 | 前置条件与幂等守卫 | 事件 |
|---|---|---|---|---|
| DRAFT | PENDING_PAYMENT | 发布方「提交」 | `state='DRAFT' AND publisher_id=?`；必填+同步初筛；同事务注册 15min 支付超时 | `SUBMITTED` |
| DRAFT | DELETED | 发布方「删除」 | `state='DRAFT' AND publisher_id=?` | `DELETED` |
| PENDING_PAYMENT | PENDING_REVIEW | 支付成功（冻结） | `state='PENDING_PAYMENT'`；重试已待审则幂等返回 | `PAID` |
| PENDING_PAYMENT | CANCELLED | 15min 支付超时（SYSTEM） | `state='PENDING_PAYMENT'` | `PAY_TIMEOUT` |
| PENDING_REVIEW | PUBLISHED | 审核通过 | `state='PENDING_REVIEW'` | `REVIEW_APPROVED` |
| PENDING_REVIEW | REJECTED | 审核拒绝＋解冻退款 | `state='PENDING_REVIEW'`；退款流水 uk 幂等 | `REVIEW_REJECTED` |
| PENDING_PAYMENT / PENDING_REVIEW / PUBLISHED / LOCKED / CONFIRMED | CANCELLED | 发布方取消 | `state IN (上列 5 态) AND publisher_id=?`（**取货前**可取消；`PICKED_UP`/`DELIVERED` 不可）；待支付无退款、其余解冻退款 | `CANCELLED` |
| PUBLISHED | LOCKED | 接单方「抢单」 | `state='PUBLISHED' AND taker_id IS NULL AND deadline>NOW()`；写 `taker_id` + `lock_seq=lock_seq+1`；同事务注册 5min 锁单超时（`bizId={orderId}:{lockSeq}`）；防占单（票 03） | `LOCKED` |
| PUBLISHED / LOCKED / CONFIRMED / PICKED_UP | CANCELLED | 执行时限到（`deadline`，SYSTEM） | `state IN (上列 4 态) AND deadline<=NOW()` | `TIMEOUT` |
| LOCKED | CONFIRMED | 接单方「确认接单」 | `state='LOCKED' AND taker_id=?`（**不改 `lock_seq`**：状态离开 `LOCKED` 后陈旧回调天然打不中） | `CONFIRMED` |
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
- **(a) 锁单期发布方可取消**：可以——把 `LOCKED` 加进发布方取消白名单（退款；接单方**不扣分**，不是他的错）。
- **(b) 锁单期不进 `DISPUTED`**：`DISPUTED` 语义是「已开工/已交付的履约纠纷」，`LOCKED` 还没开工，无可争议事实（接单方不满意→放弃，发布方不满意→取消）。
- **(c) 执行时限到点而单在 `LOCKED`**：**`markTimeout` 与 `listTimeoutOrderIds` 两个方法的适用状态集都补 `LOCKED`**（票 02 转票锚点 ②；否则锁单中的订单到期无人收口）；`grab` 已要求 `deadline>NOW()`，锁不到过期单。搜索同步口径见 §3（释放**不**落搜索同步）。

**必须修的事件撞名**：旧 `CONFIRMED` 事件指「发布方确认完成」（`DELIVERED→COMPLETED`），与新的 `CONFIRMED`（`LOCKED→CONFIRMED`）撞车。修正：
- 旧 `TAKEN`（`PUBLISHED→TAKEN`）→ **`LOCKED`**
- 新 **`CONFIRMED`**（`LOCKED→CONFIRMED`）
- 旧 `CONFIRMED`（`DELIVERED→COMPLETED`）→ 改名 **`COMPLETED`**（自动完成仍 `AUTO_COMPLETED`）

### 6. 与多人单（组队）的兼容（票内 6）

**只对单人单生效，且不按 `required_takers` 分叉**——多人单已不存在、本期不恢复。理由：(1) 事实层面已被 project-rework 票 04/16 整体删除（12 文件 + schema 列 + CONTEXT 七词条），要「多人单也走锁单」须先重开整套，属另一 effort；(2) 语义层面不可调和——multi-taker Q4 是「凑齐即自动开始、不设手动确认」，本 effort 是「抢到还要人工确认」。故：spec 显式声明「多人单/组队不适用」，`.scratch/errand-multi-taker/spec.md` 标注「其在线实现已下线，本 effort 不继承其状态机」。

### 7. 存量数据迁移（票内 7）

- **目前无存量、从头开始 ⇒ 本仓库零迁移工作**：不新建编号脚本、不回填。`TAKEN` 改名纯代码层生效（无历史行需转），`PUBLISHED` 亦无存量需补 `LOCKED` 语义。**新增列 `lock_seq`（及 `state` 注释、新枚举）随 `schema.sql` 一并加、重建库生效**——票 02 所言「新增列与迁移编号归票 01」在本仓库即「改 `schema.sql`」，无编号脚本。
- 若未来恢复编号迁移机制，从历史最大 **028** 续号（非 007）。
- 面对真实生产库的**假设性**回填（本期不产出）：`UPDATE errand_order SET state='CONFIRMED' WHERE state='TAKEN'`；`PUBLISHED` 不动。

### 8. 术语与 ADR（票内 8）

**`CONTEXT.md`「跑腿订单」节**（体例遵循现有词条：名称 + 定义 + `_Avoid_:` 反例）：
- **新增词条 4 条**：
  - `草稿`：跑腿订单的私有未提交态，可编辑全字段、可删（软删）、不付款、不对外；提交后进待支付。`_Avoid_: 草稿箱`（「草稿箱」是商品/帖子的多来源停靠区，跑腿只有一个草稿态，**不复用该词条**）。
  - `锁单`：接单方抢单成功后的 5 分钟持有态，**非开工**；期间他人不可抢，超时或放弃均回落已发布。`_Avoid_: 占单、锁定、预接单`。
  - `已确认`：接单方核对信息后确认开工的态（**收编**原 `TAKEN`）；其后取货/送达链路不变。`_Avoid_: 已接单（旧 `TAKEN` 文案，语义已改）`。
  - `放弃`：锁单期接单方主动退回（`锁单→已发布`），**不扣信用分**；与 `CONFIRMED` 后的「取消」（扣分）区分。`_Avoid_: 取消（那是已确认后的动作）、退单`。
- **修订 2 条**：「跑腿订单」词条的状态链改为新链条；「同步初筛」补一句「跑腿草稿保存不做初筛，提交才做」。

**ADR**：新增一篇 **「跑腿状态机扩展：草稿 / 锁单 / 已确认」**。够格——记录不可逆语义决策（「抢 ≠ 开工」）＋被否决选项（保留 `TAKEN` 字面量、多人单全局反转）＋与 **ADR-0003（先付后审）** 的关系：**ADR-0003 继续有效**，新 ADR 在其**两端**各插态（前插 `草稿`、后插 `锁单`），不推翻。
- ⚠️ **编号待票 09 定**：`0005`/`0006`/`0007` 均曾被占用后删除（0005：16d5e24→98a6b48；0006：31f0638→14820f9），**建议不复用退役号、取 `0008`**（当前 `docs/adr/` 仅存 0001–0004）。

### 与票 02（锁单超时）的对齐

票 02 已 resolved 且编号更大，其给票 01 的**转票锚点**已全部承载于本 Answer：① `errand_order` 加 `lock_seq`（§3 + §7）；② `markTimeout`/`listTimeoutOrderIds` 适用状态集补 `LOCKED`（§5 交叉点 c）；③ 多人单不适用故无「每名额 `lock_seq+1`」（§6）；④ 由 `LOCKED` 的对外可见口径决定释放是否落搜索同步——**裁定为不落**（§3）。票 02 的注册/回调 SQL、`bizId={orderId}:{lockSeq}`、轮次守卫、计时自锁单时刻等，由票 09 原样落 spec §锁单超时，本票不重复。

### 交接给 spec（票 09）

- 本票产出为**设计定稿，不含实现**；`spec.md` 由票 09 收口撰写（09 被 01–08 阻塞）。
- **实现触点清单**（供拆票）：`ErrandStatusVO`、`ErrandMapper.xml`（全部条件 UPDATE 白名单 + 事件插入 + `grab` 加 `lock_seq+1`）、`ErrandRepository`、`ErrandOrderServiceImpl`/`ErrandOrderCase`、`ErrandController`（新增 `/orders/draft`）、`schema.sql`（`state` 注释 + `lock_seq` 列 → 重建库）、`errand_order_event` 的 `event_type`/`reason_type` 取值扩充（+`LOCKED`/`CONFIRMED`/`COMPLETED`/`LOCK_ABANDONED`/`LOCK_TIMEOUT`/`TAKER_ABANDON`）、超时中心注册/回调（taskType `ERRAND_LOCK_TIMEOUT`，见票 02）、前端 `utils/errand.ts`、`types/api.ts`、`views/errand/ErrandDetailView.vue`、`views/me/MyOrdersView.vue`、`backend/better-school/docs/db/errand.md`（`:63`/`:68` 事件取值 + `state` 取值）、`backend/better-school/docs/api/errand.md`、`CONTEXT.md`、ADR 新文件。**搜索侧零改动**（`PUBLIC_ERRAND_STATUSES` 不动，见 §3）。
