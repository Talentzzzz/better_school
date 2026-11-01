# 08 · 对账读数与兜底：内存合并还需要 60s job 吗

Type: grilling
Status: resolved
Blocked by: 05

## Question

`inventoryMergeBackstop`（60s、xxl-job）存在的原因是「MQ 消息整条丢了」——outbox 进死信、Redis 扣了但事务失败、进程崩在两步之间。合并器搬进进程后，这几类丢失还剩哪些：

1. **还剩什么会丢**：进程崩溃 → 缓冲区里的请求随 JVM 一起消失，但**调用方也没拿到成功**（它等的是 future）。所以「丢了但用户以为成功」这种最坏情形是不是已经不存在了？如果不存在，兜底 job 的唯一职责（补合未合并明细）就随「明细不再需要合并」一起消失——**可以整条删掉吗**？
2. **那还剩什么需要定期跑**：`waiting_qty` 长期不转 `issued`（用户不兑换）、`surplus` 与明细对不上、`pendingClaimQty > 0`。这些是**对账**问题不是**补偿**问题。要不要保留一个只读的对账 job（只报警不写库）？
3. **对账读数的口径怎么重算**：现在 `pendingDeductQty` = 未合并明细之和，`pendingClaimQty` = waiting 未转 issued。去掉合并后前者恒 0，后者还在。这几个读数的命名与含义要不要改（`pendingDeductQty` 删掉，还是一个都不动以免前端/告警断掉）？
4. **xxl-job 的去留**：本工程还有别的 xxl 任务（`xxl-job-tasks.sql` 里有注册清单）。删掉这一个任务要动注册表（ADR-0011 记过「漏删旧注册会一直刷找不到执行器」），这条运维动作谁负责、怎么验证。

结论要明确：**删掉兜底 / 保留只读对账 / 两者都留**，以及 xxl 注册表的改法。

## Comments

- 票 05 已定：`state` 列删除 ⇒ `pendingDeductQty` 恒 0（这个读数失去意义）；`waiting_qty` 保留 ⇒ `pendingClaimQty` 仍有意义。
- **票 07 已定（直接给出本票第 3 问的一半答案）**：确认仍落 `type = 2` 明细（`uk_biz_type` 是幂等裁决者），但明细不再有 `state`，所以 `pendingClaimQty` **不能再从明细算**——它**就是 `waiting_qty`**（预扣进、确认出，台账三列已成唯一真相）。另外「`waiting` 不足」不再是可留给对账的静默差额，而是整组回滚 + 抛可重试进死信，所以读数只剩「长期挂在 `waiting` 上的量」这一种形态。
- 60s 兜底原本只治「消息整条丢了要补合」。合并器在进程内、调用方等结果、明细不再需要合并 ⇒ **补合这条职责整体消失**，剩下的只有「对账」而不是「补偿」。本票收窄为：读数怎么命名、要不要留只读对账 job、`xxl-job-tasks.sql` 里那行注册怎么删并验证。
- **2026-10-22 核查（三条会改答案的事实，票面两个前提不成立）**：
  1. **「以免前端/告警断掉」没有对象**：`IInventoryAuditService` 的生产调用方 = **0**。`marketing-trigger` 里没有任何 controller / job 引用它（全目录 `grep -i audit` 零命中），全仓只有 3 个集成测试类（`PhysicalQuotaIntegrationTest` / `VirtualInventoryIntegrationTest` / `InventoryFaultIntegrationTest`），`front/` 里也没有这个接口。它是一个「只被测试调用、测试之外无人读」的方法。
  2. **「只报警」没有通道**：工程没有 actuator / micrometer / prometheus（`pom.xml` 与 `application.yml` 都没有），没有 metrics 端点；xxl 的 `alarm_email` 在 seed 里是空串（`xxl-job-tasks.sql` 的每条 INSERT 都落 `''`）。一个只读 job 的产出只有日志行 + 任务成功状态，没有接收方。
  3. **`pendingClaimQty` 不是「等于 `waiting_qty`」，而是恒 0**：它的旧口径是 `SUM(quantity) WHERE state = 0 AND type = 2`（确认已写明细、尚未结转）。票 07 让确认与结转落在**同一个事务**（逐行 `INSERT IGNORE` 明细 + `UPDATE waiting -= c, issued += c`），提交后不存在「已确认未结转」的行 ⇒ 这个读数恒 0。票 07 说「改用 `waiting_qty`」，实际是**换口径**（「预扣已进、确认未出」的在途量），而不是「两个数恰好相等」。

## Answer

（2026-10-22 定）

**一句话：60s 兜底整条删掉（handler + 注册行 + 兜底扫描那套仓储与结果 VO），不建替代 job；对账读数从 7 个字段瘦成台账三列，恒等式降级为测试断言；`xxl-job-tasks.sql` 用一条 `DELETE ... IN (...)` 同时收掉 `inventoryMergeBackstop` 与 `inventoryBucketWarmup`，先重灌 seed 再发版。**

### 1. 兜底：ADR-0011 列的三种丢失，一种都不剩

| ADR-0011 列的丢失类别 | 新模型下的形态 |
| --- | --- |
| outbox 进死信 → 合并消息整条丢 | **消息不存在了**：`bs-inventory-merge` / `InventoryMergedConsumer` / `delay_ms = 2000` 全部删除（票 05）。没有「消息丢了」这个态，也就没有「等兜底捞回来」这件事 |
| Redis 扣了但事务失败 | **桶整层废除**（票 05）：扣减余额与写台账在同一个 DB 事务里，失败即一起回滚。不存在「一边扣了一边没扣」 |
| 进程崩在两步之间 | 缓冲在堆内、调用方等 future ⇒ 崩溃时调用方拿到的是超时/失败，**不是成功**；「已提交但返回前崩」的重复由 `uk_biz_type` 按成功回放兜住 |

所以票面问的「丢了但用户以为成功」在库存侧**已经不存在**：成功只可能在 `COMMIT` 之后被说出，而说出成功之后就没有第二步了。兜底的唯一职责（把 `state = 0` 的明细翻进台账）随 `state` 列一起消失，**可以整条删掉**。

补一条票面没列、但决定「删掉会不会失去保护」的关键事实：**兜底只会把「已经落在明细里的量」搬进台账，没有一条「凭业务事实上门核对」的路径**。`mergeKey` 的动作只有两个——预扣格 `waiting += s1`、确认格 `waiting -= s2, issued += s2`，加减的依据全是明细行，从不看 `draw_record`、也不看 `surplus` 是否合理。所以它不是「账不平时的修复器」，只是「结算流水线的补跑者」：流水线搬进进程之后，它连补跑对象都没有了；而真正的差额（下一条）它本来也碰不到。

### 2. 新形态下唯一还会「账上多扣、人不知道」的量：孤儿预扣

`RaffleDrawServiceImpl.draw` 不是一个大事务：`issueWithFallback`（预扣提交）→ `persistRecord`（落 `draw_record`）。中间崩溃 / 落库失败 = 库存账上 `waiting +1`，而 `draw_record` 没有这一行。方向是「**用户没成功，但库存已扣**」，与票面担心的那一类相反。

三条要点：

1. **不是本次引入的**，旧模型一模一样（`draw.md` 的「对账窗口」第 1 条就写着它）。
2. **旧兜底对它无能为力**，旧读数也抓不住：`pendingDeductQty > 0` 与「正常在途」不可区分——它只是「有未合明细」，不是「有孤儿」。
3. **它能自愈**：用户拿同一个 token 重试时，`DrawCountRepository.deductForDraw` 按 `recordId` 幂等跳过扣次，预扣撞 `uk_biz_type` 按成功回放，`persistRecord` 这次补上。所以只有「用户放弃」的那部分会永久挂账。

人工口径（写进 spec 的已知窗口，不建 job）：

```sql
-- 孤儿预扣：已计入台账，但没有任何抽奖记录（用户放弃重试后永久挂账）
-- 两段同一条：type = 1 的 biz_id 就是 draw_record.record_id
SELECT 'quota' AS segment, d.inv_id, d.biz_id AS record_id, d.quantity, d.create_time
  FROM physical_marketing_quota_detail d
  LEFT JOIN draw_record r ON r.record_id = d.biz_id
 WHERE d.type = 1 AND r.record_id IS NULL
UNION ALL
SELECT 'virtual', d.inv_id, d.biz_id, d.quantity, d.create_time
  FROM virtual_inventory_quota_detail d
  LEFT JOIN draw_record r ON r.record_id = d.biz_id
 WHERE r.record_id IS NULL;
```

### 3. 为什么不建只读对账 job

票面给的两个候选（保留只读对账 / 两者都留）都被否，理由按重要性排：

1. **报警通道是乘数，今天它是 0**：没有 metrics 端点、`alarm_email` 空、日志没有采集声明。一个「只报警不写库」的 job 落到这套设施里，产出是「每 60 秒一行 `log.error` 进一个没人看的文件 + 一个永远成功（或永远失败）的调度记录」。这比没有 job 更坏：它会让运维以为账有人看着。
2. **剩下的读数撑不起一个 job**：字段瘦到台账三列之后（§4），一次巡检能读到的就是「这一行现在是多少」。而票面问的「**长期**不转 `issued`」需要历史序列——job 的日志不是时序库，判断「长期」要么再接一套东西，要么把阈值写死在一个没有基线的常量上。
3. **唯一的非平凡检查是孤儿预扣，而它属于人工核账**：跨域 join、低频、且能自愈（§2）。为它养一个 60 秒的任务，收益低于一条 SQL。

**将来真要接监控时，第一个该上报的不是这些读数**，而是「死信条数」和「孤儿预扣条数」——前者已有的 MQ 面就有，后者是上面那条 SQL。把它们做成指标比复活一个对账 job 便宜，也不需要现在决定。

### 4. 读数：7 个字段 → 台账三列

| 字段 | 票 07 之后的取值 | 处置 |
| --- | --- | --- |
| `pendingDeductQty` | 恒 0（`state` 列已删，没有「未合并」这个态） | **删** |
| `pendingClaimQty` | 恒 0（确认与结转同事务，没有「已确认未结转」这个态） | **删**（不重命名——「挂在 `waiting` 上的量」由 `ledgerWaitingQty` 表达，两个字段同一个数只会让人猜哪个是真的） |
| `mergedDeductQty` | `SUM(quantity) WHERE type = 1`，正常路径与 `waiting + issued` 由同一事务保证 | **删读数**，等式进测试（见下） |
| `ledgerWaitingQty` | 预扣进、确认出（实物）；虚拟恒 0 | 留 |
| `ledgerIssuedQty` | 只增 | 留 |
| `ledgerConsistent` | 同上，正常路径恒真 | **删** |
| `redisConsistent` | 桶已删（票 05） | **删** |
| `surplusQty` | 余量，`queryLedger` 本来就有，只是没进读数 | **补**（人查账要看余量，三个数是一组） |

**为什么恒等式是「删读数」而不是「删断言」**：`waiting + issued == SUM(type = 1)` 在结构上被「一批一个事务」保证——但**只有正常路径**。票 05 §1 的「扣不动再探再切」有第二个事务：它若插入超过 `k` 行的明细却只扣 `k`，这条等式立刻不成立。这正是它值得留成**测试断言**（票 10 的正确性锁）而不是运行时读数的原因：它防的是自己人写错拆批，不是防线上漂移。

**`waiting_qty` 的语义要写清**：它是「已预扣、未确认」的在途量，**不是**告警阈值。它长期不降的可观测替代品是「`draw_record` 停在待发货」——那是业务表，比库存读数更贴近用户实际感受（用户看到的就是「处理中」）。

### 5. xxl 注册表：怎么删、谁负责、怎么验证

`xxl-job-tasks.sql` 的改法（沿用 `inventoryMerge` 那行的既有写法，只把范围从一个扩到两个）：

```sql
-- 票 08：库存合并兜底整条退休（合并器在进程内、调用方等 future、state 列已删，无可合之物）；
-- 票 05：库存桶预热随桶整层废除。先删注册再发版——否则调度会一直刷「找不到执行器任务」
DELETE FROM xxl_job_info
 WHERE executor_handler IN ('inventoryMergeBackstop', 'inventoryBucketWarmup')
   AND job_group = @group_id;
```

- 删掉这两个 handler 的 INSERT 段；`inventoryMerge` 那条 `DELETE` **保留**（幂等，防的是更早的库）。
- `marketing-executor` 的 handler 清单从 6 个降到 4 个（`outboxScan` / `pointsExpireScan` / `drawCountBucketExpireScan` / `raffleCacheWarmup`），`XxlJobConfig` 的 javadoc 同步。

**顺序：先重灌 seed（删注册），再发新 jar。** 反向顺序的代价是：行还在、handler 已消失，`0 * * * * ?` 每分钟派一次，admin 日志刷「找不到执行器任务」直到有人跑脚本。先删注册只让兜底在停机窗口里没有调度者——它本来就只做主链路不再需要的事，可以接受。

**验证三步**（写进票 09 的上线 checklist，由发布者执行）：

```sql
-- ① 期望 0 行（三个名字一起查，顺带确认旧的也没回来）
SELECT id, executor_handler, trigger_status FROM xxl_job_info
 WHERE executor_handler IN ('inventoryMerge', 'inventoryMergeBackstop', 'inventoryBucketWarmup');
-- ② 幂等：脚本连跑两遍，这一步的行数不变
SELECT COUNT(*) FROM xxl_job_info WHERE job_group = @group_id;
```

③ 重启后在 admin「执行器管理」看到 `marketing-executor` 在线且 handler 列表不含这两个名字，观察 ≥2 分钟调度日志无「找不到执行器任务」。

**责任人**：票 09 的「上线 8 步」里的一步（与 DDL 迁移同批、同一个人），不是「谁想起来谁跑」。理由见 ADR-0011 记的那条——漏删的后果是持续刷日志，而不是一次性报错，很容易被当成噪声忽略。

### 6. 落地清单（文件级）

- **删**：`trigger/inventory/job/InventoryMergeBackstopJob`；`IInventoryMergeService.backstopOnce/mergeKey`（连同整套时间格合并，票 05/06 换发动机）；`MergeBackstopResultVO` / `MergeKeyResultVO` / `InventoryMergeKeyVO` / `InventoryMergeKeyPO` / `InventoryCellAggregatePO`；`IPhysicalQuotaDao.selectMergeKeys / selectOldestUnmergedCellTime / aggregateUnmergedCell / flipUnmergedCell` 与虚拟段同构方法。
- **改**：`InventoryAuditVO`（7 → 4 字段：`invId / surplusQty / ledgerWaitingQty / ledgerIssuedQty`）；`InventoryAuditServiceImpl`（删两个 `sumPending*`、删 `checkRedisConsistent`、删 `ledgerConsistent`；剩下一次 `queryLedger` + 装配）；`IInventoryAuditService` 的 javadoc（两条恒等式 → 一条测试断言）；`InventoryRepository` 删 4 个 `sum*`；两个 DAO 删 7 个 `sum*`；`XxlJobConfig` 的 handler 清单。
- **测试**：`InventoryMergeEngineIntegrationTest` 整份退休；`InventoryFaultIntegrationTest` 的「合并停跑 → `pendingDeductQty` 累积 8 → 补跑归 0」两条退休；`PhysicalQuotaIntegrationTest` 的三条 `backstopOnce` / `pendingClaimQty` 用例改写成「确认一次即 `waiting` 减 `issued` 增」；新增一条断言把 §4 的恒等式锁死。
- **文档**：`marketing/docs/db/inventory.md` §「合并触发」「对账」两节整段重写；`marketing/docs/api/lottery.md`:217-225（job 清单与「找不到执行器任务」的说明）；`marketing/docs/architecture/marketing-services.md`:180/185（6 handler → 4）；`marketing/docs/architecture/timeout.md`:255-259；`marketing/docs/db/draw.md`:76-82（对账窗口第 1 条要改成「孤儿预扣，无自动读数，见 SQL」）。**ADR-0011 / 0012 不改**——它们是历史记录，由本次的新 ADR 取代。

### 7. 代价与交给下游

**代价（都是真的）**：

- 库存账不平在生产上**零自动信号**：只有死信队列、`draw_record` 停在待发货、和人工查台账这三条路。
- 「长期挂在 `waiting`」从「一个读数」退化成「一句人话」——没有时序就没有「长期」。
- 删掉的东西里有一套完整的故障用例，`waiting` 不足这类不变量今后只有单测/集成测试守着，不再有「线上反复撞」的行为。

**交给下游**：

- **票 09**：seed 重灌进上线 8 步，含 `inventoryBucketWarmup`；先删注册再发版；并且要写清「回滚只回滚 jar 时，这两个注册不会自己回来」——新表结构下旧代码根本跑不起来，这正是「改完回不去」的一部分，与 09 的立场一致。
- **spec**：把「孤儿预扣」写进已知窗口并附 §2 的 SQL；把 `waiting + issued == SUM(type = 1)` 定为集成测试不变量（票 10 的验收靠它，不靠运行时读数）。
- **09** 的阻塞项 `08` 解除。
