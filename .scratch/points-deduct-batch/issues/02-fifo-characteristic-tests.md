# 02: 顺序扣减的特征测试：跨多张券的 FIFO、恰好整笔扣完、跨一张券

**What to build:** 在既有的扣减测试集上补三条顺序扣减用例，只经兑换扣分入口发起、只断言外部可观察状态——把「跨多张券时扣减结果长什么样」从现在这份逐桶写的实现里问出来，钉成契约。

这是整个改造的安全网：后面几步会重写落库形状，能不能说「对外语义一字未变」，唯一凭据就是这三条在改造前就已经绿的用例，且断言在整个过程中不被修改。

前置态沿用测试基类直接插历史桶的方式（可指定过期时间与面额），不另造数据构造器。

**Blocked by:** 01

**Status:** resolved

- [x] 跨 3 张以上券的 FIFO 用例：断言扣后余额、每一张券各自的剩余量、消费明细行数与每行金额、积分变动事件的 delta 与 balanceAfter
- [x] 恰好整笔扣完的用例：券被扣到归零、不产生「部分扣」的残余，余额与明细行数正确
- [x] 跨 1 张券的用例：退化为单桶扣减，结果与单桶口径一致
- [x] 断言只碰外部可观察状态：兑换响应对象、积分桶剩余量、消费明细行、变动事件载荷；不断言语句条数、不 mock 任何 Bean
- [x] 三条用例在未改动的实现上全绿（若发现现状与预期不符，先在此票 Comments 里记下来，不要顺手改实现）

## Comments

### 2026-10-18 · 三条顺序扣减特征用例落地（agent）

**改动范围**：只动一个测试文件
`backend/marketing/marketing-app/src/test/java/com/betterschool/marketing/integration/points/PointsDeductIntegrationTest.java`
——既有的 5 条用例一字未改（含它们的断言），新增 3 条加 2 个只读查询小工具
（`bucketBusinessIdOf`：桶行 id → 桶业务 ID；`detailAmountsByBucketOf`：明细按桶业务 ID 归集成 `桶 → 扣掉多少`）。
产品代码 **0 行改动**，`schema.sql` **0 改动**，未新增数据构造器（前置态仍用基类 `insertBucket`）。

**三条用例的前置态与预期**（金额/过期时间都取到能区分「扣光」与「只扣差额」的值）：

| 用例 | 前置桶（面额，过期天数） | 扣分 | 预期 |
| --- | --- | --- | --- |
| `deduct_acrossMoreThanThreeBuckets_booksEveryBucketItTouches` | 20/1d、30/3d、**25/5d**、**40/5d（与前者同一到期时刻）** | 100 | 前 3 张归零，第 4 张 40→15，余额 15；明细 4 行 = 20/30/25/25，合计 100；事件一条 delta=-100、balanceAfter=15 |
| `deduct_exactlyDrainsBuckets_leavesNoPartialResidue` | 30/1d、70/3d、50/5d | 100 | 前两张恰好归零，第三张 50 一分不动，余额 50；明细只有 2 行（30、70），**没有第 3 行**；事件 -100/50 |
| `deduct_fromSingleBucket_behavesLikeSingleBucketDeduction` | 100/5d | 60 | 100→40，余额 40；明细恰好 1 行 = 60；事件 -60/40 |

第一条里第三、四张**刻意做成同一到期时刻**（同一个 `LocalDateTime` 值算出来，不是两次 `now()`）：
扣到第四张之前还差 25，若次序是「后入账的先扣」，结果会是第三张剩 15、第四张归零，与预期正好相反。
这样把 spec 里 `ORDER BY expire_at ASC, id ASC` 的**同到期时间兜底**也钉进了契约，而不只是「按到期时间排」。

**结果**：三条一次全绿，现状与预期一致，本票范围内**没有发现需要记下的偏差**。

```bash
cd backend/marketing
mvn -o test -Dtest=PointsDeductIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
# Tests run: 8, Failures: 0, Errors: 0  （原 5 + 新 3），类耗时 16.74s
```

全量回归（同一工作目录，`mvn -o test`）：

| 项 | 值 |
| --- | --- |
| Tests run / Failures / Errors / Skipped | **117 / 0 / 0 / 0** |
| 与上一基线差 | +3（114 → 117，增量恰为本票三条） |
| wall | 1m 50s |

日志里的 `No route info of this topic` 是测试栈无 broker 的正常副作用（outbox 保持 PENDING），非失败。

**断言口径**（后续票改实现时请照这个口径验，别改断言）：

- 只读两类外部可观察状态：兑换响应对象（`success`/`balance`）+ 落库三处（`points_bucket.remain`、`points_detail` 行、`outbox_message` 的 `bs-points-changed` 载荷）。
- 明细**按桶归集后断言**（`bucket 业务 ID → 扣掉多少`），不按物理行序、不断言 `id` 自增顺序——批量写若改变插入顺序，这些用例仍应保持绿。这正是「对外语义未变」该保住的东西。
- 未断言 SQL 条数、未断言私有方法、未 mock 任何 Bean。「恰好扣完」那条**只**断言可观察结果（被扣的券归零、额度够用的券一分不动、明细正好两行），
  **不声称**能证明「第 3 条语句被跳过」——批量版即便执行一条 no-op 的更新也照样过。这是有意的：语句形状属于实现，不由本票钉。
- 同一张券出现两行明细时，归集工具会抛一条带扣减量的明确断言失败，不会静默合并成一行（那样会把「多记一行」这种契约破坏藏起来）。

**给后续票（05/06 重写落库形状）的一条提醒**：明细行序在本票里刻意没有被钉住。但流水页 `selectTransactions` 是 `points_bucket ∪ points_detail` 后按 `created_at DESC, id DESC` 排序，同一毫秒内的多行相对顺序由 `id` 决定。批量插入若改变行序，**同一笔兑换在流水页里几行的先后可能变化**——这属于可观察行为，但不在本票三条用例的断言范围内，也没有既有用例覆盖。如果那条顺序被当成契约（比如前端展示「先扣哪张券」），需要在后续票里单独确认，而不是靠这三条兜。

### 2026-10-18 · 两轴评审（Standards / Spec）后的修订（agent）

改动提交前跑了一遍两轴评审，Standards 轴无硬违规（纯新增、无删除行、命名与既有用例同风格、未 mock）；
Spec 轴提了三条，逐条处理：

| 评审发现 | 处理 |
| --- | --- |
| 票面写「恰好扣完那条间接表达了第 3 条语句不执行」——这个推理不成立，不可观察的东西不能声称被证明了 | **改说法，不改测试**：那条只断言可观察结果；语句是否执行属于实现形状，不在本票口径内 |
| 明细按桶归集用 `Collectors.toMap` 无合并函数，同一张券出现两行会抛 `IllegalStateException: Duplicate key`，失败信息晦涩 | 加合并函数，直接抛带两个扣减量的 `AssertionError`；**同时不静默合并**（静默合并会把「多记一行」这种契约破坏藏起来） |
| FIFO 只钉了「按到期时间排」，没钉 spec 里 `ORDER BY expire_at ASC, id ASC` 的**同到期时间兜底** | 第一条用例的第三、四张改成同一到期时刻（同一个 `LocalDateTime` 值），扣到第四张时还差 25 —— 次序反了结果会正好相反，于是这条兜底也被钉住了 |

修订后重跑：`Tests run: 8, Failures: 0, Errors: 0`（13.55s），全量 117 条同样全绿。**断言的口径没有放宽或收紧，只是把一条钉得更实。**

