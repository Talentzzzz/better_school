# 抽奖模块接口（/api/lottery）

抽奖域（`backend/marketing/`，与积分 / 返利 / 库存同属营销服务 marketing-app **8081**；
**抽奖次数与抽奖同域**，`draw_count_*` 三张表与次数账务都在 `raffle` 域内）。
**票 13 起整域落在 `draw_*` 新模型**（决策侧票 11、库存票 12），旧域九张表与旧代码已整体删除。

**免登录白名单只有两条**：`GET /activity` 与 `GET /prizes`（访客看 banner + 奖品目录）；其余都要登录。

分页一律 `page`/`pageSize`（默认 1/10、上限 50；`page` 上限 10000），返回 `PageResult{page,pageSize,total,list}`。
失败一律走「业务 code + `info` 里逐条固定的中文文案」，成功码 `"200"`；前端按 code 分支取用。

> 票 13 删除的三个端点：`POST /my-prizes/{id}/claim`（收件信息与状态推进归票 14 发货闭环）、
> `POST /exchange/prize`（新模型没有「积分兑奖品」业务）、`POST /buckets/open`（旧奖池桶模型作废，
> 库存改走票 12 的台账 + `inventoryBucketWarmup` 预热 job）。URL 与其余七个端点全部不变。
>
> 两条写入口口径互不交叉：**`POST /draw` 只扣抽奖次数、永不直接扣积分**；
> **`POST /purchase/draw-count`（历史名 `POST /exchange/draw-count`）用积分买次数**。

## GET /activity

活动信息（免登录、不分页）。

- 成功: `{ name, startAt, endAt, status, drawCountPrice, drawCountValidDays }`
- `status ∈ NOT_STARTED · ONGOING · ENDED`，由后端按 `draw_activity.state` / `begin_date_time` / `end_date_time` **现算**：
  `state = 'close'` 与「过了 `end_date_time`」都归 `ENDED`
- **无活动数据时 `data` 为 `null`**（旧模型缺行抛异常，新模型按「没活动」返回 null，前端好判）
- `drawCountPrice` = 买一次抽奖次数的积分价（`types.raffle.Constants.DRAW_COUNT_PURCHASE_PRICE = 100`；
  旧模型活动表的价格列已随旧表删除，新模型无定价配置表，暂以常量承载）
- `drawCountValidDays` 取自 `draw_count_config`（展示用；该表归**抽奖域**——抽奖次数与抽奖同域）
- 流程图：Controller → `IRaffleQueryService.getActivity` → `IRaffleRepository.queryCurrentActivity`
  （`draw_activity` 取 `id DESC LIMIT 1` 的当前活动行）+ 域内 `IDrawCountAccountService.getValidDays`

## GET /prizes

奖品目录（免登录、**不分页**）。

- 成功: `[{ prizeId, tier, name, type, pointsAmount, remaining, total, unlimited }]`
- `type ∈ VIRTUAL · PHYSICAL · THANKS`（**旧值 `POINTS` 归入 `VIRTUAL`**）
- **库存只暴露奖品库存账户口径**（award-inventory 两层模型，池 surplus 已被分配转走）：
  - `remaining` = 奖品库存账户 `surplus_qty`（`draw_strategy_award_inventory`，按 award_id 1:1）
  - `total` = 投放总量：虚拟 `surplus_qty + issued_qty`；实物 `surplus_qty + waiting_qty + issued_qty`
  - **不读 Redis**——库存域已无桶；账户台账是唯一真相，目录只读账户
- 不限量档（`draw_strategy_award.inv_id = 0`，或账户 `surplus_qty = -1` / 账户行缺失）给 `unlimited: true`、
  `remaining` 与 `total` 都为 `null`
- `pointsAmount` = 单次发放面额（虚拟积分池的 `virtual_inventory_quota.value`，经 award_id → inv_id → 池配置；
  POINTS/DRAW_COUNT 的区分留在池配置，开放点②），非积分档为 `null`
- 流程图：Controller → `IRaffleQueryService.listPrizes` → 三段查拼装（SQL 层零 JOIN）：
  ①奖品行（`draw_strategy_award`，按 `sort, id` 升序）②奖品库存账户按 award_id 集批量回查
  ③虚拟池按 inv_id 集批量回查（面额配置）

## GET /draw-chances

我的抽奖次数。

- Auth: 需要登录
- 成功: `{ available, todayDrawn }`
  - `available` = **未过期桶的 `SUM(remain)`**（`draw_count_bucket`，没有余额列；次数与抽奖同域，直接读域内仓储）
  - `todayDrawn` = 当日 `draw_record` 行数（含谢谢参与——「今天抽了几次」按抽的动作算）

## POST /draw

单抽（**幂等**）。

- Auth: 需要登录
- Body: `{ token }` —— 前端生成的 uuid，`@NotBlank` / `@Size(max=64)`
- 成功: `{ recordId, prizeType, prizeId, tier, name, pointsAmount }`
  - **谢谢参与也走成功**（`prizeType = THANKS`），前端据 `prizeType` 弹不同结果
  - `pointsAmount` 由**重放路径也现算**（读台账面额），不存快照列

### 流程图（时序与事务边界是关键）

1. **幂等预检**：`record_id = SHA-256(userId + ":" + token) 截 63 位正整数` —— 确定性映射，**无重放表**；
   先查 `draw_record` 命中即回放（不扣次数、不预扣）
2. **活动校验**：`state = open` + 窗口内，否则 `4301` / `4302`
3. **扣次数**（域内仓储 `deductDrawCountForDraw`：幂等预检 + FIFO 桶加锁 + 明细 `ref_id = record_id`，一个事务）：
   扣不出 → 先查记录（并发同 token 的败者在此回放）→ 真没次数才是 `4303 NO_CHANCE`。**此时零副作用**。
   **抽奖只扣次数**，任何情况下都不 `直接` 扣积分——积分换次数是上面那条独立入口
4. **责任链出奖**（票 11）：黑名单 → 权重保底 → 默认（全量 `normal` 档概率表）；
   预热缺失（`4311`）**降级为 DB 直算**（`normal` 档 `award_rate ×10000` 归一化后随机）
5. **库存预扣**（库存域的 port → 仓储单件入口 `IInventoryRepository#deduct`，内部走进程内合并：
   等 5ms 窗口后明细 + 台账一组一个事务，**返回即已提交、台账已动**；ADR-0013，无桶、无合并消息。
   award-inventory 两层模型：预扣锚在**奖品位 id**（奖品库存账户按 award_id 1:1），
   池只被「分配」转移，抽奖不碰池）：
   - `inv_id = 0`（不限量哨兵，奖位品的投放池配置）→ 跳过
   - `PHYSICAL` → `preDeductPhysicalWithFallback`（主档 = 奖品库存账户 PHYSICAL）：`FALLBACK` → 切兜底奖品位；
     `FAILED` → 兜底奖品位若 `inv_id = 0`（不限量）仍发，否则 `4313 RAFFLE_ISSUE_FAILED`
   - 虚拟 → `preDeductVirtual`（扣兜底奖品位自己的账户），`false` → 切兜底奖品位
     （兜底有 `inv_id` 则再预扣一次，再 `false` → `4313`）
6. **落库（一个 MySQL 事务，事务在仓储方法里）**：`draw_record` + `draw_record_detail(DRAW)` + **中奖事件 outbox 行**；
   撞 `uk_record_id`（`DuplicateKeyException`）判定为并发同 token 重放，回放那一份
   - `state`：`THANKS` 直接 **已完成**（不走兑换/发货）；其余从 **待兑换** 起步，推进归发货闭环（票 14）
   - 明细**只落 `DRAW` 一条**；`EXCHANGE` / `SHIP` / `COMPLETE` 由票 14 的状态机按推进落
   - outbox 行与业务写**同事务**（票 14）：业务回滚时事件行随之消失，不会「没中奖却通知中奖」；
     事务提交后由 `afterCommit` 异步发送一次，失败由 `outboxScan` 兜底重发（见「事件」段）

- **预扣成功而抽奖落库失败**的窗口由**孤儿预扣对账 job**（`orphanPrereserveScan`，cron `0/30`）在一个扫描周期内
  收敛：决策可重放（活动仍有效）→ 以原幂等键重放落库后半段（不重复扣次数、不重复预扣）；决策不可重放
  （活动已结束/下线）→ 库存回补 + 次数退还。同 token 重试仍会撞明细唯一键按成功回放（fund-consistency 票 08，
  口径见 inventory.md「孤儿预扣」）
- 「奖品抽完」不是失败态：配额不足走兜底奖品位，兜底也没有才是 `4313`
- **Redis 故障**（概率表不可用）→ 抛异常落 `999`，**不假装发放**（库存域已不用 Redis，不再有「库存桶不可用」这个故障面）

### 幂等口径

`(userId, token)` 经 SHA-256 映射成 `draw_record.record_id`（正 long，`uk_record_id` 唯一）。
**同一个 token 重放**（用户连点、网络重试、刷新页面重发）返回**同一份结果**，不重复抽、不重复扣次数、不重复预扣。
**换一个 token 就是一次新的抽奖**——token 由前端按「这一次点击」生成。

- 重放必**校验归属**：查到的记录 `user_id` 与请求者不符按系统错拒绝（防 hash 碰撞顶出他人记录）
- 并发双击靠扣次的明细唯一键串行化：败者要么在扣次后查到已落记录回放，要么落库时撞 `uk_record_id` 后回放

## GET /records

我的中奖记录。

- Auth: 需要登录
- 参数: `page` / `pageSize`
- 成功: `PageResult<{ recordId, prizeId, tier, name, prizeType, pointsAmount, createdAt }>`，`create_time DESC`
- **过滤 `award_type = THANKS`**（「中奖记录」里不该出现没中奖的行）
- `pointsAmount` 三段查拼装（跨 schema 才禁止 join）：记录行的 `inv_id` 快照（= 奖品位 id，开放点①）
  → `draw_strategy_award` 的投放池配置 → `virtual_inventory_quota`（type=POINTS）取面额

## GET /my-prizes

我的奖品（= 中奖记录中非谢谢参与的行）。

- Auth: 需要登录
- 参数: `status`（缺省 `PENDING`）/ `page` / `pageSize`
- 成功: `PageResult<{ id, prizeId, tier, name, prizeType, status, createdAt, claimedAt }>`，`create_time DESC`
- `status` 是四段状态的直接映射：`PENDING` 待兑换 / `EXCHANGED` 已兑换 / `SHIPPED` 已发货 / `COMPLETED` 已完成
  （域内库值 `待兑换`/`已兑换`/`已发货`/`已完成`，HTTP 面英文；默认 `PENDING`）
- `claimedAt` = 该记录的 `EXCHANGE` 动作明细时刻（`draw_record_detail.type = 'EXCHANGE'`），未兑换为 `null`
  （**实物档当前恒为 `null`**：它落 `SHIP` 不落 `EXCHANGE`，见下「状态怎么走」；`claimedAt` 语义偏「虚拟权益入账时刻」）
- **不看活动起止**：已中奖是既得权益，活动结束后照样可查
- `status` 取值不合法 → `400` `参数不合法`（具体值只进日志）
- 旧的 `source` 字段已删除（新模型奖品只来自抽奖，无「兑换得奖」来源）

### 状态怎么走（票 14 发货闭环）

| 奖品类型 | 待兑换 → … | 触发方 |
| --- | --- | --- |
| `VIRTUAL` / `DRAW_COUNT` | → **已完成**（同事务落 `EXCHANGE` + `COMPLETE`） | 收到 `bs-lottery-drawn`（发货消费者） |
| `PHYSICAL` | → **已发货**（落 `SHIP`）+ 实物配额确认（返回即 `waiting → issued` 已提交） | 收到 `bs-lottery-drawn` |
| `PHYSICAL` | 已发货 → **已完成**（落 `COMPLETE`） | `POST /my-prizes/{id}/receive` |
| `THANKS` | 落库即已完成，不进「我的奖品」也不发事件 | — |

**消费者侧的三步顺序（实物档）**：`planShipAfterDraw`（**只读**，算「该确认什么」）→
`confirmIssued`（库存域确认：进合并器，返回即 `waiting_qty → issued_qty` 已提交）→ `advanceShipAfterDraw`（推进状态 + 落明细）。
顺序保留是为了让「确认已落、推进未落」成为一个可重放的中间态（重复确认撞明细唯一键 = 成功回放），
但**它不再承重**：确认已经是一次本地写，没有「同步转移失败 → 推进先落 → 确认再也不会执行」
那条剧情。`waiting` 不足不是业务失败而是不变量破坏：整组回滚 + 抛可重试 ⇒ MQ 重投 ⇒ 死信。

> **`已兑换` 是预留段**：本版实物档从中奖直接到「已发货」（发货即占用，没有独立的「已兑但未发」中间态），
> 虚拟档一步到「已完成」，故 `EXCHANGED` tab 恒为空。四段口径保留是为了后续接「到店自提 / 人工发放」
> 这类真有中间态的场景，前端 tab 按四段渲染。

## POST /my-prizes/{id}/receive

确认收货（票 14 发货闭环）：把实物奖从 **已发货** 推到 **已完成**。

- Auth: 需要登录
- 路径参数: `id` = `draw_record.record_id`（即 `GET /my-prizes` 返回的 `id`）
- 成功: `{ id, status }`（`status` 恒 `COMPLETED`）
- **幂等**：已是 `COMPLETED` 的记录重复调用直接返回 `COMPLETED`，不再落明细
- 失败: `4314 PRIZE_NOT_RECEIVABLE` —— 记录不存在 / 不是本人的 / 状态不是 `已发货`
  （三者同码，不泄漏「这条记录存在但不是你的」）
- 事务：`UPDATE draw_record SET state = '已完成' WHERE record_id = ? AND state = '已发货'`
  （守卫 = 起点状态）+ `INSERT IGNORE draw_record_detail(COMPLETE)`，**同事务**；
  守卫落空（并发双击的败者）不报错，按幂等返回


## POST /purchase/draw-count

**用积分购买抽奖次数**（抽奖只消耗次数，从不直接扣积分；次数不够时走这条换）。

- 路径：`POST /api/lottery/purchase/draw-count`；**历史名 `POST /api/lottery/exchange/draw-count` 仍可用**
  （同一实现，前端在调旧路径，故保留别名），下文字段名也沿用历史的 `redeemOrderId`
- Auth: 需要登录
- Body: `{ token, quantity? }`
  - `token`：前端为**这一次购买意图**生成的 uuid，`@NotBlank` / `@Size(max=64)`；**同一意图重试必须带同一个**（与单抽同口径）
  - `quantity`：购买份数（1 次抽奖 = 1 份），`@Min(1)` / `@Max(10)`，**不传按 1 份**
- 成功: `{ redeemOrderId, pointsCost, drawCount }`
  - `pointsCost` = 份数 × 单价；`drawCount` = 买后的可用次数余额（未过期桶 `SUM(remain)`）
- 失败: `4306` `LOTTERY_INSUFFICIENT_POINTS`（积分不足）；份数越界走 `400`（Bean Validation）
- 定价：`Constants.DRAW_COUNT_PURCHASE_PRICE = 100`（随 `GET /activity` 的 `drawCountPrice` 返回）；
  单次份数上限 `DRAW_COUNT_PURCHASE_MAX_QUANTITY = 10`
- **领域归属**：抽奖次数是**抽奖域内的资产**（`domain.raffle.service.drawcount`），不再是独立领域；
  购买是抽奖域内的一个用例（编排落 case 层 `DrawCountPurchaseCase`），与抽奖主链路并列、入口与幂等键各自独立
- **幂等**：`redeemOrderId = SHA-256(userId + ":" + token)` 取 16 字节组 UUID 的 36 位规范形字符串
  （确定性映射，不落重放表）——同一 token 重放恒映射同一单号，购买单唯一键、积分流水唯一键
  `(userId, REDEEM_DRAW_COUNT, refId)` 与次数桶唯一键 `(userId, PURCHASE, refId)` 各自吸收重复，
  所以**只成功一次**：积分只扣一次、次数只加一次。换 token 才是新的一笔
- **事务边界（ADR-0016 票 C 三段式）**：**不跨域开事务**，三段各自仓储本地事务、case 层顺序编排：
  ① 落单段——购买单 `CREATED`（raffle 仓储，订单兼幂等键与对账源，`uk_purchase_order_id` 是并发同 token 的唯一互斥点）
  ② 扣分段——积分域扣分（points 仓储，流水先行幂等，唯一键 = 购买单号），**资金段先行**（不先入账，杜绝短款）
  ③ 推进段——次数入账（桶唯一键幂等）+ 条件更新置 `DONE`（守卫 `state <> 1`）。
  「单在 ⇒ 分已扣」降级为「单在 ⇒ 扣分结果未知、扫描收敛」：宕机残留（CREATED 无扣分流水 /
  有流水未 DONE）由 xxl `drawCountPurchaseScan` 补偿收敛（收敛动作 = 三段全量重放，分叉被幂等吸收），
  客户端带同一 token 重试同样顺势补齐。**积分不足**在扣分段判出：一分未扣、单即置死信
  （不占补偿重试轮次），充值后带原 token 重放自愈
- 旧模型「兑换单 + PENDING 残留 + `resume`」整套机制**已废除**；「一个 MySQL 事务罩两域」也已废除，
  改为上面的顺序调用 + 幂等重试（全仓统一的跨域口径）

### 失败码（逐条固定文案）

| code | info | 判据 |
| --- | --- | --- |
| `4301` `LOTTERY_ACTIVITY_NOT_STARTED` | 活动还没开始 | 现在 < `begin_date_time` |
| `4302` `LOTTERY_ACTIVITY_ENDED` | 活动已结束 | 现在 > `end_date_time`，**或** `state = 'close'`（无活动行同码） |
| `4303` `NO_CHANCE` | 抽奖次数不足 | 扣次 Lua 扣不出且无并发重放记录 |
| `4306` `LOTTERY_INSUFFICIENT_POINTS` | 积分不足 | 购买次数时积分账扣不动（文案与积分侧 `4101` 逐字一致） |
| `4311` `RAFFLE_WARMUP_MISSING` | 抽奖活动准备中，请稍后再试 | 概率表未预热**且** DB 直算也无 `normal` 档可算 |
| `4313` `RAFFLE_ISSUE_FAILED` | 奖品发放失败，请稍后再试 | 奖品位库存与兜底库存都扣不出 |
| `4314` `PRIZE_NOT_RECEIVABLE` | 该奖品不可确认收货 | 确认收货：记录不存在 / 非本人 / 状态不是已发货 |
| `400` `PARAM_ERROR` | 参数不合法 | 缺 `token` / `token` 超 64 位 / `quantity` 越界 / `status` 取值不合法 |
> `4310` 留空未占用（旧 `lottery` 段止于 `4309`，新段从 `4311` 起以便肉眼区分新旧模型）。
> `4304 DAILY_LIMIT_REACHED` / `4305 PRIZE_NOT_FOUND` / `4307` / `4308` / `4309` 五个码随旧模型作废，
> 常量保留但新链路不再产生（每日上限随旧域日限额表删除）。
> `4312 INVENTORY_WAITING_SHORTAGE` **已退休**（确认走进程内合并，`waiting` 不足是不变量破坏：
> 整组回滚 + 抛可重试，不再有同步业务失败态）；
> 库存段只剩 `400 INVENTORY_PARAM_ERROR`（配额不存在 / 这笔记录没有预扣结转行），且**不对用户可见**
> ——它在发货消费内部抛出，HTTP 面不返回库存段的码。

## Redis key（本域，前缀 `bs:raffle:`）

| key | 结构 | 谁写 |
| --- | --- | --- |
| `bs:raffle:table:{strategyId}:default` | RMap\<槽位, awardId\>，恒 10000 槽 | 预热（票 11） |
| `bs:raffle:table:{strategyId}:weight:{count}` | RMap\<槽位, awardId\>，恒 10000 槽 | 预热 |
| `bs:raffle:award:{strategyId}:{blacklist\|fallback}` | bucket（值 = awardId） | 预热 |
| `bs:raffle:warmup:{strategyId}` | bucket（值 = 预热时刻 ISO 文本） | 预热；**不存在 = 降级态** |
| `bs:raffle:blacklist:user:{strategyId}:{userId}` | bucket（存在即命中） | **风控侧**（本域无黑名单用户表，预热不管） |

库存域不再使用 Redis（桶与去重 key 随 hot-row-merge-l2w256 删除）；库存形态见 [inventory.md](../db/inventory.md)。

## 定时任务（不是 HTTP）

handler 共 **2 个**：`outboxScan` / `raffleCacheWarmup`
（桶过期清零的两个 handler 已下线：过期改纯读路径懒判，清零只写不读、且谓词走不了索引前缀；
hot-row-merge-l2w256 票 09：`inventoryMergeBackstop` / `inventoryBucketWarmup` 随桶与兜底整条退休，
代码里已无这两个 handler；uuid-biz-id 票 08 起存量库退役（删库重建），历史切换包已删除，重灌 seed 即无这些行）。

| handler | 频率 | 干什么 |
| --- | --- | --- |
| `outboxScan` | `0/30 * * * * ?` | outbox 兜底重发：只捞 `state=0` 且 `update_time` 超过 `outbox.scan.timeout-seconds`（默认 60s）的行；重试计数到 5 次置 `state=2`（死信） |
| `pointsExpireScan` | 已下线 | 积分过期桶清零落账（过期改为纯读路径懒判，该任务连同 handler 一并删除） |
| `drawCountBucketExpireScan` | 已下线 | 过期次数桶落账清理（同上） |
| `raffleCacheWarmup` | 每天 02:00（配置变更后可手动重跑） | 概率表 + 特型奖品位预热（票 11）；参数 = `activityId` |

> 票 13 删除了 `xxl-job-tasks.sql` 里的 `lotteryDeductMerge` / `lotteryDrawCountExpireScan` /
> `lotteryOutboxScan` 三行。**`xxl_job_info` 里的旧任务行要由 admin 手工清理**——
> 同执行器内 handler 名对不上时，调度会报「找不到执行器任务」而不影响新任务。
> hot-row-merge-l2w256 同理：`xxl-job-tasks.sql` 已不再登记 `inventoryMergeBackstop` /
> `inventoryBucketWarmup`（幂等重灌不会建出它们）；存量库旧行按切换包时序处置——
> 切换窗口 `trigger_status = 0` 停用，D9 收口 `DELETE`。

## 事件（异步，不是 HTTP）

| 方向 | topic | 载荷 | 说明 |
| --- | --- | --- | --- |
| 生产 | `bs-lottery-drawn` | `{ recordId, userId, prizeId, prizeType, pointsAmount }` | 中奖才发（谢谢参与不发）；与 `draw_record` 落库**同事务**写进 `outbox_message` |
| 消费（本服务 · 积分域） | `bs-lottery-drawn` | 同上 | 组 `bs-points-lottery-drawn-group`：`prizeType == VIRTUAL` 且 `pointsAmount` 非空时加分 |
| 消费（本服务 · 抽奖域） | `bs-lottery-drawn` | 同上 | 组 `bs-raffle-shipping-group`：**发货状态机**（虚拟 / 次数 → 已完成；实物 → 已发货 + 配额兑换确认） |
| 消费（单体） | `bs-lottery-drawn` | 同上 | 组 `bs-monolith-lottery-drawn-group`：落中奖通知 |

> 同一 topic 三方订阅**各用一组**（同组会抢消息）。wire 契约（字段名与含义）在
> `types.common.LotteryDrawnMessage`，生产方与三个消费方各只见自己那一半代码，故字段改动必须两端同改。
> 旧的 `bs-inventory-merge` 合并唤醒 topic 与 `InventoryMergeMessage` 已随 hot-row-merge-l2w256 删除
> （合并进进程，不再有消息）。

### 发货消费的重投口径

实物档的兑换确认**已经是一次本地写**（`confirmIssued` 进合并器，返回即 `waiting_qty → issued_qty`
已提交——明细 + 台账转移一个事务），所以三步顺序不承重：事件早到晚到都不影响确认落库，
`waiting_qty → issued_qty` 不再有第二步，**没有 `4312` 这条失败路径了**。

- **确认不抛 = 不需要为它重投**：消费方只有在别的失败（DB 故障、状态推进撞不变量）时才抛，
  broker 照常重投（首次 10s 后）。重投重放三步是安全的：确认撞明细唯一键 `uk_biz_type` = 成功回放、
  不重复落行也不重复转移；状态推进的守卫是起点状态，落空即幂等成功。
- **去重不靠库存侧去重表**：确认的幂等由明细的业务唯一键承担，抽奖域的状态起点只是第二道守卫。
- **旧的「人工核窗口」消失**：「确认已提交、状态推进未提交」之间崩溃时，重投会把确认当成功回放、
  把推进补上，**不需要人工核账**（旧模型要人工核配额台账与那张已删除的出库流水表）。
- **不变量破坏走死信**：`waiting` 不足时确认整组回滚并抛可重试（`error` 日志），重投仍不足就进死信
  ——这是**要人看的信号**，不是可自愈的中间态；没有 `pendingClaimQty` 这类读数了。


