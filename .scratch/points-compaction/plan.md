# points-compaction 票：回退 wallet 余额行 + 新增桶合并任务（自包含执行计划）

> 交接说明：本计划自包含，供新窗口/新会话直接执行。执行后**不需要跑测试**（用户已确认），
> 但第 6 节的测试代码适配必须做完。决策记录：用户拍板回退到"无余额列、余额 = 未过期桶
> 剩余和"的纯 SUM 读（票 01/02 的 wallet 与混合读整体拆除），并新增一个定时任务把"余额少的
> 桶"合并，合并桶的过期时间取**合并集合里最大的 expire_at**（用户指定的口径，等于把小桶
> 有效期延长到其中最晚到期——这是产品决策，不是疏漏）。

## 0. 两句话背景

- 票 01/02 给积分/次数加了 wallet 汇总行（points_balance / draw_count_balance）+ 混合读。
  用户最终判断：校园规模下 O(N) 的 SUM 读完全够用，宁可要结构简单——整体回退。
- 保留原设计的一个已知弱点（碎片桶：签到返利每天入一桶，桶数随账户年龄增长），用一个
  **桶合并任务**补上：把同一用户的小额桶并成一桶，控制 SUM 扫描的工作集大小。这是美团系
  面试标准答案里的 Compaction 机制。

## 1. Part A：回退 wallet（拆票 01/02 全部改动）

### A1. DDL 回退

- `backend/marketing/sql/schema.sql`：删除 `points_balance`、`draw_count_balance` 两段
  CREATE TABLE（连同上方"wallet-read-view 票 01"注释块），恢复到票 01 之前形状。
- `Z:/better_school/sql.md` §2：删除 `points_balance`、`draw_count_balance` 两个小节；
  表数口径改回 **21 张**（`共 21 张表（按该文件 CREATE TABLE 逐张清点；其中库存票的
  draw_strategy_award_inventory / _detail / inventory_allocation_flow 三张历史表本节未展开）`）。
- 注意：票 01 的结论"无余额列"重新成立——`docs/db/points.md` 里"没有余额列"的表述回退后
  再次为真。

### A2. 删除 wallet 专属文件（整个文件删除）

- `marketing-infrastructure/.../points/dao/IPointsBalanceDao.java`
- `marketing-infrastructure/.../drawcount/dao/IDrawCountBalanceDao.java`
- `marketing-app/src/main/resources/mybatis/mapper/PointsBalanceMapper.xml`
- `marketing-app/src/main/resources/mybatis/mapper/DrawCountBalanceMapper.xml`
- **保留**：`PointsExpiredGroupPO` / `DrawCountExpiredGroupPO`（过期扫描分组读还要用），
  以及 `IPointsDao.selectExpiredRemainGroups` / `IDrawCountDao.selectExpiredRemainGroups`
  与对应 mapper 段（Part B 的合并任务也要用同样的加锁分组模式）。

### A3. `PointsRepository` 回退

- 删字段 `pointsBalanceDao` 及其 import。
- `insertBucketWithChanged`：删除插桶成功后的 `upsertTotal` 段（恢复为：插桶 → sumAvailableRemain →
  savePointsChanged）。
- `deduct`：删除 `writePlan` 成功后的 `subtractTotal` 段（恢复为：writePlan → sumAvailableRemain →
  savePointsChanged）。
- `expireBuckets()`：回退为票 01 前的单条实现 `return pointsDao.expireBuckets();`
  （删除三步事务包装——wallet 没了，过期清零不再有配对动作；分组读方法保留给合并任务用）。
- 删除 `walletTotal()` 方法。
- 类注释删掉"wallet 读视图"段，恢复"关键不变量：余额永远是未过期且未扣完的桶 remain 之和，
  读路径一律带 expire_at > NOW()"的表述。

### A4. `IPointsRepository` 回退

- 删除 `walletTotal` 声明；`sumAvailableRemain` 注释恢复为
  "余额 = 未过期且未扣完的桶 remain 之和（没有余额列）"；
- `insertBucketWithChanged` / `deduct` / `expireBuckets` 的 javadoc 删掉 wallet 相关句子。

### A5. `DrawCountRepository` / `IDrawCountRepository` 回退（对称）

- `creditBucket`：恢复为单表达式事务体（插桶即返回，删 upsertTotal）。
- `deductForDraw`：删除 `consumeBucket` 成功后的 `subtractTotal` 段。
- `expireBuckets()`：回退为 `return drawCountDao.expireBuckets();`
- 删字段 `drawCountBalanceDao`、删方法 `walletTotal()`、接口删声明、javadoc 去 wallet 句。

### A6. 域服务回退

- `PointsAccountServiceImpl.getBalance` → `return pointsRepository.sumAvailableRemain(userId);`
  类注释恢复票 01 前"关键不变量"段。
- `DrawCountAccountServiceImpl.getBalance` → `return drawCountRepository.sumAvailableRemain(userId);`
  同样恢复注释。

## 2. Part B：桶合并任务（新增，仅积分侧）

**范围决策**：只做积分桶。次数桶单笔面额大（兑换一次入 3 次）、桶数少，碎片化不成立；
后续若需要可按同构模式复制（计划末尾注明）。

### B1. 合并语义（先读这段再动手）

- **对象**：同一用户、`remain > 0 AND expire_at > NOW() AND remain <= 阈值(10)` 的桶，
  数量 ≥ 2 才合并（单桶没有合并意义）。
- **动作**：把这些桶的 `remain` 求和 S，插入**一桶新行**：`amount = S, remain = S,
  expire_at = max(各桶 expire_at), reason_type = COMPACTION, ref_id = 合并批次 UUID`；
  然后把原桶 `remain` 全部置 0（**不删行**——桶表兼做发放流水，原始入账记录必须留着审计）。
- **守恒**：SUM(remain) 合并前后不变（0+0+…+S = S），余额读不受影响。
- **事务与并发**：单事务内完成"加锁重读（FOR UPDATE）→ 不足 2 跳过 → 插新桶 → 清零旧桶
  （影响行数 = 旧桶数，不符即抛异常回滚）"。并发双跑靠桶行锁串行：后到者拿锁重读时旧桶
  已清零、不再满足条件，自然跳过。整事务原子，崩溃即回滚，无半合并。
- **为什么新桶是插入而不是改旧行**：旧行是发放流水（amount/ref_id 记着"这笔 50 分是哪次
  中奖发的"），不能被改写；合并产生的是**一笔新的资产形态**，所以是新插一桶 + 旧桶归零。
- **FIFO 不受影响**：新桶 expire_at = max，天然排在扣减顺序最后，先到期先扣的次序不变。
- **用户不可见**：合并前后余额不变，不应出现在用户流水里——见 B3 的流水过滤。

### B2. 枚举与常量

- `marketing-types/.../points/PointsReasonType.java`：新增 `COMPACTION`（桶合并）。
  确认 `points_bucket.reason_type` 列宽 VARCHAR(30) 足够（COMPACTION 10 字符）。
- `marketing-types/.../points/Constants.java`：新增
  `MERGE_BUCKET_THRESHOLD = 10`（单桶余额阈值）与 `MERGE_MIN_BUCKETS = 2`（最少桶数）。

### B3. `PointsMapper.xml` / `IPointsDao` 新增

```xml
<!-- 合并候选分组：有 >= 2 张小额有效桶的用户（阈值在应用层常量里，SQL 写死同值，两处注释互指） -->
<select id="selectMergeCandidateGroups"
        resultType="com.betterschool.marketing.infrastructure.points.dao.po.PointsMergeGroupPO">
    SELECT user_id, COUNT(*) AS bucketCount, SUM(remain) AS total
    FROM points_bucket
    WHERE remain &gt; 0 AND expire_at &gt; NOW() AND remain &lt;= 10
    GROUP BY user_id
    HAVING COUNT(*) &gt;= 2
    ORDER BY user_id
    LIMIT #{limit}
</select>

<!-- 该用户的小额有效桶，加锁重读（合并事务第一步；并发双跑在此串行） -->
<select id="selectSmallBucketsForUpdate"
        resultType="com.betterschool.marketing.infrastructure.points.dao.po.PointsBucketPO">
    SELECT id, bucket_id, user_id, amount, remain, expire_at, reason_type, ref_id, create_time, update_time
    FROM points_bucket
    WHERE user_id = #{userId} AND remain &gt; 0 AND expire_at &gt; NOW() AND remain &lt;= 10
    ORDER BY expire_at ASC, id ASC
    FOR UPDATE
</select>
```

- 新 PO：`PointsMergeGroupPO { userId, bucketCount, total }`（放 `infrastructure/points/dao/po`）。
- 清零复用现有 `drainBuckets(userId, bucketIds)`（语义完全一致：按 user+bucketIds 置零并校验行数）。
- 插新桶复用现有 `insertIgnoreBucket`。

### B4. `PointsMapper.xml` 流水过滤（合并对用户不可见）

`selectTransactions` 的 bucket 分支与 `countTransactions` 的 bucket 子查询各加一行：

```sql
WHERE user_id = #{userId} AND reason_type != 'COMPACTION'
```

（points_detail 分支不动——明细表没有 COMPACTION。）同时更新两段 SQL 上方注释：
合并是账务整形、余额不变，不算积分变动。

### B5. 仓储与域服务

- `IPointsRepository` 新增：`int compactSmallBuckets();`（返回本轮合并的桶数）。
- `PointsRepository` 实现（`TransactionTemplate` 模式照抄 `expireBuckets` 旧三步的写法）：
  1. `selectMergeCandidateGroups(limit=100)` 取候选用户批；
  2. 对每个用户（整体包在一个事务里或逐用户一事务均可，**推荐逐用户一事务**，互不拖累）：
     `selectSmallBucketsForUpdate(userId)` 加锁重读 → 少于 2 张跳过 →
     造新桶（`bucketId = idGenerator.nextId()`，`amount = remain = SUM`，
     `expireAt = max(各桶)`，`reasonType = COMPACTION`，`refId = idGenerator.nextId()` 合并批次号，
     走现有 `insertBucket` 私有方法）→ `drainBuckets(userId, 旧桶Id列表)` 校验影响行数 =
     旧桶数，不符抛 `IllegalStateException` 整笔回滚 → 累计计数。
  3. 返回本次实际清零（=合并）的旧桶总数。
- `IPointsAccountService` / `PointsAccountServiceImpl` 新增 `compactSmallBuckets()` 透传
  （模式照抄 `expireBuckets`）。
- **不改 `PointsDeductPlanVO` / 消费逻辑**：合并桶就是普通桶，FIFO 天然覆盖。

### B6. 定时任务

新文件 `marketing-trigger/.../points/job/PointsBucketCompactionJob.java`，模式照抄
`PointsExpireScanJob`：

```java
@XxlJob("pointsBucketCompaction")
public String execute(String param) {
    int merged = pointsAccountService.compactSmallBuckets();
    if (merged > 0) {
        log.info("[PointsBucketCompactionJob] 合并小额桶 {} 张", merged);
    }
    return "success";
}
```

cron 由调度中心配，建议每 10 分钟一次（合并不紧急，且与每分钟的过期扫描错峰）。

### B7. 文档

- `docs/db/points.md`：
  - 恢复票 01 前的"没有余额列"表述；
  - 新增「桶合并（points-compaction 票）」小节：对象（remain≤10 且 ≥2 张）、新桶口径
    （amount=remain=SUM、expire_at=**max**——等于把小桶有效期延长到最晚到期，产品决策）、
    旧桶归零不删（发放流水留审计）、单事务守卫、并发靠桶行锁串行、
    流水对用户不可见（COMPACTION 过滤）、`uk_user_reason_ref` 的 ref_id 用合并批次号；
  - reason_type 枚举说明补 `COMPACTION`。
- `docs/api/points.md` transactions 段：补"COMPACTION 行不出现在用户流水（余额不变的账务整形）"。
- `Z:/better_school/CONTEXT.md` 积分桶条目：回退 wallet 句，补一句
  「碎片桶由每 10 分钟的合并任务收敛（remain≤10 且 ≥2 张并成一桶，过期时间取合并集最大值，
  原桶归零留审计）」。
- 简历两份（`简历-项目经历.md` / `简历-Java后端.md`）：恢复"无余额列、余额 = 未过期桶剩余和"
  口径，追加合并任务卖点：`碎片桶由定时合并任务收敛（小桶并一桶、过期取最大值、旧桶归零留审计、
  单事务守卫），SUM 读的工作集不随账户年龄膨胀`。资产参数行把
  "余额读 wallet 汇总行 O(1)…"改回"余额 = 未过期桶剩余和"并加"小额桶定时合并"。

### B8. 测试适配（只改不执行）

- **删除** `PointsWalletIntegrationTest`（wallet 专项，随票 01 拆除）。
- `PointsApiTestBase`：`insertBucket` 删掉 wallet upsert 段；删除 `walletTotalOf` /
  `assertWalletConsistentWithBuckets` 两个 helper；truncate 列表去掉 `points_balance`。
- `RaffleApiTestBase.giveChances`：删掉 wallet upsert 段；truncate 列表去掉 `draw_count_balance`
  与 `points_balance`。
- `PointsExpireIntegrationTest`：恢复懒过期断言——第一个用例 `getBalance == 30`
  （过期桶被读路径滤掉，DisplayName 恢复"懒过期：过期桶不计入余额"）；删除 wallet 相关断言。
- `DrawCountBucketIntegrationTest`：truncate 去掉 `draw_count_balance`；删除类内
  `walletTotalOf` / `assertWalletConsistentWithBuckets` helper 与 `walletView_...` 用例；
  `expireScan_zeroesExpiredBuckets` 的 `getBalance` 断言恢复为"读路径懒过期 = 1"。
- `PointsDeductIntegrationTest` / `PointsLotteryWinIntegrationTest` / `PointsRebateIntegrationTest` /
  `RaffleQueryIntegrationTest`：无需改动（它们的断言都走 SUM 口径）。
- **新增** `PointsBucketCompactionIntegrationTest`（建议断言清单）：
  1. 铺 3 张 remain=5、expire_at 递增的桶 → `compactSmallBuckets()` 返回 3 →
     桶数变为 1（新桶）+3（归零旧行，不删）= 4 行，其中有效桶 1 张、remain=15、
     expire_at = 三者最大值；`getBalance` 仍为 15（守恒）。
  2. 单张小桶（remain=5 只 1 张）→ 返回 0，不动。
  3. 大桶（remain=20 > 阈值）不参与合并。
  4. 合并后 FIFO：新桶 expire_at = max，扣减仍先扣其它更早过期的桶。
  5. 流水接口/`transactionsOf` 不出现 COMPACTION 行；`countTransactions` 同口径。
  6. 过期桶不参与合并（铺 1 张过期小桶 + 2 张有效小桶 → 只合并 2 张有效桶）。
  7. 并发双跑幂等（可选，参照 `PointsDeductIntegrationTest` 的双线程写法）。

### B9. 明确不做

- 次数侧不做合并（桶面额大、桶数少；如需扩展按 B1–B6 同构复制，阈值与 reason_type 单列）。
- 不引入合并配置表：阈值先放 `Constants`（校园规模够用；要后台可调再升格为单行配置表）。
- 不迁移存量数据（项目惯例：删库重建）。

## 3. 完成后的验证（留给以后，本次不跑）

- `scripts/test-env.sh reset-test-db` **需要**（本次有 DDL 变更：删两张 wallet 表），
  然后跑 points/drawcount 全部集成测试 + 新增的合并测试。
- 手工验一条：连签到几天攒 3 张小桶后等合并任务跑一轮，确认余额不变、桶数变 1、
  流水里看不到合并记录。
