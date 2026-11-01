# 08: 领单 + 租约回收 + 放回

**Spec:** `.scratch/errand-lock-review-hub/spec.md` §5.2（工单状态机）、§5.3「claim」「租约与回收」

**What to build:** 审核员一次只拉一条，拉到手上就有租约、到期自动回收。领取是**原子指派**：先按「进入待审时刻」先到先选取一条候选（可带类型过滤，类型只作过滤器不做优先级插队），再用条件 UPDATE 抢占——影响行数为 1 者赢，并发下只有一个人拿得到；`claim_seq+1` 记领单轮次。**不引 `SELECT ... FOR UPDATE SKIP LOCKED`、不把 Redis 锁放关键路径**（DB 权威）。

租约 = 一个「到点收口」动作，**复用既有超时中心**，不新建扫描 job：领单事务内注册 `REVIEW_LEASE_TIMEOUT`，`bizId={taskId}:{claimSeq}`（**必须复合**，理由同跑腿锁单：只用任务 id 时「回收 → 再领单」会撞上未取消的旧任务被静默忽略，导致租约永久失效），`action_time=领单时刻+5min`。回调按轮次守卫条件 UPDATE 回 `PENDING`、清持单人、`recycle_count+1`；命中 0 行（已裁决/已放回/已按新轮次重领）＝幂等成功零副作用。不调 `cancel`（记录为可选优化）。回收次数达阈值置「需人工干预」标记并告警。

配套两条兜底：领取前顺带把租约已过期的 `CLAIMED` 行惰性放回（一行 SQL，几乎免费，防中心回调延迟/丢失把工单卡死）；支持审核员主动**放回**（审不了就退池）。**不预取、不做类型挑选**。

**Blocked by:** 06

**Status:** done

- [x] 新增 taskType 常量 `REVIEW_LEASE_TIMEOUT`；领单事务内注册超时，`bizId={taskId}:{claimSeq}`、`action_time=领单时刻+5min`
- [x] 领单：先到先选取候选（支持类型过滤）+ 条件 UPDATE 抢占 + `claim_seq+1` + 起 5 分钟租约；并发下仅一人成功
- [x] 回调回收：按轮次守卫条件 UPDATE 回 `PENDING` 并清持单人，`recycle_count+1`；命中 0 行＝幂等成功
- [x] 回收不改「进入待审时刻」（等待时长继续累加）
- [x] 回收次数 ≥3 置需人工干预标记并告警
- [x] 主动放回接口：`CLAIMED→PENDING`（可带放回原因）
- [x] 领取前的惰性兜底：把租约已过期仍处 `CLAIMED` 的行放回池中
- [x] 一次只取一条（不预取）；不引 `SKIP LOCKED`；不把 Redis 锁放关键路径；不调超时中心取消
- [x] 集成测试：并发领单仅一人成功；租约到点自动回池且「进入待审时刻」不变；重领后旧轮次回调零副作用；惰性兜底在回调缺失时也能回收（`ReviewTaskLeaseIntegrationTest` 7 用例）
