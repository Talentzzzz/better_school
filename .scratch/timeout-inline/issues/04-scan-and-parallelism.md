# 04 扫描 job + 有界并行 + 结算/退避/死信

Type: task
Status: resolved
Blocked by: 02

## What to build

把现中心那套扫描原样搬进 `better-school`（`bs-trigger`/`case`+`domain`+`infrastructure` 分层照 `backend/CLAUDE.md`）：

1. **job**：`@XxlJob("timeoutTaskScan")`（现 `timeoutTaskScanJob` 改名贴合本仓命名），走 `better-school-executor`；seed 行落 `better-school/sql/xxl-job-tasks.sql`：`0/10 * * * * ?`、`SHARDING_BROADCAST`、`DISCARD_LATER`、`executor_fail_retry_count=0`、`misfire_strategy=FIRE_ONCE_NOW`。
2. **认领**：`XxlJobHelper.getShardIndex()/getShardTotal()` → `slot % shardTotal == shardIndex && slot < TOTAL`（现 `TimeoutTaskScanCase:83-88` 口径原样保留；`TOTAL = dbCount × tablesPerDb = 16`，见票 02——**只吃全局槽位号，不要在这里写死库表维度**）。
3. **有界并行（本票与现实现的唯一实质差异）**：认领 N 张表，但**只按 `timeout.scan.parallelism`（默认 4）并发**执行、其余排队——**绝不为「一表一线程」开线程**。现实现是一表一虚拟线程、靠池 24 撑住；本工程池只有 **10** 且与用户请求共用（arch doc §6 记有池 16 时 `active=16` → `Connection is not available` 的实测）。
4. **结算**：三点照搬现口径——一 tick 固定一个 `dueBefore`（循环拉批到空的终止条件）；结算按「结果 × taskType」分桶、每桶一条批量条件 UPDATE 且**保留 `status IN ('WAITING','RETRYING')` 行级守卫**（捞批与结算之间的取消/死信窗口）；重试桶 `next_fire_time` 用 `CASE` 按行算退避（键序错位会把退避写串）。
5. **退避与死信**：30s 起指数翻倍、单次封顶 1h、16 次进 `FAILED`；`timeout.retry.{base-seconds,max-seconds,max-times}` 可配。
6. **取消**：按需保留 `cancel(taskType,bizId)` 软置 CANCELED（现契约把它标为可选优化；两个复合 `bizId` 接入方都不调）。

## 依据

现实现：`timeout-center-case/.../TimeoutTaskScanCase:61-110`、`domain/.../scan/TimeoutTaskScanService:66-99,130-144,205-211`、`TimeoutTaskMapper.xml:50-108`；参数与坑：`backend/docs/architecture/timeout-center.md §6`（含池 24 的来历与三条批量 UPDATE 的守卫理由）。

## 注意

- **SQL 形态避让（票 02 实测）**：datasource 已坐 ShardingSphere 驱动，`INTERVAL ?` 这种「函数内嵌占位符」会被解析器弄丢参数（两个 errand 集成测试夹具已踩过并避让）——本票新增的批量结算 SQL 一律不用 `INTERVAL ?`，退避时刻按现口径在域服务算好当普通参数传。

## 验收

- 集成测试钉：批内回调失败 → 退避后**不被同一 tick 重捞**；16 次进 `FAILED`；重发/取消只打 `FAILED`；取消与结算竞态时守卫不把行翻回来。
- 并行度断言：认领 16 张表时实际并发连接数 ≤ `timeout.scan.parallelism`（用 Hikari 指标或探针）。
- 单实例（`shardTotal = 1`）与多实例（模拟 `shardIndex/shardTotal`）两态都过。

## Answer

### 池与并行度的最终取值（本票的落定点）

**`timeout.scan.parallelism = 4`、Hikari 池不动（仍 10）。** 实测支持这个取值：认领 16 张分表时

- 回调时刻的并发峰值 `maxInFlight` **恰好 = 4**（= 并行度，不随认领表数放大；16 张表里其余 12 张在池队列排队）；
- 跑过回调的扫描线程**去重后 ≤ 4**（线程名 `timeout-scan-0..3`，即「一表一线程」没有发生）；
- 扫描期 Hikari 活跃连接峰值 **≤ 4**（2ms 采样式观测，全程未超），池 10 里留给用户请求的余量 ≥ 6；
- 认领 8 张表（`shardIndex=0, shardTotal=2`）时并发峰值同样 = 4，吞吐不受限（16 行全部结算完）。

`parallelism` 因此是**唯一**的并发旋钮，与分片粒度（16 张表）解耦；抬它等于抬扫描对共享连接池的占用峰值，
要抬之前先看 arch doc §6 的实测口径。默认值**未改动**（4 就是最终取值，不是待调的上限）。

### 验收落到哪

- 单实例（`shardTotal=1`，16 槽位全认领）与多实例（`0/2`、`1/3` 模拟）两态都过：`TimeoutTaskScanIntegrationTest` 12 例 + `TimeoutTaskScanParallelismIntegrationTest` 2 例（并行度断言 = 探针三断言 + Hikari 采样峰值，票面允许的两条证据都取）。
- 退避 30s 起指数翻倍 / 单次封顶 1h / 第 16 次进 `FAILED`：`TimeoutTaskScanServiceTest` 8 例（离线，含「同桶内两行按行算退避不串序」）+ 集成侧「同一 tick 内不被重捞」「第 16 次离开扫描集合」。
- 重发/取消只打 `FAILED`、竞态守卫不把行翻回来：集成侧各一例（这两条验收要求票 06 的管理面落点，所以仓储方法 + SQL 在本票落地）。
- 全量：`better-school` 315 例、`marketing` 82 例、`timeout-center` 37 例，全绿（改动只落在 better-school）。

### 执行中偏离/补充票面的三处（都在票面口径内）

1. **`max-seconds` 也兜底 ≥1s**：票面只要求「退避可配」，但 `max-seconds: 0` 会让退避算成 0、`next_fire_time` 贴住 tick 起始时刻，
   于是同一轮把这一行反复捞起、一个 tick 跑光 16 次重试（正是本票「退避后不被同一 tick 重捞」要防的形态）。
   现在两个配置项都兜底，并有单测钉住。
2. **`batch-size: 200` 也进了本票的配置面**：票面的「循环拉批到空的终止条件」需要一个批上限，本票落 `timeout.scan.batch-size`（200）与 `timeout.scan.parallelism` 同处配置。
3. **探针的测试隔离**：回调 seam 探针是 `@Primary` 单例、context 跨测试类缓存，桩住的模式会漏给下一个共用 context 的测试类——
   夹具在 `@BeforeEach` **和** `@AfterEach` 都摆回默认（委派真分发器）并清空观测，不依赖 surefire 的类执行顺序。

### 与票 05 / 票 06 的边界（重述，避免重复实现）

- **票 05**：`taskType → 四个域动作` 的 switch **已在本票抽到 `usecase.timeout.TimeoutTaskCallbackDispatcher`**（`ITimeoutTaskCallbackPort` 的进程内实现）；
  扫描与 Dubbo Provider 共用它，两条边不会走岔。票 05 剩下的是**删 Dubbo 边**（`ITimeoutTaskCallback`/DTO/References/`timeout-center-api` 依赖/Nacos 边）
  + 四个 taskType 的真实动作集成测试 + 坏 `bizId`/未注册 taskType 的口径验收。本票未动 Dubbo 边。
- **票 06**：死信重发/取消的**仓储方法 + SQL + 集成测试**在本票落地（本票验收写着「重发/取消只打 FAILED」），
  **管理面落点与鉴权仍留票 06**（本票没有加任何 Controller）。

### 一处留给评审/后续的判断题（未改）

`TimeoutTaskCallbackDispatcher` 落在 **case 层**实现 domain 端口——`backend/CLAUDE.md §1/§3` 的口径是「domain 的 adapter 只定义接口、由 infrastructure 实现」，
此处是有理由的显式例外（分发要调其它域的 case，而 infrastructure 看不到 case 层，见票 05 的跨域编排口径）。
本票在类的 javadoc 里写明了理由，但**没有**在 arch doc / ADR 里留下这条例外；若要收口，应在票 05 删 Dubbo 边时一并写进架构文档。
