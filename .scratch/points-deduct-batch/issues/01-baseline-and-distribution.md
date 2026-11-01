# 01: 前置读数：扣减测试集基线全绿 + 消费明细分布实测

**What to build:** 一份改造前的读数，用来决定「这件事到底值不值得做」。

两件事：一是起好常驻测试栈，把营销域积分侧现有的扣减用例逐条跑一遍，记录命令与结果，作为后续每一步的对照基线；二是按（用户，原因，业务 ID）分组统计消费明细的行数分布，回答 spec 里那个前提问题——如果 95% 以上集中在 1 行，说明积分桶并没有预期地碎，「事务语句数随桶数增长」这条收益就不成立，应当停下来重新评估优先级，而不是继续往下做。

此票不改任何代码。结论写在票尾的 Comments 里，后续票只引用它、不重新测量。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 常驻测试栈已起（容器与测试库就绪），积分侧现有扣减用例逐条跑绿，命令、通过/失败清单与耗时记进本票的 Comments
- [x] 消费明细按（用户，原因，业务 ID）分组的行数分布有数：1 行 / 2–3 行 / 4–10 行 / 10 行以上 各占多少
- [x] 写明结论：「收益前提成立（桶确实会碎）」或「不成立（95% 以上为 1 行，建议停手重排优先级）」
- [x] 无代码改动，无表结构改动，未改动任何测试断言

## Comments

### 2026-10-18 · 基线 + 分布实测（agent）

#### 1. 环境：常驻测试栈

```bash
cd backend && docker compose up -d      # 11 个容器，全部 Up；mysql/redis/minio/nacos/namesrv/es healthy
```

- 起栈 wall **18s**；`bs-mysql` healthy，宿主端口 3306（开发）/ 13306（测试）都在。
- 测试库 `bs_marketing_test` 已存在且与 `marketing/sql/schema.sql` 一致（20 张表，`points_bucket` / `points_detail` 就位）。
- 口径依照 `backend/CLAUDE.md` §10：测试进程只连库，不建任何容器；没起栈就是连接失败，不加兜底。

#### 2. 基线：积分侧用例逐条结果（改造前）

工作目录 `backend/marketing`，离线构建。`-Dsurefire.failIfNoSpecifiedTests=false` 是必须的——这个工程是多模块，
其余模块没有匹配的测试类，不加这个开关会在 `marketing-types` 阶段直接失败（首跑踩过一次，不是测试红）。

```bash
mvn -o test -Dtest=PointsDeductIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

**5/5 绿**，类耗时 11.86s（其中 Spring 上下文 ~10s，首跑冷启动 wall 35s）。

| 用例 | 结果 | 耗时(s) |
| --- | --- | --- |
| `deduct_spansBucketsInFifoOrder`（跨桶 FIFO） | ✅ | 0.21 |
| `deduct_insufficientPoints_changesNothing`（积分不足） | ✅ | 0.24 |
| `deduct_sameRedeemOrderId_isIdempotent`（同单号重放） | ✅ | 0.25 |
| `deduct_withNonRedeemReason_isRejected`（非兑换原因） | ✅ | 1.22 |
| `deduct_ignoresExpiredBuckets`（跳过过期桶） | ✅ | 0.21 |

积分域其余三类一起跑（同一命令换 `-Dtest=` 列表）：

```bash
mvn -o test -Dtest='PointsDeductIntegrationTest,PointsExpireIntegrationTest,PointsLotteryWinIntegrationTest,PointsRebateIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

| 类 | 用例数 | 结果 | 类耗时(s) |
| --- | --- | --- | --- |
| `PointsDeductIntegrationTest` | 5 | ✅ 全绿 | 11.86 |
| `PointsExpireIntegrationTest` | 2 | ✅ 全绿 | 0.79 |
| `PointsLotteryWinIntegrationTest` | 4 | ✅ 全绿 | 0.80 |
| `PointsRebateIntegrationTest` | 4 | ✅ 全绿 | 1.01 |

合计 **15 tests, 0 failures, 0 errors, 0 skipped**，wall **26.2s**。这就是后续每一票的对照基线。

#### 3. 消费明细分组行数分布（实测）

**数据来源必须先说清**：本机没有任何生产数据——`bs_marketing.points_detail` 与 `bs_marketing_test.points_detail`
实测都是 **0 行**（两边 `points_bucket` 也是 0）。所以「实测」只能是**按仓内已配置的参数重放一年流水**，
再把重放出的行灌进 scratch 库，用真 SQL 做 `GROUP BY` —— 报出来的每个百分比都来自查询结果，不是脚本自报。

- 探针与原始输出：`.scratch/points-deduct-batch/probe/measure_distribution.py` / `measurement.txt`
- 三份 scratch 库（`points_probe_a/b/c`）测完即 DROP；脚本重跑一次约 **20s** 即可复现（含灌库与查询）。
- 重放规则逐条对齐实现，没有自创语义：

| 参数 | 值 | 依据 |
| --- | --- | --- |
| 兑换价 | 100 分/次 | `marketing-types` raffle `Constants.DRAW_COUNT_EXCHANGE_PRICE` |
| 积分有效期 | 365 天 | `points_validity_config` 种子（桶在观测窗口内不会过期） |
| 签到返利 | 20 分/天 | `action_rebate_config` 种子（`sign → POINTS → 20`） |
| 中奖积分面额 | 5 / 20 分 | 文档与用例里出现过的 `virtual_inventory_quota.value` |
| FIFO 扣减 | `remain > 0 AND expire_at > NOW() ORDER BY expire_at, id`，逐桶 `take = min(remain, remaining)` | `PointsRepository.deduct` 循环照抄 |

三个场景 × 300 用户 × 365 天（人均 126–154 张券，单人最多 389 张）：

| 场景 | 1 行 | 2–3 行 | 4–10 行 | 10 行以上 | 平均行数/组 | 最大 |
| --- | --- | --- | --- | --- | --- | --- |
| **A 仓内文档口径**：签到 20 + 中奖 5/20 | **0%** | **0%** | **100%**（7557 组） | 0% | 5.74 | 8 |
| **B 大额中奖**：中奖 100/200 | 22.41%（2964 组） | 24.24% | 53.35% | 0% | 3.40 | 5 |
| **C 纯签到**：不抽奖 | **0%** | **0%** | **100%**（7161 组） | 0% | 5.00 | 5 |

**行数真正的驱动因素**（固定兑换价 100，只改入账面额的灵敏度扫描，同一重放引擎）：

| 单桶面额 | 平均行数/次兑换 | 分布 |
| --- | --- | --- |
| 5 | 20.00 | 10 行以上 100% |
| 10 | 10.00 | 4–10 行 100% |
| 20 | 5.00 | 4–10 行 100% |
| 50 | 2.00 | 2–3 行 100% |
| 100 | 1.00 | 1 行 100% |

即 **行数 ≈ 兑换价 ÷ 单桶面额**（`100/20 = 5`，与场景 C 的 5.00 精确吻合；场景 A 因为混入 5 分桶抬到 5.74）。
它**不随用户攒了多少张券增长**：重放里人均 136.7 张、最多 348 张，每次兑换仍然只碰最老的那 5～6 张——
FIFO 只从最老一端取，取到够数就停。

#### 4. 结论

**收益前提成立：桶确实会碎，1 行占比是 0%，不是 95%。** 可以按 spec 往下做。

但有两处需要按实测修正 spec 的说法，避免后续票按错的量级估算收益：

1. **量级修正**：spec 写「跨十几张券属于常态」，实测是 **4–10 行（平均 5.74，上限 8）**。按当前扣减形状
   `2N+4`，N=5.74 → **约 15.5 条语句**；改造后 6～7 条，**约减半（≈55%）**，不是「50 → 7」那种量级。
   收益仍然成立（锁持有时间与语句数同比例缩短，最坏情况从「随桶数线性」变成「常数」），
   但预期值请按「减半」而不是「降一个数量级」写进后续票的动机。
2. **因果修正**：让行数变多的不是「用户攒了几十张券」，而是 **兑换价 ÷ 单桶面额** 这个比值。
   用户桶数再多，FIFO 也只碰最老的前几张。所以：
   - 桶会碎的前提**依赖入账面额 < 100 分**（100 是抽奖次数兑换价）。面额 20 → 5 张；面额 100 → 1 张。
   - 反向条件已量过：中奖面额上到 100/200 时，1 行占比升到 22.41%（场景 B），仍远低于 95%；
     只有面额普遍 ≥100 才会退化成「1 行占绝对多数」。

**留给下一位的一件事（不影响本票结论，但影响收益是否被高估）**：仓内 `creditForRebate`（签到/支付返利）
目前**只有测试在调，没有生产调用方**；也就是说线上真正在入账的只有「中奖积分档」，
其面额来自 `virtual_inventory_quota.value`，而这个值**在仓内没有任何生产种子**（测试里出现过 5 和 20）。
建议在生产只读副本上跑一次下面这段，确认面额分布；若面额普遍 ≥100，回到本票把收益预期下调。

```sql
-- 行数分布：消费明细按（用户，原因，业务 ID）分组
SELECT CASE WHEN rows_per_group = 1 THEN '1 行'
            WHEN rows_per_group <= 3 THEN '2-3 行'
            WHEN rows_per_group <= 10 THEN '4-10 行'
            ELSE '10 行以上' END AS 分组行数,
       COUNT(*) AS 组数,
       ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) AS 占比
FROM (SELECT user_id, reason_type, ref_id, COUNT(*) AS rows_per_group
      FROM points_detail GROUP BY user_id, reason_type, ref_id) g
GROUP BY 分组行数
ORDER BY FIELD(分组行数, '1 行', '2-3 行', '4-10 行', '10 行以上');

-- 入账面额分布（真正决定行数的变量）
SELECT amount AS 单桶面额, COUNT(*) AS 桶数,
       ROUND(COUNT(*) * 100.0 / SUM(COUNT(*)) OVER (), 2) AS 占比
FROM points_bucket GROUP BY amount ORDER BY 桶数 DESC LIMIT 20;
```

#### 5. 本票边界

- 产品代码 **0 行改动**，`schema.sql` **0 改动**，既有测试断言 **0 改动**。`git status` 里 backend / front / docs
  下的改动都是本票开工前就有的（未动过），本票新增的只有 `.scratch/points-deduct-batch/` 这一处。
- 唯一新增物是 `.scratch/points-deduct-batch/probe/` 下的探针脚本与读数——是读数工具，不进产品代码。
- 探针用的三个 scratch 库测完已 DROP，`bs_marketing` / `bs_marketing_test` 未被写入任何探针数据。
  （跑完测试后 `bs_marketing_test` 里会留最后一条用例的 1 行 `points_bucket`——基类在**每个用例前** TRUNCATE，
  这是正常残留，不是探针写的，也不影响读数。）
- 后续票只引用本票数字，不重新测量。

