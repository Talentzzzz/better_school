# 03 grilling：防恶意占单规则定稿

Type: grilling
Status: resolved
Blocked by: 01

## Question

「先抢后确认」放开了「抢到手不一定真做」的口子，必须有防恶意占单（抢了不确认、反复抢了放弃、占着名额不让别人抢）。要裁决**规则与参数**：

1. **超时释放**：5 分钟未确认自动释放回 `已发布`（票 02 落机制）。**是否给接单方提前提醒**（如剩 1 分钟）？提醒走哪个通知通道（现有 `ORDER_EVENT` 先例）。
2. **主动放弃的计数**：计数口径与存储——新增列/表（如 `errand_grab_abuse`，或用户在 `user` 上的计数）？窗口是滑动 24h 还是自然日？只统计「锁单后放弃」，还是也含「锁单后超时未确认」（超时可能是掉线，与主动放弃要不要分开计）。
3. **阈值与冷却**：超阈值（建议 24h 内 ≥3 次）触发冷却（建议 2h），冷却期内 `grabOrder` 直接拒（`BusinessException` 文案）；冷却是否**按订单类型/全员**统一。
4. **同一订单限制**：同一用户对同一订单放弃后，冷却期内不可再抢该单；是否需要「同一订单最多被同一人抢 N 次」。
5. **信用分联动**：复用现有信用分体系（`.scratch/errand-multi-taker/spec.md` 有「执行期退团 -20」先例）。**锁单期放弃**扣分口径：不扣 / 轻扣 / 超阈值重扣；与多人单退团规则（组队期退团不扣分）如何对齐，避免两套口径打架。
6. **发布方视角**：被反复占单的发布方要不要有补偿（提高曝光？优先派给高信用接单方？）——若不做，明确记为不做。
7. **并发与幂等**：抢单原子性（条件 UPDATE `PUBLISHED→LOCKED`，多人单进团已有 `FOR UPDATE` 先例）；确认 / 放弃 / 超时释放三条路径的竞态守卫（都走状态守卫的条件 UPDATE，重复调用失败）。
8. **可观测**：是否需要在管理侧看到「占单滥用」榜单（供运营人工干预）——若不做，记为不做。

**产出**：规则清单 + 参数默认值 + 数据落点（表/列/迁移编号），写进 `spec.md`；术语落 `CONTEXT.md`。

## Answer

**resolved（2026-10-14，grilling 两轮）。** 下列为定稿；编号更大的票若反转，以后票为准。本票产出为**设计定稿，不含实现**——`spec.md` 由票 09 收口撰写（票 09 被 01–08 阻塞）。

### 0. 开工前事实校正（改写了票面 1/5 两问的框架）

- **`bs_user` 无任何计数列**。库内惯例两条：**独立计数表**（`post_like_count` / `post_comment_count`）或**流水 + 幂等键聚合**（`user_credit_detail` 的 `uk_user_reason_ref` + `sumTodayChange`）。`errand_order.taker_canceled` 是**订单级幂等位**（防重复扣分），不是用户级计数。
- **抢单域零 Redis 使用**（Redisson 3.52.0 已就绪，现有 `RBucket`/`RLock` 先例）；**全仓不存在任何限流 / 冷却 / 黑名单**——唯一准入闸门是 `CreditAccountServiceImpl.ensureOperable`（非 `BANNED` 且 `credit_score ≥ 60`；<30 自动封禁）。
- **票面「执行期退团 -20」先例**：`.scratch/errand-multi-taker/spec.md` 是**孤立 spec**（其在线实现已随 project-rework 下线）；其原文为「**组队期退出不扣分** / 执行期退出 `ERRAND_TAKER_CANCEL` -20」。代码层现存两个 -20 是 `ERRAND_TAKER_CANCEL`（`CONFIRMED` 后取消）与 `ERRAND_TIMEOUT`（执行超时未完成）——**都属"已承诺后违约"**。⇒ 该先例**支持**票 01 的「锁单期放弃不扣分」，而非反对。
- **`errand_order_event`**：12 列、唯一索引 `idx_order(order_id)`、append-only；`event_type` / `reason_type` 是 `ErrandRepository` 内的**字符串字面量**（无枚举类）。票 01 已把两条路径分开落：`LOCK_ABANDONED`（actor = 接单方）/ `LOCK_TIMEOUT`（actor = `SYSTEM`，**`actor_id` 为 NULL**）——**这决定了"超时数不出来"**。
- **通知先例**：`ErrandOrderCase.notifyPaidReview`（scope `MARKET_ERRAND` / type `ORDER_EVENT` / `refType="ERRAND"` / `dedupKey="ORDER_EVENT:ERRAND_PAID:{orderId}"`）；通道是**拉取式**站内通知（per-(user,scope) 已读水位线），**不是实时推送**。

### 1. 超时释放提醒（票内 1）— 不提前预警；释放后补一条结果通知

- **不做「剩 1 分钟」提前预警**：现有 `ORDER_EVENT` 是拉取式（用户打开消息页才看得到），5 分钟窗口内触达不了眼前；真正的"及时"要靠实时 WS，而为一次 5 分钟锁单单开用户侧 WS（票 05 的 `/ws/review` 是审核员的）不成比例。前端**倒计时**（票 01 §3：`grab` 的 `update_time` + 5min 派生）已是主要手段。
- **做**：超时释放后给**接单方**落一条结果通知——复用 `ORDER_EVENT` × `MARKET_ERRAND`、`refType="ERRAND"`、`dedupKey="ORDER_EVENT:ERRAND_LOCK_TIMEOUT:{orderId}:{lockSeq}"`（带 `lockSeq`，与超时任务的复合 bizId 同构 ⇒ 多轮释放各发各的，不撞 `uk_dedup`）。
- ⚠️ **实现约束**：释放会清 `taker_id`，故域方法 `handleLockTimeout` 必须**在清空前读出并返回被释放的接单方 id**（对齐 `ErrandCancelResult.getTakerId()` 先例），case 层才发得出通知。
- ⚠️ **反转票 02**：票 02 Q3 定「释放边不发通知」，但同句写明「通知归票 03」；本票编号更大，**在此反转**（仅"结果通知"一条，**不含**提前预警）。

### 2. 计数口径与存储（票内 2）— 复用事件表，滑动 24h，只计主动放弃

- **(a) 落点：不新增表 / 列。** 复用 `errand_order_event`（append-only，**事实源**；票 01 分开落事件正是为此），查询 `actor_id=? AND event_type='LOCK_ABANDONED' AND create_time >= NOW()-INTERVAL 24 HOUR`。抢单是**低频**动作（每人每天个位数），不需要计数表的写放大优化，也不该造"第二个真相"。→ `schema.sql` 给该表加索引 **`idx_actor_type_time(actor_id, event_type, create_time)`**（现仅 `idx_order`），重建库生效。
- **(b) 窗口：滑动 24h**，非自然日——自然日的「23:59 放弃 3 次 + 00:01 放弃 3 次」可跨零点刷新逃逸。
- **(c) 只计「主动放弃」（`LOCK_ABANDONED`）**；**超时未确认（`LOCK_TIMEOUT`）不计入触发**。理由：超时是**非可控**的（掉线 / 离开 / 没看到），惩罚不公；票 02 已定超时不惩罚。事件照记（分开），为运营 / 调参保留观察面。
- ⚠️ **显式约束**：若将来要把超时纳入计数，**必须让 `LOCK_TIMEOUT` 事件带上 `actor_id = taker`**——当前该事件 actor 是 `SYSTEM`、`actor_id` 为 NULL，**从事件表数不出来**。

### 3. 阈值与冷却（票内 3）— 3 次 / 24h → 2h，全员统一，触发即重置窗口

| 项 | 默认值 |
|---|---|
| 阈值 | 滑动 24h 内 **≥3 次**主动放弃 |
| 冷却 | **2 小时** |
| 适用范围 | **全员统一，不按订单类型（`type`）分** |
| 触发副作用 | **重置该用户计数窗口** |
| 冷却写 | `SET NX EX 7200`（幂等，不叠加延长） |
| 判定点 | `grabOrder` 前置（条件 UPDATE 之前） |

- **为什么"触发即重置窗口"**：若不清空，冷却结束（t2+2h）时窗口内那 3 次仍在 ⇒ 立刻再触发 ⇒ 循环到 24h 滑出，**实际封禁 ≈22h**，与"轻惩罚"的设计直接冲突；重置把"罚过了"和"重新起算"绑定。
- **为什么按类型不分**：滥用主体是"人"不是"某类单"；按类型分会让滥用者换类型逃逸，且库里无按类型分权的先例。
- **拒绝**：`throw new BusinessException(ResponseCode.FORBIDDEN, "因近期多次放弃接单，您已被限制接单，请于 HH:mm 后再试")`。⚠️ 与 `<60` 闸的 `BUSINESS_ERROR(500)` 不一致是**有意的**（403 = 权限类拒绝；500 会污染客户端 / 监控的错误语义）。

### 4. 同一订单限制（票内 4）— (a) 自动成立；(b) 不做

- **(a) 冷却期内不可再抢该单：自动成立**，不需要额外规则——冷却 key 是 **user 维度、禁全量抢单**，天然包含"该单"；再写一条同单规则是重复口径。
- **(b) 「同一订单最多被同一人抢 N 次」：不做**，**记为开放项**。理由：与冷却口径重叠（跨单层面已兜住），单独设 N 会造第三套参数；订单生命周期受 `deadline` 限制、单次抢单最多占 5 分钟，收益有限。**备选（若实测出现"同一发布方被同一人反复占"）**：加「**放弃过的单，本发布周期内不可再抢**」——零参数、一条事件查询即可。

### 5. 信用分联动（票内 5）— 不扣分（维持票 01）

**裁决：`LOCKED→PUBLISHED`（放弃）不扣信用分，维持票 01；本票（编号更大）确认而非反转。**

- 惩罚载体选**限权（冷却）**而非**扣分**：锁单放弃是低代价高频小动作（一次几秒），而信用分**只减不增**且 `-20` 一档——扣 3 次即低于 60 直接禁接单，**量刑与过错严重不成比例**；冷却（2h 不可抢）才是"等长于过错"的惩罚。
- 现存两个 -20（`ERRAND_TAKER_CANCEL` / `ERRAND_TIMEOUT`）针对的都是**已承诺后违约**（产生对方真实损失）；票 01 定「抢 ≠ 开工」，锁单期放弃**未产生承诺**，不在这条口径上。与（已下线的）multi-taker「**组队期**退出不扣分 / 执行期退出 -20」口径一致（锁单期 ≈ 组队期）。
- 幂等键 `(user_id, reason_type, ref_id)` 对"每 N 次触发"的重复行为不友好（同 ref 只扣一次），要做须造合成 ref，成本高且语义脏。
- ⇒ **口径正交**：**信用分只记违约，冷却只记滥用**，两套互不触发。

### 6. 发布方视角（票内 6）— 不做（明确记为 Out of scope）

- 「提高曝光」**没有落点**：跑腿可见面就是 `GET /api/errand/orders` 的 `state='PUBLISHED'` 平铺列表（票 01 §3），无排序权重、无推荐位；要做等于新造一个排序维度，超出本 effort。
- 「优先派给高信用接单方」与**先到先得**（条件 UPDATE）及票 01 的锁单语义直接冲突；改成定向派单要引入新模型，成本远超收益。
- 发布方在占单场景的真实损失 ≈ **5 分钟**（超时自动释放）+ **0 元**（无扣款），且手里已有救济：`LOCKED` 期**可直接取消**（票 01 矩阵）或等超时释放。补偿属纠纷 / 仲裁域的问题，不是反滥用的问题。

### 7. 并发与幂等 + Redis 落点（票内 7）

**状态迁移互斥一律走条件 UPDATE，Redis 不参与状态互斥**（`FOR UPDATE` 先例随多人单下线，不继承）：

| 路径 | 守卫 | 0 行分流 |
|---|---|---|
| 抢单 `PUBLISHED→LOCKED` | `state='PUBLISHED' AND deadline>NOW()`（同 UPDATE `lock_seq+1`） | 抛业务错「手慢了…」（既有） |
| 确认 `LOCKED→CONFIRMED` | `state='LOCKED' AND taker_id=?` | 抛业务错 |
| 放弃 `LOCKED→PUBLISHED` | `state='LOCKED' AND taker_id=?` | 抛业务错 |
| 超时释放 `LOCKED→PUBLISHED` | `state='LOCKED' AND lock_seq=?`（轮次守卫） | **幂等成功** |

四条 UPDATE 在同一 `state` 列上互斥于 DB 行锁，任意交错至多一条成功。**抢单本身无幂等键**（重复点第二次必然 0 行，既有行为不改）。

**Redis（用户准入）用在"冷却计数与判定"这条热路径**：

| 用途 | 结构 | Key | 语义 |
|---|---|---|---|
| 滥用计数 | ZSET | `bs:errand:grab-abandon:{userId}` | member `{orderId}:{lockSeq}`、score = 放弃时刻 → **精确滑动 24h**（`ZREMRANGEBYSCORE .. now-24h` + `ZCARD`） |
| 冷却标记 | String | `bs:errand:grab-cooldown:{userId}` | 值 = 到期时刻；`SET NX EX 7200` |

- **写点**：放弃事务**提交后**写 Redis（对齐既有 `afterCommit` 风格）；`grab` 前置读 Redis 判定。
- **口径**：`errand_order_event` 仍是**事实源 / 审计**，Redis 是**判定加速层**——故 §2 的 `idx_actor_type_time` **照加**：**Redis 不可用 / 降级时回落查事件表**（ZSET 不存在 ⇒ 该窗口 0 次 ⇒ 放行；只有 Redis 报错才回落 SQL）。
- **已知取舍**：Redis 写失败或数据丢失 ⇒ **少计 ⇒ 宽恕**（限流不是资金，宽恕方向安全）；不引入对账补偿。
- **为什么冷却判断放在 `grab` 前置读、不塞进 UPDATE 条件**：计数是**跨订单**的、不是这一行的属性；且冷却只是限流不是资金，乐观判断（并发窗口多放一个人）不产生正确性问题。

### 8. 可观测（票内 8）— 不做榜单，留 WARN 日志

- **不做管理侧「占单滥用」榜单**（明确记为开放项）：本阶段触发冷却的用户量 ≈ 0，榜单没有读者；事实数据已在 `errand_order_event`，运营需要时可 SQL 自查；本 effort 管理端产能集中在审核中台（票 05–08）。
- **做**：冷却触发时打一条 `WARN` 日志（`userId` + 窗口内计数 + 到期时刻），成本 ≈ 0，供排查 / 调参。

### 9. 规则清单（汇总）

1. 接单方抢单 → `LOCKED`（写 `taker_id`、`lock_seq+1`），他人不可抢；5 分钟后超时自动释放回 `PUBLISHED`。
2. 接单方可**主动放弃**（`LOCKED→PUBLISHED`），**不扣信用分**；落 `LOCK_ABANDONED` 事件（actor = 接单方）。
3. 每次**主动放弃**在 Redis 上计 1（ZSET，滑动 24h）；**超时未确认不计**。
4. 滑动 24h 内主动放弃 **≥3 次** → 置**冷却 2h**（`SET NX EX`），并**重置该用户计数窗口**；冷却触发打 WARN 日志。
5. 冷却期内 `grabOrder` 直接拒（`FORBIDDEN` + 文案）；冷却期天然含"该单"，无需同单规则。
6. 超时释放后给接单方发一条 `ORDER_EVENT` 结果通知（`dedupKey` 带 `lockSeq`）。
7. 状态迁移幂等一律靠条件 UPDATE；抢单原子性**不依赖 Redis 锁**。
8. **不做**：提前预警、超时计数、扣信用分、同单 N 次上限、发布方补偿、管理侧榜单。

### 10. 参数默认值

| 参数 | 默认 | 落点 |
|---|---|---|
| 滥用窗口 | 24h（滑动） | `ErrandOrderServiceImpl` 常量（与 `PAY_TIMEOUT_MINUTES` / `LOCK_TIMEOUT_MINUTES` 并列） |
| 滥用阈值 | 3 次 | 同上 |
| 冷却时长 | 2h | 同上 |
| 锁单超时 | 5min | 票 02 已定 |
| Redis key 前缀 | `bs:errand:grab-abandon:` / `bs:errand:grab-cooldown:` | `bs-types` `Constants`（对齐 `bs:chat:ws-ticket:` 先例） |

### 11. 数据落点

- **不新增表、不新增列。** 唯一 DDL 改动：`errand_order_event` 加索引 `idx_actor_type_time(actor_id, event_type, create_time)`（改 `schema.sql` + 重建库；本仓库无编号迁移机制，票 01 已裁；若未来恢复编号迁移，从历史最大 **028** 续号）。
- **Redis 两个 key**（见 §7），均带 TTL / 时间裁剪，无常驻数据。
- **无新事件类型**（`LOCK_ABANDONED` / `LOCK_TIMEOUT` 票 01 已定）；冷却触发**不落事件**，只落 WARN 日志。

### 12. 术语（`CONTEXT.md`「跑腿订单」节）

票 01 已定 `草稿` / `锁单` / `已确认` / `放弃` 四条；本票增补两条：

- **占单**：抢单后未确认即离手（主动放弃或超时释放），把名额占住使他人抢不到的行为；其中**主动放弃**计入滥用计数，**超时未确认**不计。`_Avoid_: 恶意抢单（"抢单"本身是正常动作，被否定的是"占了不做"）、刷单（那是虚假交易）。`
- **接单冷却**：因滑动 24h 内主动放弃达 3 次，用户在 2h 内被禁止抢任何订单的限权状态；**不扣信用分**，与信用分（只记"承诺后违约"）正交。`_Avoid_: 封禁（那是 BANNED，需管理员 / 低分触发的持久态）、限流（泛称，本规则无请求频率含义）。`

### 13. 交接 / 实现触点（供拆票）

- 域：新增 **port** `IErrandGrabAbusePort`（`isCoolingDown(userId)` / `recordAbandon(userId, orderId, lockSeq)`）。
- 基础设施：新增适配器（Redisson ZSET + bucket；Redis 异常时回落 `errand_order_event` 查询）。
- 用例：`ErrandOrderCase.grabOrder`（前置冷却判定 + 拒绝）、`ErrandOrderCase` 放弃路径（事务提交后写 Redis 计数 + 阈值判定 + 冷却 + WARN）、`ErrandOrderCase.handleLockTimeout`（释放后发通知——需域方法返回被释放的 `takerId`）。
- 域服务：`ErrandOrderServiceImpl.handleLockTimeout` 返回值扩为「被释放的接单方 id」。
- DDL：`schema.sql`（`errand_order_event` 加索引）。
- 文档：`backend/better-school/docs/db/errand.md`（事件取值说明 + 新索引）、`docs/api/errand.md`（放弃 / 冷却拒绝的响应语义）。
- **不改**：状态迁移 SQL（票 01/02 已定）、搜索侧（票 01 §3）、超时中心（票 02）。

### 14. 测试要点（供票 09 参考）

- 并发抢单：条件 UPDATE 仅一人成功（既有范式）。
- 放弃 → 计数 +1；第 3 次放弃触发冷却；冷却期内 `grab` 抛 `FORBIDDEN`。
- 冷却触发后窗口重置（冷却到期即可再抢，不循环触发）。
- 超时释放**不**计入滥用；`lock_seq` 轮次守卫挡住陈旧回调释放新锁。
- Redis 不可用时回落事件表查询（降级路径）。
- 释放通知的 `dedupKey` 含 `lockSeq`，多轮释放各发各的。

### 15. 转票锚点

- **票 09（spec）**：§1–§11 原样落 `spec.md` 的「防占单」章节；术语两条并入 `CONTEXT.md` 清单。
- **票 02**：本票在「释放边通知」上**反转**其"不发通知"（仅结果通知，无预警）；其余（taskType / 复合 bizId / 轮次守卫 / 不取消 / 不扣分不计数）原样沿用。
- **票 01**：本票**确认**其「锁单期放弃不扣分」，并落实其锚点「按 `LOCK_ABANDONED` 计数」；其对 spec 的交接（§「交接给 spec」）不变。
