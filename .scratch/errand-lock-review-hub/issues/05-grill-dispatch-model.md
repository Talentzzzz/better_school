# 05 grilling：在线审核员派单技术方案（推信号 + 拉工单）

Type: grilling
Status: resolved
Blocked by: 04

## Question

**已定方向**：派单 = **推信号 + 拉工单**。推送只负责**唤醒**（向在线审核员广播「有新活」，不指定给谁、不锁任何东西），拉取负责**取活**（审核员手里审完才 `claim`，服务端**原子指派 + 租约**）。不做「抢审」，不做纯指派，不用纯拉。用户态度：Redis / WS **有无都不阻塞**，缺什么建什么；审核员人数不是约束。

本票把方向落成可实现方案（票 04 的盘点结论作为事实底座）：

1. **presence 怎么维护**：审核端 WebSocket 长连 + 心跳（间隔取值、掉线判定），在线集合存哪（Redis set / 内存 / DB 心跳表）；多实例部署时是否需要共享（若无 Redis 且要横向扩展，presence 会不会失效）。
2. **推送通道**：复用 04 查到的既有 WS 层，还是审核端自建一条？推的信号粒度（只推「有活」总信号，还是按**内容类型**推——审核员可能只擅审某类）；是否需要对**离线审核员**积累（上线即推）。
3. **claim 的原子性**：`SELECT ... FOR UPDATE SKIP LOCKED`（MySQL 8）取一条，还是 Redis 原子操作，还是 CAS 乐观锁；**取件顺序**（FIFO / 按类型 / 按优先级 / 按等待时长）；一次只取一条还是可预取 N 条（预取影响「手里压单」与超时率）。
4. **租约与回收**：租约时长取值；回收靠定时扫（xxl-job）还是惰性（下次 claim 时顺带回收过期）；审核员**主动放回**（审不了）要不要支持；回收次数上限（同一条工单被反复领走又超时 → 进人工池/告警）。
5. **公平与均衡**：拉取天然自平衡，是否还需要**约束**（每人手里最多 N 条、按类型分区、老工单饥饿保护）；若需要，落在 claim 时还是推送时。
6. **无人在线 / 积压**：无人在线的降级（积压 + 上线补推）；积压告警阈值（超时中心有 `register.backlog-warn-threshold` 先例）。
7. **派单池来源**：哪些待审内容进派单池——**全量待审**，还是按来源做子集（如抽检单独入池）？
8. **可观测与运维**：在线人数、队列深度、平均等待时长、租约超时率——本期做多少。

**产出**：派单技术方案（presence / 推送 / claim / 租约 / 均衡 / 降级），写进 `spec.md`；明确「复用 vs 新建」清单与外部依赖（Redis 要不要进 `docker-compose.yml`）。

## Answer

2026-10-13 grilling 拍板。用户答：**Q4 改为复用既有超时中心**（「我已经有超时中心微服务了，看看能不能用上」）；**Q7 前提消失**（「agent 裁决已经全部去掉了，文档里还有的删了」）；其余按推荐。

事实底座（票 04 + 本次补核）：`content_review` 是**只插入的裁决记录表**（uk `(target_type,target_id,action,review_round)`），**不是在途工单**；Redis 已就绪（`docker-compose.yml`，Redisson，含 `RLock` 先例）；WS 连接层已在单体 `bs-trigger` 落地（票据/注册表/心跳/互踢/并发写）；原子认领范式 = **条件 UPDATE（影响行数=1 者赢）**，全仓无 `SKIP LOCKED`；**超时中心已就绪**（`ITimeoutTaskRegister` / `ITimeoutTaskCallback`，业务侧 `timeout_register_outbox` + `TimeoutTaskCallbackDubboService.applyCloseAction`）；**agent 裁决层已随「审核去 agent 化」整体下线**（backend 代码零残留、`agent_review_log` 已删、ADR-0005 已撤）。

### 结论（逐问）

| # | 问题 | 裁决 |
|---|---|---|
| 1 | presence 怎么维护 | 审核端 WS 长连 + 心跳（**25s 心跳 / 60s 判死**，复用 chat 口径）；在线集合存 **Redis**（`RMap<userId,lastSeenTs>` 或 `RSet`，key TTL 覆盖心跳周期），**只放 AUDITOR/ADMIN**；多实例靠 Redis 天然共享。 |
| 2 | 推送通道 | **泛化新建 `/ws/review`**，连接层骨架整体复用；**不复用 `/ws/chat`**（帧类型写死 chat 语义）。信号粒度 = **粗粒度总信号**（「有新工单 + 积压数」），**不按类型推**——类型偏好在**拉取侧**过滤。**离线积累：做**（上线即补推）。 |
| 3 | claim 原子性 | **CAS 条件 UPDATE**（复用 `grab` 范式）：选候选 → `UPDATE ... WHERE id=? AND claim_state='PENDING'`，0 行顺延。**不引 `SKIP LOCKED`**，**不把 Redis 锁放关键路径**（DB 权威，Redis 只做信号/计数）。取件顺序 **FIFO（最早等待优先）**；类型仅作过滤；**一次一条**（不预取）。 |
| 4 | 租约与回收 | 租约 **5 分钟**；回收 **复用超时中心**（不建扫描 job，见下）；claim 路径**另留一条惰性过期回收**作廉价双保险；**支持主动放回**；`recycle_count ≥3` → 升级标记 + 告警。 |
| 5 | 公平与均衡 | **轻量约束，全落 claim 时**：① 每人手里最多 1 条（=一次一条）；② FIFO 防饥饿；③ 类型分区放拉取侧。推送不加约束（只唤醒、不管分配）。 |
| 6 | 无人在线 / 积压 | 池 **DB 持久化**，无人在线静默积压、上线补推；告警沿用命名先例 **`review.backlog-warn-threshold`**（队列深度）+「**最老待审等待时长**」阈值。不自动扩容、不派给离线者。 |
| 7 | 派单池来源 | **全量待审**（人工为唯一裁决者）。`ReviewSourceVO` 的 MANUAL/KEYWORD/RANDOM 扩展点保留，用于区分「常规待审 / 敏感词命中转审 / 抽检」。 |
| 8 | 可观测与运维 | 本期做 **在线审核员数 / 队列深度（按类型）/ 平均等待时长 / 租约超时率**；不做监控面板接入、不做「每人工作量」视图（map 已列为 Not-yet-specified）。 |

### 定稿派单技术方案

**presence**
- 审核端持有 WS 长连，按 **25s** 发 PING、服务端以任意入站帧刷新活跃时间、**60s** 无入站判死清理（直接复用 `CONTEXT.md` 已定的「心跳」口径，不新造参数）。
- 在线集合落 **Redis**：`RMap<userId, lastSeenTs>`（连接建立写入、心跳刷新、断开删除，key 到期即视为离线）或 `RSet` 在线 id。**只收 AUDITOR/ADMIN**（角色过滤是新逻辑，chat 那套内存注册表不区分角色）。
- 选 Redis 的理由：跨实例共享（多实例 presence 不失效）、**可查**（「在线人数」可观测）、重启可恢复；单机内存方案在多实例下直接失效。合规仓库取向——「Redis 只做快面，真相在 DB」，presence 本就是快面状态。

**推送通道**
- **后端**：把 chat 的连接层骨架（一次性票据鉴权、握手拦截、连接注册表、心跳判死、互踢 close 4001、`ConcurrentWebSocketSessionDecorator` 并发写保护）抽出，**新增 `/ws/review` 端点与审核帧类型**（如 `REVIEW_SIGNAL`）；**不复用 `/ws/chat`**（端点常量与帧类型写死 chat 语义，语义错位）。前端 `chatWs.ts` 可复用骨架（重连/心跳/退避/踢人），泛化票据接口、拆出 chat 专属 handler。
- **信号粒度**：只推**粗粒度总信号**（「有新工单」+ 当前积压数），**不按内容类型推**——推送只负责唤醒、不指定给谁、不锁任何东西；「只擅审某类」的偏好在**拉取侧**表达（拉取时带类型过滤）。
- **离线积累**：审核员上线（presence 由离线转在线）时，服务端**立即补推**「积压 N 条」。这就是原型验证过的「离线不推、上线补推」。

**claim（原子取一件）**
- 复用仓库既有「条件 UPDATE，影响行数=1 者赢」范式，**不引 `SELECT ... FOR UPDATE SKIP LOCKED`**（全仓无先例、当前单库，条件 UPDATE 已足够）、**不把 Redis 锁放关键路径**：
  ```
  ① 选候选：SELECT id FROM review_task
            WHERE claim_state='PENDING' [AND target_type=?]   -- 类型仅当拉取侧带过滤
            ORDER BY created_at ASC LIMIT 1                   -- FIFO
  ② 认领：  UPDATE review_task
            SET claim_by=?, claim_state='CLAIMED', claim_seq=claim_seq+1, lease_expire_at=NOW()+5min
            WHERE id=? AND claim_state='PENDING'              -- 0 行 = 被别人领走，顺延下一条
  ```
- 取件顺序 **FIFO（最早等待优先）**，类型只作**过滤器**、不做优先级插队（对齐「不做抢审」）。**一次只取一条**（预取 N=1，配置位预留但不启用），避免「手里压单 → 超时率上升」。

**租约与回收（复用超时中心 —— Q4 修订点）**
- **复用既有超时中心**，不为回收新建扫描 job。租约 = 一个「到点收口」动作，与中心通用模型（`task_type + biz_id + 回调`）严丝合缝，且**业务侧提交通道已在单体**（`timeout_register_outbox` 事务内写 + afterCommit 发 + 30s 扫描兜底）。
- **注册（claim 事务内）**：
  ```
  task_type = 'REVIEW_LEASE_TIMEOUT'
  biz_id    = '{reviewTaskId}:{claimSeq}'      -- 复合键，按「第几次领单」区分
  action_time = 领单时刻 + 5min
  ```
  **必须复合 bizId**：`uk(task_type,biz_id)` 是「同键复位」语义（在途不改成期），若只用 `taskId`，则「超时回收 → 再次领单」会撞上尚未取消的旧任务、被静默忽略 → **租约永久失效**。按领单轮次分键后，每次注册都是新键插入（与票 02 锁单超时用 `lock_seq` 区分是**同一手法**，前后一致）。`claim_seq` 同时作回调守卫的比对值。
- **回调（回收）**：`TimeoutTaskCallbackDubboService.applyCloseAction` 增 `case Constants.REVIEW_LEASE_TIMEOUT`；bizId 是复合键，需拆 `taskId:claimSeq`（**不能走现有 `Long.parseLong` 的 `parseBizId`**）→ 审核侧用例 → 条件 UPDATE：
  ```
  UPDATE review_task SET claim_state='PENDING', claim_by=NULL, lease_expire_at=NULL
   WHERE id=? AND claim_state='CLAIMED' AND claim_seq=?     -- 轮次守卫
  ```
  命中 0 行（已裁决 / 已放回 / 已按新轮次重领）= **幂等成功、零副作用**（同 `handlePayTimeout` / 锁单超时口径）。
- **不调 cancel**：轮次守卫已使陈旧回调零副作用，`cancelAsync` 是 best-effort 且带失败/竞态面（票 02 同结论）。记「可选优化，本期不做」。
- **惰性兜底**：claim 前顺带跑一条 `UPDATE ... SET claim_state='PENDING' WHERE claim_state='CLAIMED' AND lease_expire_at < NOW()`（一行，几乎免费），防中心回调延迟/丢失导致工单卡在 `CLAIMED`。
- **主动放回**：支持（审不了 → `CLAIMED→PENDING`，可选放回原因）。**回收次数上限**：`recycle_count` 每次回收 +1，**≥3 次打「需人工干预」升级标记并告警**（同一条反复领走又超时不再无声循环）。
- **参数**：`LEASE_MINUTES = 5`。**中心零代码改动**，属**新增接入方**。

**公平与均衡**
- 保留三条**轻量约束，全落 claim 时**：① 每人手里最多 1 条（=一次一条，防囤单）；② FIFO 天然防饥饿（老单先被拉走）；③ 类型分区放拉取侧（可带过滤），不做服务端硬分区。理由：「不做抢审」+ 拉取自平衡 → 约束越少越好；推送保持「只唤醒、不管分配」。

**降级（无人在线 / 积压）**
- 池子是 **DB 持久化**的，无人在线就静静积压、不上锁、不丢单；审核员上线即补推（见上）。**积压告警**沿用超时中心的命名先例：`review.backlog-warn-threshold`（队列深度）+「最老待审等待时长」阈值，两条都只告警，不做自动扩容/自动派给离线者。

### 复用 vs 新建 + 外部依赖

- **复用**：WS 连接层骨架（票据/握手/注册表/心跳/踢人/并发写装饰器）、Redis/Redisson、条件 UPDATE 原子认领范式、**超时中心（租约回收）**、既有通知设施、xxl-job（备用）。
- **新建**：`/ws/review` 端点 + 审核帧类型、presence 的 Redis 结构、**在途「审核工单」表**（票 06 出 DDL）、claim/租约/回收逻辑、`front-review/` 独立工程（票 08）。
- **外部依赖**：**Redis 已在 `backend/docker-compose.yml`**（dev 6379 / test 16379），**无需新增**；超时中心已就绪（新增接入方，零代码改动）。

### 术语（供票 09 落 `CONTEXT.md`）

`content_review` 语义是**裁决记录**，派单需要的是**在途工单**——建议新概念命名「**审核工单**」与之严格区分（落票 06）。本期引入/复用术语：「**派单信号**」（推送，只唤醒）·「**领单**」（claim，原子指派）·「**租约**」·「**放回**」·「**回收**」，复用已入册的「**心跳**」「**连接票据**」。

### 转票锚点

- **票 06（数据模型）**：在途工单表带 `claim_state(PENDING/CLAIMED/DONE)` + `claim_by` + `claim_seq`（领单轮次，兼作租约复合 bizId 与回调守卫）+ `lease_expire_at` + `recycle_count` + `source`（常规/敏感词转审/抽检）；claim 走条件 UPDATE，需 `(claim_state, created_at)` 排队索引。
- **票 07（原型）**：已产出（`prototype/`），派单交互与本方案一致（推信号 + 拉一条 + 租约倒计时 + 回收 + 上下线补推）。
- **票 08（工程形态）**：`front-review/` 独立工程 + `/ws/review` 端点与接口归属。
- **票 09（spec）**：落稿本节；**ADR 候选** = 审核中台「推信号 + 拉工单」派单模型。

### 回填点（文档，不改中心代码）

- `.scratch/timeout-center/spec.md` + `backend/docs/architecture/timeout-center.md`：接入方清单补「**审核租约超时**」（taskType `REVIEW_LEASE_TIMEOUT`、注册点 = claim、回调点 = 回收、复合 bizId `{taskId}:{claimSeq}`），与票 02 的「跑腿锁单超时」并列。
- **不动**：`backend/timeout-center/` 任何代码。

### 随票完成的清理（Q7 连带）

「审核去 agent 化」已在代码/库完成（backend 零残留、`agent_review_log` 已删、ADR-0005 已撤），本次把**文档残留**一并对齐：删除已下线 feature 的整个 effort 目录 `.scratch/agent-review/`；清掉 ADR-0003/0004 里「未来 agent」措辞、本 effort 的 `map.md`（Notes / Not-yet-specified / Out-of-scope）与票 06/07/09、原型 README 及 `src` 里的 agent 引用。**已收口的旧 effort（virtual-thread / chat-refactor / marketing 等）里的历史引用未改**——它们记录的是当时的事实，属归档，未动。
