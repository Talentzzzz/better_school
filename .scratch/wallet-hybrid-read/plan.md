# wallet-read-view 票 02：余额混合读（消展示窗口）

> 交接说明：本计划自包含，供新窗口/新会话直接执行。执行后**不需要跑测试**（用户已确认），
> 但测试代码要按第 5 节适配到位。

## 1. 背景与目标

wallet-read-view 票 01 给积分/次数加了 wallet 汇总行（`points_balance` / `draw_count_balance`，
每用户一行，写路径同事务相对增减）。现状的读口径是 `wallet.total`（= 全部桶行 SUM(remain)，
**含已过期未清零的量**），于是桶过期后到下一个扫描周期之间存在一个 ≤ 扫描间隔的展示窗口：
读数可能大于实际可用余额。

本票把读口径改成**混合读**：

```
读数 = wallet.total − 已过期未清零量（该用户 remain>0 且 expire_at<=NOW() 的桶之和）
```

恒等变形后读数 ≡ `SUM(remain) WHERE remain>0 AND expire_at>NOW()`，与**扣减口径完全一致**，
展示窗口归零。wallet 单行仍是 O(1) 主体，尾巴子查询走 `idx_user_expire` 索引、绝大多数时刻
命中 0 行，代价近乎零。

定位变化：扫描任务（`pointsExpireScan` / `drawCountBucketExpireScan`）从"影响读数的收口步骤"
降级为**纯账务收拾**（清零 + 留审计），跑得快慢不再影响任何读数——从关键路径上摘出去。

## 2. 改动点 1：`PointsBalanceMapper.xml` 的 selectTotal

文件：`backend/marketing/marketing-app/src/main/resources/mybatis/mapper/PointsBalanceMapper.xml`

把现有：

```xml
<!-- 余额读：无行视为 0（该用户从未入账） -->
<select id="selectTotal" resultType="int">
    SELECT COALESCE((SELECT total FROM points_balance WHERE user_id = #{userId}), 0)
</select>
```

改为：

```xml
<!--
  余额读 = wallet 汇总行 − 已过期未清零量（wallet-read-view 票 02 混合读）。
  主干是 uk_user 主键级单行读；尾巴子查询走 idx_user_expire（user_id, expire_at）索引范围，
  只在"桶刚过期、扫描还没跑到"的窗口内命中非 0 行，通常为 0 行、代价近乎零。
  混合后读数恒等于扣减口径 SUM(remain) WHERE remain>0 AND expire_at>NOW()，
  与 PointsMapper.sumAvailableRemain 同值——展示口径与资金口径一致，无窗口。
  无 wallet 行视为 0（该用户从未入账；桶侧有量而无 wallet 行属不变量破坏，由对账暴露）。
-->
<select id="selectTotal" resultType="int">
    SELECT COALESCE((SELECT total FROM points_balance WHERE user_id = #{userId}), 0)
         - COALESCE((SELECT SUM(remain) FROM points_bucket
                     WHERE user_id = #{userId} AND remain &gt; 0 AND expire_at &lt;= NOW()), 0)
</select>
```

## 3. 改动点 2：`DrawCountBalanceMapper.xml` 的 selectTotal

文件：`backend/marketing/marketing-app/src/main/resources/mybatis/mapper/DrawCountBalanceMapper.xml`

同构改法（事实源表换 `draw_count_bucket`，守卫条件与积分侧逐列一致）：

```xml
<!--
  剩余次数读 = wallet 汇总行 − 已过期未清零量（wallet-read-view 票 02 混合读，与积分侧同构）。
  混合后与 DrawCountMapper.sumAvailableRemain 同值，无展示窗口；无 wallet 行视为 0。
-->
<select id="selectTotal" resultType="int">
    SELECT COALESCE((SELECT total FROM draw_count_balance WHERE user_id = #{userId}), 0)
         - COALESCE((SELECT SUM(remain) FROM draw_count_bucket
                     WHERE user_id = #{userId} AND remain &gt; 0 AND expire_at &lt;= NOW()), 0)
</select>
```

## 4. 改动点 3：文档口径更新（三处）

**4a. `backend/marketing/docs/db/points.md`**

- `points_balance` 一节「写路径契约」下新增一条：
  `- 余额读（selectTotal）为**混合读**（票 02）：wallet.total − 已过期未清零量，恒等于扣减口径
   SUM(remain) WHERE remain>0 AND expire_at>NOW()；wallet 行缺失视为 0`
- 「与读语义的变化」一节改写：原"窗口 ≤ 扫描间隔"描述替换为——
  `票 02 混合读后展示窗口归零：读数与扣减口径同值。扫描任务降级为纯账务收拾（清零 + 留审计），
   其快慢不影响任何读数。不变量 wallet.total = 全部桶行 SUM(remain) 仍由写路径维护不变。`

**4b. `backend/marketing/docs/api/points.md`** 的 `GET /balance` 段

把"窗口语义"一条改为：`混合读（票 02）：wallet 汇总行 − 已过期未清零量，读数恒等于可用余额，
无展示窗口；扫描任务只做账务收拾。流程图的 selectTotal 描述补"（混合读）"。

**4c. `Z:/better_school/CONTEXT.md`** 积分桶条目

把「读数含『已过期未落账』的窗口 ≤ 扫描间隔」一句改为：
`余额读为混合读（票 02）：wallet 汇总行 − 已过期未清零量，读数恒等于可用余额（扣减口径），
无展示窗口；扫描任务只做账务收拾。`

注意：`sql.md` 与 `sql/schema.sql` **不动**（无 DDL 变更）。

## 5. 改动点 4：测试适配（改代码，不执行）

混合读后 `getBalance` 的读数从"wallet 原值"变为"精确可用余额"，两处窗口断言要翻转：

**5a. `PointsExpireIntegrationTest`**

- 第一个用例 `expiredBucket_staysInWalletUntilScan_andStaysInTransactions`：
  `assertThat(pointsAccountService.getBalance(USER_ID)).isEqualTo(80)` 与 HTTP 断言 80
  → 改为断言 **30**（读数已精确剔除过期量），DisplayName 改为
  「混合读：过期量不进余额读数，wallet 行原值不动（留待扫描落账）」；
  并补一行 `assertThat(walletTotalOf(USER_ID)).as("wallet 行原值仍含未落账过期量").isEqualTo(80);`
  （walletTotalOf 是基类直查 wallet 表的 helper，与 getBalance 读数区分开）。
- 第二个用例 `expireScan_zeroesRemainWithoutDeletingRowOrEmittingEvent`：断言不变
  （清零后 wallet=30、读数=30、`assertWalletConsistentWithBuckets` 仍成立）。

**5b. `DrawCountBucketIntegrationTest`**

- `expireScan_zeroesExpiredBuckets` 用例：
  `getBalance ... "读视图窗口：落账前含过期量（2+1）" ... isEqualTo(3)` → 改为 `isEqualTo(1)`
  （DisplayName 改「混合读：过期量不进读数」），并在其后补
  `assertThat(walletTotalOf(USER_ID)).isEqualTo(3);`（wallet 行原值不动，需在本测试类补一个
  `walletTotalOf` 直查 helper——已有 `assertWalletConsistentWithBuckets`，仿照它写即可）。
- 扫描后 `getBalance == 1`、扣次后 `== 0` 的断言不变。

其余测试（`PointsWalletIntegrationTest`、`PointsLotteryWin/Rebate/Deduct`、`RaffleQuery` 等）
不受影响：它们的场景里没有"过期未落账"的桶，混合读与 wallet 原值恒相等。

## 6. 明确不做（边界）

- **不改 DDL**：`sql/schema.sql`、`sql.md` 不动；无新表新列。
- **不改写路径**：入账/消费/过期扫描三路径的 wallet 维护逻辑原样保留（恒等式
  `wallet.total = 全部桶行 SUM(remain)` 仍是写路径契约与对账基准）。
- **不删扫描任务**：职责降级为账务收拾（清零、留审计、wallet 收口），仍需继续跑；
  `PointsExpireScanJob` / `DrawCountBucketExpireScanJob` 及 xxl-job 配置不动。
- `sumAvailableRemain` 的角色不变（消费不足判断、事件 balanceAfter、对账基准）；
  混合读后它与 `selectTotal` 数值恒等，但保留两个方法（口径文档化 + 未来可能分叉）。

## 7. 完成后的验证（留给以后跑，本次不执行）

- `scripts/test-env.sh reset-test-db` 无需执行（无 DDL 变更）。
- 跑 `marketing-app` 的集成测试：`PointsExpireIntegrationTest`、`PointsWalletIntegrationTest`、
  `DrawCountBucketIntegrationTest`、`PointsDeductIntegrationTest`、`RaffleQueryIntegrationTest`。
- 手工验证一条混合读：插入一个过期桶（wallet 已由 helper 同步加过 remain），调
  `getBalance` 应立即返回不含过期量的值，而 `points_balance.total` 仍是原值。

## 8. 简历口径（可选微调）

混合读后简历句不必改——"余额读走每用户一行的 wallet 汇总行 O(1)"仍成立（主干）。
若想把这个卖点带出来，可在面试口头补一句："读是 wallet 单行加一个通常为空的过期量尾巴子查询，
展示窗口归零，扫描任务降级成纯账务收拾。"
