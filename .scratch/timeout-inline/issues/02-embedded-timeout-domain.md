# 02 主体内嵌超时中心的域与存储

Type: task
Status: resolved
Blocked by: 01

## What to build

在 `backend/better-school/` 里把超时中心立成**进程内的一域 + 一套表**（不引 timeout-center 工程）：

1. **域与分层**（照 `backend/CLAUDE.md`）：`domain.timeout`（`model/entity`+`valobj`、`service/scan`+`service/deadletter`、`adapter/repository/ITimeoutRepository`；`adapter/port/` 需要的话只放「业务动作分发」端口）+ `infrastructure` 实现（PO/DAO/mapper XML 按 §3 归位）+ `trigger` 的 job/管理面入口。事务按 §1 落**仓储内**（REQUIRED），域服务不注入 `TransactionTemplate`。
2. **表**：16 张 `timeout_task_00..15` 进 `better-school/sql/schema.sql`（唯一 DDL 来源，改表 = 改它 + 重建库）。列照现 `schema-timeout-task.sql`，但**删 `callback_group`**（进程内分发不再需要路由列）；保留 `uk_type_biz (task_type, biz_id)`、`idx_scan (status, next_fire_time)`、5 态状态机（无 PROCESSING）。
3. **路由**：按**票 01 结论**二选一——(a) `jdbc:shardingsphere:` + `sharding.yaml`（dev/test 两份，`better_school` / `better_school_test`）+ HINT 强制路由；(b) MyBatis 表名后缀路由（业务 SQL 零影响）。两者都必须把槽位计算收敛到**一个函数**（现 `TimeoutTaskShardSlots` 形态），hash 输入改为 **`biz_id`**（同一业务对象的多种超时/多轮次落同一张表，`uk` 全局唯一性不受影响）。
4. **保留「库×表」两维表达**（用户 2026-10-16 拍板，**不删**预留）：槽位仍是**全局展平编号** `g`，`db = g / TABLES_PER_DB`、`table = g % TABLES_PER_DB`；配置项 `dbCount = 1`（本期只用一个库）、`tablesPerDb = 16`、`TOTAL = dbCount × tablesPerDb = 16`。**认领条件写成 `g % shardTotal == shardIndex && g < TOTAL`——认领只看全局号、与库表维度解耦**，所以将来 `dbCount > 1` 时认领逻辑一行都不用改；路由侧把 `g` 拆成 `HintManager.addDatabaseShardingValue` + `addTableShardingValue` 两个 hint 值（现工程只塞了表维）。
   - ⚠️ 同时在架构文档里写明**硬约束**：`dbCount > 1` 与本 effort 的核心前提（超时行与业务写入同事务）**互斥**——业务行留在原库、超时行按 hash 落到另一个库时同事务失效。本期 `dbCount` 恒为 1、不得启用：保留的是**表达**，不是**开关**。文档里要写清「要启用的前提是业务表也按同一分片键一起分库」。

## 依据

- 现 DDL/列/状态机/幂等语义：`.scratch/timeout-center/spec.md §3`；现实现 `timeout-center-infrastructure/.../TimeoutTaskHintShardingAlgorithm`、`TimeoutTaskShardSlots`、`TimeoutTaskRepository:155-160`（hint 与 DAO 必须同线程）。
- 业务工程的一域一仓储/事务规约：`backend/CLAUDE.md §1/§2/§3`；`bs-app/.../config/TransactionConfig.java:16-21` 已有唯一 `TransactionTemplate`。
- 库表事实源：`sql.md`（注意它现在**漏了 timeout_task**）。

## 验收

- 建库脚本幂等；集成测试断言「按 `(task_type,biz_id)` 算出的槽位 = 实际落库的物理表」。
- **`user_id` 列不加**（本票的显式结论，理由：主查询按 `(status, next_fire_time)` 扫、分片键不参与；现 hint 方案扩库只改公式与 `actualDataNodes`、加列省不下任何事；一旦路由键 ⊥ 幂等键，就要额外汇总「user 与幂等键绑定不可变」这条不变量，破了即双行双回调；且 `REVIEW_LEASE_TIMEOUT` 的 `bizId = {taskId}:{claimSeq}` 没有天然 user，只能塞 NULL/系统用户）。若将来真要分库，分片键由**业务表实际选的那个维度**决定，且业务表也必须同键分片——那是另一次全仓改造（见 map 的 Out of scope）。
- 业务侧回归：全量集成测试绿（SS 或表名路由都不得改变既有业务 SQL 的行为）。

## Answer

**已落地（2026-10-16）。路由取了 (a)：ShardingSphere 挂业务主 datasource + HINT 强制路由**（照票 01 处方「原形态 + 显式 `!SINGLE`」；不退化成 MyBatis 表名后缀路由）。

**域与分层**（`backend/better-school/`，照 `backend/CLAUDE.md`）：

- `bs-domain`：`domain.timeout.model.entity.TimeoutTaskEntity`（无 `callbackGroup`；`shardSlot` 不落库）、`model.valobj.TaskStatusVO`（5 态无 PROCESSING）、`adapter.repository.ITimeoutRepository`（本票只立**按键读写四原语**：`insertIfAbsent` / `findByKey` / `reviveTerminal` / `cancelActive`）。
- `bs-infrastructure`：`dao.timeout.ITimeoutTaskDao` + PO、`adapter.repository.timeout.TimeoutRepository`（撞 uk 翻返回值；每次 DAO 调用前把**全局槽位 g 同时塞 `addDatabaseShardingValue` + `addTableShardingValue` 两个 hint 值**——补齐了原工程只塞表维的缺口）、`sharding/` 三件套：`TimeoutTaskShardSlots`（**单点槽位函数**）+ 库维/表维两个 `CLASS_BASED` hint 算法（ miss 路由目标即抛 `IllegalStateException`，不静默）。
- 事务：四原语都是单条语句原子操作（幂等裁决权在库上：uk_type_biz + 条件 UPDATE），不需要显式事务模板；票 03 的业务事务内直插经 Spring 事务同步与仓储共用同一条连接（天然 REQUIRED）。域服务不注入 TransactionTemplate。**这是显式裁量而非漏做**：票面「事务按 §1 落仓储内（REQUIRED）」的落点是「需要事务的多次读改写收进仓储」，本票四原语各是单条语句、无多步收口需求，与现中心仓储（同样单语句、无事务模板）同形；票 04 的批量结算与票 03 的三分支如出现多步读改写，届时按 §1 在仓储内包 `TransactionTemplate`（REQUIRED）。
- `bs-types`：`types.timeout.TimeoutConstants`（`TASK_LOGIC_TABLE` / `DB_COUNT=1` / `TABLES_PER_DB=16` / `TOTAL_SLOTS=16` / `ds` 前缀）。

**表**：16 张 `timeout_task_00..15` 进 `better-school/sql/schema.sql`（紧跟 `timeout_register_outbox` 之后；票 03 删 outbox 时不动它们）。列照原 DDL、删 `callback_group`；保留 `uk_type_biz (task_type, biz_id)`、`idx_scan (status, next_fire_time)`、5 态；时间列**保留 `created_at/updated_at`**（不打乱 create_time/update_time 口径是有意的：按原样自 bs_timeout 迁入，票 07 迁移免列名映射；已在 schema.sql 与 sql.md 登记为例外）。幂等验证：init-test.sql + schema.sql 连喂两遍零错误（`CREATE DATABASE IF NOT EXISTS` + `IF NOT EXISTS`）。

**槽位公式（单点）**：`slot = floorMod(hash(biz_id), 16)`——hash 输入从 `(task_type, biz_id)` 改为 **`biz_id`**（票面明确要求）：同一业务对象的多种超时/多轮次（复合 bizId）落同一张表；`uk_type_biz` 全局唯一性不受影响（同键行永远同表）。两维表达照票面保留：g 是全局展平编号（`db = g/16`、`table = g%16`），认领条件将来写成 `g % shardTotal == shardIndex && g < TOTAL` 只吃全局号（票 04 落地）；`DB_COUNT=1` 的硬约束写进了 `TimeoutConstants` javadoc + `sharding.yaml` 头注释 + `backend/docs/architecture/timeout-center.md §0`（保留的是表达，不是开关；启用前提 = 业务表同键分库）。

**路由**：`bs-app/src/main/resources/sharding.yaml`（dev，`better_school`）+ `src/test/resources/sharding-test.yaml`（test，`better_school_test`），主 datasource 换 `jdbc:shardingsphere:classpath:sharding.yaml`（两份 application yml 同步）。YAML 形态 = `!SINGLE tables: ["*.*.*"]`（在 `!SHARDING` 之前）+ `!SHARDING`（`databaseStrategy`/`tableStrategy` 各配一个 hint 算法）+ `databaseName` 显式写真实 schema 名（票 01 建议）。SS 5.5.3 依赖全套（含 commons-lang3 3.18.0 覆盖）进 better-school 根 pom dependencyManagement；算法 SPI 在 bs-infrastructure、特性 artifact 在 bs-app。启动期自检 `bs-app/.../config/ShardingSelfCheck`：逻辑库必须可见 `timeout_task` + 6 张代表业务表、且不得见任何 `timeout_task_NN` 物理名，验不过启动失败（挡住票 01 记的两个「启动成功、首条 SQL 才炸」的坑）。

**`user_id` 列不加**：照票面结论执行，理由已写进架构文档 §0（主查询按 `(status, next_fire_time)` 扫、加列省不下任何事、路由键 ⊥ 幂等键会引入额外不变量、`REVIEW_LEASE_TIMEOUT` 无天然 user）。

**验收证据**：
- `bs-app/src/test/.../integration/timeout/TimeoutShardingRouteIntegrationTest`：注册（`insertIfAbsent`）后**裸 MySQL 连接**逐张断言 16 张分表——槽位那张恰 1 行、其余 15 张全 0（物理断言必须绕过 SS：物理名经 SS 连接查是 TableNotFoundException，票 01 已定位该行为）；另钉同键重注册幂等、业务取消、终态复活。裸连地址收在 `application-test.yml` 的 `timeout-it.raw-mysql-url`（与 sharding-test.yaml 的 jdbcUrl 同源，测试代码不另写地址）。
- 全量集成测试绿（既有业务 SQL 经 SS 驱动 + `!SINGLE` 行为不变，含基类 TRUNCATE 单表、ODKU/INSERT IGNORE 等路径）。**一处既有测试夹具避让**：两个 errand 集成测试的 `DATE_SUB(NOW(), INTERVAL ? HOUR)` 直插 SQL 在 SS 解析下丢第 4 个占位符（`INTERVAL ?` 内嵌占位符是解析器边界形态）——夹具改为 Java 侧算好时间戳当普通 `?` 传。**限定词：生产代码 grep `INTERVAL ?` 零命中**，「SS 不改变既有业务 SQL 行为」的结论只对生产 SQL 成立；票 03/04 新增 SQL 时避免 `INTERVAL ?` 形态（已写进票 04 相关注意事项的话头，见下）。
- 附带取值（票面未点名、属 SS 挂主 datasource 的必要配套）：内层池 `maximumPoolSize: 20`（≥ 外层 10，留扫描并行度与驱动元数据连接余量）、test 侧 `minimumIdle: 2`（刻意压低，测试不常驻）。
- 坑记录：`SHOW TABLES` 经 SS 是**原样透传**（返回物理表名，逻辑表 `timeout_task` 反而看不到）——启动期自检与任何「逻辑库可见性」断言必须走 JDBC `DatabaseMetaData.getTables`。第一版自检用 SHOW TABLES 误判了健康的配置，已改。
- 环境备注：本轮 bs-mysql 起初反复崩（宿主曾非正常关机，InnoDB 崩在 xxl_job_log 统计查询上），`CHECK TABLE` 确认无损坏后稳定；`bs-nacos`（开发侧）JRaft 启动失败过一次、重启后恢复——均为环境问题，与本票改动无关。

**本票没做、留给后续票的**：`service/scan` + `service/deadletter` 与 trigger job/管理面入口在本票只定了归位（`domain.timeout.service.{scan,deadletter}`、`bs-trigger/job`），实现由票 04（扫描+有界并行+结算退避）、票 06（死信管理面落点与鉴权）落地；注册三分支的编排与四个接入点改造在票 03；`adapter/port`（业务动作分发）随票 05 进程内分发落地——本票域层只依赖仓储，尚无 port。`marketing` 工程同款改造不在本票（票 08 只定规格）。
