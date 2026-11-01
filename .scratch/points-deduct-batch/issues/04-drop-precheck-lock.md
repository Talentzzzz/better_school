# 04: 幂等预检去掉加锁读，互斥只留给消费明细唯一键

**What to build:** 把扣减前置的幂等预检从加锁读改回普通快照读，并把「它凭什么能防重」这件事在代码与 SQL 上改成事实。

理由：该查询在「这笔没扣过」时结果为空、加的是间隙锁，而间隙锁彼此兼容、并不互斥，根本拦不住并发重复；它唯一的实际效果是在「空查 + 后续插入」这个 InnoDB 常见组合上埋了一份死锁可能。真正的互斥一直是消费明细的唯一键。

代价只在「本事务开始之后才提交的重复请求」这一极窄窗口：预检会漏判，改由唯一键兜底，最坏结果与现状一致（回滚、按成功回报），无正确性损失。

这一票只有这一件事，不动落库形状——先把假互斥拆掉，再改写入条数，两步各自可验证。

**Blocked by:** 03

**Status:** resolved

- [x] 预检查询不再带加锁读语义
- [x] 该查询上的注释与对应仓储方法上的注释改为「间隙锁彼此兼容、拦不住并发；真正互斥是消费明细唯一键」的理由陈述，旧的「加锁读让并发写落在同一间隙上」表述不再出现
- [x] 并发重复兑换用例绿、同一兑换单号重放用例绿、积分不足用例绿
- [x] 既有用例的断言一字未改
- [x] 兑换入口的请求与响应形状、业务码无变化；无表结构与数据变更

## Comments

### 2026-10-18 · 预检改快照读，并发用例由「9/9 轮死锁」转绿（agent）

**改动范围**：4 个产品文件 + 2 份文档；测试文件 **0 改动**（`git diff --name-only -- backend/marketing/marketing-app/src/test` 为空），`schema.sql` 0 改动，无数据变更。

| 文件 | 改动 |
| --- | --- |
| `PointsMapper.xml` | `selectConsumeIdForUpdate` → `selectConsumeId`，删掉 `FOR UPDATE`；注释重写为「间隙锁彼此兼容、拦不住并发；互斥是 `uk_bucket_reason_ref`；死锁正是从这条间隙锁开始的」 |
| `IPointsDao` | 同名方法改名 + 注释改为「普通快照读、不加锁」 |
| `IPointsRepository` | `existsConsumeForUpdate` → `existsConsume`；方法 javadoc 与类 javadoc 改成理由陈述（FIFO 消费依赖行锁，预检不靠锁防重） |
| `PointsRepository` | 实现改名；类 javadoc 与撞 uk 分支的行内注释同步（「预检不承担互斥」） |
| `docs/api/points.md`、`docs/db/points.md` | 流程图与索引说明里的「幂等预检 FOR UPDATE / `LIMIT 1 FOR UPDATE`」同步为快照读，并写明互斥在唯一键（`backend/CLAUDE.md` §9 的文档同步要求） |

**用例结果**

```
cd backend/marketing
mvn -o test -Dtest=PointsDeductIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

| 时点 | 结果 |
| --- | --- |
| 改造前（基线） | 9 tests，**8 绿 / 1 红**：`deduct_sameRedeemOrderFromTwoThreads_chargesOnce` 抛 `DeadlockLoserDataAccessException`（MySQL 1213） |
| 改造后 | **9 tests，0 失败**，类耗时 13.55s |

按 03 的交接，并发用例单独连跑 **10 轮**（每轮独立 JVM）：
`mvn -o test -Dtest='PointsDeductIntegrationTest#deduct_sameRedeemOrderFromTwoThreads_chargesOnce'`
→ **10/10 绿**，无死锁、无异常、两侧都按 `success` 回报 `balance=260`。断言口径与 03 完全一致（响应 + 落库，不看语句、不看线程先后）。

全量：`mvn -o test`（工作目录 `backend/marketing`）→ **118 tests, 0 failures, 0 errors, 0 skipped**，
与 02 的 117 基线差 +1，增量恰为 03 新增的那条并发用例。

**决策 1：03 交接的窄窗口——接受，不在本票处理**

03 记录的窗口是：重复者的预检发生在先到者提交之前（快照漏判），而先到者恰好把余额扣到不足本次额度，
重复者拿到桶锁后会先走「积分不足」返回 `4101`，唯一键没机会出场。

- 它是**既有行为**，不是本票引入：改造前预检是当前读，重复者的预检同样可能在先到者持有明细行锁、尚未提交时拿到空结果（03 的 `SHOW ENGINE INNODB STATUS` 现场就是这条路径），只是那时它更常先撞死锁。
- 唯一的替代做法是在「不足」分支里于桶锁内再查一次明细——那是给写路径加判断，属于「不动落库形状」之外的事，留给 05/06 重写写阶段时一并决定。
- 因此代码注释没有把「必由唯一键兜底」写成无条件断言，而是显式标注了这个分支（见 `IPointsRepository#existsConsume` 的 javadoc）。

**决策 2：方法一并改名，不只是删 `FOR UPDATE`**

票面要求「预检查询不再带加锁读语义」并把注释改成事实。方法名 `*ForUpdate` 本身就是一句假话——留着它，注释说「不加锁」、名字说「加锁」，谁读都得先怀疑一遍。改名只影响 `IPointsDao` / `IPointsRepository` 两个内部方法（全仓 grep 无其他调用点，`git grep` 仅 `.scratch` 历史票里还有旧名），入口、DTO、DAO 语句之外的东西一律没动。

**顺带核实：预检改快照读不会让 `balanceAfter` 读到更旧的余额**

理论上有个隐忧：REPEATABLE READ 下读视图由事务的第一条读语句建立，预检从当前读变快照读后，读视图可能提前到预检那一刻，后面 `sumAvailableRemain` 就看不到「预检之后、扣减之前」别的线程提交的入账。

用一次性探针（scratch 库 `points_probe_tx`，测完已 DROP，未碰 `bs_marketing*`）对两种预检形态做了对照：事务内 `SELECT ... WHERE ref=99`（空）→ 另一连接插入并提交 → 事务内再普通读。

| 预检形态 | 并发提交后的普通读 |
| --- | --- |
| 普通快照读（改造后） | 0（看不到） |
| 加锁读（改造前） | 0（同样看不到） |

两种形态的读视图都建立在预检那一步，`balanceAfter` 口径不变——**这条隐忧不存在**，无需额外处理。

**两轴评审后的修订**

| 轴 | 发现 | 处理 |
| --- | --- | --- |
| Standards | `docs/api/points.md`、`docs/db/points.md` 仍写「幂等预检 FOR UPDATE」，违反仓库「改代码同步文档」的约定 | 两份文档同步（见上表），并补一句「互斥在唯一键」 |
| Standards | 同一段论证在 XML、domain 接口、实现类、行内注释各写一遍（Duplicated Code） | 保留票面要求的两个权威位置（XML + 仓储方法 javadoc），类 javadoc 与行内注释收成一句 + 指针 |
| Spec | javadoc 把「由唯一键兜底」写成无条件，抹掉了 03 记录的「余额不足分支」反例 | 改为「通常由唯一键兜底；余额已被扣到不足时先走 4101，既有行为」，并注明见票 03 |
| Spec | 「多一份死锁可能」把实测的确定性死锁说轻了 | XML 注释改为「埋下死锁（并发重复兑换的死锁正是从这条间隙锁开始的）」 |
| Spec | 改名属票面未列动作 | 接受，理由见决策 2 |

**环境事故（不改代码，记录以免复现时误判）**：跑用例期间 `bs-nacos-test` 与 `bs-mysql` 先后因宿主机内存压力崩溃（MySQL 是在 xxl-job 的统计查询上崩的，`OOMKilled=false`、restart 后数据完好）。处置：停掉测试不需要的开发侧容器（`es / xxl-job-admin / rocketmq-broker / rocketmq-dashboard / nacos / rocketmq-namesrv`），再拉起 MySQL；需要恢复开发栈时 `cd backend && docker compose up -d` 即可。期间有一次 9 条全红是 `CannotGetJdbcConnection`（MySQL 已崩），不是用例失败。
