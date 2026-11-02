# Map：超时中心内嵌化（两个业务服务自带超时中心）

Label: wayfinder:map

## Destination

`backend/better-school/` 与 `backend/marketing/` 各自在**进程内**持有一个超时中心：超时任务行与业务写入**同一个本地事务**、16 张分表与业务**同库**、xxl-job **分片广播**（实例认领若干张表，按配置的**有界并行度**执行）；`backend/timeout-center/` 工程整体下线，注册 outbox / Dubbo 注册边与回调边 / 动态 `ReferenceConfig` / 旧扫描链路一并删除。

终点 = **改造落地**：两工程编译过、相关集成测试绿、`CONTEXT.md` 词条 + `backend/CLAUDE.md` + ADR + `backend/docs/` 收口。**本 map 自带执行**（用户 2026-10-16 拍板「直接改造完」），不另起 impl effort。

## Notes

**领域与事实源**：better_school 校园平台；改造前三个后端工程（`better-school` / `marketing` / `timeout-center`），改造后两个。设计事实源为 [`backend/docs/architecture/timeout.md`](../../backend/docs/architecture/timeout.md)（改造后文档；旧独立工程文档已随票 09 删除）、[`.scratch/timeout-center/spec.md`](../timeout-center/spec.md)（旧设计，已被本 effort 取代）、`backend/CLAUDE.md`、`CONTEXT.md`、`sql.md`。

**本 effort 的口径已在 charting 会话（2026-10-16，三轮 grilling）定稿**——执行时不再重开；若执行中发现新证据，记在对应票的 `## Answer` 里再推翻：

1. **载体**：两个工程**各自实现一份**，不抽共享 jar（只共享 DDL 模板与语义文档）。判据：等第三处出现再抽库。
2. **本期只分表不分库，但保留「库×表」两维表达**（用户 2026-10-16 拍板）：`dbCount = 1`、`TABLES_PER_DB = 16`、`TOTAL = dbCount × TABLES_PER_DB = 16`；槽位是**全局展平编号** `g`（`db = g / TABLES_PER_DB`、`table = g % TABLES_PER_DB`），认领条件 `g % shardTotal == shardIndex && g < TOTAL`——**认领只看全局号、与库表维度解耦**，将来 `dbCount > 1` 时认领逻辑一行都不用改（路由侧靠 `HintManager.addDatabaseShardingValue` + `addTableShardingValue` 两维 hint）。16 张表全在**业务自己的 schema** 里（`better_school` / `bs_marketing`，测试侧 `*_test`）。分片键**只在超时表内部自洽**，业务表完全不进路由。
   - ⚠️ **硬约束**：`dbCount > 1` 与本 effort 的核心前提（超时行与业务写入**同事务**）**互斥**——业务行留在原库、超时行按 hash 落到另一个库时同事务立刻失效（退回 2PC 或 outbox+RPC，即改造前形态）。本期 `dbCount` 恒为 1、不得启用：保留的是**表达**，不是**开关**。
3. **路由**：ShardingSphere-JDBC 5.5.3 + **HINT 强制路由**（沿用现工程已跑通的 `sharding.yaml` 与 `TimeoutTaskHintShardingAlgorithm` 形态）。✅ **票 01 已判定（原「唯一待验项」已收口）**：**照现 YAML 原样（只有 `!SHARDING`、没有 `!SINGLE`）整库不可用**——逻辑库只暴露 `timeout_task` 一张表，30 张业务表一律 `TableNotFoundException`；**补一条 `!SINGLE / tables: ["*.*.*"]` 后业务表完全透明，且事务仍是同一条连接、同一个本地事务、一条 rollback 全清**。所以**不需要退化成「MyBatis 表名后缀路由」**，处方是「原形态 + 显式 `!SINGLE`」（纯 YAML 改动）。另：`HintManager` 是 `TransmittableThreadLocal`，纪律口径是「**hint 作用域必须先于子线程的创建/提交**」（不是「同线程」，见票 01）。
4. **调度**：xxl-job `SHARDING_BROADCAST`（`XxlJobHelper.getShardIndex()/getShardTotal()`），认领条件 `slot % shardTotal == shardIndex && slot < 16`（现 `TimeoutTaskScanCase:83-88` 的口径原样保留）。
5. **执行**：**表数 ≠ 并行度**。认领 N 张表，但只按 `timeout.scan.parallelism`（默认 **4**）并发执行、其余排队——池只有 10 且与用户请求共用（现中心池 24 才敢一表一虚拟线程，arch doc §6 记有池 16 时 `active=16` 的实测）。表数（分片粒度）与连接数（真并发上限）**解耦**，绝不为「一表一线程」开线程。
6. **回调**：**进程内方法调用**，case 层按 taskType 分发到各域动作；删 Dubbo 回调边、`callback_group` 列、动态 `ReferenceConfig`。幂等责任仍在业务状态机（未命中 = 幂等成功）。
7. **注册**：业务事务内**直插 `timeout_task`**，删 `timeout_register_outbox` + afterCommit 发送器 + 兜底扫描 job + `ITimeoutTaskRegister` + 双清单 DTO。幂等分支收敛为三分支（新插入 / 在途同键不改期 / 终态复活）；原「不同 group 冲突失败」随 group 概念消失。
8. **边界判据**（防「内嵌中心 + 域内扫描」长期并存）：*deadline 已是业务列、动作在同域 → 域内扫描（现状）；deadline 无处存放 / 动作跨域 / 需要重试-退避-死信-管理面 → 超时中心*。既有 4 个域内扫描 job（`errandTimeoutScan` / `tradeExpireScan` / `productTimeoutScan` / `postReviewTimeoutScan`）**不收编**（`errandTimeoutScan` 是 MQ 延迟消息的兜底，不是 deadline 设施）。
9. **范围**：只搬既有 4 个 taskType —— `PRODUCT_ORDER_PAY_TIMEOUT` / `ERRAND_PAY_TIMEOUT` / `ERRAND_LOCK_TIMEOUT` / `REVIEW_LEASE_TIMEOUT`（全在 better-school；求购随旧域已删）。营销侧**只把落法写进文档，本期不落代码**（营销零接入方）。
10. **旧链路一并删**：`ProductTimeoutScanJob` + `ProductPayTimeoutConsumer`（及其 xxl seed 行）。

**为什么这次改造值得做（改造收益的账面）**：`timeout_register_outbox` 已经在业务库、也已经在业务事务内写（`TimeoutRegisterOutboxStore:47-56`），所以「能同事务」不是新闻；真正换到的是**删掉整条 outbox + Dubbo + 动态引用链路**（1 张表 + 4 个类 + 1 个 job + `timeout-center-api` 整个 jar），并让 arch doc §8 记的「注册在途 vs 取消竞态 / 陈旧任务提前关单」这个残留窗口**结构性消失**。

**参考（改造前实测数据，执行时按此定位）**：`timeout-center` 是唯一用 ShardingSphere 的工程、也是唯一**没有任何事务设施**的工程（全仓 `@Transactional` 0 命中，事务一律编程式 `TransactionTemplate` 且规定落仓储内，`backend/CLAUDE.md:35`）；两个业务工程 Hikari 池均 **10**；四张业务表的 deadline/轮次列（`errand_order.lock_seq` / `review_task.claim_seq`）已就位，复合 `bizId` 形态已在用。

## Decisions so far

<!-- 每张票 resolve 后追加一行：要点 + 链接。charting 阶段的口径见上面 Notes。 -->

- **票 01（ShardingSphere 挂业务 datasource 的爆炸半径）→ 口径 3 成立，处方是「原形态 + 显式 `!SINGLE tables: ["*.*.*"]`」**：5.5.3 无 `!SINGLE` 时未配置表**不直通**（逻辑库只暴露 `timeout_task`，业务表全 `TableNotFoundException`；`single-table.default-data-source-name` 已不存在，`defaultDataSource` 单独写也无效，`tables` 只认 `*.*.*`/`*.*` 两个字面量）；补上之后 30 张业务表**完全透明**、hint 分片路由照常、**同一条 MySQL session 同一本地事务**（`CONNECTION_ID` 三次相同、一条 rollback 三张表全清）。**不退化**为 MyBatis 表名后缀路由；「分裂 datasource」形态确认**保不住同事务**（5.5.3 只有 XA，不是本地事务合并）。附带纠正两条旧口径：`HintManager` 是 `TransmittableThreadLocal`（「作用域先于子线程创建/提交」，非「必须同线程」）；上一轮记的「偶发 `TableNotFoundException`」是探针自身缺陷（探针直接点名物理分表 + 四变体共用同一 schema/URL + 断言混入历史行），已干净复现排除。→ [票 01](issues/01-research-ss-blast-radius.md)
- **票 02（域与存储）→ 落地，路由取 (a)：SS 5.5.3 挂业务主 datasource**：`domain.timeout` 骨架（entity/TaskStatusVO/`ITimeoutRepository` 按键四原语）+ infra（PO/DAO/mapper XML/`TimeoutRepository` + `TimeoutTaskShardSlots` 单点槽位函数 + 库维/表维两个 CLASS_BASED hint 算法，g 同时塞两维 hint）；16 张 `timeout_task_00..15` 进 `better-school/sql/schema.sql`（删 `callback_group`，保留 `created_at/updated_at` 作口径例外登记）；`sharding.yaml`/`sharding-test.yaml`（`!SINGLE` 在前 + `databaseName` 显式）+ 启动期自检 `ShardingSelfCheck`；hash 输入改 **`biz_id`**；两维表达保留（`DB_COUNT=1` 硬约束写进 `TimeoutConstants`/YAML/架构文档 §0）；`user_id` 不加（理由入档）。验收：裸 MySQL 连接逐张断言「槽位 = 实际落库物理表」的集成测试绿 + 全量集成测试绿。坑记录：`SHOW TABLES` 经 SS 是**原样透传**（返回物理表名），逻辑库可见性必须走 JDBC `DatabaseMetaData.getTables` 断言——自检第一版用 SHOW TABLES 误判了健康的配置。→ [票 02](issues/02-embedded-timeout-domain.md)
- **票 04（扫描 job + 有界并行 + 结算/退避/死信）→ 落地，池与并行度的最终取值定为「池 10 不动 + `timeout.scan.parallelism = 4`」**：`bs-trigger` 的 `@XxlJob("timeoutTaskScan")`（seed 行 `0/10 * * * * ?` + `SHARDING_BROADCAST` + `DISCARD_LATER` + 重试 0 + `FIRE_ONCE_NOW`）→ `usecase.timeout.TimeoutTaskScanCase`（认领只看全局槽位号；**宽度 = min(并行度, 认领表数)** 的每 tick 固定池，绝不一表一线程；一 tick 一个 `dueBefore`、每槽循环拉批到空；批内逐项回调 + 逐项隔离）→ `domain.timeout.service.scan.TimeoutTaskScanService`（结果 × taskType 分桶，每桶一条条件 UPDATE，三条都保 `status IN ('WAITING','RETRYING')` 行级守卫；退避 30s 起指数翻倍、单次封顶 1h、第 16 次进 FAILED）。**实测（16 张表全认领）**：回调并发峰值 = 4、扫描线程去重 ≤ 4、扫描期 Hikari 活跃连接峰值 ≤ 4（池 10 留 ≥6 给用户请求）；认领 8 张表时并发峰值同样 = 4。**`taskType → 四个域动作` 的 switch 已抽到 `usecase.timeout.TimeoutTaskCallbackDispatcher`**（domain 端口 `ITimeoutTaskCallbackPort` 的进程内实现，扫描与 Dubbo Provider 共用），票 05 只剩删 Dubbo 边 + 真实动作验收。执行中两处补强：`retry.max-seconds` 与 `base-seconds` 一样兜底 ≥1s（否则配成 0 会在一个 tick 里跑光 16 次重试、破「同一 tick 不重捞」）；测试探针在 `@BeforeEach`+`@AfterEach` 都复位（`@Primary` 单例 + 缓存 context，桩住的模式会漏给下一个共用 context 的测试类）。→ [票 04](issues/04-scan-and-parallelism.md)

- **票 05（进程内回调分发）→ 落地**：`TimeoutTaskCallbackDubboService`（Provider）+ bs-trigger/根 pom 的 `timeout-center-api` 依赖删除，better-school 全模块零 `com.betterschool.timeout.api` 引用；分发本体（`TimeoutTaskCallbackDispatcher`）原地保留，5 个测试类的回调入口从 Dubbo Provider 换成进程内分发器（双清单断言改逐项隔离驱动器，与扫描 case 同口径）；四个 taskType 的到点动作验收全绿、9 个集成测试类 46 用例全绿、验收 grep（`ITimeoutTaskCallback\b|TimeoutTaskCallbackDubboService|ReferenceConfig`，`\b` 词边界避免误命中保留的域端口 `ITimeoutTaskCallbackPort`）在两业务工程零命中。arch doc §0 增补落地条目、§9 加过时横幅（group/改名失配坑随边删除消失）；旧中心侧回调类与整工程下线仍归票 07/09。→ [票 05](issues/05-in-process-callback.md)

- **票 06（死信管理面落点与鉴权）→ 落地，四问裁决：只主体落 / 接 JWT+ADMIN / 钳制保留 / 重发取消不变**：主体侧 `bs-trigger/trigger/http/timeout/TimeoutTaskAdminController` 走 8080 的 `/api/admin/timeout-tasks/**`，登录态由 SecurityConfig 过滤链 401、控制器内 `requireAdmin()` 收 ADMIN（403，与 `AdminReviewController` 同款），原「无鉴权 + 8083 部署侧收紧」缺口关闭；控制器照 §4/§5 实现 bs-api 的 `ITimeoutTaskAdminService`、DTO 进 `api.dto`（code-review 纠正首版偏差）。**票面「退化成单表分页」的前提被纠正**：分表没消失、列表仍是 16 分表广播归并（仓储 `countDeadLetters`/`selectDeadLetters` 是全仓唯二故意不塞 hint 的读），且现在与用户请求共享池 10——每页 50/页码 1000 钳制保留且更有必要；重发/取消仍只打 FAILED（票 04 仓储原语，本票只接 HTTP 面）。营销不落（零接入方，口径「谁有超时表谁才有管理面」入档，票 08 将来时）。`domain.timeout.service.deadletter` 两件套 + bs-case 用例落地；集成测试 7 用例（401/403、跨槽广播归并、钳制、条件 UPDATE 语义）+ 超时域 39 用例全绿（全量套件中途 MySQL 容器崩掉、31 个类连接错误为环境事故，与本票无关）；docs/api/timeout.md 新建、arch doc §0/§7 更新。→ [票 06](issues/06-dead-letter-surface.md)

- **票 07（存量在途任务收尾与切换顺序）→ 落地，选 (b) 一次性搬迁，不做双跑/不丢弃**：(a) 双跑被否——票 05 已删业务侧 Dubbo 回调 Provider，旧中心继续扫会把在途行重试进死信；(c) 丢弃被否——商品支付超时无业务侧兜底、跑腿复合 `bizId` 无可重放事件源。范围：`WAITING/RETRYING/FAILED` + `timeout_register_outbox.PENDING`，不搬 `SUCCESS/CANCELED`；目标同键保留目标行、只补缺不覆盖；outbox 同键按旧中心状态 + `action_time` 判定：在途跳过、终态且 outbox 更晚算新一轮注册（搬）、终态且 outbox 不更新算残留（跳过）。**关键坑**：槽位公式从 `hash(len(taskType)+":"+taskType+bizId)` 改为 `hash(bizId)`，必须按新公式重算（按旧表号搬 = 静默漏扫），工具已实现并校验目标每行物理表号。顺序：停旧 job → 滚动上线 → 停新 job → forward + 校验 → 开新 job → 停旧中心 → 观察 24h/7d → 清理；回滚用同一工具 `reverse`（含复活/取消陈旧旧行，reverse 前旧/新 job 都必须停）。`bs_timeout` 最终 dump + restore drill、留档 30 天再 DROP；`xxl_job_info`/`xxl_job_group` 先归档后删（先 info 后 group），`timeout-center/` 目录删除归票 09。产出 `.scratch/timeout-inline/archive/timeout-cutover-migrate.py` + 39 个离线单测 + [runbook](archive/timeout-inline-cutover.md)；干净 E2E（真实 outbox 列名）forward 5 插入/3 跳过、重跑幂等、reverse 2 插入/1 复活/1 取消、错表退出码 3 全通过。执行中纠正了 outbox 列名、outbox 终态键的新旧轮次判定、xxl 读不到静默放行三处缺陷。→ [票 07](issues/07-inflight-migration.md)
- **票 08（营销侧规格）→ 落地（纯文档，零表零码）**：新建 [`backend/marketing/docs/architecture/timeout.md`](../../backend/marketing/docs/architecture/timeout.md) 把「将来要落时怎么落」定死——16 张 `timeout_task_00..15` 进 `marketing/sql/schema.sql`（`DB_COUNT=1`、列与主体同构含 `created_at/updated_at`，落表时登记为营销库 naming 的第二处例外）；超时按**技术域**在 `types/domain/infrastructure/usecase/trigger` 各占 `timeout` 段，`marketing-api` 只放管理面 DTO、不建 Dubbo 契约；注册走业务域自己的仓储方法（同事务直插），回调端口在域层、Dispatcher 在 case 层；SS 5.5.3 + 显式 `!SINGLE` + hint，xxl 分片广播 + `parallelism=4`/池 10；管理面路径用营销前缀 `/api/marketing/timeout-tasks/**`（`/api/admin` 前端代理到 8080，不能照抄；照 `/api/marketing/config` 先例不设 `/admin` 段）。三个既有扫描 job（`pointsExpireScan`/`drawCountBucketExpireScan`/`inventoryMerge`）**不收编**：前两个是懒过期 + 批量落账（读路径 `expire_at > NOW()`），`inventoryMerge` 是每秒结算流水线；判据与「每个积分桶一条超时任务 → 行数随入账笔数放大」反例入档。`CONTEXT.md` 只新增「过期落账」词条——「超时任务」「注册 outbox」两条归票 09 改写，两票不碰同一词条。→ [票 08](issues/08-marketing-side-spec.md)
  - **2026-10-18 补注**：那条判据里点名的 `inventoryMerge`（每秒结算流水线）**已被
    `marketing-inventory-refactor` effort 删除**，合并改为「预扣发 1 秒 MQ 延迟消息 + 60 秒
    `inventoryMergeBackstop` 兜底」，按 `(segment, inv_id, bucket_id)` 合最老时间格。判据随之失效
    ——它不再是「三级扫描 job 之一」，也不该被当超时任务收编（没有 deadline、没有逐对象回调）。
    `timeout.md` §5 已同步改写（两个扫描 job + 一段「已删除」说明）。

- **票 09（收口）→ 落地**：删除 `backend/timeout-center/` 整工程与旧架构文档；一次性 cutover 产物（runbook + 迁移脚本 + 离线单测）归档到 `.scratch/timeout-inline/archive/`；新建 `backend/docs/architecture/timeout.md`；`sql.md` 删 §3 旧独立库整段、表数纠正为 **29+16+22=67**；`CONTEXT.md` 改写「超时任务」、删除「注册 outbox」、新增「超时槽位」「死信」；ADR 0010；`backend/CLAUDE.md` 两工程口径 + 两处规范例外；`dev-stack.ps1`/`test-env.sh`/`run-app.sh`/`docker-compose.yml` 收口两库两服务。验收：两工程 `mvn -o clean package -DskipTests` SUCCESS、全量 **328** 测试全绿（新增 0/2、1/2 多实例用例与四 taskType E2E + 退避/死信）、四个旧 token 全仓零命中、`sql.md` 与两份 `schema.sql` 逐表逐列全等、`better_school` 45 = 29+16。→ [票 09](issues/09-closeout.md)

## Not yet specified

- ~~**池大小与 `timeout.scan.parallelism` 的最终取值**~~：**票 04 已定稿**——池保持 10（全应用共享，抬它等于抬用户请求的并发上限），`timeout.scan.parallelism = 4`；实测 16 张表全认领时回调并发峰值 = 4、扫描期 Hikari 活跃连接峰值 ≤ 4，给业务留 ≥6 条。结论与实测见[票 04 的 Answer](issues/04-scan-and-parallelism.md)。
- **超时任务行的归档口径**：spec 原写「首版不归档、终态行永久保留」，量级论证是「独立库 20MB/年」；现在表进了业务库，业务库要不要归档、按什么口径（`updated_at` 搬历史表？）需要重新确认。
- ~~**`sql.md` 的表数口径**~~：**票 09 已收口**——统一写成「29（单体业务）+ 16（单体超时分表）+ 22（营销）= 67」，并删掉旧独立库的 §3；第 30 张注册意图表随票 03 删除。见[票 09](issues/09-closeout.md)。
- ~~**`bs_timeout` 库的去留**~~：**票 07 已定稿**——停写后做最终 dump + 恢复演练，留档 30 天再 DROP（含 `bs_timeout_test`）；`backend/timeout-center/` 目录删除归票 09，且必须在验收之后。见[票 07 的 Answer](issues/07-inflight-migration.md) 与 [上线/回滚清单](archive/timeout-inline-cutover.md)。
- **改造后是否顺手修两处已知文档/配置漂移**：`marketing` 测试 profile 没设独立 `xxl.job.executor.appname`（测试 JVM 会注册成生产 appname）、`reviewDispatchObservation` 没有 seed 行。都在本次改动路径附近，但**不属于本 effort 的终点**，执行时若不顺手就单列。
- ~~**回调端口实现在 case 层的规范回写**~~：**票 09 已收口**——写进 `backend/CLAUDE.md` §4 的显式例外（端口接口仍在 domain，实现落 `usecase.timeout.TimeoutTaskCallbackDispatcher`，不反向把 case 依赖灌进 infrastructure）。

## Out of scope

- **业务表分库分表 / 按 `user_id` 分库 / 启用 `dbCount > 1`**：一旦业务表与超时表分到不同库，本 effort 的全部前提（同事务注册）立刻失效；真要做是另一次全库改造（30 张表 + 66 处事务点，且分片键应由业务表实际选的那个维度决定）。见票 02 里对「加 `user_id` 列预留分库」的否决理由与两维表达的硬约束。
- **营销侧超时接入方的实现**：营销零接入方，本期只定规格（票 08）。
- **既有 4 个域内扫描 job 的收编**：判据见 Notes 8；本期不动，只把判据写进文档。
- **跑腿执行时限（`errand_order.deadline`）接入超时中心**：`.scratch/timeout-center/spec.md §10` 已排除，本期维持。
- **回改历史 `.scratch/**` 里基于「中心是独立微服务」做出的 resolved 结论**：历史记录保留，新口径从本 effort 起生效。
