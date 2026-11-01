# 票 12 · 库存两段式：虚拟权益池 + 实物营销配额

Type: task
Status: resolved
Blocked by: 11

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **200–250k**；硬顶 **300k** |
| 预估改文件 | ~53（8 张表五层 + Redis 分桶 / Lua + **一套**合并 job 骨架挂两个目标 + 兜底 + 测试 5~7） |
| 必读 | **`sql.md#L846-L898`（虚拟 3 张 + L898 算法）+ `sql.md#L900-L989`（实物 5 张 + L989 算法）**；rafflex 的 `LuaScriptManager`；本票 |
| 不读 | `sql.md` 其余段落；旧 `lottery_bucket_config`/`lottery_deduct_detail` 代码（票 13 才废）；rafflex 的 product/redeem 域 |
| 必跑 | `backend/marketing`：库存两类用例 + 合并 job 用例 + 兜底用例；旧 `Lottery*` 用例仍须绿 |
| 越界信号 | 想「顺手把抽奖主链路接起来」→ 停手，那是票 13 |

## 为什么这两段合成一张票（票面说明）

`sql.md` **L989 明说实物与虚拟是同一 pattern**，唯一差异是合并后进 `issued_qty` 还是先进 `waiting_qty`（用户兑换成功再转 issued）。同构批量改动的单文件成本远低于异构改动：拆成两票会让第二票重新读一遍第一票刚写的合并骨架（跨窗口知识丢失），反而更贵。这是 `docs/agents/issue-tracker.md` 里「~50 文件」阈值的**同构例外**。

## 已核实事实

1. **虚拟段 = L846–L898**，3 张表：`virtual_inventory`(846) / `virtual_inventory_bucket`(867) / `virtual_inventory_detail`(882)。L898 是权威算法：
   > 先扫出当前运行的 activity_id → strategy_id → inv_id → bucket_id，再扫本表 `state=0` 的行，按 `(inv_id, bucket_id, state, quantity)` 汇总出各桶待合并总量，用这个量去减 `virtual_inventory_bucket.redis_qty`，同时减 `virtual_inventory.surplus_qty`、加 `issued_qty`。
2. **实物段 = L900–L989**，5 张表：`physical_inventory`(900) / `physical_inventory_detail`(920) / `physical_marketing_quota`(937) / `physical_marketing_quota_bucket`(958) / `physical_marketing_quota_detail`(973)。L989 是权威算法：
   > ……再扫本表 `state=0` 的行汇总待合并总量，用它去减 `physical_marketing_quota_bucket.redis_qty`，同时减 `physical_marketing_quota.surplus_qty`、加 `waiting_qty`；**用户真正兑换后再减 `waiting_qty`、加 `issued_qty`**。
3. 两段的实质差异**只有这一处**：虚拟合并后直接 `+issued_qty`；实物先 `+waiting_qty`，兑换成功后再 `−waiting_qty +issued_qty`。这一处必须分开建模，其余全同构。
4. `virtual_inventory.type` = `POINTS` / `DRAW_COUNT`；`value` = 单次发放面额。
5. **不抄 rafflex 的纯 Redis 库存方案**（`map.md` 决策 3）：只借「Redis 分桶 + 预热 + Lua」的工程手法，**库存真相在 DB 台账**。
6. 现状旧表 `lottery_bucket_config` / `lottery_deduct_detail` 仍在 `schema-legacy.sql` 并被旧代码使用，**本票不动**（票 13 才废）。
7. 「库存不足降级兜底奖品」参考 rafflex 的 `domain/.../raffle/service/raffle/AbstractActivityRaffle.java` 兜底分支，但库存真相仍走本仓两段式台账。

## Question

一次把**两类两段式库存**做完（同一套骨架，两个目标）：

**虚拟权益池（积分 / 抽奖次数）**
- 扣减路径：校验桶余量 → Lua 原子递减 Redis 桶 → 落 `virtual_inventory_detail(state=0)`。**同一步不碰 DB 台账总量**。
- 合并路径（xxl 每秒，按 L898）：汇总 → 减 `redis_qty` / `surplus_qty`、加 `issued_qty`；明细置 `state=1`（幂等可重入）。
- 桶预热：启动 / 配置变更时按 (inv_id, bucket_id) 把 `redis_qty` 灌入 Redis 桶。

**实物营销配额**
- 复用上面同一套扣减 / 预热 / 合并骨架，只换目标表与 `waiting_qty` 分支。
- 兑换确认路径：提供 `waiting_qty → issued_qty` 的转换接口（供票 14 发货成功后调用）。
- 降级兜底：配额不足时落约定的兜底奖品，且**不写配额占用**（不产生悬挂 `waiting_qty`）。

**共同**
- 故障收敛：Redis 挂掉 / 合并任务停跑时，DB 台账可收敛、可对账，并给出可重复的对账检查口径。

## 施工边界（不做什么）

- 不接抽奖主链路（票 13 调本票暴露的库存 port）。
- 不删旧 `lottery_*` 库存表与旧代码（→ 票 13）。
- 不改票 11 的责任链语义（只被它调用）。
- 不做发货消费与 outbox 重发（→ 票 14）。

## 验收

1. `backend/marketing && mvn -o test` 全量绿。
2. **虚拟**用例 ≥3：预扣成功（Redis 桶 −N、明细一行 `state=0`、台账**未变**）；合并一轮后三处数值自洽；同批明细重复跑合并**不重复扣减**（幂等）。
3. **实物**用例 ≥3：预扣 → `waiting_qty` 增 / `surplus_qty` 减 / 明细 `state=0`；兑换确认 → `waiting_qty` 减 / `issued_qty` 增；配额不足 → 落兜底奖品且 `waiting_qty`/`issued_qty` **都不变**。
4. 故障用例 ≥1：Redis 不可用 / 合并任务停跑 → 台账可收敛、可对账（写明对账口径）。
5. `schema.sql` 收录本票 8 张表 + 比对脚本逐表一致。
6. `## Answer` 记录：两套合并 SQL 原文、幂等键、对账口径、Redis key 命名、兑换确认接口签名、**骨架复用边界**（哪部分共享、哪部分按段分叉）。

## Answer

**落地形状**：单域段 `domain.inventory`（两段：virtual / physical，目标表不同、流转语义同构）。
types（Constants/BucketUnavailableException/ResponseCode +2 码）→ domain（8 实体 + 5 valobj + 2 仓储接口 + 1 端口接口 + 5 service）→ infrastructure（8 PO + 2 DAO + 2 mapper XML + 2 仓储实现 + Redis 桶端口 + Lua 预加载器 + `lua/inventory_bucket_deduct.lua`）→ trigger（2 job）→ app（MapperScan 扫 inventory.dao + xxl seed ×2）→ docs（db/inventory.md + README 索引行）。schema.sql 本就含 8 张表（先前一次性收录），cmp_schema_marketing.py 逐表全等 ✔（22 表）。

**两套合并 SQL 原文**（差异只有一列，其余逐字同构；全文见 mapper XML）：
```sql
-- 虚拟（VirtualInventoryMapper.xml#settleInventory，L898 尾步）
UPDATE virtual_inventory
SET state = IF(surplus_qty >= 0 AND surplus_qty = #{quantity} AND state = 'open', 'exhausted', state),
    issued_qty = issued_qty + #{quantity},
    surplus_qty = IF(surplus_qty < 0, surplus_qty, surplus_qty - #{quantity}),
    update_time = NOW()
WHERE inv_id = #{invId};
-- 实物（PhysicalQuotaMapper.xml#settleQuota，L989 尾步）：同上，issued_qty → waiting_qty，表名换 physical_marketing_quota
```
两段共用：`SELECT … WHERE state = 0`（无 LIMIT）、`UPDATE bucket SET redis_qty = redis_qty − #{quantity} … AND redis_qty >= #{quantity}`（守卫）、`UPDATE detail SET state = 1 WHERE id IN (本组携带 id)`。
注意点：`state` 的 IF 必须放在 `surplus_qty` 赋值之前（MySQL SET 从左到右用新值，口径是旧值恰好扣空）。

**幂等键**：合并幂等 =「明细翻转只按本组携带的明细 id + 落账/翻转同事务」（绝不 `WHERE state=0`）——重跑同批明细不可能二次扣减；预扣幂等不靠本票（每笔预扣本身就是新明细，重复发放是调用方票 13 的幂等责任）；兑换确认幂等 = 票 14 的兑换单号去重（本方法按数量转移，见接口注释）。

**对账口径**（`IInventoryAuditService.auditVirtual / auditPhysical`，随时可重复执行）：
① 收敛性：pending（state=0 明细总量）合并停跑只增、补跑归 0；
② DB 恒等式（永远可查）：虚拟 `issued_qty == Σ(state=1)`；实物 `waiting_qty + issued_qty == Σ(state=1)`（台账全部由明细合并而来，前提：不手工直改台账）；
③ Redis 恒等式（可读时）：每桶 `Redis 余额 == redis_qty − 该桶未合并明细和`（预扣只动 Redis+明细、合并只动 redis_qty，恒等式把两段变化缝起来）；Redis 挂 → 给 null 不下结论。
预扣「Redis 已扣、明细没落上」的崩溃窗口由 ③ 暴露（Redis 余额小于应有值）；合并守卫不过的桶组跳过留在 state=0，由 ①② 暴露。

**Redis key 命名**（`types.inventory.Constants`）：`bs:inventory:virtual:bucket:<invId>:<bucketId>`、`bs:inventory:quota:bucket:<invId>:<bucketId>`；扣减走 Lua（查余量+递减一步原子，-1=未预热 / -2=余量不足），回补/预热/读余额走 RAtomicLong（同一 key，SET 语义预热）。预热 job `inventoryBucketWarmup` 在 xxl seed 里 **trigger_status=0**（调度不启用）：SET 会把在途预扣灌回去，只允许启动/配置变更手动跑。

**兑换确认接口签名**（票 14 调）：`IPhysicalQuotaService.confirmIssued(Long invId, Long redeemOrderId, long quantity)`——一事务内 waiting→issued（守卫 waiting ≥ qty，不足抛 `INVENTORY_WAITING_SHORTAGE(4312)`）+ physical_inventory 递减 surplus/加 issued + 落 OUT 明细（quantity 负、ref_id=兑换单）。

**骨架复用边界**：共享 = Redis 桶端口（含 Lua）、合并骨架（扫描 → 公共形状 `UnmergedDetailVO` 按 (inv_id, bucket_id) 分组 → 逐组事务：桶守卫递减 → 台账结算 → 按 id 翻转）、预热、对账口径；按段分叉 = 目标仓储与桶 key 前缀、结算列（issued vs waiting）、兑换确认（实物独有）、预扣主体是两段孪生（~25 行，分叉在仓储类型与 key 组装，抽象收益低于两份可读孪生，故未强行抽基类）。兜底 `preDeductWithFallback(invId, fallbackInvId, qty)` 返回 `DEDUCTED/FALLBACK/FAILED`：配额不足改扣兜底虚拟权益，配额段零写入（不产生悬挂 waiting_qty）。

**测试**：marketing 97 绿（旧 85 + 新 12：虚拟 5 / 实物 5 / 故障 2；`Inventory*` 用例覆盖预扣副作用、合并自洽、合并幂等、限量扣空 exhausted、兜底三态、waiting→issued、Redis 挂与合并停跑收敛）。旧 `Lottery*` 用例零回归；`mvn -o test` 全量绿。

**对票面的两处有意偏离（code-review 双轴复核确认）**：
1. 合并扫描不做「activity_id → strategy_id → inv_id → bucket_id」前置连接（L898/L989 首句）：明细行自带 inv_id + bucket_id，扫 state=0 直接可得桶组；不连接反而让停用（closed/exhausted）库存的遗留明细也能收敛。activity 维度的限定（只合「当前运行」活动的预扣）由票 13 在预扣入口控制。
2. 预热「启动时」只落了手动 job（`inventoryBucketWarmup`，seed 里 trigger_status=0）：SET 语义在桶上有在途预扣（明细未合并）时会把已扣量灌回去，代码化 startup 钩子无法判断这一点，故只允许启动/配置变更时人工触发。

**给票 13 的口径备注**：预扣按桶整笔扣（一桶容不下整笔即视为不足，不跨桶拆单）——票 13 配桶容量时应保证单次发放量 ≤ 单桶容量。

<!-- 收口时补：Context used: ~?k；是否触发拆分；连带影响清单 -->
