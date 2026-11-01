# 切换记录：cutover-<日期>（复制本模板落档）

> 日期：____　执行：____　复核：____　批次：【A】bs D0 /【B】marketing D2（各复制一份）
> 本文件是**回滚与「三列守恒」的唯一凭据**——第 1 步的初值快照必须先落 here，后面的步骤才有对照。

## 1. 备份文件名（第 1 步）

- better_school：`backup-bs-________.sql`（结尾有 `Dump completed`：是 / 否）
- bs_marketing：`backup-mkt-________.sql`（结尾有 `Dump completed`：是 / 否）

## 2. 初值快照（第 1 步，原样粘贴）

### 2.1 实物台账三列（逐 inv）

```sql
SELECT inv_id, surplus_qty, waiting_qty, issued_qty FROM physical_marketing_quota ORDER BY inv_id;
```

| inv_id | surplus_qty | waiting_qty | issued_qty |
| --- | --- | --- | --- |
|  |  |  |  |

### 2.2 虚拟台账（逐 inv）

```sql
SELECT inv_id, surplus_qty, issued_qty FROM virtual_inventory_quota ORDER BY inv_id;
```

| inv_id | surplus_qty | issued_qty |
| --- | --- | --- |
|  |  |  |

### 2.3 点赞合计（better_school；旧链路滞后，此时可以不等，切换后必须相等）

```sql
SELECT (SELECT SUM(like_count) FROM post_like_count) AS 窄表合计, (SELECT COUNT(*) FROM post_like) AS 关系行数;
```

- 窄表合计 = ____　关系行数 = ____

## 3. 逐步验证勾选（8 步）

- [ ] 第 2 步：退出码 0，`@PreDestroy` 收尾日志在，端口不再监听
- [ ] 第 3 步【A】：`SUM(like_count) == COUNT(post_like)`（切换后值：____ = ____）；评论断言 0 行
- [ ] 第 3 步【B】：`physical_pending = 0` / `virtual_pending = 0` / `waiting_negative = 0`
- [ ] 第 4 步【B】：`--scan` 复跑为 0；差额表已填（见下）
- [ ] 第 5 步【B】：`inventoryMergeBackstop` / `inventoryBucketWarmup` 均 `trigger_status = 0`
- [ ] 第 6 步【B】：两处 `SHOW CREATE TABLE` 均 `DEFAULT '1'`；没删列、没删表
- [ ] 第 7 步：健康检查 200；无 `Unknown column` / `Table doesn't exist`；【B】handler 恰 4 个
      （`outboxScan` / `pointsExpireScan` / `drawCountBucketExpireScan` / `raffleCacheWarmup`）；
      ≥2 分钟无「找不到执行器任务」；日志无 `PostCountAggregator` / `bs-inventory-merge`
- [ ] 第 8 步：D1 守恒 SQL 0 行；D2 计数抽样逐行相等；链路演示（抽奖 / 确认 / 点赞）三个数都对

## 4. 差额表（第 4 步【B】，只记不修；没差额每行写 0）

> diff = 台账 surplus − Σ桶余量。非 0 就是真实泄漏，写一句诊断；台账是唯一真相，不改数。

| inv | 段 | 台账 surplus | 桶合计 | diff | 诊断 |
| --- | --- | --- | --- | --- | --- |
|  |  |  |  |  |  |

## 5. D3 观测读数（第 8 步，第一次观测基线）

- `BUFFER_FULL` 降级次数：____（期望 0）
- `drainFailed`：____（期望 0）
- `flush 次数 / 请求数`：____ / ____（合并比基线）
- `timedOut`：____（非 0 先看 `maxFlushNanos` 与 `waitTimeoutMs` 的关系）

## 6. 结论

- [ ] 抽样全平，进入观察期（D+1 晚高峰后再抽样一次 → 下一切批）
- [ ] 触发回滚（勾选则回填：触发条件 = ____，回滚四步完成时间 = ____）
