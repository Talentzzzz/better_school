# 02 grilling：锁单 5 分钟超时接入超时中心

Type: grilling
Status: resolved
Blocked by: 无

## Question

**已定**：锁单倒计时 = 5 分钟，且**复用既有超时中心**（不自己写扫描 job）。本票把这句落成契约级设计。事实底座（已核）：

- 中心在 `backend/timeout-center/`（HTTP 8083 / Dubbo tri 50054）；注册接口 `ITimeoutTaskRegister`（**批量信封 + 双清单**），回调接口 `ITimeoutTaskCallback`（中心按任务行 `callback_group` **动态 ReferenceConfig** 路由）；at-least-once，**幂等在业务方**（推荐状态机条件 UPDATE）。
- 业务侧提交通道 = 单体 `timeout_register_outbox`（**事务内 INSERT 原子注册** + `afterCommit` 即时发送 + `@XxlJob timeoutRegisterOutboxScan` 30s 兜底），发送器 `infrastructure/mq/TimeoutRegisterOutboxSender`；边 `infrastructure/adapter/port/timeout/TimeoutTaskRegisterPort`。
- 跑腿创建**已接** `ERRAND_PAY_TIMEOUT`（`ErrandOrderServiceImpl.createOrder` → `schedulePayTimeout`）；`taskType` 常量在 `bs-types` 的 `Constants`。
- 业务侧回调消费 = `trigger/rpc/TimeoutTaskCallbackDubboService`（`@DubboService(group = "${spring.application.name}")`），分发在 `applyCloseAction`，跑腿关单落入 `ErrandOrderCase.handlePayTimeout`。
- ⚠️ 超时中心 spec 的 Out of scope 原写「跑腿执行时限、组队截止等**非支付超时类型的实际接入**」不在其范围——锁单超时属**新增类型**，需确认这只算「接入方新增」（中心通用模型本就支持 `task_type + payload + 回调`），还是要动超时中心自己的 spec。

要裁决：

1. **taskType 命名与归属**：新增常量（如 `ERRAND_LOCK_TIMEOUT`）落 `bs-types` `Constants`；`callback_group` 沿用 `${spring.application.name}`。
2. **注册点**：抢单事务内（`grabOrder` 的条件 UPDATE `PUBLISHED→LOCKED` 同事务）写 `timeout_register_outbox`，`action_time = now + 5min`；是否需要一个「锁单超时」的专用 payload（现有 `ErrandPaidMessage` 是先例）。
3. **回调处理**：中心到点回调 → `ErrandOrderCase` 新增处理，条件 UPDATE `LOCKED → PUBLISHED`（**未命中即幂等成功**，与既有 `handlePayTimeout` 同口径）；释放要不要发通知 / 记 event。
4. **取消边**：确认/放弃发生时，是否需要 `ITimeoutTaskRegister.cancel` 撤销那条 5 分钟任务（不撤销的话，到点回调会打在一个已 `CONFIRMED` 的行上——条件 UPDATE 会挡住，**功能正确**，但要多一次无谓回调；是否接受）。
5. **计时起点**：`action_time` 从**锁单时刻**起算，与既有 `ERRAND_PAY_TIMEOUT`（从创建时刻）的口径差异要不要在 spec 写明。
6. **多人单的锁单超时**：若票 01 裁决多人单也走锁单，同一订单的多条超时任务如何区分（`biz_id` 是订单 id，`uk(task_type, biz_id)` 撞键复位——多人单是否需要按「第几次锁单」区分，还是复用同键复位语义）。
7. **中心侧要不要动**：是否需要在 `.scratch/timeout-center/spec.md` / `backend/docs/architecture/timeout-center.md` 补一条「新增接入方」记录（只在文档层面，不改中心代码）。

**产出**：锁单超时的接入设计（taskType / 注册点 / payload / 回调 / 幂等 / 取消），写进 `spec.md` 对应章节，并标注是否需要回填超时中心文档。

## Answer

2026-10-13 grilling 拍板。事实底座已逐条核到源码 / DDL：`ErrandOrderServiceImpl`（`PAY_TIMEOUT_MINUTES=15`、`grabOrder`、`handlePayTimeout`）、`ErrandRepository`（`grab` 条件 UPDATE、`schedulePayTimeout`、`saveEvent` 流水）、`ErrandMapper.xml`（`grab`/`cancelByTaker`/`markTimeout` 的 SQL）、`TimeoutRegisterOutboxStore`（afterCommit 即时发 + `callbackGroup=应用名`）、`TimeoutTaskRegisterPort`（`register`/`cancel`）、`TimeoutRegisterOutboxSender.cancelAsync`（javadoc 已写明「同键复位必须取消」）、`TimeoutTaskCallbackDubboService.applyCloseAction`、`Constants`、超时中心 spec §3.2/§3.4/§4.2/§6.2/§7.6、`sql.md` 的 `errand_order` DDL。

### 结论（逐问）

| # | 问题 | 裁决 |
|---|---|---|
| 1 | taskType 命名与归属 | 新增常量 **`ERRAND_LOCK_TIMEOUT`**（字面量 = `"ERRAND_LOCK_TIMEOUT"`）落 `bs-types` `Constants`，紧邻 `ERRAND_PAY_TIMEOUT`（沿 §7.4 业务域前缀规范）；`callback_group` **沿用 `${spring.application.name}`**，不改（`TimeoutRegisterOutboxStore` 已从该来源取，应用改名两侧自动一致）。 |
| 2 | 注册点 / payload | 注册点：**抢单事务内**（`grab` 的条件 UPDATE `PUBLISHED→LOCKED` **同事务**）写 `timeout_register_outbox`，`action_time = 锁单时刻 + 5min`，且**同一 UPDATE 顺带 `lock_seq = lock_seq + 1`**（见 Q6）。**不需要专用 payload**：超时注册边**没有 payload 位**——中心回调 DTO 只有 `taskType/bizId/actionTime`（spec §6.2），业务库 outbox DDL 也无 payload 列（spec §7.6）。`ErrandPaidMessage` **不可类推**：那是 `bs-errand-paid` 主题的 outbox 业务消息（另一条机制），与超时注册无关。唯一需携带的定位信息 = **复合 `bizId`**。 |
| 3 | 回调处理 | `TimeoutTaskCallbackDubboService.applyCloseAction` 增 `case Constants.ERRAND_LOCK_TIMEOUT`；**bizId 是复合键，不能走现有 `parseBizId`（`Long.parseLong`）**，需拆 `orderId:lockSeq`。→ `ErrandOrderCase.handleLockTimeout` → 域方法 → 仓储条件 UPDATE `LOCKED→PUBLISHED`（`taker_id=NULL`），**未命中即幂等成功**（同 `handlePayTimeout` 口径）。**同事务落 `errand_order_event`**（`LOCK_TIMEOUT`, from `LOCKED`, to `PUBLISHED`, actor `SYSTEM`）。**通知 / 滥用计数 / 信用分一律不做**（归票 03）。 |
| 4 | 取消边 | **不调 `cancel`。** ① 条件 UPDATE + 轮次守卫使「多余回调」零副作用（spec §4.2 本就定「取消是可选优化，接入方不取消也正确」）；② `cancelAsync` 是 best-effort、带失败与竞态面；③ 轮次区分 bizId 后，陈旧任务天然打不中当前锁（见 Q6）。记「可选优化，本期不做」。 |
| 5 | 计时起点 | **从「锁单时刻」起算**（5min 是 taker 的确认窗口，必须从抢到手那刻开始）；与 `ERRAND_PAY_TIMEOUT`（从**创建**时刻）**口径不同，spec 必须写明**（读者易误以为对称）。 |
| 6 | 多人单 / 轮次区分 | **不复用 `uk(type,biz_id)` 同键复位语义；按锁单轮次区分**：`bizId = {orderId}:{lockSeq}`，`lockSeq` 为订单上的单调计数（每次抢单 +1）。**这不是为多人单才要**——单人单「放弃→重抢」同样会踩（下节论证）。 |
| 7 | 中心侧动不动 | **不改中心代码**（新增接入方；中心通用模型 `task_type + biz_id + 回调` 本就支持，复合 bizId 在 §3.2 已有先例 `userId:couponId`）；**只回填文档**（见「回填点」）。 |

### 关键发现：`uk(task_type, biz_id)` 的「撞键复位」对锁单超时是错的

这是本票最重要的裁决，且**独立于多人单**。

**问题**：超时中心 spec §3.4 规定——同 `(task_type, biz_id)` **在途**（WAITING/RETRYING）时再次注册 = **幂等成功应答、不改期**；只有终态才「复活」并刷新 `action_time`。既有 `cancelAsync` 的 javadoc 已写明这个坑（原话「同键复位场景必须先取消」）。

若 `bizId` 直接用 `orderId`，锁单超时的正常路径就会踩：

1. A 抢单（t0）→ 注册 `LOCK(orderId)`，action = t0+5。
2. A 放弃（t1）→ 回 `PUBLISHED`；中心任务若**未取消**，仍 `WAITING(t0+5)`。
3. B 抢单（t2，t2 < t0+5）→ 同键再注册 → 中心**幂等成功、不改期** → B 的锁仍挂在 **t0+5** 到点。
   - 结果甲：`t0+5` 释放 B 的锁 → **B 的确认窗口被压缩**（可能不足 5min）。
   - 若放弃边补调 `cancelAsync`（best-effort、异步），与 B 的注册异步竞态：**注册先落地**（行 WAITING → 标记 SENT）、**取消后落地**（行 → CANCELED）→ **B 的锁永久无计时、卡死**（比「早释放」严重）。

即 `orderId` 单键下，「放弃→重抢」的计时重启**不可靠**，最坏产生**永久卡死的锁**。

**裁决**：给每次锁单一个**独立 bizId**（`{orderId}:{lockSeq}`），使每次注册都是**新键插入**（携带正确的 `action_time`），陈旧任务只影响旧键；回调再以**轮次守卫**（条件 UPDATE 带 `lock_seq`）挡住陈旧任务。这样取消彻底退化为可选、竞态面消失。

> 与超时中心模型的关系：**不改中心任何东西**——中心 spec 明确「biz_id 无需全局唯一、复合引用编码进此列」（§3.2，例 `userId:couponId`）。「同键复位」语义本就用于**同一逻辑计时器的重复登记**（如「同一订单再次进入待支付」）；锁单超时是**同一订单上反复重启的计时器**，语义上就该是不同键。

### 定稿接入设计（供票 09 落 spec §锁单超时）

**参数**：`LOCK_TIMEOUT_MINUTES = 5`（落 `ErrandOrderServiceImpl` 常量，与 `PAY_TIMEOUT_MINUTES` 并列）。

**标识与字段**：`errand_order` 新增 `lock_seq INT NOT NULL DEFAULT 0`（每次抢单 +1；既是 bizId 的轮次，也是回调守卫的比对值）。前端倒计时取锁单时刻（`grab` 的 `update_time`）+ 5min，无需另加时间列。

**注册（抢单事务内）**：

```
UPDATE errand_order
   SET state='LOCKED', taker_id=?, lock_seq=lock_seq+1, update_time=NOW()
 WHERE errand_order_id=? AND state='PUBLISHED' AND deadline > NOW()   -- 复用 grab 现条件
同事务写 timeout_register_outbox：
   task_type='ERRAND_LOCK_TIMEOUT',
   biz_id='{orderId}:{lock_seq}',
   callback_group=${spring.application.name},
   action_time=NOW()+5min
```

走既有 `TimeoutRegisterOutboxStore.save` 形态（提交后即时发 + 30s 扫描兜底）；复合 bizId 使 `uk(type,biz_id)` 不再撞键，走纯插入。

**回调（释放）**：

```
中心按 callback_group 回调 → applyCloseAction 拆 bizId='{orderId}:{lockSeq}'
→ ErrandOrderCase.handleLockTimeout(orderId, lockSeq)
→ 域 + 仓储条件 UPDATE：
   UPDATE errand_order SET state='PUBLISHED', taker_id=NULL, update_time=NOW()
    WHERE errand_order_id=? AND state='LOCKED' AND lock_seq=?     -- 轮次守卫
同事务落 errand_order_event（LOCK_TIMEOUT / LOCKED→PUBLISHED / SYSTEM）
```

命中 0 行 = 已确认 / 已放弃并重抢（新轮次）/ 已取消 → **幂等成功、零副作用**。

**幂等**：完全落在「状态机条件 UPDATE」既有口径上（业务方负责不重，spec §6.4）；`lock_seq` 守卫额外挡住**陈旧轮次**——这是单靠状态守卫挡不住的。

**取消**：不做（见 Q4）。**通知 / 滥用计数 / 信用分**：不做（票 03 拥有这些动作；票 02 只做释放 + 流水）。

**与订单执行 deadline 的交叉（跨票锚点）**：现有 `markTimeout`（deadline 扫描）适用状态集是 `PUBLISHED/TAKEN/PICKED_UP`——**必须补 `LOCKED`**，否则锁单中的订单到期无人收口。若锁单释放发生在订单 deadline 已过之后，释放回 `PUBLISHED` 的订单会被 deadline 扫描立即取消（无害，但票 01 的迁移矩阵要写清「LOCKED 也是 deadline 可作用态」）。释放是否落搜索同步消息，取决于票 01 对 `LOCKED` 的对外可见口径（现有 `grab` 不落搜索同步，暗示接单不改变对外可见性）。

### 供票 09 落 spec 的草稿（§锁单超时）

> **3.x 锁单超时（接入既有超时中心，不自建扫描 job）**
> - taskType `ERRAND_LOCK_TIMEOUT`；bizId `{orderId}:{lockSeq}`；callback_group = 应用名。
> - 注册点：抢单事务内（`PUBLISHED→LOCKED` 条件 UPDATE 同事务）写 `timeout_register_outbox`，action_time = 锁单时刻 + 5min；同一 UPDATE `lock_seq+1`。
> - 回调点：条件 UPDATE `LOCKED→PUBLISHED`（带 `lock_seq` 守卫），未命中即幂等成功；同事务落 `errand_order_event`。
> - 参数：5 分钟；计时自**锁单**时刻（区别于 `ERRAND_PAY_TIMEOUT` 自**创建**时刻）。
> - 取消：不做（可选优化，同超时中心 §4.2）。
> - 中心零代码改动；属**新增接入方**。

### 回填点（文档，不改中心代码）

- `.scratch/timeout-center/spec.md`：§8「首个接入方」补一行「跑腿锁单超时（新增接入方：taskType `ERRAND_LOCK_TIMEOUT`、注册点=抢单、回调点=释放）」，并界定 §10 Out of scope 那句「跑腿执行时限、组队截止等**非支付超时类型的实际接入**」——它是指该 spec 自身不落具体设计，**不排斥**后续新增接入方；锁单超时由本 effort 引入，可回填为该 spec 的第二个接入示例。
- `backend/docs/architecture/timeout-center.md`：接入方清单补「跑腿锁单超时」一条（taskType / 注册点 / 回调点 / 复合 bizId 形状）。
- **不动**：`backend/timeout-center/` 任何代码；`sql.md`（除票 01 的 `lock_seq` 列外无新表）。

### 转票锚点（后续票必须承载）

- **票 09（spec）**：本节「定稿接入设计」+「供票 09 落 spec 的草稿」原样落稿，含参数、标识形状、注册/回调/幂等/取消/通知口径。
- **票 01（状态机）**：① `errand_order` 加 `lock_seq`（新增列与迁移编号归票 01）；② `markTimeout` / `listTimeoutOrderIds` 的适用状态集**补 `LOCKED`**；③ 若多人单也走锁单，确认每名额抢单同样 `lock_seq+1`（每名额各持独立计时）；④ 由 `LOCKED` 的对外可见口径决定释放是否落搜索同步。
- **票 03（防占单）**：本票已定「释放边不发通知、不计滥用、不扣分」；「超时未确认 vs 主动放弃」的区分、是否提前提醒，全部归票 03。
