# 抽奖新模型 schema（draw_* 表集，已并入单体库 better_school）

> 业务：抽奖决策侧 + 主链路（营销域，票 11 落表、票 13 接主链路；monolith-restructure 票 07 起并入单体）；
> 归属域：`domain.raffle`（包根 `com.betterschool`，marketing 命名空间已退役）
> 结构与根 `sql.md` §2 逐表一致（唯一事实源；schema 已并入单体 `better-school/sql/schema.sql` 尾段，比对脚本随之失效退役）。
> DDL 在单体 `better-school/sql/schema.sql`（`schema-legacy.sql` 已随旧域表整体删除）。

|表名|说明|
|----|----|
|draw_activity|抽奖活动（活动 → 策略 1:1，uk_strategy_id；state create/open/close）|
|draw_strategy|抽奖策略；`rule` 列 = 责任链节点码逗号序列（如 rule_blacklist,rule_weight），ChainFactory 按它动态组链|
|draw_strategy_award|奖品位配置；概率口径 award_rate（DECIMAL(6,4)，×10000 即槽位）；type = normal（进概率池）/ fallback（库存兜底）/ blacklist（黑名单强制奖）；库存账户 1:1 见 draw_strategy_award_inventory（[inventory.md](inventory.md)）|
|draw_strategy_weight|权重档（票 11 自设计稿 strategy_weight 规范化：并入 draw_* 命名、去时间戳默认值、加 COMMENT；weights 随 uuid-biz-id 票 07 award_id UUID 化放宽到 varchar(512)）|
|draw_record|抽奖记录（中奖事实 + 奖品快照；写入方 = 票 13 主链路）|
|draw_record_detail|动作明细（只插入：DRAW/EXCHANGE/SHIP/COMPLETE，uk_record_type 幂等；票 13/14 用）|

## 决策侧口径（票 11 定，票 13 直接引用）

- **责任链**：黑名单（rule_blacklist，Redis 黑名单 bucket 存在即短路，强制出 blacklist 奖品位）→
  权重保底（rule_weight，累计抽数 ≥ 档位 count 时从该档奖品池出奖并短路）→ 默认兜底（rule_default，恒链尾）。
  链按 `draw_strategy.rule` 动态组装、按 strategyId 缓存，重预热时失效重组。
- **权重档命中**：累计抽数 = `draw_record` 中该 user_id + strategy_id 行数；取「count ≤ 累计抽数」中最大的一档。
- **预热**：概率表恒 10000 槽（award_rate ×10000；权重池内按 award_rate 归一化、最大余数法补齐）。
  Redis key 见 `types.raffle.Constants`（前缀 `bs:raffle:`）：`table:{strategyId}:default` /
  `table:{strategyId}:weight:{count}`（RMap 槽位 → awardId）、`award:{strategyId}:{blacklist|fallback}`、
  `warmup:{strategyId}`（预热态标记）；黑名单成员 `blacklist:user:{strategyId}:{userId}`（风控侧写入，预热不负责）。
- **降级态**：`warmup:{strategyId}` 不存在即未预热——默认节点抛 `RAFFLE_WARMUP_MISSING(4311)`，
  主链路捕获该码**降级走 DB 直算**（`normal` 档 `award_rate ×10000` 归一化后随机取槽）。

## 主链路口径（票 13 定，接口面见 [../api/lottery.md](../api/lottery.md)）

**写入方**：`RaffleDrawServiceImpl.draw` 是 `draw_record` / `draw_record_detail` 的唯一写入方。

- **顺序**：幂等预检 → 活动校验（`state = open` + 窗口）→ 扣次数（`draw_count_bucket` Lua，明细 `ref_id = record_id`）
  → 责任链出奖 → 库存预扣（库存域的 port，走进程内合并，返回即台账已动）→ 落库。
- **事务边界（三处，别混）**：
  1. **扣次数**在自己的域内事务里（Lua 原子 + 明细行唯一键），主链路不包它；
  2. **落库**一个 MySQL 事务：`draw_record` + `draw_record_detail(DRAW)` + **outbox 中奖事件**同事务
     （`insertDrawRecord`）；
  3. **库存预扣**在库存域的域内事务（明细 + 台账一组一个事务，返回即 `surplus` 已减），与落库
     **不在同一事务**——这正是「预扣成功而落库失败」窗口（孤儿预扣）的来源，见下。
- **幂等键**：`record_id = SHA-256(userId + ":" + token)` 取前 16 字节组 UUID（msb/lsb），输出 36 位规范形字符串（uuid-biz-id 票 07 起，原「截 63 位正整数」的 Long 形态退役）。
  **确定性映射、无重放表**——同一 `(userId, token)` 恒映射同一 `record_id`，`uk_record_id` 就是幂等互斥。
  - 重放快路径：先查 `draw_record`，命中即回放（不扣次数、不预扣）
  - 并发同 token 串行化：败者要么在扣次后查到已落记录回放，要么落库撞 `uk_record_id`
    （捕获 `DuplicateKeyException`）后回放
  - 回放**必校验归属**（`user_id` 不符按系统错拒绝）：防 hash 碰撞把他人记录顶出来
- **`state` 初值**：`award_type = THANKS` 直接落 **已完成**（不走兑换/发货）；
  其余落 **待兑换**，推进归票 14 状态机。明细**只插 `DRAW` 一条**，其余三个动作由状态机落。
- **`inv_id`**：award-inventory 两层模型（开放点①拍板）：记**奖品库存账户的 award_id**（奖品位 id，
  1 奖品位 1 账户，账户见 [inventory.md](inventory.md) `draw_strategy_award_inventory`），不再是池 inv_id。
  不限量档（配置哨兵 `draw_strategy_award.inv_id = '0'`，字符串）落库时置 `NULL`（「无库存来源」比哨兵更好判）；
  来源池不落快照列——从分配流水表（`inventory_allocation_flow`，award_id → pool_inv_id）反查。

## 发货状态机口径（票 14 定）

**写入方**：`RaffleShippingServiceImpl` 是 `draw_record.state` 推进的唯一入口。

| 起点 | 动作（`draw_record_detail.type`） | 终点 | 触发方 | 其他副作用 |
| --- | --- | --- | --- | --- |
| （落库） | `DRAW` | 待兑换 | 主链路 | THANKS 直接 已完成，无后续动作 |
| 待兑换 | `EXCHANGE` | 已兑换 | 收到 `bs-lottery-drawn`（虚拟 / 次数档） | — |
| 已兑换 | `COMPLETE` | 已完成 | 同上，**同一事务**连续两步 | 与积分域入账各自独立（该域按 `(userId, LOTTERY_WIN, recordId)` 幂等） |
| 待兑换 | `SHIP` | 已发货 | 收到 `bs-lottery-drawn`（实物档） | case 层**先**调实物奖品库存 `confirmIssued`（进合并器，返回即 `waiting → issued` 已提交），再推进 |
| 已发货 | `COMPLETE` | 已完成 | `POST /my-prizes/{id}/receive` | — |

- **每一步的幂等**：`UPDATE draw_record SET state = ? WHERE record_id = ? AND state = ?`（守卫 = 起点）
  + `INSERT IGNORE draw_record_detail`（`uk_record_type`）。守卫落空 = 重复投递，整笔回滚不推进。
- **兑换确认排在推进之前（顺序保留，但不再承重）**：确认已是一次**本地写**——仓储 `claim`（合并组件在
  `infrastructure/merge`），返回即 `waiting_qty → issued_qty` 已提交（明细 + 转移一个事务），
  不再抛 `4312`（`waiting` 不足是不变量破坏：整组回滚 + 抛可重试 ⇒ 死信）。
  所以它不会因为「事件先到」而失败，重投的剧情随之消失。
  状态机仍拆成**只读计划**（`planShipAfterDraw`）与**推进**（`advanceShipAfterDraw`）两步、跨域确认插在中间：
  顺序本身无害且让「确认已落、推进未落」成为一个可重放的中间态（确认撞明细唯一键 = 成功回放），
  但**它不再是正确性的前提**，别在此基础上再叠加新的顺序依赖。
- **`已兑换` 当前是预留段**：实物档从中奖直达「已发货」，虚拟档直达「已完成」；四段口径保留给后续
  「到店自提 / 人工发放」这类真有中间态的场景（前端 tab 按四段渲染，`EXCHANGED` 恒空）。


**对账窗口（两处，明说而不是假装没有）**：
1. **孤儿预扣（预扣成功后落库失败），无自动读数，见 SQL**：预扣提交、`draw_record` 没落 ⇒
   库存被白扣一份。对账读数瘦成台账三列直读后（hot-row-merge-l2w256），这条**没有自动暴露口**——
   能自愈的靠同 token 重试撞明细唯一键回放；用户放弃的部分永久挂账，只能跑 inventory.md 里那条
   「两张明细表 LEFT JOIN `draw_record` 找 `record_id IS NULL` 的 `type = 1` 行」人工 SQL（不建 job、不建巡检）。
   **draw-recon-round 起该口径被取代**：孤儿预扣收敛 job（fund-consistency 票）已承接逐笔收敛，其上的
   每日全量对账轮（`drawReconScan`）做总量兜底复查与平账分流（自动补种 / 告警人工），见 [recon.md](recon.md)。
2. **确认不分活动**（库存域的有意口径）：合并器只认分片键（奖品库存账户 awardId），确认与预扣都按
   `awardId` 路由落库，
   不查活动是否还在运行——好处是停用库存的遗留确认也能收敛；
   活动维度的限定由**本票的预扣入口**控制（活动不 open 就不产生新预扣）。

