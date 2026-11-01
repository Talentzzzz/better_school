# 11: 派单信号推送

**Spec:** `.scratch/errand-lock-review-hub/spec.md` §5.3「信号粒度」「离线积累」「降级」、§9（可观测）

**What to build:** 池里进了新工单就唤醒在线审核员。信号是**粗粒度的总信号**——「有新工单」+ 当前积压数，**不按内容类型推**：推送只负责唤醒，不指定给谁、不锁任何东西；「只擅审某类」的偏好在拉取侧表达。审核员从离线转在线时**立即补推**当前积压数（离线期间不推、不丢单——池是持久化的，无人在线就静默积压）。

推送通道用票 10 的审核长连接；积压数来源为工单池（票 06 的按类型计数汇总）。同时落可观测口径：在线审核员数、队列深度（按类型）、平均等待时长、租约超时率；积压告警沿用既有命名先例的队列深度阈值 + 「最老待审等待时长」阈值，**两条都只告警**，不做自动扩容、不自动派给离线者。

**Blocked by:** 10, 06

**Status:** done

- [x] 新工单入池后向在线审核员广播粗粒度信号（含当前积压数），不按内容类型分推
- [x] 审核员由离线转在线时立即补推当前积压数
- [x] 无人在线时静默积压、不丢单、不上锁；上线后信号与池中实际一致
- [x] 推送不指定收件人、不做分配、不引入任何锁；公平约束（一次一条 + 先到先审 + 类型分区）仍全在拉取侧
- [x] 队列深度（按类型）、在线审核员数、平均等待时长、租约超时率四项可观测
- [x] 积压告警：队列深度阈值 + 最老待审等待时长阈值，只告警不自动处置
- [x] 验证：入池 → 在线端收到信号；离线端上线后收到补推；并发多次入池的积压数与池内行数一致

**落地说明（2026-10-15）**

- 信号链路：`ReviewTaskPoolStore.enter`（基础设施，真实开新工单时）注册事务 afterCommit 钩子 → 虚拟线程 → 域端口 `IReviewSignalPort` → trigger 层 `ReviewSignalPushService` 遍历 `ReviewConnectionRegistry.snapshot()` 广播 `REVIEW_SIGNAL {backlog: N}`（积压数推前现算，= `GET /tasks/counts` 同口径求和）。端口注入走 `ObjectProvider` 惰性解析（实现依赖链回到池仓储，构造器直注入成环）。
- 上线补推：`ReviewWsHandler.afterConnectionEstablished` 在注册/presence 写入后立即向新连接推当前积压数（与广播同帧型同载荷；空池也推 backlog=0）。
- 可观测：xxl-job `reviewDispatchObservation`（每分钟，注册默认不自动触发）落 INFO 一条——在线审核员数（presence 按 60s 判死阈值过滤）、队列深度按类型、平均/最老等待时长（`selectPendingWaitStats`，entered_at 起算）、租约超时率（Redis 累计计数 `bs:review:metric:claim-count` / `bs:review:metric:lease-timeout-count`，领单提交成功 +1、租约超时回收含惰性兜底 +1、主动放回不计）。
- 积压告警双阈值 `review.backlog-warn-threshold`（默认 50）+ `review.oldest-wait-warn-minutes`（默认 30），只 WARN 不处置。
- 测试：`ReviewDispatchSignalIntegrationTest` 5 例全绿（上线补推/入池广播/广播不锁/静默积压/观测计数）；注意「等待某类帧不丢其他类帧」的按类型分队客户端骨架（与票 10 混队轮询的差异点）。
- 文档：`backend/better-school/docs/api/moderation.md` 补 REVIEW_SIGNAL 载荷/时机与可观测口径两节。

**评审收口（/code-review 两轴）**

- 回池不广播（有意边界）：广播只挂「真实开新工单」（`enter`）；主动放回/租约回收回池**不推**——票文口径是「新工单入池后」，且回收多与拉取动作同场发生（回收后审核员自己正在线），感知靠各端手动刷新与上线补推兜底。若票 12 前端实测需要回池唤醒，另补。
- 租约超时率口径：进程生命周期累计比（Redis 计数不清零），近期恶化会被历史稀释；spec 未定窗口口径，按「观测低估方向安全」取舍，记档于此。
- 上线补推在握手线程同步查一次积压（建连低频、单条计数查询），不异步化——与广播路径（虚拟线程）风格不同是有意的：补推要在注册完成后立刻可见。
- 互踢替换连接时新连接也补推（spec 字面是「离线转在线」）——幂等快照无害，代码注释已记。
- 求和口径收敛：`IReviewService.backlogTotal()` 统一「四类型计数求和」（信号载荷 + 观测 job 共用，不再各自手写）；观测快照改值对象 `IReviewDispatchMetricsPort.Snapshot`（替代 `long[]` 魔法下标）。
- 测试 WS 客户端骨架与票 10 存在复制（已按类型分队改进）——票 12 做 `front-review` 时若再要一份，抽共享测试支撑类。
