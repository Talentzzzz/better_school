# 05: 扣减拆成「算方案」与「落库」两段，写入仍是逐桶

**What to build:** 把扣减拆成清楚的两段，但**不换写入形状**：一次加锁读出该用户全部可用券 → 纯粹在内存里算出这笔方案（「被扣光的券」集合 + 「至多一张部分扣的券」）→ 再用现有的逐桶语句把这份方案照原样写下去。

这一步对外行为完全不变，连语句条数都还随券数增长——它的价值是把「方案」先变成一个有名字的东西，让下一步替换写入形状时只需改一段、不必重写整个方法。属于「先把改动变容易，再做容易的改动」。

方案之所以「部分扣的券最多一张」，是 FIFO 顺序本身的性质：一旦某张券的面额大于还差的额度，扣掉差额后剩余归零、循环即止。这个性质要在代码里显式表达出来，而不是散落在循环里。

**Blocked by:** 04

**Status:** resolved

- [x] 方案计算是纯内存步骤、不触库；产出两个语义明确的集合：被扣光的券、至多一张部分扣的券
- [x] 消费明细的写入仍然先于任何桶余量变更
- [x] 全部扣减用例绿：跨多张券、恰好整笔扣完、跨一张券、并发重复、积分不足、同一单号重放、跳过过期桶
- [x] 既有用例的断言一字未改
- [x] 对外契约、业务码、FIFO 语义、表结构与数据零变化

## Comments

### 2026-10-18 · 扣减拆成「算方案（纯内存）+ 落库（逐桶）」两段，对外零变化（agent）

**改动范围**：5 个产品文件（2 新增 + 3 改）+ 1 个新增单测 + 2 份文档；集成测试文件 **0 改动**
（`git diff --name-only -- backend/marketing/marketing-app/src/test` 为空），`marketing-api` / `ResponseCode` /
`PointsMapper.xml` / `sql/schema.sql` 均 0 改动，无数据变更。

| 文件 | 改动 |
| --- | --- |
| `PointsDeductPlanVO`（新） | 扣减方案：`allocate(buckets, amount)` 纯内存摊派，产出**被扣光的桶**集合 + **至多一条部分扣的桶**；`deductions()` 给落库用（扣光在前、部分扣在最后）。不可变 |
| `PointsBucketDeductionVO`（新） | 方案里的一条：桶业务 ID + 这一笔扣掉的量（不可变）；一条落一行消费明细 |
| `PointsRepository` | `deduct` 拆成「加锁读桶 → `PointsDeductPlanVO.allocate` 算方案 → `writePlan` 照方案落库」；`writePlan` 先逐条落明细、再按同一顺序逐桶扣 `remain`（语句条数仍是 2N，形状未换） |
| `IPointsRepository` | 端口去掉 `consumeBucket`，`deduct` 的契约改成两段描述；类 javadoc 注明「逐桶落库是消费用例的内部步骤」 |
| `marketing-domain/pom.xml` | 域模块首个单测依赖：`junit-jupiter` + `assertj-core`（test 作用域） |
| `PointsDeductPlanVOTest`（新） | 方案计算的 7 条纯内存用例（不连库、不起 Spring） |
| `docs/api/points.md`、`docs/db/points.md` | 流程图与写路径说明同步为「两段 + 明细先行」（`backend/CLAUDE.md` §9） |

**用例结果**

```
cd backend/marketing
mvn -o test -Dtest=PointsDeductIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false   # 9 tests，0 失败
mvn -o test -pl marketing-domain -am -Dtest=PointsDeductPlanVOTest                        # 7 tests，0 失败
mvn -o test                                                                               # 118 + 7，0 失败
```

改造前后对比：改造前基线 `PointsDeductIntegrationTest` **9/9 绿**；改造后仍 **9/9 绿**，断言一字未改。
全量两次绿（改造后一次、两轴评审修订后一次），增量恰为新增的 7 条 domain 单测。

**决策 1：明细从「逐桶交错」变成「全部先行」**

票面 checkbox 是「消费明细的写入仍然先于任何桶余量变更」。旧的逐桶交错只保证「某桶自己的明细先于该桶的余量」，
第 2 个桶的明细仍落在第 1 个桶的余量变更之后——按全局读法就不成立。既然方案是整笔算完的，写入也照整笔来：
先把方案里所有明细插完，再动余量。撞 uk 时余量一行未改，回滚是唯一结果，比旧形状更强；
这也正是 06「一条多值写全部明细」的落点。

**决策 2：`consumeBucket` 从端口移走（06 的一项验收提前做了一半）**

票面 05 没列端口清理，06 列了（「逐桶的内部步骤应成为实现内部步骤，而不是继续对外暴露」）。
写入改成方案驱动后 `consumeBucket` 再没有被调用——留着就是端口上一个没有调用者的方法，名与实都不对。
故随本次收成 `writePlan` 的私有步骤。06 的该 checkbox 不作废：`listAvailableBucketsForUpdate` 仍在端口上，
06 收端口时仍有可收的东西；若要求严格按票面分工，回退这一步是十来行的事。

**决策 3：新增纯内存单测（票面未列）**

方案计算是这次改动里唯一真正的「纯函数」接缝（不碰库、不碰事务），按 `/tdd` 先写用例再实现。
7 条覆盖：跨多桶、恰好整笔扣完、跨 1 桶（部分扣与恰好扣完各一条）、多桶时部分扣仍至多一条、落库顺序、凑不够时抛不变量异常。
domain 模块此前没有测试目录，为此只加 `junit-jupiter` + `assertj-core` 两个 test 依赖，不引入整套
`spring-boot-starter-test`。既有集成用例的断言一字未改这条约束不受影响。

**两轴评审后的修订**

| 轴 | 发现 | 处理 |
| --- | --- | --- |
| Standards | 注释用票面的「券」，与 `CONTEXT.md`「积分桶」一词两名（而同名字段叫 `drainedBuckets`） | 代码与两份文档统一为「桶」；票面的「券」只是比喻，领域词汇以 `CONTEXT.md` 为准 |
| Standards | `PointsDeductPartVO` / `parts` / `part` 没揭示「哪个桶扣多少」 | 改名 `PointsBucketDeductionVO`，局部变量 `deductions` / `deduction` |
| Standards | 方案不可变、方案里的一条却可变（`@Data`） | 一条也改成不可变（`final` + `@Getter`） |
| Standards | 为 JUnit + AssertJ 引入整套 `spring-boot-starter-test` 偏重 | 换成 `junit-jupiter` + `assertj-core` |
| Spec | 「至多一张」只靠算术成立、没有显式终止；若输入出现 `remain <= 0` 的脏桶，第二张部分扣会静默覆盖前一张，那笔扣减从方案里蒸发 | 出现部分扣即 `remaining = 0` + `break`，注释写明「这是本次摊派的最后一条」——性质显式长在代码里 |
| Spec | `allocate` 的 javadoc 说「调用方须保证」，方法里又抛异常，像两份契约 | 改成「调用方已判过够不够；这条不可达，留着是为了不变量被破坏时宁可整笔失败」 |
| Spec | 端口删 `consumeBucket` 属 06 范围 | 接受，理由见决策 2 |
| Standards | `newDetail` → `toDetailPO` 两跳映射是 Middle Man | 保留：仓储做「实体 → PO」映射是 §3/§8 的分层要求，不是无谓转发；删掉它反而要为一个只有一处使用的方法引入更长参数表 |

**06 的落点**：换写入形状只需要动 `writePlan` 一个方法——方案已经是现成的入参，
`getDrainedBuckets()` 与 `getPartialBucket()` 正好对应「一条清零 + 一条扣部分」，
`deductions()` 正好对应「一条多值写全部明细」。

**环境事故（不改代码，记录以免复现时误判）**：全量第二次跑到 raffle 段时 Docker Desktop 整体重启
（`bs-*` 容器全部 `Exited (255)`，23 个用例报 `CannotGetJdbcConnection` / `Communications link failure`）。
处置：只拉起测试需要的容器
`cd backend && docker compose up -d mysql redis minio nacos-test rocketmq-namesrv-test`
（`bs-rocketmq-namesrv-test` 原本是停的，缺它测试上下文起不来，报 `connect to null failed`），
恢复后全量重跑全绿。期间那 23 条红不是用例失败，也不是本次改动引入的。
