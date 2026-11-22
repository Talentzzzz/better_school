# 积分模块接口（/api/points）

积分服务对外**只有两个只读 HTTP 接口、零写接口**——兑换的两个写接口挂在抽奖服务上
（前端打抽奖 → 抽奖同步 Dubbo 调 `IPointsExchangeService` 扣分）。
两个 GET 都需登录（本服务 `SecurityConfig` 白名单为空）。

分页一律 `page`/`pageSize`（默认 1/10、上限 50），返回 `PageResult{page,pageSize,total,list}`。

## GET /balance

- Auth: 需要登录
- 成功: `{ balance }`，`balance` = 未过期且未扣完的桶 remain 之和（**没有余额列**，
  `SUM(remain) WHERE remain > 0 AND expire_at > NOW()`，读路径懒过期）
- 流程图：Controller → `IPointsAccountService.getBalance` → `IPointsDao.sumAvailableRemain`

## GET /transactions

- Auth: 需要登录
- 成功: `PageResult<{ id, direction, amount, reasonType, refId, createdAt, expireAt }>`，`created_at DESC`
  - `direction`：`IN`（入账，来自 `points_bucket`）/ `OUT`（消费，来自 `points_detail`）
  - **`COMPACTION` 行不出现在用户流水**：桶合并是余额不变的账务整形（`SUM(remain)` 前后守恒），
    不是积分变动——桶分支按 `reason_type != 'COMPACTION'` 过滤（`total` 同口径）
  - 两表在 SQL 里 `UNION ALL` 后统一排序分页；`id` 是各自表内自增，列表项唯一性靠 `(direction, id)`
  - 一笔兑换跨几个桶 = 几行 `OUT`（保留每桶被扣掉的量）
  - `expireAt` 仅 `IN` 行有值

---

# 兑换边（Dubbo，不是 HTTP）

## IPointsExchangeService.deduct(PointsDeductRequestDTO)

Provider：`marketing-trigger/.../trigger/points/rpc/PointsExchangeDubboService`（`@DubboService`）；Consumer：抽奖服务。
请求 `{ userId, amount, reasonType, refId }`，`reasonType` 只接受 `REDEEM_DRAW_COUNT` / `REDEEM_PRIZE`，
**`refId` = 兑换单 id = 幂等键**。

- 成功：`{ success: true, code: "200", info: "成功", balance }`（`balance` = 扣后余额）
- 积分不足：`{ success: false, code: "4101", info: "积分不足", balance }` —— **不抛异常**，
  由抽奖侧映射成自己的 `LOTTERY_INSUFFICIENT_POINTS`（4306）返回前端
- 同一个 `refId` 重复调用（抽奖侧重试）：幂等跳过，返回当前余额，不重复扣分、不重复发事件
- 跨桶按 FIFO 扣：先扣 `expire_at` 最早的桶，扣完再扣下一个；**凑不够则一行都不动**
  - 扣减分两段：先在内存里算出方案（**被扣光的桶**若干 + **至多一张部分扣的桶**，纯内存、不碰库），
    再照方案落库——**至多三条语句**（一条多值 `INSERT IGNORE` 写全部明细、一条把扣光的桶清零、
    一条扣那张部分扣的桶），落库语句数与桶数无关：跨多个桶是三条，跨 1 个桶时后两条退化为单行更新
    （扣光集合为空或没有部分扣的桶时，对应那条不执行，整笔两条）。「部分扣的桶最多一张」是 FIFO 的性质：
    某个桶的 remain 大于还差的额度时，扣掉差额后剩余归零、循环即止
  - 三条语句各校验影响行数：明细的影响行数必须等于明细条数、清零的影响行数必须等于扣光集合大小，
    任一不等即整笔回滚（明细撞 `uk_bucket_reason_ref` 表现为行数偏少 → 判为重复兑换，
    回滚后事务外重读余额按成功回报）

流程图：

```
抽奖服务                     积分服务
 插兑换单(PENDING) ──┐
   Dubbo deduct ─────┼─→ 事务：幂等预检(消费明细，快照读)
                     │        → 取可用桶 FOR UPDATE（FIFO）
                     │        → 内存算方案：扣光的桶 + 至多一张部分扣的桶（不碰库）
                     │        → 照方案落库，至多三条语句：①多值写全部明细 ②清零扣光的桶 ③扣部分扣的桶
                     │        → 发 bs-points-changed（本服务 outbox）
                     └─← 结果对象（success / 4101 积分不足）
 本地事务(加次数/落奖品)
 置兑换单 DONE
```

## 事件边（异步）

| topic | 方向 | 事件体 | 说明 |
| --- | --- | --- | --- |
| `bs-lottery-drawn` | 消费 | `{recordId,userId,prizeId,prizeType,pointsAmount}` | 消费组 `bs-points-lottery-drawn-group`；**只有 `prizeType = POINTS` 才加分**，谢谢参与/实物档忽略 |
| `bs-points-changed` | 生产 | `{userId,delta,balanceAfter,reasonType,refId}` | 走本服务 outbox + `@XxlJob("outboxScan")`（`MAX_RETRY_COUNT = 5`，死信 `status = 2`）。`reasonType` 入账为 `LOTTERY_WIN` / `REBATE`（行为返利），出账为 `REDEEM_DRAW_COUNT` / `REDEEM_PRIZE` |

## 行为返利入账（域内能力，暂无 MQ/HTTP 入口）

`IPointsAccountService.creditForRebate(actionType, userId, refId)`：按
`action_rebate_config` 的 POINTS 行（`state=open`）发放，落一行 `REBATE` 桶并发变动事件；
规则未配/关闭返回 false。幂等键 = `(userId, REBATE, refId)`，refId 是调用方的发放幂等键
（签到/支付等业务接入时自定）。规则管理见 [config.md](config.md)。

## 定时任务

| 任务 | handler | 作用 |
| --- | --- | --- |
| 桶合并 | `pointsBucketCompaction` | 同用户小额桶（remain ≤ 10 且 ≥ 2 张）并成一桶：`expire_at` 取合并集最大值、旧桶归零留审计、余额守恒；每 10 分钟，对用户不可见 |
| outbox 兜底扫描 | `outboxScan` | 重发 PENDING 消息 |

> 过期没有定时任务：过期是读路径懒判（`expire_at > NOW()`），过期行不删不置零。
> 曾有的每分钟清零（`pointsExpireScan`）已下线——只写不读，且谓词走不了索引前缀，净负收益。
