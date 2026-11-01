# 07 · 兑换确认去掉时间格合并后：waiting → issued 怎么落

Type: grilling
Status: resolved
Blocked by: 05

## Question

现在「兑换确认」是异步的：落一条 `type = 2` 明细 + 一条带 `cellTime` 的合并消息，转移由合并按时间格完成（ADR-0012）。时间格合并整体退休后，这条动作要有新落点：

1. **直接改台账还是继续攒批**：确认是低频动作（用户点「兑换」才发生），直接 `UPDATE ledger SET waiting_qty -= N, issued_qty += N WHERE … AND waiting_qty >= N` 一条就够，还是也塞进合并器（多一次排队换一次合并）？
2. **幂等靠什么**：现在靠 `uk_biz_type (type, biz_type, biz_id)` 撞键 + 「按成功回放」。不走明细后，幂等键放哪（`draw_record` 状态？一张确认表？还是保留 `type = 2` 明细只为幂等）？
3. **`waiting` 不足怎么办**：ADR-0012 的 `INVENTORY_WAITING_SHORTAGE(4312)` 已退休，现在只表现为对账读数 `pendingClaimQty > 0`。直接 UPDATE 之后，`waiting_qty < N` 变成一次同步失败——是继续「静默不回滚、留给对账」，还是恢复成一个明确的错误码/重试？
4. **两段状态机的差异**：虚拟段没有 waiting（`surplus → issued`），确认动作在虚拟段不存在。这条差异在代码里怎么表达（两段各一套 SQL，还是 `segment` 决定改哪两列）？

结论要能让 07 落地时只改 `InventoryRepository.confirmIssued` 一个方法。

## Comments

- 票 05 已定：桶废除、台账三列保留、预扣直接推进（实物 `surplus→waiting`、虚拟 `surplus→issued`）、明细删 `state/bucket_id` 但 `type` 列暂留。所以本票范围收窄为三问：**确认动作是一条直接 `UPDATE` 还是也进合并器**、**幂等键放哪（是否继续用 `type=2` 明细）**、**`waiting` 不足时的语义**。原问题里的「两段各一套 SQL」可简化为「`segment` 决定改哪两列」。
- **2026-10-22 核查（纠正提问的事实前提）**：提问说「确认是低频动作（用户点兑换才发生）」，代码里不成立——见 `## Answer` §0。三条事实（调用方只有 MQ 消费者、与实物预扣 1:1、预扣 happens-before 中奖事件）改变了 Q1 的论据与 Q3 的答案，但不改 Q1 的结论方向。

## Answer

（2026-10-22 定）

**一句话：兑换确认进合并器（与预扣同分片、同一条落库方法），幂等继续由 `type = 2` 明细的 `uk_biz_type` 裁决，`waiting` 不够不再静默——整组回滚 + 抛可重试异常，交 MQ 重投到死信让人看。确认只存在于实物段，所以方法不带 `segment`。**

### 0. 先纠一个前提：确认不是「用户点兑换」的低频动作

| 事实 | 出处 | 对票面假设的差 |
|---|---|---|
| `confirmIssued` 的唯一调用方是 `RaffleShippingCase.shipAfterDraw`，由 `bs-lottery-drawn` 消费者驱动 | `RaffleShippingCase:51`、`LotteryShippingConsumer:54` | 是**每笔实物中奖自动确认一次**，与实物预扣**同频** |
| 没有任何 HTTP 兑换入口（用户侧「确认收货」是抽奖域单域动作，不碰库存） | `LotteryController` 全量 | 确认动作没有面向前端的失败出口 |
| 预扣先于 `draw_record` 落库（`issueWithFallback` → `persistRecord`），中奖事件与记录同事务写出 | `RaffleDrawServiceImpl:145-229` | 确认到达时预扣**必然已提交** ⇒ 「`waiting` 不足」没有暂时性解释 |
| 实物段流量被 `surplus` 硬约束（一笔确认必对应一份已预扣实物） | `physical_marketing_quota.surplus_qty` | 热点量级是**活动库存总量**，不是请求速率 |

三条推论，后面 §1～§3 全靠它们：

1. 确认与预扣同频 ⇒ 它和预扣一样是「同一个 `invId` 一行台账上的并发写」，不是可以单开一条直写路径的旁支。
2. 但绝对量由库存总量封顶 ⇒ **性能不是选合并器的理由**；选它是为了形状与顺序。
3. 预扣 happens-before 中奖事件 ⇒ 守卫失败只能是账不平，不能是竞态（§3 的立足点）。

### 1. 落点：进合并器

确认件进同一个 `QuotaMergeBuffer`，分片键仍是 `invId`（与预扣同片），落库走同一个 `flushGroup` 的**另一组**（票 06 §2 的「动作类型 × shardKey 二次分组，一组一个事务」）。正常路径 = 一组一条 `UPDATE`：

```sql
UPDATE physical_marketing_quota
   SET waiting_qty = waiting_qty - #{claimed},
       issued_qty  = issued_qty  + #{claimed},
       update_time = NOW()
 WHERE inv_id = #{invId} AND waiting_qty >= #{claimed}
```

理由按重要性排：

1. **形状**：票 05 已把「一批 = `INSERT IGNORE` 明细 + 一条 `UPDATE` 台账」定成唯一落库形状。确认是同一形状的另一个 `type`，只是聚合列不同（`waiting -= c, issued += c` 而不是 `surplus -= d, waiting += d`）。直写等于给同一张台账再写第二条 SQL——正是票 06 §4「两套 SQL 迟早语义漂移」点名要防的面。
2. **顺序**：同分片单飞 ⇒ 「确认看到的一定是已落账的 `waiting`」成为结构事实，不需要论证它与 flush 抢同一行锁时的行为。
3. **成本可忽略**：确认多等 ≤ `windowMs` + 一次 flush（≤100ms 预算内），调用方是 MQ 消费者，无感。
4. **降级天然复用**：缓冲满 / `CLOSED` 时域服务线程直接调同一个 `confirmIssued(invId, List.of(item))`，组大小 1——票 06 §4 的「降级必须复用同一个落库方法」不用额外设计。

**这里不宣称「为了性能」**：实物流量太小，合并比在确认路径上大概率≈1，合并器在那里是净成本。收益只有「同一行台账的写入次数与预扣持平、且只有一条落库路径」。

### 2. 幂等键：`type = 2` 明细常留（票 05 的「暂留」结案为「留」）

`uk_biz_type (type, biz_type, biz_id)` 原样保留；`type = 2 / LOTTERY_CLAIM / recordId` 与预扣行 `type = 1 / LOTTERY_DRAW / recordId` 天然互不冲突。撞键 = 这笔已确认过 = **按成功回放**，与现在的语义一字不差。

三条否决：

- **靠 `draw_record.state` 守卫**：库存的幂等判据必须是库存自己的事实。借抽奖域的状态要仓储反向依赖另一个域，而且「确认已提交、状态未推进」这个窗口里读到的仍是 `PENDING`，两笔并发都会放行——守卫在起点条件上落空才叫幂等，读到别人还没写的事实不叫。
- **新建一张确认表**：只换来多一套 DDL + 清理 + 两处真相。明细表本来就要留着做审计与对账（`issued_qty` 从哪来，只有这张表能回答）。
- **明细行只当审计、幂等另找地方**：同一笔业务事实落两处判据，迟早对不上。一行明细同时是「幂等裁决者 + 出库审计」，这两件事本来就是一回事。

**副作用（交给票 08）**：删掉 `state` 之后 `pendingClaimQty` 不再能从明细算——但也不需要，它**就是** `waiting_qty`（预扣进、确认出）。票 08 的重算口径可以直接用这条。

### 3. `waiting` 不足：不静默，整组回滚 + 抛可重试

ADR-0012 的「整格回滚、不抛、留给对账」前提是「确认是异步的、没有调用方在等」。新模型里确认是**调用方等结果的写**，「静默」只能实现成「回滚但不抛」——而在 MQ 消费侧那不叫静默，叫**当成功**：`draw_record` 推进到已发货，`issued` 少记、`waiting` 永久挂着；而 60s 兜底最初也是唯一的职责（补合）已随合并器进进程消失（票 08 收口），没有谁会去修那份差额。

再加上 §0 那条时序事实：预扣 happens-before 中奖事件，确认不可能抢在预扣落账前 ⇒ 守卫失败**没有暂时性解释**，只能是不变量破坏。

落法（全部收在 §5 那个方法里）：

1. `UPDATE … WHERE waiting_qty >= c` 返回 0（且 `claimed > 0`）；
2. 整组回滚（连 ① 步插进去的确认明细一起回滚，不留半组）；
3. 打 `error`，带 `invId / claimed / 当前 waiting / 本组 bizId 清单`；
4. 抛可重试异常 ⇒ `LotteryShippingConsumer` 本来就不吞异常、broker 重投 ⇒ 重投上限进死信。

**不复活 `INVENTORY_WAITING_SHORTAGE(4312)`**：确认没有 HTTP 入口，那个码没有真正的接收方；给运维看的是 `error` 日志与死信，不是前端文案。

**不做部分确认**（对比预扣的「探一次再切」）：预扣切 k 是业务语义（库存不够就少发几份），确认没有这个语义——一笔确认是一个已完成的中奖事实，确认不了就是账错了，拆一半只是把错账藏一半。这也正是守卫失败必须抛出而非降级的原因。

**顺带收掉今天就会踩的一个边**：`draw_record.inv_id` 对不限量档落 `NULL`（`RaffleDrawServiceImpl:229`），而 `quotaToConfirm` 只看 `awardType = PHYSICAL`（`RaffleShippingServiceImpl:54`）⇒ 会拿 `null` 去确认、现在直接抛 `INVENTORY_PARAM_ERROR`。新方法先短路「无库存来源 = 无事可做（正常返回）」，不要和「账不平面」共用异常——前者是配置的合法形态，后者要人看。

### 4. 两段差异：确认没有虚线段，所以不需要 `segment`

- **虚拟段没有 `waiting` 列**（预扣直接 `surplus → issued`），也没有确认动作。所以 `confirmIssued` **不收 `segment` 参数**，只碰 `physical_marketing_quota` 一张表。
- 「`segment` 决定改哪两列」这条只在**预扣**的 `flushGroup` 里成立：`QUOTA → surplus -= d, waiting += d`；`VIRTUAL → surplus -= d, issued += d`。不是两套 SQL，是一段 `switch` 分派到各自 DAO 的一条 `UPDATE`（两张表本来就两套 mapper）。
- 于是「两段各一套 SQL」这个问题在新模型里自动消失：确认段只有一段。

### 5. 落地形状：只收敛在一个方法里

先对掉票面那句约束：语义决定全部落在 `InventoryRepository.confirmIssued` 里（幂等裁决、转移守卫、失败形态）；域服务那侧的改动是**变短**——删掉 `queryDeductBucketId`（桶已废）与 `catch (DuplicateKeyException)`（撞键在仓储内消化），不是新增分支。`RaffleShippingCase` / `IQuotaConfirmPort` 一行不动。

```java
// ── marketing-domain: InventoryQuotaServiceImpl（缩成「短路 + 一次提交 + 失败抛出」）
//    入口沿用票 06 的 submitAndWait（marketing 没有 submitAsync）；枚举名以 spec 为准，这里只标语义
@Override
public void confirmIssued(long invId, String bizType, long bizId, long quantity) {
    if (invId <= 0) {
        return;                                       // 不限量档：无库存来源 = 无事可做
    }
    ClaimItem item = new ClaimItem(bizType, bizId, quantity);
    SubmitOutcome outcome = quotaMergeBuffer.submitAndWait(invId, item);
    switch (outcome) {
        case ACCEPTED -> { }                          // 已提交（含「撞键回放」）
        case BUFFER_FULL, CLOSED ->                   // 背压：调用方线程同步降级，调同一个落库方法
                inventoryRepository.confirmIssued(invId, List.of(item));
        case FAILED, TIMEOUT ->                       // 落库失败 / 等超时（可能已落）：一律可重试
                throw new AppException(ResponseCode.INVENTORY_PARAM_ERROR, "兑换确认未完成 invId="
                        + invId + " bizType=" + bizType + " bizId=" + bizId);
        // 确认路径上不会出现 INSUFFICIENT：`waiting` 不够在这里是不变量破坏，由落库方法抛（§3）
    }
}
```

`TIMEOUT` 在确认路径上可以和 `FAILED` 合并成一个出口，因为**幂等键把它兜住了**：超时的件可能稍后落、也可能没落，重投要么撞 `uk` 回放、要么补上——不像预扣那样需要当场分出第三态（§6）。

```java
// ── marketing-infrastructure: InventoryRepository.confirmIssued
//    一组一个事务（组 = 同一 invId 的一批确认）。正常路径 = N 条 INSERT IGNORE + 1 条 UPDATE；
//    整组重放 = N 条 INSERT IGNORE + 0 条 UPDATE；守卫不过 = 整组回滚 + 抛。
@Transactional(rollbackFor = Exception.class)
public int confirmIssued(long invId, List<ClaimItem> group) {
    long claimed = 0;
    for (ClaimItem item : group) {
        // ① 幂等裁决：撞 uk_biz_type(type=2, biz_type, biz_id) 的行不进账
        if (physicalQuotaDao.insertClaimDetailIgnore(invId, item) == 1) {
            claimed += item.quantity();
        }
    }
    if (claimed == 0) {
        return 0;                                     // 整组都是重放：成功回放，台账不动
    }
    // ② 台账转移：守卫就是「waiting 够不够」
    if (physicalQuotaDao.transferWaitingToIssued(invId, claimed) == 0) {
        // ③ 不变量破坏：整组回滚（① 的明细一起回滚），抛出去让人看；不静默、不部分确认
        throw new AppException(ResponseCode.INVENTORY_PARAM_ERROR, "兑换确认守卫不过 invId="
                + invId + " claimed=" + claimed + " waiting=" + physicalQuotaDao.selectWaitingQty(invId));
    }
    return group.size();
}
```

两处刻意的「不照抄票 05」：

- **逐行 `INSERT IGNORE`，不批插**。批插的 `affected` 只给「本次新增了几行」，给不出「哪几行是新插入的」，而转移量必须精确等于新增量。票 05 用批插 + 部分撞键回查，是因为预扣批能到 200 行；**确认批天然小**（一笔确认 = 一份实物出库，物理奖品本就限量），逐行插把这个歧义整条删掉，不需要回查、不需要锁读、不踩 RR 快照与锁等待的坑。
- **不把预扣与确认合成一条三列 `UPDATE`**（`surplus -= d, waiting += d - c, issued += c`，数学上等价）。省 0.2ms，换来两个失败域混在一条语句里：预扣的 `surplus` 不足是**业务**（切兜底档），确认的 `waiting` 不足是**不变量破坏**（要人看）。票 06 已定一组一个事务，两条 `UPDATE` 天然落在两组里——这个分离不是成本，是目的。

### 6. 票 05 转过来的：预扣 `TIMEOUT_UNKNOWN` 在抽奖编排里怎么落

**不切兜底、不落库、抛可重试**：`preDeduct` 走到超时（`waitTimeoutMs = 100`，窗口 5ms、flush ≈8ms）意味着这一笔还在缓冲里、**大概率稍后会被扣**。当失败去切兜底档 = 一次抽奖扣两份库存、发两个奖。正确落法是让用户凭**同 token** 重试：`recordId` 由「用户 + token」确定性映射（`CONTEXT.md`「抽奖记录 id（幂等键）」），重放返回同一份结果，次数与预扣都不重复。

被否：把 `waitTimeoutMs` 抬到「绝不超时」（如 2s）——卡住的 flush 线程会把 HTTP 线程一起按 2 秒，且把引擎的「超时」这个真实出口在编排层掩盖掉。

**预扣与确认在这里不对称，理由要说清**：确认的「未知」可以统一按失败抛，因为它后面有 MQ 重投兜着，重投撞 `uk` 即回放；预扣不能，它是用户付钱的那一下，主链路必须当场决定「切不切兜底」，所以它需要一个独立的第三态。

### 7. 代价与交给下游的接口

代价（都是真的）：

- 确认的落库延迟从「一次同步 SQL」变成「≤5ms 等待 + 一次 flush」，失败语义从「当场抛 SQL 异常」变成「合并结果回传后再抛」；
- 同一行台账上「预扣组」与「确认组」两个事务，极端并发下多一次行锁乒乓（单次 ≤ 一次 `UPDATE` 的持锁时间）；
- 守卫失败会走满 MQ 重试再到死信，期间那条 `draw_record` 停在待兑换，用户看到的是「处理中」——这是想要的失败形态（比「账面不一致且无人知道」好），但要写进运维手册。

交给下游：

- **票 08**：`pendingClaimQty` 改用 `waiting_qty`；`pendingDeductQty` 随 `state` 一起失去意义；60s 兜底已无「补合」职责可担。
- **票 09**：切流时在途的 `state = 0` 的 `type = 2` 行必须先由旧逻辑合完（与 `type = 1` 同一条规则），否则切换后 `waiting` 会永久高挂。
- **落地 spec**：删 `InventoryRepository.insertClaimDetail` / `queryDeductBucketId` / `mergeCell` 的 ⑥ 分支 / `aggregateCell` 的 claim 分支；`physicalQuotaDao` 加 `insertClaimDetailIgnore` 与 `selectWaitingQty`；`RaffleShippingCase` 与 `IQuotaConfirmPort` **一行不动**。
