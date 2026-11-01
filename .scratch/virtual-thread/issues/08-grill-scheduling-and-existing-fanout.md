# 08 grilling：调度侧与已有扇出链路的对齐

Type: grilling
Status: resolved
Blocked by:

## Question

两件事一起拍，都属于「批处理扇出 vs DB 上限」：

**A. XXL-Job 串行逐行扫表循环**。单体 10 个 job handler + 三个服务各 2~4 个。其中商品订单 / 求购 / 跑腿 / 到期扫描都是 `for (id : listExpiredIds())` 的**逐行 DB 串行**（`TradePublishCase.java:201,204,225,228,234,272`、`ProductOrderCase.java:79`）。哪些并发化？

**B. 复核已有 5 处虚拟线程**（`TimeoutTaskScanCase.java:71`/`:102`、`TimeoutTaskRegisterCase.java:40`、`TimeoutRegisterOutboxSender.java:65`/`:90`/`:148`）：它们是否与图绘期定稿的选点规则一致？特别是**无界 fan-out vs Hikari 上限**——timeout-center 已立「池 ≥ 并行度」的口径，单体那几处（`TimeoutRegisterOutboxSender`）有没有同样的约束、有没有漏配池。

**C. [票 03](../research/03-jdk21-pinning-inventory.md) 新揭示的硬约束，一并核**：ShardingSphere 的 `HintManager` 是 **TTL**（`extends InheritableThreadLocal`）——hint 必须在**执行 SQL 的那个虚拟线程内** `getInstance()` + `close()`，**跨线程靠继承会炸**（`getInstance()` 二次调用直接抛）。要核：timeout-center「一槽一虚拟线程 + hint 同线程」的口径是否已被后续改动破坏；`TimeoutRegisterOutboxSender` 的逐行虚拟线程有没有碰 hint（它跨的库是不是 SS 路由的库）；票 A 里若要把某个扫表循环并发化，SS 路由的 hint 线程亲和是否成为额外约束（这会决定「串行扫表循环的虚拟线程化模板」长什么样）。

**要回答**：

1. A 里逐条给「并发化 / 保持不变」的裁决与理由——**语义不变是硬条件**（幂等位、条件 UPDATE、资金流水顺序不受影响）
2. B 里给「一致 / 需修正」的复核结论，需修正的写明改法与是否进本版
3. 归纳出「串行扫表循环的虚拟线程化模板」，供 spec 复用（timeout-center 已验证的形态：一槽一虚拟线程 + 循环拉批到空 + 就地对账）
4. 若某处并发化会碰到幂等 / 顺序假设，按 map 的 Out of scope 第 5 条判出 scope 并记录

结论落 spec 的「调度与批处理」节。

## Answer

**一句话：全仓 17 个 `@XxlJob` + 1 个 Dubbo 批处理入口逐条裁完——共 **8 处扇出到虚拟线程**（单体 7 个 job 入口：`tradeExpireScan` / `wantToBuyTimeoutScan` / `productTimeoutScan` / `errandTimeoutScan` / `postReviewTimeoutScan` / `dailyCreditJob` / `searchIndexRebuildJob`，＋中心的回调批处理 `onTimeoutBatch`），3 类"不采用"（已异步的 outbox 扫描 ×5 / 无循环的批量 SQL ×2 / 单事务不变量 ×1），既有 5 处 VT 复核全部一致、仅三处记账；hint 契约完好。模板定两个变体 + 四条硬规矩，宽度一律等于批大小、不加信号量。裁决理由照[票 06](06-grill-rocketmq-consumer-model.md) 先例记为「用户口径」，不包装成性能收益。**

> ⚠️ **本票翻掉了助手自己的初稿推荐**（2026-09-21）：初稿对单体扫表循环推荐「维持串行」（理由是"单 tick 几百 ms / cron 60s / 不占用户路径 ⇒ 抬高了没用"），被用户否掉——原话「要改并行，上虚拟线程」。**被否掉的那条论据原样留档在「裁决依据」节，不许再被引用**，与[票 07](07-grill-dubbo-thread-model.md) 同款处理。

### 票面 ① A：逐条裁决（17 个 job handler + 1 个批处理入口，全覆盖）

单体 10 个（与票面数一致）；另三个服务实测为 `marketing-activity` **4** / `marketing-asset` **2** / `timeout-center` **1**（票面写"各 2~4 个"，中心实为 1——它全中心只有一个 job 入口，spec §5.1 有意如此）。

| # | 入口 | 循环形状（含锚点） | 裁决 | 理由（"语义不变"这一硬条件的逐条结论） |
| --- | --- | --- | --- | --- |
| 1 | `tradeExpireScan`〔单体〕 | `TradePublishCase.scanExpiredListings:201,204`（商品 1 条条件 UPDATE；求购 `findById` + 解冻事务 + 条件 UPDATE）＋ `remindTradeReviewTimeouts:225,228` | **扇出** | 行间互不相干；商品侧幂等在条件 UPDATE、求购侧解冻走流水 `uk_order_type`；提醒每条独立事务 + `dedup_key` 幂等 |
| 2 | `wantToBuyTimeoutScan`〔单体〕 | `TradePublishCase.scanWantPayTimeouts:272` → `handlePayTimeout` | **扇出** | 单条条件 UPDATE（非 `PENDING_PAYMENT` 直接忽略），无跨行顺序 |
| 3 | `productTimeoutScan`〔单体〕 | `ProductOrderCase.scanPayTimeouts:79` → 1 个事务（条件 UPDATE + 释放商品 + 写搜索同步 outbox） | **扇出** | 幂等闸门在条件 UPDATE；搜索同步只写 outbox 行，落库顺序无要求 |
| 4 | `errandTimeoutScan`〔单体〕 | `ErrandOrderCase.scanTimeouts:206`（待支付）＋ `:209`（超时：条件 UPDATE + 解冻 + 信用分 + 逐在团者通知 + 封盘）＋ `:239` 提醒（**嵌套逐收件人循环**） | **扇出** | 各项按订单隔离；资金/信用余额是原子 SQL + 流水 uk；通知各自独立事务 + `dedup_key` 幂等 |
| 5 | `postReviewTimeoutScan`〔单体〕 | `PostPublishCase.remindPostReviewTimeouts:97`（**嵌套逐收件人循环**） | **扇出** | 每条提醒独立事务、`dedup_key` 含审核轮次；只提醒不改状态 |
| 6 | `dailyCreditJob`〔单体〕 | **无界**：`CreditAccountServiceImpl:137`（昨日全部活跃用户 × ≥3 语句）＋ `:147`（全部低分用户） | **扇出 + 查询改「批 + 游标」** | `applyChange` 自带事务 + `existsReasonToday`/流水幂等；`banIfLowScore` 是单语句条件 UPDATE |
| 7 | `searchIndexRebuildJob`〔单体〕 | `SearchServiceImpl:169,182,195,208` 4 个实体 × 每 500 一 chunk（手动触发）；4 条 `selectAllIds` **无 LIMIT** | **扇出 + 4 个 `listAllIds`/`listAllUserIds` 调用点改游标分页** | 只读 MySQL 快照 + 只写 ES；`clearIndex` 在 chunk 循环**之前** ⇒ chunk 间无顺序依赖、无共享状态 |
| 8 | `timeoutRegisterOutboxScan`〔单体〕 | 已是 VT 扇出（≤200 行，逐行同步 Dubbo） | 保持一致 | —（见 B 复核） |
| 9 | `outboxScan`〔单体〕 | 扫 ≤100 行 → `rocketMQTemplate.asyncSend` + 专职标记线程 | 不采用 | **已异步，无阻塞循环可改** |
| 10 | `delayOutboxScan`〔单体〕 | 同上 | 不采用 | 同上 |
| 11 | `lotteryOutboxScan`〔activity〕 | 同上 | 不采用 | 同上 |
| 12 | `referralOutboxScan`〔activity〕 | 同上 | 不采用 | 同上 |
| 13 | `lotteryDeductMerge`〔activity〕 | `DeductMergeServiceImpl.merge` 整批明细**一个事务**折算 + 只翻本批幂等键带回的 id | 不采用 | **并发化会拆掉"绝不按 `status=0` 翻状态"这条不变量**（拆掉就少扣 ⇒ 超卖） |
| 14 | `lotteryDrawCountExpireScan`〔activity〕 | 单条批量 SQL，无循环 | 不适用 | 无可扇出对象 |
| 15 | `pointsExpireScan`〔asset〕 | 单条批量 SQL，无循环 | 不适用 | 同上 |
| 16 | `outboxScan`〔asset〕 | 同 #9 | 不采用 | 同 #9 |
| 17 | `timeoutTaskScanJob`〔center〕 | 槽位 VT 扇出 + 循环拉批到空（**已验证的参考实现**） | 保持一致 | —（见 B 复核） |
| 18 | `TimeoutTaskCallbackDubboService.onTimeoutBatch:43`〔单体，Dubbo provider〕 | 批 ≤200 行**逐项串行**关单 | **扇出** | 逐项已隔离（双清单本为部分失败设计）；各项是独立 `(taskType, bizId)` 状态机；批内无顺序语义（见下方单列） |

**#18 单列，因为它的判据与循环类不同**（转自[票 07](07-grill-dubbo-thread-model.md) 的唯一实例）：回调单次调用有 **2s 硬上限**（`TimeoutTaskCallbackReferences` 的 `timeout-ms: 2000` + `setRetries(0)`，全仓无覆盖），超时即整组按失败重投；而重跑腿一项就是 5~10 条语句（`findById` + 条件 UPDATE + wallet 流水/余额 + 信用流水/余额 + 逐在团者通知各自独立事务 + 系统群封盘）⇒ 200 项串行不可能在 2s 内跑完。**即"健康系统下也走不完一批"的进度缺陷**，不是纯性能问题。注意它的判据与选点规则的字面读法**不同**：宿主 provider 线程[票 07](07-grill-dubbo-thread-model.md) 后已是 VT（占满整批不再贵），这里的上限**由 2s 截止时刻决定**——扇出的目的是把批时长从 `Σ` 变成 `Σ/W`。

**范围界定：`onTimeoutBatch` 只动"批内并行"这一点。**中心侧的扫描粒度（`scan.batch-size`）与 2s 超时值**都不动**——缩 batch / 加 timeout 会把中心对所有 group 的扫描吞吐一起降级，而批内轻重混杂（商品关单 1 条 UPDATE vs 跑腿 5~10 条），调一个数治不了两头。**顺序语义按现状为基线判**：现状是"按批内顺序"处理，扇出后批内各项顺序不再确定——不构成语义（各项是独立的 `(taskType, bizId)` 状态机，双清单本就为部分失败设计，批内无顺序要求）⇒ 可扇出。实现时 `successList`/`failList` 仍回主线程汇总，**不换并发容器**，保住"每项恰好落一个清单"。

### 裁决依据（含被否掉的论据）

采纳扇出的**理由只有一条：用户口径**（原话「要改并行，上虚拟线程」），与[票 06](06-grill-rocketmq-consumer-model.md)（消费池整体 VT 化）同性质——**按选点规则本应判"不换"，这条必须原样入 spec，不许包装成性能优化**。这批循环的实测特征恰恰是"没有可观察收益"：单 tick 上界 100 行 × 每行 1~10 条语句 ≈ 几百 ms，cron 间隔 60s，不占任何用户路径；扇出后真上限立刻变成单体的池（默认 10）⇒ 封顶约 10×，且只在积压 >100/分钟（14.4 万/天）时才有意义。

**⚠️ 被否掉的论据**（初稿的推荐依据，用户 2026-09-21 否掉）：

1. ~~「单 tick 几百 ms、60s cadence、不占用户路径 ⇒ 抬高了没用」~~ **作废**。同[票 07](07-grill-dubbo-thread-model.md) 被否的论据 1——**判据本就不该是"我们测到多少"**。更根本的是：这里连"量级"都没实测过（同上，是纸面推理），而"有用性"本身不是本 effort 的否决理由（展示面取向）。
2. ~~「语义侧虽安全但风险不值得为零收益承担」~~ **作废**。"零收益"这个前提来自论据 1，前提没了结论自然没。语义侧的安全性我核过且**是硬事实**（见下方"没有一处判出 scope"），它支持扇出而非反对。

**这一条要原样写进 spec**：本节的裁决**不是**从选点规则推导出来的——按规则字面读，"串行循环的上限由循环自己决定"确实可以答"该扇出"，但"抬高了有没有用"在规则里没有判据位置。**别把用户的展示面取向包装成规则结论**。

### 票面 ② B：复核既有虚拟线程扇出（票面点的 5 处）—— 全部「一致」，无必修项，三处记账

| 站点 | 结论 |
| --- | --- |
| `TimeoutTaskScanCase.java:71`（槽位扇出，≤16 槽位，池 24） | **一致**（池 ≥ 并行度，spec §5.3 口径成立） |
| `TimeoutTaskScanCase.java:102`（批内按 group 组间并行） | **一致**（同一 group 才合成一次调用，组数受批长约束；落库回到槽位线程） |
| `TimeoutTaskRegisterCase.java:40`（一 VT 一行注册落库） | **一致，但宽度是潜变量**：今天唯一调用方 `TimeoutTaskRegisterPort.register` 只发 1 item × 1 taskType ⇒ **实际宽度 = 1**；而 `TimeoutTaskRegisterDubboService.expand()` 对 `items.size() × taskTypes.size()` **没有上限** ⇒ 记账（潜伏项） |
| `TimeoutRegisterOutboxSender.java:65`（`sendAsync` 单 VT） | **一致** |
| `TimeoutRegisterOutboxSender.java:90`（`cancelAsync` 单 VT + 3 次重试 + `Thread.sleep`） | **一致**（`Thread.sleep` 在 VT 上卸载载体，无妨） |
| `TimeoutRegisterOutboxSender.java:148`（`sendAndSettle`，≤200 行扇出） | **一致**；每行是**纯 Dubbo 调用**（本地 DB 读写在扇出外的调用线程上：`selectPendingList` / `markSent` / `incrementRetry` / `countPending`）⇒ **单体自己的池不受扇出影响**；但**中心侧会有 ≤200 并发注册请求打池 24**——属"连接池是唯一真上限 + `connection-timeout`"口径内（票 05 已定），记账即可 |

**三处记账（写进 spec，不设改造项）**：

1. **`expand()` 的注册批宽度无界**（潜伏项）——**改法**＝在 `expand()` 出口按一个与 `scan.batch-size` 同口径的常量（建议直接复用 `timeout-center.scan.batch-size`，默认 200）截断并抛参数异常，使宽度恒 ≤ 池；**本版只记账不修**（今天宽度恒 1、不可达），配到期中心池 24 的适用条件一起写。
2. ⚠️ **票 05 的 `connection-timeout: 3000` 在单体 / `marketing-activity` / `marketing-asset` 尚未落地**：全仓只有 timeout-center 显式配了 Hikari（`application.yml` 的 `maximum-pool-size: 24` + `connection-timeout: 3000`），另三个工程**没有 Hikari 配置块** ⇒ 今天跑的是 Boot 默认（池 10、`connection-timeout` **30s**）。spec 里必须记为**待落地配置**，不许当既成事实——本票所有"扇出宽度 vs 池"的论证都建立在这个口径上。
3. **池仍是 10**（无显式配置只是记录，不改；票 05 已定"池大小不为了 VT 而调"）。

**不加闸的复核结论**：五处都**不加信号量**。判据是"上限由谁决定"——这些边的上限都在下游池，加闸就是票 05 已经否掉的"重复限流器"。与本节 Q1/Q2/Q3 的口径统一为**"宽度 = 批大小，不加闸"**。

### 票面 ③ C：hint 约束 —— 契约完好，两处事实修正

**核查结论**：

- **timeout-center「一槽一虚拟线程 + hint 同线程」的口径没有被后续改动破坏**。`TimeoutTaskScanCase` 的最后一次改动就是它的落地票本身（`53ebac9`）；其后的票 09（`5e72b3d`）只加了 `deadCount` 三个计数（已逐行 diff 核对）。结构上：`TimeoutTaskRepository.withSlotHint` 是**每次 DAO 调用**一开一关的 try-with-resources（`:132-137`，`close()` 会摘掉本线程的 hint），`fetchDueBatch` 与 `settle` 都在槽位 VT 上跑；扇出的子 VT（`callGroup`）只发 Dubbo、**不碰 DB**。
- **`TimeoutRegisterOutboxSender` 的逐行虚拟线程不碰 hint**：全仓 `HintManager` 的命中点**全部在 `timeout-center-infrastructure`**（单体的 outbox 发送器跨的是"单体自己的库 + Dubbo 到中心"两条边，前者无分片、hint 在中心进程里）。同理票面 A 里那 8 处扇出**全部不碰 hint**（7 处在单体、1 处是单体的 Dubbo provider；**营销两服零扇出、零 hint**），无额外约束。
- **将来把 timeout-center 的 `settle` 再扇出也不会碰 hint**：仓储层"每次 DAO 调用自设 hint"使它对调用方**透明**——这是现成的保障，真正的约束是池（并发 DB 需求 ≤ 池），不是 hint。

**两处事实修正**（⚠️ 票 03 的说法要改）：

1. **`HintManager` 5.5.3 的持有者是 `com.alibaba.ttl.TransmittableThreadLocal`，不是 `InheritableThreadLocal`**（`javap` 实证；TTL 继承自 `InheritableThreadLocal`）。票 03 与票 05 表格里"`extends InheritableThreadLocal`"的写法不准确。已回填[票 03](../research/03-jdk21-pinning-inventory.md)。
2. **虚拟线程确实会继承 inheritable 值**（JDK 21.0.6 本机实测：`Thread.ofVirtual()` 子线程可见父值，嵌套亦然；平台线程行为相同）⇒ 真正的雷不是"不会继承"，而是**"扇出边界落在打开的 hint 作用域内"**：子 VT 继承到那个已打开的 `HintManager` 实例后再调 `getInstance()`，会命中 `Preconditions.checkState(null == holder.get(), "Hint has previous value, please clear first.")` **直接抛**。今天没踩，正是因为"每次 DAO 调用包一次"+ 子 VT 不碰 DB。`close()` → `clear()` → `holder.remove()`（javap 实证：关闭后本线程的值确实被摘掉，之后可再次 `getInstance()`）。

**模板硬规矩（C 的产出）**：**hint 不得跨线程传递**（不得把 `HintManager` 实例交给另一个线程）；**扇出边界不得落在打开的 hint 作用域内**（要扇出就先把当前 hint 关掉，或让子线程自己开一个）。

### 产出物：串行扫表循环的虚拟线程化模板（票面 ④ 第 3 问）

两个变体，共同的形状是"**原线程拿批 → 批内扇出 → 回主线程汇总**"。

**变体 A — 单发一批**（任务量有界，或被调度频率 + 幂等收敛兜住）

```java
// @XxlJob 方法 / Dubbo provider 方法：原线程
List<Long> ids = xxxDao.selectBatch(before, BATCH);          // LIMIT = BATCH（批大小即并发宽度）
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    List<Future<R>> futures = ids.stream().map(id -> executor.submit(() -> one(id))).toList();
    for (int i = 0; i < ids.size(); i++) {                   // 回主线程按项汇总（成功/失败清单、计数）
        ...
    }
}                                                            // close() = shutdown + 等全部结束
```
适用：`onTimeoutBatch`（#18）、单体 5 个扫表/提醒 handler（#1–#5，共 7 个循环）。**这几处维持现有 `SCAN_BATCH_SIZE = 100` 与"单发一批"形状不变，只把循环体扇出**——它们的 tick 频率与幂等收敛已经兜住积压。

**变体 B — 循环拉批到空**（任务量无界 ⇒ 批 + 游标是唯一终止条件）

```java
Long lastId = 0L;
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    while (true) {
        List<Long> ids = xxxDao.selectBatchAfter(lastId, BATCH);   // WHERE id > #{lastId} ORDER BY id LIMIT N
        if (ids.isEmpty()) break;
        ... 批内扇出 + 回主线程汇总（同变体 A）...
        lastId = ids.get(ids.size() - 1);                          // 推进游标
    }
}
```
适用：`dailyCreditJob`（#6）、`searchIndexRebuildJob`（#7）——这两处必须**先给查询补 `LIMIT` + 游标**再扇出，否则扇出宽度无界（有界性由批大小提供，不是信号量）。

| 查询 | 推进性 | 处置 |
| --- | --- | --- |
| `selectUsersActiveOn`（昨日活跃用户） | ⚠️ **不会自然推进**：处理完的用户仍满足条件（`existsReasonToday` 是另表守卫）⇒ 重复 `LIMIT N` 每次拿同一批前 N 行，**尾巴永远处理不到** | 必须加 `AND user_id > #{lastId} ORDER BY user_id` |
| `selectLowScoreNormalUsers`（低分用户） | ✅ 能自推进（`banIfLowScore` 成功后该行不再满足 `status='NORMAL'`） | 为统一仍走同一游标形态 |
| `selectAllIds` ×4（搜索重建：`SearchServiceImpl:169,182,195,208`） | ⚠️ **最危险**：`clearIndex` 先清空索引，若只重建前 N 行，索引**永久不完整且不报错** | 4 个调用点全改游标分页（不留例外）<br>⚠️ `selectAllIds` 还有**第 5 个调用点** `PostDetailCache.initBloom():59`——它**刻意不设限**：布隆过滤器首次创建必须灌全量 id，且是一次性 `@PostConstruct` 预热、**不是循环**。spec 要显式记为「全量 id 列表唯一不设限处」，否则将来会被照本行的规矩"修"坏 |

**四条硬规矩**：

1. **并发宽度 = 批大小**（`LIMIT` / `batch-size`），**不加信号量**——上限由池兜（票 05：「池已经是真上限；信号量是重复的限流器」；票 06「不加信号量、改用 `pullThresholdForQueue` 表达」；票 07「上限口径 = 连接池 + `connection-timeout`」）。三者同一条口径。
2. **执行器即用即关**：`try (newVirtualThreadPerTaskExecutor())`，粒度 = **一个工作单元**（一次 tick / 一次批调用），不是每个任务一个。理由：VT 执行器**不是池**（不预建线程、不占栈，创建 ≈ 建一个对象 ⇒ 与平台线程池的成本结构完全不同）；作用域与工作单元对齐让"这一轮收口了"由语言结构保证。**常驻的代价不是性能，是"全进程共享的无界池"**——它没有"这一轮有多少活"的概念，任何一处忘了设界就把压力串到别人身上，而即用即关让每个调用点的并发天然被这次的数据量限制住。（反过来：若将来落地**有界** VT 池（票 07 可回收触发条件 ① 的 `threads.virtual.core`），"闸必须共享"才要求常驻——即用即关与"不加闸"是同一枚硬币的两面。）
3. **结果回主线程汇总**，不靠并发容器凑（`onTimeoutBatch` 的双清单"每项恰好落一个"靠这条保住）。
4. **项内异常自己吞并记账，不向上冒**（批量信封为部分失败设计；向上冒会让一项毁掉整批）。

⚠️ **一条必须写进模板的 caveat**：`close()` 是**无限等待**（`shutdown()` + `awaitTermination(MAX_VALUE)`）——好处是它等于 join，坏处是**一个卡住的任务会把调用点永远挂住**。所以项内必须自带超时（Dubbo 2s / DB `connection-timeout` / xxl-job 调度超时）。将来有人在项里加一个无超时的下游调用，会把整个 tick 钉死。

**与 timeout-center 已验证形态的两处刻意差异**（模板**不能原样搬**它）：

1. 它的扇出是**两层**（槽位 ≤16 → 批内按 group），且**落库回到槽位线程串行做**——"只并行网络调用、落库不并行"。单体这些循环里**落库就是项本身的工作**，扇出即并发落库。
2. 因此 timeout-center 的"**池 ≥ 并行度**"（池 24 vs 16 槽位，spec §5.3）在单体换成"**池是唯一闸**"——没有"并行度 ≤ 16"这种设计约束，池自己封顶。
3. 另：它的循环形态是变体 B 的一个特例（外层多一层槽位并行），**不能**用它的"失败行被退避推远 ⇒ 天然终止"当变体 B 的终止依据——变体 B 的终止依据只能是**游标推进到空批**。

### scope 判定（Out of scope 第 5 条）：没有一处判出 scope（票面 ④ 第 4 问）

按 map 的 Out of scope 第 5 条（「任何会动幂等或顺序假设的并发化不属本 effort」）逐处核：**没有一处判出 scope**。硬事实：

- 并发化**没有改动任何幂等位或顺序假设**——各循环都有幂等闸门（条件 UPDATE / 流水 `uk_order_type` / `dedup_key` / `INSERT IGNORE`），批内各项互不相干。
- **资金与信用余额更新全是原子 SQL**（`SET frozen_amount = frozen_amount + #{amount}`、`GREATEST(0, ...)`、`SET balance = balance + #{amount}`），不是 Java 读改写 ⇒ **跨行按用户并发无丢失更新**。
- 通知的"取号与落库同一事务（取号序 = 提交序）"不变量照旧成立（每条通知仍是一个独立事务），扇出只改变不同通知之间的交错。

**一处新可达的既有竞态（扇出真实换来的暴露面，进代价表 + 进 spec，本版不改）**：

> **同一用户的多个订单可能被同时关单**（今天串行，天然不可能）。症状＝偶发 InnoDB 死锁——回滚其中一项，落 `failList`，中心按退避重投（30s 起指数翻倍）；最坏打满 16 次进死信、管理面人工重发。**不丢数据**（流水 uk 与条件 UPDATE 的幂等闸门全程兜底），也**与虚拟线程无关**——票 04 实测时 VT 开/关各臂都出现。
> **根因**：REPEATABLE READ（MySQL 默认，本仓无隔离级别配置）下，流水插入是 `INSERT IGNORE INTO wallet_transaction ... SELECT ... FROM virtual_wallet WHERE user_id = ?`（`WalletMapper.xml:41`），对**源表**即该用户的钱包行取**共享 next-key 锁**；紧接着同一事务的 `UPDATE virtual_wallet ... WHERE user_id = ?` 需要把同一行升级成**排他**锁。同用户两个事务各持 S、各求 X ⇒ 成环。这解释了观测到的两个特征：只在同用户并发时出现（不同用户锁不同行）、与线程模型无关。
> **最小修法**：`wallet_transaction.wallet_id` 全仓**没有任何读取者**（无索引、无查询用到、实体字段只被写和被映射），它只因 `NOT NULL` 才逼出那条 `INSERT ... SELECT` ⇒ **删掉该列，改走 `INSERT ... VALUES`**，S 锁随之消失。连带须显式化"钱包行必须存在"（今天那条 SELECT 兼职了这个隐式守卫，且"查不到钱包"与"流水已存在"都返回 0 行、**语义撞车**，而 `unfreeze`/`income` 都不检查返回值 ⇒ 会静默成功；只有 `freeze` 会往下走并抛出**指错原因**的"余额不足"）。
> **本版不做**（用户拍板："放进 spec 里就行，不用现在改"）。**可回收触发条件**：关单路径的日志里出现 `Deadlock found when trying to get lock` ⇒ 把这条修法提上来。
>
> 记录口径：本条按票面第 4 问（"若某处并发化会碰到幂等/顺序假设…判出 scope 并记录"）与 map 的「其他设施若有交互，越界时回来处置」登记；**根因与最小修法一并写下是用户明示要求**（要把修法带进 spec），不是本票自行扩张的设计内容——本票**未改任何代码、未开新票**。

### 事实自核与修正

1. ⚠️ **两处上游票修正**（完整论述在对应节，此处只作索引；两处均已回填上游票）：**票 03** — `HintManager` 的 Holder 类型（见 C 节）；**票 05** — `connection-timeout: 3000` 尚未落地（见 B 节第 2 条）。
2. **单体那 7 个循环是"单发一批"，不是"循环拉批到空"**（落在 #1–#5 五个 handler 上）：全部 `LIMIT 100`（`SCAN_BATCH_SIZE`）、每 tick 拉一次就结束 ⇒ timeout-center 那套模板**不能原样搬**。
3. **每日信用任务的两条查询无 `LIMIT`**（`selectUsersActiveOn` / `selectLowScoreNormalUsers`）——它是单体唯一**无界**的逐行 DB 循环；搜索重建的 4 条 `selectAllIds` 同样无 `LIMIT`（另有第 5 个调用点刻意不设限，见模板小表）。这两处的"无界"是真缺陷，但**属批处理设计、不属线程模型**：本票按用户口径把它们与扇出一起处置（补 `LIMIT` + 游标）。
4. **票面"三个服务各 2~4 个 job handler"**：实测 `marketing-activity` 4 / `marketing-asset` 2 / `timeout-center` **1**。
5. **票面说"单体 10 个 job handler"**：核对无误（含 `bs-infrastructure` 里那三个 `@XxlJob`）。全仓 17 个 + `onTimeoutBatch` 1 个 = 18 个入口，逐条裁完。
6. **票面问"`TimeoutRegisterOutboxSender` 有没有漏配池"**：单体侧**没有** pool 约束问题（扇出的每行是纯 Dubbo 调用，DB 读写都在扇出外的调用线程上）；有约束的是**中心侧**（≤200 并发注册打池 24）与**回调侧**（单体池 10）。见 B。

### 转下游（收口时一并留痕）

- **[票 11](11-task-write-spec.md)**：本节落 spec 的「调度与批处理」节。落稿口径：**只写裁决与模板，不搬落地步骤**（spec 不含代码改造步骤）。必须原样带上的四件：① 裁决依据 = **用户口径**（不是性能收益，也不许包装成规则推导）；② 被否掉的"有用性"论据原样留档；③ 模板两变体 + 四条硬规矩 + `close()` 无限等待的 caveat + 与 timeout-center 的三处差异；④ 上一条"新可达的既有竞态"整段（含根因、最小修法、本版不改、触发条件）。
- **[票 10](10-grill-observability-and-acceptance.md)**：① 扇出后单体池是**唯一闸** ⇒ 连接池等待指标（Hikari `active`/`awaiting`、等连接线程数）成为调度路径的主观测点，验收口径挂它；② 新增一条**告警观测点**：关单路径出现死锁日志 ⇒ 触发上一条修法；③ "平台线程数应当下降"的验收对调度路径**不适用**——xxl-job 的 `JobThread` 本来就只有 1 条，扇出后 VT 不产生平台线程，看的是峰值不是常驻（与票 07 转给它的 Dubbo provider 同款口径）。
- **[票 05](05-grill-framework-flip-and-backpressure.md)**：⚠️ 本票复核发现它的第 ③ 条裁决**部分未落地**——`connection-timeout: 3000` 只在 timeout-center 生效（另三个工程无 Hikari 配置块，今天跑默认 30s）。已在票 05 的 `## Answer` 加注记。
- **[票 03](../research/03-jdk21-pinning-inventory.md)**：⚠️ 两处修正（Holder 类型是 TTL 非 `InheritableThreadLocal`；VT 会继承 inheritable 值、所以真正的雷是"扇出边界落在打开的 hint 作用域内"）。已回填票 03。
- **不开独立票的一项**：`wallet_id` 删列（含"钱包行必须存在"显式化、`unfreeze`/`income` 的 0 行静默成功）。用户拍板"放进 spec 里就行，不用现在改" ⇒ 只落 spec，**本版不开票、不改代码**。
