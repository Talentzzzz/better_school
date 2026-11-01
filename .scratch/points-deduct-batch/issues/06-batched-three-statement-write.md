# 06: 落库换成固定三条语句，事务长度不再随桶数增长

**What to build:** 把上一步已经算好的方案，用固定三条语句落库：一条多值写全部消费明细（行数 = 被碰到的券数）、一条把扣光的券清零、一条扣那张部分扣的券（整笔恰好扣完、没有部分扣时这条跳过）。

跨 1 张券和跨 50 张券，落库语句数都是三条。跨一张券时后两条退化为单行更新，不为最常见的单桶场景引入额外分支。

判定口径：第一条的影响行数必须等于明细条数，第二条必须等于「扣光」集合大小，任一不等即整笔回滚，绝不静默继续。撞唯一键表现为行数偏少，语义与逐桶版一致——判为重复兑换、回滚，事务外重读余额按成功回报。

顺带把仓储端口收干净：批量写之后，「单桶扣减」不再是端口上该有的概念，逐桶的内部步骤应成为实现内部步骤，而不是继续对外暴露。

**Blocked by:** 05

**Status:** resolved

- [x] 跨 1 张券与跨多张券，落库语句数相同（三条上界），跨桶场景不再随券数增长
- [x] 批量明细的影响行数必须等于明细条数、清零的影响行数必须等于扣光集合大小；任一不等整笔回滚
- [x] 恰好整笔扣完、没有部分扣的券时，第三条语句跳过，结果仍正确
- [x] 并发重复兑换仍只扣一次（表现为影响行数偏少 → 整笔回滚 → 事务外重读余额按成功回报）
- [x] 积分不足仍是一行不动、无明细、无事件，且回报「积分不足」而不是系统错误
- [x] 扣减失败时整笔回滚，不留半扣的中间态
- [x] 全部扣减用例绿，跨桶用例的断言与改造前逐条一致（断言若被改过，说明语义被改动，停下来重审）
- [x] 仓储端口不再暴露逐桶内部步骤；兑换入口与调用方零改动；表结构与数据零变更

## Comments

### 2026-10-18 · 落库换成「至多三条」批量语句，事务长度不再随桶数增长（agent）

**改动范围**：4 个产品文件（1 XML + 1 DAO + 1 仓储实现 + 1 端口）+ 2 份文档；集成测试与单测文件 **0 改动**
（`git diff --name-only -- backend/marketing/marketing-app/src/test` 为空），
`marketing-api` / `marketing-trigger` / `marketing-case` / `marketing-types` / `sql/schema.sql` 均 0 改动，无数据变更。

| 文件 | 改动 |
| --- | --- |
| `PointsMapper.xml` | 删 `insertIgnoreDetail`（单条）；新增 `insertIgnoreDetails`（一条多值 `INSERT IGNORE`）与 `drainBuckets`（`bucket_id IN (...)` 批量清零）；`deductRemain` 保留，只服务那张部分扣的桶 |
| `IPointsDao` | 两个批量语句替换单条明细写；`deductRemain` 的注释收敛为「方案里唯一那张部分扣的桶」 |
| `PointsRepository` | `writePlan` 换成三条语句 + 逐条影响行数校验；`existsConsume` / `listAvailableBucketsForUpdate` / `savePointsChanged` 降为私有 |
| `IPointsRepository` | 端口只剩「一次入账 / 一次消费 / 余额 / 过期 / 流水 / 流水条数」，逐桶与预检的内部步骤全部下移 |
| `docs/api/points.md`、`docs/db/points.md` | 写路径说明与流程图同步为「至多三条 + 影响行数校验」 |

**用例结果**

```
cd backend/marketing
mvn -o test -Dtest=PointsDeductIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false   # 9 tests，0 失败
mvn -o test                                                                               # 118 + 7，0 失败
```

扣减 9 条用例断言**一字未改**，改造前后同一条命令都 9/9 绿（改造前基线见票 01/05）；
全量 125 tests 绿（118 集成 + 7 条方案计算单测），增量 0——本票没有新增用例，因为对外可观察行为按规格不许变，
批量写入的形状按 `spec.md`「Testing Decisions」不进 seam（不断言 SQL 条数、不 mock Bean）。

**语句数实测（不是估计）**

打开 MySQL general log（`log_output=TABLE`），单跑一条用例后按 SQL 文本捞 `points_*` 的写语句：

| 用例 | 桶数 | 明细写 | 清零 | 部分扣 | 合计 |
| --- | --- | --- | --- | --- | --- |
| `deduct_acrossMoreThanThreeBuckets...` | 4 | 1（多值，4 行） | 1（`IN (3 id)`） | 1 | **3** |
| `deduct_exactlyDrainsBuckets...` | 2 恰好扣完 | 1（多值，2 行） | 1（`IN (2 id)`） | 跳过 | **2** |
| `deduct_fromSingleBucket...` | 1 部分扣 | 1（单值） | 跳过（扣光集合为空） | 1 | **2** |

实测的三条语句原文（4 桶那条）：

```sql
INSERT IGNORE INTO points_detail (...) VALUES (...4 组...)
UPDATE points_bucket SET remain = 0, update_time = NOW()
  WHERE user_id = 1003 AND remain > 0 AND bucket_id IN (..., ..., ...)
UPDATE points_bucket SET remain = remain - 25, update_time = NOW()
  WHERE bucket_id = ... AND remain >= 25
```

**决策 1：准确的说法是「至多三条」，不是「恒三条」**

票面写「跨 1 张券和跨 50 张券，落库语句数都是三条」，实测单桶场景是 2 条：扣光集合为空时 `IN ()` 不是合法 SQL，
没有部分扣的桶时第三条自然无事可做。三条是**上界**且与桶数无关（跨 4 桶、跨 50 桶都恰好 3 条），
这正是本票要的「事务长度不随桶数增长」；为了字面凑成恒三条而发一条无用语句，是把校验口径做成了仪式，
故不做。代码注释与两份文档都按「至多三条」表述，票面 checkbox 的括号「（三条上界）」即此口径。

**决策 2：三条语句各校验行数，两类不等分开处置**

- 第 1 条（明细）行数 `< 明细条数` → 撞 `uk_bucket_reason_ref`，判为重复兑换：`setRollbackOnly` + 返回 false，
  事务外重读余额按成功回报。语义与逐桶版一致（逐桶版是某一条插入返回 0）。
- 第 2 条（清零）行数 `≠ 扣光集合大小` → 抛 `IllegalStateException` 整笔回滚。这不是业务失败，
  是「桶余量与方案不一致」的不变量被破坏，必须作为系统错误冒出去，不能按幂等成功掩盖。
- 第 3 条（部分扣）行数 `≠ 1` → 同样抛异常回滚（沿用逐桶版的口径）。

**决策 3：端口多收了两项，属票 05 清单外的同类收缩**

票面只要求「单桶扣减不再是端口上的概念」。实际一并下移了三项：`listAvailableBucketsForUpdate`（逐桶加锁读）、
`existsConsume`（幂等预检）、`savePointsChanged`（发变动事件）——三者在全仓都只有 `deduct` /
`insertBucketWithChanged` 自己调用，留在端口上就是「没有调用者的公开方法」，与票 05 收掉 `consumeBucket`
是同一条理由。收到的效果可以验证：端口上每个方法在 `PointsAccountServiceImpl` 里都有调用方。
若评审要求严格按清单只动 `listAvailableBucketsForUpdate`，回退这两项是十来行的事。

**决策 4：`drainBuckets` 比票面的 `WHERE bucket_id IN (...)` 多两条件**

多了 `user_id = #{userId}` 与 `remain > 0`。前者是防御（桶业务 ID 是雪花、理论上不会跨用户，但一条 UPDATE
写明用户边界不花代价）；后者让行数校验真正有意义——不加它，一行 remain 已经是 0 的脏桶也会被算作「命中」，
把「余量与方案不一致」吞掉。两条都不改变正常路径的结果（这些行已被加锁读拿住、remain 恒大于 0）。

**既有窗口（不在本票范围，记录以免误以为已收口）**

幂等互斥靠 `uk_bucket_reason_ref (bucket_id, reason_type, ref_id)`，它挡的是「同一张桶同一笔业务」。
若并发后到者预检漏判、且它的方案只落在先到者**没碰过**的桶上（先到者恰好扣光最老一张、后面还剩够用的桶），
明细行数对得上、唯一键不出场，仍会扣第二次。逐桶版同样如此，不是本票引入的；要收它得改幂等键粒度
（例如按兑换单号唯一），那是另开票的事。已写进 `existsConsume` 的 javadoc。

**两轴评审后的修订**

| 轴 | 发现 | 处理 |
| --- | --- | --- |
| Standards | `docs/api/points.md` 用「跨 1 张券」——「券」不在 `CONTEXT.md` 词表（词表是「积分桶」） | 两份文档与代码注释统一为「桶」 |
| Spec | 文档与注释写「固定三条语句」，与实测（单桶 2 条）不符 | 全部改成「至多三条」，并写明哪一条在什么情况下不执行 |
| Spec | `savePointsChanged` 移出端口不在「接口层变更清单」里 | 保留（理由见决策 3），票面记录在案 |
| Spec | `drainBuckets` 多出 `user_id` / `remain > 0` 两个条件 | 保留，理由见决策 4；行数校验若不带 `remain > 0` 会失效 |
| Standards | `writePlan(plan, userId, reasonType, refId, now)` 的四个参数总是结伴出行（Data Clumps 提示） | 不改：这组参数就是「本次消费的来源 + 时点」，出现两处（`writePlan` / `newDetail`），为它引入一个值对象会让这两个私有方法多一层拆包；真有第三处再收 |
| Standards | `PointsRepository` 与 `DrawCountRepository` 现在两种写入形状（逐桶 vs 批量） | 不改：`spec.md`「Out of Scope」明确本次不动抽奖次数域 |
| Standards | `drainBuckets` 与 `expireBuckets` 形状相近（都是 `SET remain = 0`） | 不改：语义不同（一个按 id 清单、一个按过期时间），合并要加语义开关，反而更难读 |

**环境事故（不改代码，记录以免复现时误判）**

本票期间 Docker Desktop 又整体重启过一次（`bs-*` 全部 `Exited`），`bs-nacos-test` 另有一次 JVM 崩溃
（SIGSEGV，`Exited (139)`，启动约 87s 后）。两次用例红都是 Spring 上下文起不来
（`Can not create registry service-discovery-registry://127.0.0.1:18848/...`），不是用例失败。
处置：`docker compose up -d mysql redis minio nacos-test rocketmq-namesrv-test`，等 `healthy` 后重跑即绿。
`bs-nacos-test` 崩溃那次重启容器即恢复，用例与代码未做任何迁就。

**遗留边界**：批量写只落在积分域；抽奖次数域的扣减仍是逐桶（`spec.md` 划在范围外）。表结构、索引、
数据一行未改，`schema.sql` 零改动。
