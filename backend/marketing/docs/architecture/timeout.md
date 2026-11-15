# 营销侧超时中心接入规格（未落地）

> 归属：营销服务（`backend/marketing/`）。**状态：规格——本期不落表、不落代码。**
> 来源：[`.scratch/timeout-inline/issues/08-marketing-side-spec.md`](../../../../.scratch/timeout-inline/issues/08-marketing-side-spec.md)（内嵌化 effort 票 08）。
> 主体实现与口径事实源：[`backend/docs/architecture/timeout.md`](../../../docs/architecture/timeout.md)、
> [`backend/CLAUDE.md`](../../../CLAUDE.md) §1–§3/§7。

## 0. 结论

营销工程**零超时接入方**：没有任何 `timeout_task` 读写、没有 `ITimeoutTask*` 引用（`Timeout` 字面只命中
outbox 的 `scanTimeoutSeconds`、一处 javadoc 的 `payTimeoutMinutes` 与 RocketMQ 客户端副本的 `consumeTimeout`，
都与超时中心无关）；`bs_marketing` 20 张表里唯一带重试语义的是 MQ 的 `outbox_message`（事件重发，不是 deadline）。
所以本期**不预建** 16 张空表 + 一套无人调用的扫描 job——
空表 + 空扫描是纯负债，还会让后来者误以为营销已经有超时接入方。

将来真出现接入方时，**照主体的同一形态一次落地**（§6 是清单）：

- 16 张 `timeout_task_00..15` 进 `bs_marketing`，`DB_COUNT = 1`；
- 超时按**技术域**占一个 `timeout` 域段（五层同名包，§2）；
- 路由 = ShardingSphere-JDBC 5.5.3 + 显式 `!SINGLE` + HINT 强制路由（§3）；
- xxl `SHARDING_BROADCAST` 认领 16 槽位；执行并行度 `timeout.scan.parallelism = 4`，与池 10 解耦（§4）；
- 回调是进程内分发，**不建 Dubbo 边**。

两条**不可动摇**的约束：

1. **`DB_COUNT` 恒为 1**：超时行落到另一个库 = 与业务写入不再同一条连接、不再同一个本地事务，
   而「同事务注册」正是内嵌化的全部前提（主体 arch doc §0 的硬约束照抄）。
2. **表数 ≠ 并行度**：池 10 是全应用共享（HTTP / 抽奖 / 积分同池），认领 16 张表也只按并行度 4 真并发，
   绝不为「一表一线程」开线程。

两个既有营销扫描 job **不迁**（§5）：它们分别是批量到期落账 / 懒过期，
不是「一对象一 deadline 一动作」，套进超时模型会放大行数而不增加任何能力。

## 1. 库与表

### 1.1 16 张分表

| 项 | 值 |
| --- | --- |
| 落点 | `marketing/sql/schema.sql`（本工程**唯一 DDL 来源**） |
| 逻辑表 | `timeout_task`（业务/测试 SQL 只写逻辑表名） |
| 物理表 | `timeout_task_00..15`，逐张同构（零填充，不用 `${0..15}` 内联表达式——它生成的是 `_0..`） |
| 槽位表达 | `DB_COUNT = 1`、`TABLES_PER_DB = 16`、`TOTAL_SLOTS = 16`；**全局展平编号** `g`：`db = g / 16`、`table = g % 16` |
| 槽位公式 | `slot = floorMod(hash(biz_id), 16)`（hash 输入只有 `biz_id`，单点函数 `TimeoutTaskShardSlots`） |
| 建库文件 | `marketing/sql/init-dev.sql` / `init-test.sql`：本票不改；表进 `schema.sql`，开发 `bs_marketing`、测试 `bs_marketing_test` 共用 |

**列与主体逐列同构，且没有回调路由列**（进程内分发不需要按应用名路由）。DDL 直接照
`better-school/sql/schema.sql` 的 16 张表复制，只改表注释（`超时任务分表 NN` → `营销超时任务分表 NN`）；
逻辑表形状与主体完全一致（下面的片段为可读性压缩了列注释的长尾，**落表以主体 schema.sql 原文为准**，
表注释除外）：

```sql
CREATE TABLE IF NOT EXISTS timeout_task_00 (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键（无业务含义）',
    task_type       VARCHAR(64)  NOT NULL COMMENT '任务类型（业务对象上的一种超时动作）',
    biz_id          VARCHAR(64)  NOT NULL COMMENT '业务引用 id（不带快照；复合引用编码进此列；分片 hash 输入）',
    action_time     DATETIME     NOT NULL COMMENT '理论到期时刻（单轮内不可变；终态复活=重开一轮时刷新）',
    next_fire_time  DATETIME     NOT NULL COMMENT '下次可触发时刻（注册时=action_time；失败重试后=now+退避）',
    status          VARCHAR(16)  NOT NULL COMMENT 'WAITING/RETRYING/SUCCESS/FAILED/CANCELED（5 态，无 PROCESSING）',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数（16 次封顶进 FAILED）',
    created_at      DATETIME     NOT NULL COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (task_type, biz_id),
    KEY idx_scan (status, next_fire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '营销超时任务分表 00';
```

- **不加 `user_id`**：内嵌化票 02 已显式否决（主查询按 `(status, next_fire_time)` 扫、分片键不参与；
  路由键 ⊥ 幂等键会引入「user 与幂等键绑定不可变」的额外不变量，破了即双行双回调）。
- 营销是**全新落表**、没有存量任务要搬：不需要主体那种 forward/reverse 迁移工具与切换顺序。
  落表后空表随首个接入方开始写入。
- 表数：营销现在 **20** 张，落超时后 **36** 张（20 + 16）。
  `marketing/docs/README.md` 与根 `sql.md` 的营销段届时同步；营销这 16 张在真正落表时再补进 `sql.md`
  （当前全库口径：单体 29 业务表 + 16 超时分表、营销 20 张）。

### 1.2 列名口径的例外（落表时必须登记）

营销 `schema.sql` 头注释现在写「每表 `create_time`/`update_time` 收尾（不带默认值，由应用写入），
**全库无 `created_at`/`updated_at`**」；`sql.md` 文首的命名口径也只登记了主体那一处例外。
营销这 16 张表**沿用主体的 `created_at`/`updated_at`**，落表时这是**第二处例外**，
要在 `schema.sql` 头注释与 `sql.md` 命名口径各补一行，否则下一个人会以为可以随手改成
`create_time`/`update_time`。

为什么不让营销改名：

- 两个工程共享的是**同一份 DDL 模板与语义文档**（map Notes 1），列名一分叉，两份 PO / mapper XML
  就不再逐行可对照，排查要同时记两套；
- 主体那 16 张是从原独立超时库原样迁入的，改名要动 16 份 DDL + XML + 存量迁移，收益只有命名整齐
  （内嵌化票 02 已拒绝）；营销是全新落表，唯一代价是一行例外注释——比维护两套列名便宜。

## 2. 域段落位

营销是**多域工程**（`points` / `drawcount` / `raffle` / `inventory` / `config`），照
[`backend/CLAUDE.md` §7](../../../CLAUDE.md) 的口径「**先按域分段、再按三类分**」。
超时是**技术域**：没有业务模型、没有面向用户的词汇，只有 deadline 登记、扫描、重试/死信与管理面；
业务域只通过「自己仓储方法里的注册」和「回调端口」认识它。因此它在每一层各占一个 `timeout` 域段，
**不摊进任何业务域**。

| 层 | 落点 | 放什么 |
| --- | --- | --- |
| `marketing-types` | `types.timeout.TimeoutConstants` | 设施常量：`TASK_LOGIC_TABLE`、`DB_COUNT=1`、`TABLES_PER_DB=16`、`TOTAL_SLOTS=16`、ds 前缀 |
| `marketing-domain` | `domain.timeout.*` | `model.entity.TimeoutTaskEntity`；`model.valobj`（`TaskStatusVO`/`TaskKeyVO`/`CallbackReportVO`/`ScanSummaryVO`/`SettleOutcomeVO`/`DeadLetterPageVO`/`DeadLetterResendOutcomeVO`）；`service.scan.{ITimeoutTaskScanService,TimeoutTaskScanService}`；`service.deadletter.{ITimeoutTaskDeadLetterService,TimeoutTaskDeadLetterService}`；`adapter.repository.ITimeoutRepository`；`adapter.port.callback.ITimeoutTaskCallbackPort` |
| `marketing-infrastructure` | `infrastructure.timeout.*` | `dao.ITimeoutTaskDao` + `dao.po.TimeoutTaskPO`；`adapter.repository.TimeoutRepository`；`sharding.{TimeoutTaskShardSlots,TimeoutTaskDbHintShardingAlgorithm,TimeoutTaskTableHintShardingAlgorithm}` |
| `marketing-case` | `usecase.timeout.*` | `TimeoutTaskScanCase`（认领 + 有界并行 + 结算）、`TimeoutTaskCallbackDispatcher`（端口实现）、`TimeoutTaskDeadLetterCase`（管理面编排） |
| `marketing-trigger` | `trigger.timeout.job.TimeoutTaskScanJob`；需要管理面时 `trigger.timeout.http.TimeoutTaskAdminController` | 只做入口，不含业务判据 |
| `marketing-api` | 仅管理面 DTO（`api.timeout.dto`） | **不建 Dubbo 契约**——没有跨进程边，别复制改造前独立中心那套契约 jar |
| `marketing-app` | `config/ShardingSelfCheck`；`config/MybatisConfig` 的 `@MapperScan` 加一行；`mybatis/mapper/timeout/` | 应用装配与 mapper XML（营销 `mapper-locations` 是 `classpath:/mybatis/mapper/**/*.xml`，子目录可用） |

落位细则：

- **Mapper 扫描逐域列出**：`MybatisConfig` 的 `@MapperScan` 现有 6 个域段，落超时时**显式加**
  `com.betterschool.marketing.infrastructure.timeout.dao`；不要扫 `infrastructure.dao` 伪公共前缀
  （磁盘上不存在），bean 名冲突继续靠 `FqcnAnnotationBeanNameGenerator`。
- **业务域的 `taskType` 常量留在业务域**（`types.<域>.Constants`，如 `types.raffle.Constants`），
  因为 taskType 描述的是业务对象的超时动作，是业务词汇；`types.timeout` 只放超时设施自身的常量。
  这也避免「加一个接入方就改 `types.common`」。
- **一域一仓储**：超时域的 `ITimeoutRepository` 是唯一仓储；扫描、结算、死信都在它上面。
  不要按聚合拆多个仓储。

### 2.1 注册路径：走业务域自己的仓储方法

业务域服务**不注入** `ITimeoutRepository`（`backend/CLAUDE.md` §1：domain 只注入本域仓储 + 本域 port）。做法照主体：

- 业务域仓储接口加一个语义方法（如 `IRaffleRepository.scheduleXxxTimeout(...)`）；
- 实现在 `infrastructure.<业务域>.adapter.repository` 内**注入 `ITimeoutRepository`**，在**该仓储自己的
  事务内**直插超时行（同一条连接、同一个本地事务，回滚即无行、提交即被扫描可见）；
- 三分支幂等（新插入 / 在途同键不改期 / 终态复活）由 `ITimeoutRepository.register` 在库上裁决，
  业务仓储不重复实现。原 outbox 的「同键复位」语义由这三分支接管，不需要「先 cancel 再复位」。

主体先例：`TradeRepository.schedulePayTimeout` / `ErrandRepository` / `ReviewTaskPoolStore`。

### 2.2 回调路径：端口在域层、实现在 case 层

- `domain.timeout.adapter.port.callback.ITimeoutTaskCallbackPort` 定义「到点执行一条业务动作」；
- 实现 `usecase.timeout.TimeoutTaskCallbackDispatcher` 落在 **case 层**，`switch(taskType)` 分发到营销各域动作。
  这是 `backend/CLAUDE.md` §1/§3「domain 端口由 infrastructure 实现」的一处**已知例外**：分发要调其它域的 case，
  而 infrastructure 看不到 case 层。内嵌化票 04/05 也只把这条例外写在实现类 javadoc、**没有回写规范**；
  营销真正落码时要把它一并写进 `backend/CLAUDE.md` §4 的例外说明（或架构文档），
  不要再留一个只在 javadoc 里的口头例外。
- 幂等责任仍在**业务动作的状态机**（条件 UPDATE）；未命中 = 幂等成功，重复回调零副作用。
- 未注册 taskType / 坏 `bizId` 一律**抛异常按失败重投**（16 次进 FAILED、管理面可见），不静默当成功。
  回调载荷只有 `TimeoutTaskEntity` 的定位字段，**不带业务快照**——业务自己回查。

### 2.3 与 `types.common`、`common/outbox` 的边界

| 相邻物 | 是什么 | 边界 |
| --- | --- | --- |
| `types.common` | 跨域公共件：`AppException` / `ResponseCode` / `OutboxStatus` / MQ topic / 跨域 wire 契约 / 框架装配常量 | 超时的 `TimeoutConstants` 是域专属常量，落 `types.timeout`；状态值对象 `TaskStatusVO` 按主体落 `domain.timeout.model.valobj`。两者都**不要**塞进 `types.common`——那会让所有域耦合进技术域，也是 §7 明令的「域自有常量放 common 会在第二域并入时撞名」 |
| `infrastructure.common.outbox` + `outbox_message` | **事件发布**：业务事务写行 → `afterCommit` 异步发 MQ → `state 0/1/2` + 重发/死信 | 超时表是 **deadline 登记**：业务事务写行 → 扫描到期 → 进程内回调。二者唯一共同点是「与业务写同事务」，因此**不要用 `outbox_message` 承载超时注册**，理由见下 |
| `domain.common` | 跨域只读端口（`IUserQueryPort` 等） | 超时域不读用户、不碰业务表，只认 `TimeoutTaskEntity` 的定位字段 + 端口；**不要把 `UserBasicVO` 塞进超时载荷** |

**为什么超时注册不能走 outbox**：

1. outbox 的重发扫描只补发**尚未发出**的消息，消费端是 MQ 订阅方——没有任何机制在 15 分钟后回调业务；
   把到期时刻塞进 outbox 就得**再造一个扫描器**，正是本 effort 要删掉的形态。
2. outbox 的 `state/retry_count` 表达不了「在途同键不改期 / 终态复活 / 每轮 action_time」，
   而这三条正是超时幂等的全部语义。
3. outbox 发送成功后即 `state=1`；超时任务要到点才动作，且失败要按退避重试到死信——
   生命周期、重试语义、管理面都不同。

反过来是允许且正常的：**回调动作里可以写 outbox**（如超时关单后发通知/事件），
两者同事务共存、互不替代。

## 3. 路由

与主体同一处方（内嵌化票 01 实测、票 02 落地）：**ShardingSphere-JDBC 5.5.3 挂业务主 datasource +
HINT 强制路由**。照 `better-school/bs-app/src/main/resources/sharding.yaml` 复制，只改四处：
`databaseName`、`jdbcUrl` 的库名、两个 `algorithmClassName` 的包名、表注释。
本节与主体 arch doc §0/§3 重叠的部分（`DB_COUNT`、`!SINGLE`、hint 纪律）**以主体为事实源**，
本文只固定营销侧的差异（库名、包名、管理面路径、池共用关系）；主体先改时，本节跟改。

| 项 | 定死 |
| --- | --- |
| 配置文件 | `marketing-app/src/main/resources/sharding.yaml`（开发 `bs_marketing`）+ `src/test/resources/sharding-test.yaml`（测试 `bs_marketing_test`）；`spring.datasource.url` 改 `jdbc:shardingsphere:classpath:sharding.yaml`。SS 解析不到 Spring `${}`，所以只能是两份实值 YAML |
| `!SINGLE` | **必须在 `!SHARDING` 之前**，`tables` 只认 `"*.*.*"` / `"*.*"` 两个字面量（逐张列出静默无效）。缺了它，20 张业务表在逻辑库里全部 `TableNotFoundException`，且启动成功、第一条业务 SQL 才炸 |
| 物理表 | `actualDataNodes` 逐张写 `ds0.timeout_task_00,...,_15`（内联表达式生成 `_0..`，零填充对不上） |
| hint | 每一次 DAO 调用前，仓储把**同一个**全局槽位 `g` 同时塞 `addDatabaseShardingValue` + `addTableShardingValue`；漏塞时写语句被路由校验当场拒掉、读语句退化成 16 表广播 |
| 线程纪律 | `HintManager` 是 `TransmittableThreadLocal`：**hint 作用域必须先于子线程的创建/提交**（不是「必须同线程」） |
| 算法 | 库维/表维各一个 `CLASS_BASED` hint 算法；miss 路由目标即抛 `IllegalStateException`，不静默 |
| 内层池 | YAML 内 Hikari `maximumPoolSize: 20`（≥ 外层 10：每个外层 `ShardingSphereConnection` 干活都占一条内层连接，余量给扫描并行度与驱动元数据） |
| 启动自检 | `marketing-app/.../config/ShardingSelfCheck`：逻辑库必须可见 `timeout_task` + 若干代表业务表、且不得见任何 `timeout_task_NN` 物理名；验不过启动失败。**必须走 JDBC `DatabaseMetaData.getTables`**——`SHOW TABLES` 经 SS 是原样透传，返回物理表名，会误判健康的配置 |

**硬约束**：`DB_COUNT > 1` 与「超时行与业务写入同事务」互斥（业务行留在原库、超时行按 hash 落到另一个库时，
同一条连接、同一个本地事务立刻失效，退回 2PC 或 outbox+RPC）。营销同样**恒为 1、不得启用**：
保留「库 × 表」两维表达是为了将来不必重写结构，**保留的是表达，不是开关**。
真要启用，前提是**业务表也按同一分片键一起分库**（另一次全仓改造，不在本 effort 范围）。

**备选路线（不走，除非出现主体没遇到的新阻塞）**：MyBatis 表名后缀路由。它同样对 20 张业务表零影响，
但会让两份实现的 PO/mapper 不再逐行可对照，也放弃了主体已验证的 `!SINGLE` 处方。若真要走，
必须在 issue 里记成显式偏离；**槽位函数与幂等契约不变**（那才是不可变的部分）。

新增 SQL 一律**不用 `INTERVAL ?`**（SS 解析器会丢函数内嵌占位符；票 02 实测踩过）——退避时刻在域服务算好当普通参数传。

## 4. 调度与并行

### 4.1 任务 seed

落 `marketing/sql/xxl-job-tasks.sql`，执行器 `marketing-executor`（现有 6 个 handler，落超时后 7 个）。
照文件里既有的 `INSERT ... SELECT ... WHERE NOT EXISTS (executor_handler, job_group)` 幂等形态加一行：

| 参数 | 值 | 为什么 |
| --- | --- | --- |
| `executor_handler` | `timeoutTaskScan` | 与主体同名，语义同 |
| `schedule_conf` | `0/10 * * * * ?` | 10s 周期（精度口径见主体 arch doc §6） |
| `executor_route_strategy` | `SHARDING_BROADCAST` | 每个实例都触发，靠 `shardIndex/shardTotal` 认领槽位 |
| `executor_block_strategy` | `DISCARD_LATER` | tick 重叠丢后到的（防漏靠下轮重扫） |
| `executor_fail_retry_count` | `0` | 调度层不重试，周期重扫即重试 |
| `misfire_strategy` | `FIRE_ONCE_NOW` | 错过补一次 |
| `trigger_status` | `1` | 直接启用 |

认领条件照主体：`slot % shardTotal == shardIndex && slot < TOTAL_SLOTS`。
**只吃全局槽位号**，不要在这里写死库表维度（将来 `dbCount` 变了认领逻辑一行不用改）。

### 4.2 并行度与池

营销连接池：`application.yml` 的 `spring.datasource.hikari.maximum-pool-size: 10`、
`connection-timeout: 3000`；虚拟线程已开（`spring.threads.virtual.enabled: true`），因此**池是唯一真并发上限**，
且与 HTTP 用户请求、抽奖主链路共用同一个 10。

| 配置 | 值 | 说明 |
| --- | --- | --- |
| `timeout.scan.parallelism` | **4** | 唯一并发旋钮；**宽度 = min(并行度, 认领表数)**，其余排队。绝不为「一表一线程」开线程 |
| `timeout.scan.batch-size` | 200 | 单批上限（循环拉批到空的步长） |
| `timeout.retry.base-seconds` | 30 | 首败 30s 后指数翻倍 |
| `timeout.retry.max-seconds` | 3600 | 单次退避封顶 1h |
| `timeout.retry.max-times` | 16 | 第 16 次失败置 `FAILED` 死信 |

- 内嵌化票 04 实测（16 张表全认领）：回调并发峰值 **= 4**、扫描线程去重 **≤ 4**、扫描期 Hikari active **≤ 4**，
  留给用户请求 ≥ 6。营销的池同为 10、且 HTTP 与抽奖链路更忙，**没有理由抬 parallelism**；
  要抬之前先按主体 arch doc §6 的实测口径复测。
- `base-seconds` / `max-seconds` 都要兜底 ≥ 1s：配成 0 会让退避算成 0，一个 tick 里跑光 16 次重试
  （内嵌化票 04 的坑，已写成单测钉住）。
- **测试 profile 的 `xxl.job.executor.appname` 必须另起一个 `-test` 后缀**：营销现在只错开了执行器端口
  （9990），**没设独立 appname**（见 map 的「Not yet specified」漂移项）。真正落扫描 job 前必须补上——
  否则本机 xxl-job-admin 里启用的 `timeoutTaskScan` 会每 10s 往测试 JVM 打一次真扫描，
  与用例自己调的 `scan()` 并发改同一批行（主体踩过：断言收到 `[200, 129, 5]`）。
- 管理面随超时表一起落：有 `FAILED` 死信就必须有管理面出口（「谁有超时表谁才有管理面」）。
  路径**不能照抄主体的 `/api/admin/timeout-tasks/**`**——前端 `/api/admin` 前缀代理到 8080 单体；
  营销照自己的路径惯例用 `/api/marketing/timeout-tasks/**`（营销控制器不另设 `/admin` 段，
  照 `MarketingConfigController` 的 `/api/marketing/config` 先例），
  鉴权沿用营销的 `SecurityUtils.currentRole() == ADMIN`（过滤链 401、控制器内收 ADMIN 403），
  分页钳制（每页 ≤ 50、页码 ≤ 1000）保留；若要做后台 UI，需在 `front/vite.config.ts` 的 `/api` 之前加
  `/api/marketing` → 8081 的代理。

## 5. 边界判据：既有扫描 job 与超时中心的分工

营销现有两个扫描 job（`marketing/sql/xxl-job-tasks.sql` 已注册、都**不迁**）：

| job | 落点 / 频率 | 干什么 | 为什么不是超时模型 |
| --- | --- | --- | --- |
| `pointsExpireScan` | `trigger.points.job.PointsExpireScanJob`，`0 * * * * ?`（每分钟） | 把已过期积分桶 `remain` 置 0：不删行、留审计、**不发事件** | **懒过期 + 批量落账**：真实过期以读路径 `expire_at > NOW()` 为准（所有读路径都带这个条件），**扫描只落账**（`remain` 置 0）；动作是集合谓词，不是「一对象一 deadline 一回调」 |
| `drawCountBucketExpireScan` | `trigger.drawcount.job.DrawCountBucketExpireScanJob`，`0 * * * * ?`（每分钟） | 同上，对象换成 `draw_count_bucket` | 同上（与积分桶逐列同构，连口径都同源） |

曾经列在这里的两个库存 job —— 每秒的 `inventoryMerge` 与 60 秒的 `inventoryMergeBackstop` 兜底
——**都已删除**（hot-row-merge-l2w256，ADR-0013）：库存合并进了**进程内合并器**
（`InventoryMerger`，请求等 5ms 窗口后一组一个事务），不再有扫描 job、合并消息与兜底，
也就没有「该不该改成超时任务」的问题——它本来就不是 deadline（曾是结算流水线），
改成超时任务只会把 O(批) 的合并退化成 O(行) 的回调。与超时中心的「同事务登记 + 到点回调」
仍是两回事（它没有重试 / 退避 / 死信 / 管理面，也不需要）。

### 5.1 判据（与 map Notes 8 同口径）

| 判据 | 域内扫描（现状） | 超时中心 |
| --- | --- | --- |
| deadline 存在哪 | **已经是业务列**（如 `expire_at`）；或根本不需要 deadline（流水扫描） | 没地方放，要为它新加列 |
| 动作粒度 | **集合谓词**（`WHERE expire_at <= NOW()`），一次清扫一批 | **一对象一 deadline 一动作**，每行有自己的到期时刻与回调 |
| 动作范围 | 同域 | 跨域编排（如抽奖 + 库存） |
| 失败语义 | 扫描重跑即收敛（幂等谓词） | 需要逐次重试 / 指数退避 / 死信 / 管理面 |
| 延迟要求 | 分钟级可接受 | 10s 级、且到点必须动作 |

一句话：**deadline 已是业务列、动作在同域 → 域内扫描；deadline 无处存放 / 动作跨域 / 需要重试-退避-死信-管理面 → 超时中心。**
再加一条动作判据：如果「到点」做的只是让读路径换一个过滤条件的**账面临界**，那它就不是超时动作。

### 5.2 反例：把每个积分桶建模成一条超时任务

这是最容易犯的错（「桶有过期时间，不就是 deadline 吗」）。真这么做会得到：

1. **行数随桶数放大，不随业务对象数放大**：每个积分桶 = 一条入账流水（`points_bucket` 一行），
   每次入账都要注册一条超时任务。行数 = 累计入账笔数，而不是「需要到期动作的业务对象数」。
   16 张分表只是把行摊开，**不减少行数**；且积分有效期 365 天（`points_validity_config` 种子），
   每条任务行都要在 `idx_scan` 里占一年空间，每次写入/结算还得维护这套索引。
2. **回调动作是空的**：到期回调能做的只有 `remain = 0`，而**所有读路径本来就带 `expire_at > NOW()`**，
   过期桶在回调发生前就不可用。回调不改变任何业务事实，只是把懒判已经表达过的事情再写一遍。
3. **退避/死信变成业务噪声**：`remain = 0` 是幂等谓词，失败重试 16 次没有任何增量收益；
   真失败时进死信，管理面里全是「清理没成功」这种非业务条目，把真正的业务死信淹没。
4. **注册点扩散**：积分入账不止一条路径（抽奖中奖、行为返利、后续可能的补发），
   每条入账路径都要在各自事务里多写一张表；而懒过期模型下，入账只写 `points_bucket` 一行。
5. **配置变更的语义也没变好**：`expire_at` 在入账时算定，超时模型不会让有效期配置更灵活，
   只多了一套「任务行与桶行必须同生同死」的不变量要维护。

结论：**桶过期永远留在域内扫描**（`pointsExpireScan` / `drawCountBucketExpireScan`），
不要因为「它有时间字段」就迁进超时中心。

### 5.3 将来什么样的营销动作才该用超时中心（判据示例）

按 §5.1 的判据，下面这类才是候选（**只是判据示例，不是承诺的产品范围**）：

- 「中奖记录确认收货超时自动完成」：每一条中奖记录有自己的 deadline，到点推进的就是这条记录的状态机；
  失败要重试、最终要死信可见。
- 「（若产品定义兑换窗口）待兑换超时自动关闭并释放预扣配额」：动作跨 `raffle` 与 `inventory` 两域，
  配额释放不能靠读路径懒判表达。

反过来说，只要「到点」能写成 `WHERE <业务列> <= NOW()` 的一次批量清扫，就回到域内扫描。

## 6. 将来落地的顺序清单（一次做完）

1. **DDL**：16 张表进 `marketing/sql/schema.sql`；补 §1.2 的命名例外注释；重建 `bs_marketing_test`
   （`schema.sql` 是唯一 DDL 来源，改表 = 改它 + 重建库）；更新 `marketing/docs/README.md` 表数（20 → 36）。
   没有存量搬迁——空表起步。
2. **依赖**：营销根 pom 的 `dependencyManagement` 加 ShardingSphere-JDBC 5.5.3 + 特性 artifact 全套
   （`sharding-core`、`infra-data-source-pool-hikari`、`standalone-mode-repository-memory`、`authority-simple`、
   `parser-sql-engine-{mysql,sql92}`）+ `commons-lang3 ≥ 3.18.0` 覆盖（Spring Boot 3.3.5 管的 3.14.0 缺
   `org.apache.commons.lang3.Strings`，运行期 `NoClassDefFoundError`）；特性 artifact 进 `marketing-app`，
   算法 SPI 在 `marketing-infrastructure`。
3. **路由**：两份 `sharding.yaml`（`!SINGLE` 在前）；`application.yml` / `application-test.yml` 的
   datasource URL；`ShardingSelfCheck`。
4. **分层骨架**：按 §2 建 `types.timeout` / `domain.timeout` / `infrastructure.timeout` / `usecase.timeout` /
   `trigger.timeout.job`；`MybatisConfig` 加 `infrastructure.timeout.dao`；mapper XML 放
   `mybatis/mapper/timeout/TimeoutTaskMapper.xml`。
5. **注册**：`ITimeoutRepository` 的按键四原语 + `register` 三分支；业务域仓储方法内直插；
   集成测试用**裸 MySQL 连接**断言「槽位 = 实际落库物理表」（物理名经 SS 连接查是 `TableNotFoundException`）。
6. **扫描**：`TimeoutTaskScanJob` + seed 行 + §4.2 配置；有界并行；退避/死信；
   单实例与多实例（模拟 `shardIndex/shardTotal`）两态测试；并行度断言（探针或 Hikari 采样）。
7. **回调**：`TimeoutTaskCallbackDispatcher` + 端口；真实 taskType 动作验收；幂等（重复回调零副作用）；
   未注册 taskType / 坏 bizId 按失败重投。
8. **管理面**：控制器 + DTO + JWT/ADMIN + 分页钳制（路径见 §4.2 的营销前缀）；
   401 / 403 / 跨 16 表广播归并 / 钳制 / 条件 UPDATE 语义的集成测试。
9. **文档**：本文状态改为「已落地」；`marketing/docs/db/`、`marketing/docs/api/` 增补；
   `backend/docs/architecture/marketing-services.md` §6 的 handler 清单（6 → 7）与表数同步。

**不要做**：加 `user_id` 列；恢复回调路由列；用 `outbox_message` 承载注册；发 Dubbo 契约；
启用 `DB_COUNT > 1`；一表一线程；把 `pointsExpireScan` / `drawCountBucketExpireScan` 迁进来。

## 7. `CONTEXT.md` 词条分工（与票 09 协调）

- **票 09** 负责改写「超时任务」「注册 outbox」两条（它们描述的还是独立中心 + outbox 注册通道）。
- **票 08 只新增「过期落账」一条**（营销积分段），不改那两条——两票不碰同一词条；
  词条把「批量过期落账」与「超时任务」的边界钉进领域词汇，供 §5 引用。
- 营销真正接入后，才可能需要补「营销超时接入方」类词条；本期不加。
