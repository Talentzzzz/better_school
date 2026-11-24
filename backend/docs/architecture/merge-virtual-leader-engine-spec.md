# Spec：合并引擎专属虚拟线程 leader 改造（infrastructure.mergevirtual）

> 状态：**已落地**，其中「收集窗口」条款**已作废**
> 决策来源：2026-11-24 引擎讨论——在 per-invId leader 模型（`merge-per-invid-leader-engine-spec.md`）基础上，讨论「每个 group 创建时顺手 spawn 一根专属虚拟线程当 leader」，经三轮修正后拍板的结论。
>
> ⚠️ **修订（2026-11-24）**：本 spec 中「收集窗口同口径平移」条款**已作废**，由
> `merge-window-removal-spec.md` 取代——三处收集段（本包 + `merge` 根包 + `merge/count`）统一改为
> 「**队列非空即发**」，`maxFlushDelayMillis` 退役、「攒满提前发车」删除。spawn claim-CAS、supervisor、
> 退出谓词、停机三步全部沿用。

## Problem Statement

现行 `infrastructure.merge` 引擎用「请求线程客串 leader」：第一条到达的请求线程接管「收集 + 落库」，其余线程 park。这个模型靠一套**运行时领导权协议**维持——`hasLeader` 执行权标志、`tryPromote` 接管、`yieldLeadership` 交权、卸任点名接班、`WAKEUP_PROBE` 交接空档探测。它是整个引擎里并发推理最毛的部分：执行权在谁手里、谁会在什么时候醒来、点名落空怎么办，全要靠推演。

用户想要：每个 group 创建时顺手给一根**专属虚拟线程**当 leader，由它独占该组的「收集 + 落库」，把上面那套协议删掉。

## Solution

新包 `infrastructure.mergevirtual` 实现同语义引擎，与 `infrastructure.merge` 并存：commit 对外语义不变（提交 → 等终态，COMPLETED / FAILED 两态），调用方零改动地切换。

组创建时经 claim-CAS 恰好 spawn 一根专属虚拟 leader；leader 以 supervisor 形态循环「收集窗口攒批 → 一组一事务落库 → 逐件回填」，队列空则置死组、摘出 map、线程退出；任何 Throwable 被就地消化为整组回填 FAILED 并继续，线程只在组摘除后退出（退出谓词）。领导权协议整体删除——**leader 不退位，就不需要接班**。

## User Stories

1. As a 点赞用户, I want 我的点赞请求在落库完成后才返回, so that 我看到的结果与数据库真实状态一致
2. As a 点赞用户, I want 同一帖子的高并发点赞被合并成一次落库, so that 热帖计数写入不因行锁排队而超时
3. As a 秒杀用户, I want 我的库存预扣按 invId 排队合并, so that 结果精确反映我这一批的扣减事实
4. As a 秒杀用户, I want 同一 invId 任一时刻至多一条 flush 在途, so that 不出现两条事务并发写同一行
5. As a 引擎维护者, I want 每个 group 恰好一根专属虚拟 leader, so that 「谁在收集落库」由结构保证而不是协议推演
6. As a 引擎维护者, I want leader 循环整体罩在 catch(Throwable) 的 supervisor 里, so that 任何 Throwable（含 Error）被就地消化成整组 FAILED，线程不死
7. As a 引擎维护者, I want leader 只在组被摘除后退出（退出谓词）, so that 不存在「组活着但没人管」的逻辑死亡
8. As a 引擎维护者, I want spawn 经 claim-CAS 恰好一次, so that 不双 spawn（并发写同一行）也不漏 spawn（组僵死）
9. As a 引擎维护者, I want spawn 不发生在 computeIfAbsent 的 lambda 里, so that 不在 CHM 桶锁下跑外部代码
10. As a 引擎维护者, I want 创建者在 claim 与 start 之间死亡时由下一个提交者兜底 claim, so that 窗口自愈
11. As a 引擎维护者, I want 领导权协议（tryPromote / yieldLeadership / 卸任点名 / 交接探测）整体删除, so that commit 收敛为「入队 → park 等终态」
12. As a 引擎维护者, I want 组退化为「一把 ReentrantLock + 一个队列 + closed 标志 + 单 leader 线程驱动」, so that 并发推理面最小化
13. As a 引擎维护者, I want 乐观删除与换组重建闭环保留, so that 迟到入队者换新组、新组 spawn 新 leader
14. As a 领域服务开发者, I want commit / awaitIdle / pending / close 对外语义不变, so that 调用方切换零改动
15. As a 领域服务开发者, I want 两态出口 COMPLETED / FAILED 不变, so that 上层重试语义不变
16. As a 秒杀用户（重复提交）, I want 超时重放被唯一键吸收, so that 失败重试不双扣
17. As a 运维, I want 停机三步（停收 → 排空 → 残余回填 FAILED）保留, so that 滚动发布不丢已受理的写
18. As a 运维, I want 虚拟 leader 天生 daemon、close 无需 join, so that 停机不依赖后台线程 join
19. As a 运维, I want flush 并发度 = 活跃热点行数（与旧引擎相同）, so that 背压形状不变、上游仍经 tomcat 耗尽快速失败
20. As a 运维, I want 背压不加新闸门, so that 不引入新参数与调优面
21. As a DBA, I want 同一 invId 落库保持入队顺序, so that 台账守卫式 UPDATE 的语义可推理
22. As a DBA, I want 一组一个事务不变, so that 合并落库的事务形状不变
23. As a 测试, I want 引擎纯内存可测（不起 Spring、不连库）, so that 并发不变量测试秒级跑完
24. As a 测试, I want 新增断言：supervisor 吞 Throwable 不死、退出谓词、双 spawn 不可能、claim 死亡窗口自愈, so that 新模型独有的失效模式被钉住
25. As a 引擎维护者, I want JDK 21 下 JDBC pinning 风险被显式记录, so that 性能风险不被误当正确性缺陷
26. As a 运维, I want 进程级 OOM 归 ops 策略（ExitOnOutOfMemoryError）, so that 引擎不假装能扛进程级灾难
27. As a 引擎维护者, I want 新旧引擎并存、调用方暂不切换, so that 新引擎验证充分后再做替换决策

## Implementation Decisions

- **新包并存**：`infrastructure.mergevirtual` 与既有 `infrastructure.merge` 并存；本票交付新引擎实现，调用方（库存 / 计数写入组件）暂不切换——替换决策在新引擎测试充分后另立票。
- **组的生命周期 = leader 的生命周期**：组惰性创建，创建路径上由首个提交者经组内 claim-CAS（`leaderClaimed` false→true）恰好 spawn 一根专属虚拟 leader；**不许在 computeIfAbsent 的 lambda 里 spawn**（持桶锁跑外部代码，且 leader 收尾的 map.remove 会撞同一把桶锁）。
- **spawn 双向兜底**：claim-CAS 保证恰好一次（双 spawn = 并发写同一行）；claim 与 start 之间创建者死亡时，下一个提交者仍可 CAS 赢得 claim 并 spawn——窗口自愈；残余长尾泄漏（组滞留 map）接受为已知无害。
- **supervisor 循环**：leader 主体为 `while (组活着) { try { 收集 → 落库 → 回填 } catch (Throwable) { 整组回填 FAILED + error 日志 } }`——Throwable 含 Error 一律就地消化；崩溃恢复语义 = 整组 FAILED、从未进入事务（或已回滚）、可安全重试。进程级 OOM 是例外：归 ops 的 `ExitOnOutOfMemoryError`，引擎不假装能扛（catch 块本身也可能 OOM）。
- **退出谓词**：循环只在「组已置死并从 map 摘除」后退出；组内不存在提前 return 的合法路径——这是新模型唯一需要单测钉死的不变量。
- **领导权协议删除**：无 hasLeader / tryPromote / yieldLeadership / 卸任点名 / WAKEUP_PROBE 交接探测；`leaderClaimed` 是组内唯一新增标志。leader 不退位，接班链路不存在。
- **commit 收敛**：入队（或建组）→ park 在自己的件上等终态（`MergeItem` 的终态 CAS + 精确 unpark 机制原样复用），无任何接班分支。
- **<s>收集窗口</s> / maxBatch / 组容量 / 排空超时**：与旧引擎同口径——<s>窗口攒批、攒满或停机提前发车</s>**队列非空即发车（无收集窗口，`maxBatch` 只是单批上界）**、满即拒不扩容不挤掉已入队件。
- **乐观删除闭环保留**：队列空 → 置死 → 引擎从 map 摘除（两步必须成对）；迟到入队得 GROUP_REMOVED → 换组重建 → 新组 spawn 新 leader。
- **背压决策（讨论第二轮修正后拍板）**：不加任何新闸门。两种模型下 commit 都阻塞到终态，在途请求数、tomcat 耗尽点、门前快速失败、flush 并发度（= 活跃热点数）完全相同；「线程耗尽 → 延迟上升」的形状变化属于 commit 异步化场景，不在本 spec 范围。
- **pinning 已知风险**：JDK 21 下 JDBC 驱动 synchronized 可能钉住载体线程（JEP 491 于 JDK 24 解决）——记录为性能风险而非正确性缺陷；初始接受，基准对比后再决定是否升 JDK / 审驱动版本。
- **close**：停收 → markClosed（窗口作废、leader 立即排空）→ awaitIdle → 残余回填 FAILED；虚拟线程天生 daemon，无需 join。
- **五条不变量平移**：I1 不丢件恰一终态、I2 同 key 单飞（由「每组恰一根 leader」结构直接保证，不再需要执行权标志）、I3 锁不跨 DB、I4 停机三步、I5 背压有界——实现时全部在新结构下重新论证并写入类注释。

## Testing Decisions

- **零新增 seam**：完全复用既有 Seam 1（纯内存引擎单测 + 录制型落库桩 + 「闸门后并发起跑 + 虚拟线程后台提交」的并发骨架），先例为 `MergeEngineTest` 系——理想 seam 数为零。
- **只测外部行为**：提交 → 终态、批上限、同 key 单飞与保序、停机排空、背压有界——不测内部结构（组对象、CAS 标志、摘除时机）。
- **新模型专属断言**：
  - supervisor 吞 Throwable：落库桩抛 Error，整组仍得 FAILED 且引擎继续服务后续提交；
  - 退出谓词：组摘除后无残留线程（活跃线程数回落）；
  - 双 spawn 不可能：并发提交同 key，落库桩按批计数恰好串行、无并发批；
  - claim 死亡窗口：建组后 claim 前放弃，下一个提交者仍能 spawn 并落库。
- **本票（spec + 包骨架）不实现、不跑测试**；实现票按上述决策落地并补齐全部断言。

## Out of Scope

- 调用方切换与旧包 `infrastructure.merge` 的退役（新引擎验证充分后另立票）
- commit 异步化（fire-and-forget / future）——届时背压形状才真正改变，需重新评估
- 收集与落库流水线重叠（下一批收集与上一批落库并行；需引入独立落库权标志，属后续演进）
- JDK 升级 / JDBC 驱动替换（pinning 的缓解手段，等基准数据说话）
- 窗口 / 批大小的生产调优（等合并比 metrics 说话）
- 计数引擎（merge/count）的对应改造（可参照本 spec 另立票）

## Further Notes

- 讨论过程中的三轮修正已固化为决策：①「单点卡死」经 supervisor + 退出谓词消解，领导权协议是**删除**而非搬回（leader 不退位就不需要接班）；②「背压改形」撤回——commit 阻塞语义下两种模型行为完全相同，形状变化属于异步化；③ OOM 属进程级灾难，catch(Throwable) 的价值是窄的（非致命 Error + 存活窗口内的干净收尾），不声称引擎能扛进程级灾难。
- 旧引擎 `infrastructure.merge` 的 flushOneGroup 已在本讨论中先行修复一处 Error 缝隙（`catch (RuntimeException)` → `catch (Throwable)`，否则 Error 穿透会让 hasLeader 卡死 + 批次孤儿悬挂），与本 spec 无依赖关系，但独立提交。
- spawn 原子性的要点备忘：错法 A = lambda 里 spawn（持桶锁跑外部代码）；错法 B = computeIfAbsent 后无条件 spawn（每提交者一个 leader）；正确 = 组内 claim-CAS + 后续提交者兜底 claim。
