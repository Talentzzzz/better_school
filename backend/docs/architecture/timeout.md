# 超时中心（内嵌形态）

> 归属：单体 `backend/better-school/` 的超时技术组件 `infrastructure/timeout`（原 `domain.timeout`「技术域」历史遗留已整体并入技术包，2026-11-24）。超时任务与业务写入**同事务、同库、同进程**。
> 本文只写「怎么跑起来、怎么改不翻车」；接口见
> [`../../better-school/docs/api/timeout.md`](../../better-school/docs/api/timeout.md)，
> 库表见 [`../../better-school/docs/db/timeout.md`](../../better-school/docs/db/timeout.md)。
> 需求与历史决策见 `.scratch/timeout-inline/`（map + 已 resolved 的 01–09）。

## 0. 形态与一条不可破的硬约束

改造前它是独立工程（独立库、Dubbo 注册/回调两条边、outbox 注册通道、8083 管理面）。
现在它只是业务工程里的一个技术域：

- **注册**：业务仓储在**自己的本地事务**里直插 16 张 `timeout_task` 分表 → 业务行与超时行同生同死；
- **调度**：单体里唯一的 `@XxlJob("timeoutTaskScan")`，xxl-job 分片广播，每个实例只认领属于自己的槽位；
- **执行**：进程内方法调用，按 `taskType` 分发到四个业务动作；
- **管理面**：单体 8080 的 `/api/admin/timeout-tasks/**`，接单体 JWT + ADMIN。

> ⚠️ **硬约束：`dbCount > 1` 与「同事务注册」互斥**。业务行留在原库、超时行按 hash 落到另一个库，
> 同一条连接 / 同一个本地事务的前提立刻失效（退回 2PC 或 outbox + RPC）。本期 `dbCount` 恒为 1、**不得启用**；
> 代码与配置保留「库 × 表」两维表达是为了将来不必重写结构，**保留的是表达，不是开关**。
> 真要分库，前提是业务表也按同一分片键一起分库——那是另一次全仓改造。

## 1. 模块落位（照 `backend/CLAUDE.md`）

| 模块 | 落点 | 内容 |
| --- | --- | --- |
| `bs-infrastructure` | `infrastructure.timeout` | 组件整体（一个包）：`TimeoutConstants`（规格常量）、`TimeoutTaskEntity` / `TimeoutTaskStatusEnum` / 各结算与死信值对象、`TimeoutTaskStore`（16 分表唯一读写口 + hint）、`TimeoutScanner`（认领 + 有界并行 + 循环拉批到空）、`TimeoutSettler`（分桶 + 退避）、`TimeoutDeadLetterAdmin`（死信管理面）、`ITimeoutHandler`（到点动作 SPI）、`ITimeoutBucketWriter`（结算写出缝，单测用）、槽位函数 `TimeoutTaskShardSlots` 与库/表维两个 hint 算法；DAO/PO 仍按惯例住 `dao/timeout` |
| `bs-trigger` | `trigger.job` / `trigger.http.timeout` | `TimeoutTaskScanJob`（取分片参数 + 递入装配）、`TimeoutActionRouter`（taskType → 业务动作的装配点，实现 `ITimeoutHandler`）、`TimeoutTaskAdminController`（死信管理面，直调组件） |
| `bs-api` | `api` / `api.dto` | `ITimeoutTaskAdminService` + 管理面 DTO（**不建 Dubbo 契约**——没有跨进程边） |
| `bs-app` | `config` / `mybatis/mapper` | `ShardingSelfCheck`、`sharding.yaml`（dev）与测试侧 `sharding-test.yaml`、`TimeoutTaskMapper.xml` |

**装配为什么在 trigger**（组件化后唯一的落位要点）：

1. 到点动作要调业务域的 case——infra 看不见 case 层、case 也看不见 infra，trigger 两边都看得见，
   所以 taskType → 动作的装配落在 `trigger/job/TimeoutActionRouter`（实现组件的 `ITimeoutHandler` SPI）。
   同款先例：`FileController → FileStorage`、各域仓储直用 `ISearchIndex`。
2. 业务仓储（idle 侧）在自己的事务里调用同层技术组件 `TimeoutTaskStore.register(...)` 注册超时行
   （infra → infra，不构成「域注入他域仓储」）。同事务注册是内嵌化的全部前提，注册必须落在
   **已经开着事务的那个仓储方法**里；域服务本身仍不注入超时组件，也不自持事务边界（见 CLAUDE.md §1/§3）。

## 2. 数据面：16 张分表，与业务同库

| 文件 | 用途 |
| --- | --- |
| `better-school/sql/schema.sql` | 唯一 DDL 来源：29 张业务表 + 16 张 `timeout_task_00..15` |
| `better-school/sql/init-{dev,test}.sql` | 只建库并 `USE`，必须与 `schema.sql` 按序拼给同一个连接 |
| `bs-app/src/main/resources/sharding.yaml` | 开发库分片配置 |
| `bs-app/src/test/resources/sharding-test.yaml` | 测试库分片配置（同内容，jdbcUrl / databaseName 指 `_test`） |
| `bs-app/src/main/java/.../config/ShardingSelfCheck` | 启动期自检：缺 `!SINGLE`、物理分表泄漏都在启动期炸掉 |

**逻辑表只有一个**：业务/测试 SQL 一律写 `timeout_task`，物理分表由 ShardingSphere 的 hint 强制路由。
经 SS 连接点名 `timeout_task_07` 是 `TableNotFoundException`（物理表不在逻辑元数据里）；物理名只给分片算法、
裸 MySQL 运维连接（grep / `DROP` / 单表巡检）用。

**槽位公式**（单点函数 `TimeoutTaskShardSlots.slotOf`，别在别处重算）：

```
slot = floorMod(hash(biz_id), 16)          // hash 输入只有 biz_id
g    = 全局展平槽位号（0..15）
db   = g / TABLES_PER_DB, table = g % TABLES_PER_DB
```

- hash 输入从改造前的 `(task_type, biz_id)` 改成 **`biz_id`**：同一业务对象的多种超时 / 多轮次落同一张表。
  `uk_type_biz (task_type, biz_id)` 的全局幂等不受影响——同键行永远同表。
- g 同时塞 `HintManager.addDatabaseShardingValue` + `addTableShardingValue` 两个 hint 值；
  认领只看全局号 `g % shardTotal == shardIndex && g < 16`，与库表两维解耦。
- **不加 `user_id`**：主查询按 `(status, next_fire_time)` 扫、分片键不参与；路由键 ⊥ 幂等键会引入
  「user 与幂等键绑定不可变」这条额外不变量，破了即双行双回调。

**ShardingSphere 5.5.3 的形态（票 01 实测，别改回去）**：

- 主 datasource 直接坐 `jdbc:shardingsphere:classpath:sharding.yaml`（驱动 + YAML 形态）。
  代价：Spring 的 `${}` 占位符到不了驱动解析层，所以 dev / test 各一份 YAML，改库地址要改两处。
- **`!SINGLE` 必须显式声明且放在 `!SHARDING` 之前**，`tables` 只认 `*.*.*` / `*.*` 两个字面量。
  缺它时逻辑库只暴露 `timeout_task`，29 张业务表全部 `TableNotFoundException`，**且启动成功、第一条业务 SQL 才炸**。
  补上后业务表完全透明（SELECT/INSERT/JOIN/ODKU/批量 UPDATE/generated keys/FOR UPDATE 都照常），
  事务仍是同一条 MySQL session、同一个本地事务、一条 rollback 全清。
- `databaseName` 显式写真实 schema 名（不写则恒为 `logic_db`）。
- 表名逐张列出（`timeout_task_00..15`），**不要用 `${0..15}` 内联表达式**——它生成 `_0.._15`，没有两位零填充。
- 内层池（YAML 的 Hikari）与外层池（`spring.datasource.hikari`）是两层：外层 10、内层 20。
  内层 ≥ 外层（每个外层连接干活时要占一条内层连接），余量留给扫描并行度与驱动元数据连接。
- `shardingsphere-jdbc` 5.5.x 只是驱动壳，要自己补齐特性 artifact（缺哪个报哪个，错误不提「少依赖」）：
  `shardingsphere-sharding-core`、`shardingsphere-infra-data-source-pool-hikari`、
  `shardingsphere-standalone-mode-repository-memory`、`shardingsphere-authority-simple`、
  `shardingsphere-parser-sql-engine-{mysql,sql92}`。权威清单 = 驱动自己 pom 里那批 test scope 依赖。
- **commons-lang3 必须 ≥ 3.18.0**：Boot 3.3.5 管的是 3.14.0，而 sharding-core 用了 3.18 才有的
  `org.apache.commons.lang3.Strings`（工程 pom 已显式覆盖，否则运行期 `NoClassDefFoundError`）。

## 3. 注册：业务事务内直插（票 03）

接入点在**仓储自己的事务方法**里调 `TimeoutTaskStore.register`：

| taskType | 注册点 | 计时起点 | bizId |
| --- | --- | --- | --- |
| `PRODUCT_ORDER_PAY_TIMEOUT` | `IdleRepository.createOrderWithLock` | 下单时刻 + 15min | 裸 `productOrderId` |

**三分支幂等**（裁决权全在库上，与「读-判-写」时序无关）：

1. 同键无行 → 新插入（`WAITING`，`next_fire_time = action_time`）；
2. 同键**在途**（`WAITING` / `RETRYING`）→ 幂等成功、**不改期**（沿用首次注册的时刻）；
3. 同键**终态**（`SUCCESS` / `FAILED` / `CANCELED`）→ 复活刷新 `action_time`（重开一轮）。

- 没有注册 outbox、没有 afterCommit 异步发送、没有兜底扫描 job；回滚即无行（无幽灵注册）。
- 复合 `bizId` 使每次注册都是**新键插入**，绕开「在途键重注册 = 不改期」的陷阱——「放弃 → 重抢」不会让
  新计时被旧到点吞掉；回调侧轮次守卫（条件 UPDATE 带 seq）让陈旧回调幂等落空。
- `cancelActive` 是业务取消（只打未终态行）；死信取消是管理面另一条（只打 `FAILED`），语义不混。
- **同事务注册后，旧「先 cancel 再复位」纪律已不需要**：那是 outbox 时代「注册在途 vs 取消竞态」的产物；
  当前各接入方也都不调取消。

## 4. 调度：xxl 分片广播 + 有界并行（票 04）

只有一个 job：`timeoutTaskScan`（`bs-trigger/trigger/job`），扫描 = 重试 = 死信重发的统一入口。
调度参数在 admin 库（`better-school/sql/xxl-job-tasks.sql`，幂等脚本）：

| 参数 | 值 | 为什么 |
| --- | --- | --- |
| `schedule_conf` | `0/10 * * * * ?` | 10s 周期；重试退避底数 30s，周期必须远小于它 |
| `executor_route_strategy` | `SHARDING_BROADCAST` | 每个实例都触发，靠 `shardIndex/shardTotal` 认领槽位 |
| `executor_block_strategy` | `DISCARD_LATER` | tick 重叠丢后到的（防漏靠下轮重扫） |
| `executor_fail_retry_count` | `0` | 调度层不重试，周期重扫即重试 |
| `misfire_strategy` | `FIRE_ONCE_NOW` | 错过补一次 |

一次 tick（`TimeoutScanner.scan(shardIndex, shardTotal, handler)`）：

1. 认领槽位：`g % shardTotal == shardIndex && g < 16`。实例数 > 16 时多出的实例空转（正常形态，不是错误）。
2. **一个 tick 固定一个 `dueBefore`**；每槽**循环拉批到空**（单批 ≤ `timeout.scan.batch-size` = 200，
   无单 tick 批数上限）。重试行结算后被推到「结算时刻 + 退避」，本轮一定不会再被捞起——这是循环的终止条件。
   别改成每批现取 `now`：tick 拖长到超过退避间隔会重复回调同一行。
3. **有界并行**：认领 N 张表，只按 `timeout.scan.parallelism`（默认 **4**）并发，其余排队；
   执行器是宽度 = `min(并行度, 认领表数)` 的每 tick 固定池，**绝不为「一表一线程」开线程**。
   某槽炸了只记账、不毁整个 tick（下一轮重扫补上）。
4. 批内**逐项**回调，随后就地结算（见 §5）。

**为什么是并行度 4 而不是一表一线程**：单体 Hikari 池只有 **10**，且与用户请求 / HTTP / 其它 job 共用。
改造前的独立中心有专属池 24（1.5 × 槽位数）才敢一表一虚拟线程；照搬到这里，16 槽位同时抢连接会把池占满，
用户请求随即 `Connection is not available`（实测：池 16 时扫描期第 17 个请求失败，`active=16`）。
票 04 实测（16 张表全认领）：回调并发峰值 = 4、扫描线程 ≤ 4、扫描期 Hikari 活跃连接峰值 ≤ 4，给业务留 ≥ 6 条。
认领 8 张表时峰值同样是 4。**表数（分片粒度）与连接数（真并发上限）在这里解耦**；要抬并行度先看这条实测口径。

## 5. 结算：分桶 + 每桶一条条件 UPDATE（票 04）

一批是**按槽位**从单张物理分表捞出来的，所以结算语句天生单表。回调结果按「成功 / 死信 / 重试」分桶，
每桶（× taskType）一条批量 UPDATE：

- 成功桶 → `SUCCESS`；
- 失败且本次就是第 `timeout.retry.max-times`（默认 **16**）次失败 → `FAILED` 终态（死信）；
- 其余失败 → `RETRYING`、`retry_count + 1`、`next_fire_time = now + base × 2^(失败次数-1)`，单次封顶
  `timeout.retry.max-seconds`（默认 3600s）。退避序列：30s、60s、120s……最多 1h。
- **三条 UPDATE 都带 `status IN ('WAITING','RETRYING')`**：行级裁决权仍在库上，回调在途被取消 / 被死信重发的行
  不会被翻回来。返回值从逐行布尔变成「本桶实际改到几行」，差值 = 被并发改走的行，**不是错误**。
- **空桶不发语句**（`IN ()` 是语法错误）；退避时刻在域服务算好后作为普通参数传下去，
  SQL 里不用 `INTERVAL ?`（ShardingSphere 解析器会弄丢函数内嵌占位符，票 02 实测）。
- 批内结果种类数 × taskType 数决定写库次数，与批大小无关（满批 200 不再产生最多 600 条逐行 UPDATE）。
- **结算不另开事务**：三条各是单条语句；回调已经发生，回滚结算撤销不了业务动作，靠幂等 + 重试兜底。
- 重试桶的 `SET next_fire_time` 走 `CASE`：各行 `retry_count` 不同 ⇒ 退避不同；仓储层把
  `bizIds` 与 `nextFireTimes` 两个列表按下标对齐，**键序错位会把退避时刻写到别的行上**（集成测试钉住）。

## 6. 回调：进程内分发（票 05）

扫描器到点后逐项调用 `ITimeoutHandler`，生产装配是
`trigger.job.TimeoutActionRouter`——按 `taskType` switch 到既有域动作：

| taskType | 动作 | 说明 |
| --- | --- | --- |
| `PRODUCT_ORDER_PAY_TIMEOUT` | `IdleCase.handlePayTimeout` | 条件 UPDATE 取消 + 商品回在售 + 搜索同步消息 |

- **幂等责任仍在业务状态机**：未命中（已支付 / 已取消 / 不存在）= 幂等成功、零副作用。
  **不要**在回调层加去重表或「已处理 key」。
- **逐项隔离**：一项抛异常只让这一项算失败，同批其余项照常回调、照常结算；扫描 case 逐项 catch。
- **未注册的 taskType、坏 `bizId` 一律按失败重投**（不静默当成功吞掉）：前者是注册与消费的 `Constants`
  不一致，后者是注册方的编程错误，两者都该 16 次后进死信、在管理面显式暴露。
- 复合 `bizId` 按 taskType 各自拆 `{id}:{seq}`；**不能走** `Long.parseLong` 那条路。
- 日志 tag：扫描/死信告警用 `[超时中心告警]`（WARN/ERROR 保留标记），普通 INFO 用 `[超时中心]`；
  **别让 INFO 蹭 `[超时中心告警]`**，否则告警面 grep 会失灵。

## 7. 管理面：死信出口（票 06）

| 动作 | 落点 | 状态迁移 |
| --- | --- | --- |
| 列表 | `GET /api/admin/timeout-tasks/dead-letters` | 只读（`updated_at` 倒序） |
| 重发 | `POST …/dead-letters/resend` | `FAILED → RETRYING`（`retry_count` 清零、`next_fire_time = now`，**不动 `action_time`**） |
| 取消 | `POST …/dead-letters/cancel` | `FAILED → CANCELED` |

- **鉴权**：登录态由 JWT 过滤链罩住（未登录 401），控制器内收 **ADMIN**（其余 403）。
- **列表是唯一跨 16 分表广播的查询**（按状态翻，SQL 无分片键）：故意不塞 hint。**别给它塞 hint**——
  塞了只查一张表、翻不出全量死信。每页上限 50、页码上限 1000 是刻意夹住的（广播一次 = 16 张表各扫一遍，
  深 offset 每张各付一遍）；且与用户请求共享池 10，夹住比放开更必要。
- 重发 / 取消只打 `FAILED` 行，是条件 UPDATE：并发两个管理员抢同一条只有一个赢，不需要加锁。
  返回 false / 进失败清单的含义是「该键当前不是死信」，不是出错。
- 重发走**同一个扫描 job 入口**：不新开重试通路，下一个 10s 周期即被捞起（设计内最多一个周期延迟）。
- 营销侧本期没有管理面（零超时接入方）；口径是「谁有超时表、谁才有管理面」。

## 8. 多实例形态（0/2、1/2）

- 认领是纯全局槽位取模：实例 0/2 拿 `0,2,4,…,14`，实例 1/2 拿 `1,3,5,…,15`，**不重叠**；
  1/3 拿 `1,4,7,10,13`。16 个槽位在任意 `shardTotal` 下恰好被完整覆盖一次（`shardTotal > 16` 时多出的实例空转）。
- 同一槽位只属于一个实例 → 同一行不会被两个实例同时捞起；`DISCARD_LATER` 保证同一实例的 tick 不重叠。
- hint 由仓储在**每次 DAO 调用内**自设自清，槽位任务跑在固定池的哪条线程上都成立；
  hint 是 `TransmittableThreadLocal`，纪律是「**hint 作用域先于子线程的创建/提交**」（票 01）。
- 已验证：`TimeoutTaskScanIntegrationTest` 用 1/3 与 0/2、1/2 两组分片跑完整 16 槽，断言行不落在别人槽位、
  两个分片合计回调 16 次且**同一 key 不重复回调**。

## 9. 新接入方怎么接（既有接入方之外）

判据（防「内嵌中心 + 域内扫描」长期并存）：

- **deadline 已是业务列、动作在同域** → 域内扫描（现状：`idleExpireScan` / `productTimeoutScan`
  都不收编）。
- **deadline 无处存放 / 动作跨域 / 需要重试-退避-死信-管理面** → 超时中心。

接入四步：

1. 在业务域的 `Constants` 加 `taskType`（taskType 是业务词汇，留在业务域）；
2. 在业务仓储的**事务方法**里 `timeoutRepository.register(TimeoutTaskEntity.newWaiting(...))`，
   `action_time` 用业务计时起点；
3. 在 `trigger/job/TimeoutActionRouter` 加一条 case 分支，调该域的状态机动作（条件 UPDATE 自带幂等）；
4. 补一条「注册 → 到点 → 回调 → 状态落定」集成测试；复合 `bizId` 要在分发里加自己的解析形状。

**不要做**：加 `user_id` 列；恢复回调路由列；用 `outbox_message` 承载注册；建 Dubbo 契约；
在扫描 job 里一表一线程；为「一表一线程」抬 Hikari 池。

## 10. 测试与常驻栈

- 集成测试连**预先起好的常驻栈**（`backend/scripts/dev-stack.ps1 up`），测试进程不建容器。
- 物理断言必须**绕过 ShardingSphere**：裸 MySQL 连 `timeout-it.raw-mysql-url`，逐张断言「槽位 = 实际落库物理表」
  （`TimeoutShardingRouteIntegrationTest`）；经 SS 点名物理表是 `TableNotFoundException`。
- `SHOW TABLES` 经 SS 是**原样透传**（返回物理表名），逻辑库可见性要用 JDBC `DatabaseMetaData.getTables` 断言。
- 测试 profile 的 `xxl.job.executor.appname` 必须另起一个（`better-school-executor-test`）：否则 admin 里启用的
  `timeoutTaskScan` 会每 10s 真扫测试库，与用例自己调的 `scan()` 抢同一批行（症状：批次账莫名多出回调）。
- `PostApiTestBase` 每用例清 16 张物理分表：业务表 TRUNCATE 会重置自增 id，而超时行不清会污染下一用例
  （同 bizId 被三分支吸收）。
- 相关集成测试：`TimeoutShardingRouteIntegrationTest`、`TimeoutTaskScanIntegrationTest`（含多实例/退避/死信/竞态）、
  `TimeoutTaskScanParallelismIntegrationTest`、`TimeoutDeadLetterSurfaceIntegrationTest`。

## 11. 切换与存量（票 07，已完成）

- **不做双跑**：回调边已删，旧中心继续扫会把在途行重试进死信；也**不丢弃**（商品支付超时无业务侧兜底）。
- 一次性把 `WAITING/RETRYING/FAILED` 与旧 outbox 的 `PENDING` 残留搬进新表；**槽位公式已变**，
  必须按 `hash(biz_id)` 重算，不能按旧表号搬（按旧表号 = 静默漏扫）。
- 顺序：停旧 job → 滚动上线 → 停新 job → forward + 校验 → 开新 job → 停旧中心 → 观察 24h/7d → 清理。
  回滚用同一工具 `reverse`（含复活/取消陈旧旧行；reverse 前新旧 job 都必须停）。
- 旧库先 dump + restore drill，留档 30 天再 DROP。**旧中心工程已删除**；迁移工具与上线/回滚清单是**一次性历史产物**，
  已归档到 `.scratch/timeout-inline/archive/`（不再随代码演进，别在 `backend/scripts/` 里找它）。

## 12. 已知坑速查

| 坑 | 处方 |
| --- | --- |
| 缺 `!SINGLE` → 业务表 `TableNotFoundException`，启动不报错 | `sharding.yaml` 里显式 `!SINGLE`，且放在 `!SHARDING` 之前 |
| `SHOW TABLES` 看到物理表名 ≠ 逻辑库可见性 | 用 JDBC `DatabaseMetaData.getTables` 断言 |
| 漏塞 hint 的写语句被路由校验拒掉；读语句退化成 16 表广播 | 别绕开 `TimeoutTaskStore` 直接调 DAO |
| `INTERVAL ?` 参数被解析器丢掉 | 退避时刻在 Java 算好，作为 `LocalDateTime` 参数传 |
| `IN ()` 语法错误 | 空桶不发语句（域服务 + 仓储两层都挡） |
| 退避配成 0 → 一个 tick 跑光 16 次重试 | `base-seconds` / `max-seconds` 都兜底 ≥ 1s |
| 重试键序错位 → 退避写到别的行 | `bizIds` 与 `nextFireTimes` 按下标对齐，集成测试钉住 |
| 测试 JVM 注册成生产 xxl appname → 后台真扫测试库 | 测试 profile 用 `-test` 后缀的 appname |
| 16 槽同时抢连接 → 用户请求 `Connection is not available` | 并行度 4 + 池 10；表数 ≠ 连接数 |
