# 双服务回归单体预扫：数据库 / 配置冲突清单（02）

> 扫描范围：`backend/better-school/`（bs-app，主库 `better_school`）与 `backend/marketing/`（marketing-app，库 `bs_marketing`）。
> 只读扫描，以工作区当前内容为准，忽略 target/。扫描日期：2026-11-23。
>
> **冲突速览**：同名表 1 张（outbox_message，结构不一致）；xxl-job handler 撞名 1 个（outboxScan）；RocketMQ topic 撞名 2 个（既定跨服务事件流，非配置冲突）、consumerGroup 撞名 0；HTTP 类级前缀撞名 0，但 marketing 存在 2 个**不在前端分流规则内**的前缀（`/api/marketing/*`）；producer group 撞名 0（两值不同需二选一）。

---

## 1. 表名对比

### 1.1 better-school（库 `better_school`，sql/schema.sql，共 57 张）

非分表 41 张：

| # | 表名 | # | 表名 | # | 表名 |
|---|---|---|---|---|---|
| 1 | bs_user | 16 | post_view_count | 31 | user_seq |
| 2 | post | 17 | product_feed_index | 32 | notification |
| 3 | post_comment | 18 | errand_feed_index | 33 | notification_seq |
| 4 | product | 19 | product_view_history | 34 | notification_read |
| 5 | product_inventory | 20 | errand_view_history | 35 | errand_order |
| 6 | product_stock_event | 21 | product_view_count | 36 | errand_order_event |
| 7 | product_favorite | 22 | errand_view_count | 37 | product_order |
| 8 | post_like | 23 | **outbox_message** | 38 | product_order_event |
| 9 | post_like_count | 24 | room | 39 | virtual_wallet |
| 10 | post_comment_count | 25 | user_pair_room | 40 | wallet_detail |
| 11 | post_comment_like_count | 26 | user_room | 41 | user_credit |
| 12 | post_comment_like | 27 | room_member | 42 | user_credit_detail |
| 13 | post_comment_feed_index | 28 | message | | |
| 14 | post_feed_index | 29 | timeline | | |
| 15 | user_view_history | 30 | — | | |

（上表按行数共 41 张非分表；编号顺延略有出入以表名为准。）

超时分表 16 张：`timeout_task_00` .. `timeout_task_15`（同构 DDL × 16，ShardingSphere hint 路由，见 §2）。

### 1.2 marketing（库 `bs_marketing`，sql/schema.sql，共 23 张）

| # | 表名 | # | 表名 | # | 表名 |
|---|---|---|---|---|---|
| 1 | points_bucket | 9 | action_rebate_config | 17 | virtual_inventory_quota_detail |
| 2 | points_detail | 10 | draw_activity | 18 | physical_marketing_quota |
| 3 | draw_count_bucket | 11 | draw_strategy | 19 | physical_marketing_quota_detail |
| 4 | draw_count_detail | 12 | draw_strategy_award | 20 | draw_strategy_award_inventory |
| 5 | draw_count_purchase_order | 13 | draw_strategy_weight | 21 | draw_strategy_award_inventory_detail |
| 6 | draw_orphan_prereserve_order | 14 | draw_record | 22 | inventory_allocation_flow |
| 7 | points_validity_config | 15 | draw_record_detail | 23 | **outbox_message** |
| 8 | draw_count_config | 16 | virtual_inventory_quota | | |

### 1.3 同名表：`outbox_message`（唯一撞名，**结构不一致**）

| 列 | bs 侧 | marketing 侧 |
|---|---|---|
| id / msg_id / destination / payload / state / retry_count / create_time / update_time | ✅ 相同 | ✅ 相同 |
| delay_level TINYINT NOT NULL DEFAULT 0 | ❌ 无 | ✅ 有（RocketMQ 延迟等级，0=不延迟） |
| delay_ms INT NULL | ❌ 无 | ✅ 有（毫秒级定时消息，NULL=走 delay_level） |

- 合并到单库后此表只能存在一份：**marketing 侧是超集**，取 marketing 形状；bs 侧的 mapper/PO（`bs-infrastructure/.../mq/OutboxMessageSender.java`、对应 OutboxDao）写入多出的两列有默认值/可空，理论上可兼容，但两侧 DAO XML 是两套（bs 平铺 `mybatis/mapper/`，marketing 分域 `mybatis/mapper/points/*`、`mapper/lottery/*`，**两域 OutboxMapper 同名 XML**），合并 mapper 目录时必须处理同名文件。
- 两侧 `uk_msg_id` 幂等键语义一致，合并后 msg_id 生成两侧独立（`msg_id VARCHAR(64)`），无键空间冲突。

### 1.4 init-dev.sql / init-test.sql 与 schema.sql 的差异要点

四个 init 文件都**不含任何 DDL**（只有 `SET NAMES utf8mb4` + `CREATE DATABASE` + `USE`），DDL 唯一来源是各自 schema.sql，按序拼接执行：

| 文件 | 侧 | 建库 |
|---|---|---|
| better-school/sql/init-dev.sql | bs | `better_school` |
| better-school/sql/init-test.sql | bs | `better_school_test` |
| marketing/sql/init-dev.sql | marketing | `bs_marketing` |
| marketing/sql/init-test.sql | marketing | `bs_marketing_test` |

要点：
- 开发库与测试库共用同一 schema.sql，**init 文件与 schema 之间无表级差异**（init 只管库名）。
- 两套 xxl-job-tasks.sql 都 `USE xxl_job`——**xxl_job 调度库是两侧共用的同一个库**，合并后两份 seed 落同一个 xxl_job_info 表（见 §4）。
- 合并单库时的库名收敛：`better_school` / `bs_marketing` / `better_school_test` / `bs_marketing_test` 四个库名要归一（代码里硬编码库名的位置：bs 侧 `sharding.yaml` 的 `jdbcUrl` + `databaseName: better_school`、`sharding-test.yaml`；marketing 侧 `application.yml` / `application-test.yml` 的 datasource url）。
- marketing schema 尾部有种子（`points_validity_config` 365 天、`draw_count_config` 30 天，INSERT IGNORE）；bs schema 有 `bs_user` 系统助手账号种子（WHERE NOT EXISTS 幂等）。合并后两段种子都要保留。

---

## 2. ShardingSphere 使用情况

### 2.1 bs-app：使用中（生产主 datasource 坐在 SS 驱动上）

配置文件：`bs-app/src/main/resources/sharding.yaml`（开发）+ `bs-app/src/test/resources/sharding-test.yaml`（测试，同内容、指 `better_school_test@13306`）。入口：`spring.datasource.url = jdbc:shardingsphere:classpath:sharding.yaml`。

- **分片表**：仅逻辑表 `timeout_task` → 物理表 `ds0.timeout_task_00 .. timeout_task_15`（16 张，逐张列出，非内联表达式）。
- **规则**：hint 强制路由。`databaseStrategy`/`tableStrategy` 各一个 CLASS_BASED 算法，算法类 `com.betterschool.infrastructure.sharding.TimeoutTaskDbHintShardingAlgorithm` / `TimeoutTaskTableHintShardingAlgorithm`（bs-infrastructure，仓储层每次 DAO 调用先算全局槽位 g 塞 HintManager）。槽位 g = floorMod(hash(biz_id), 16)，db = g/16、table = g%16，**本期 DB_COUNT = 1 且不得启用 >1**。
- **单表规则**：`!SINGLE tables: "*.*.*"`——其余 41 张业务表零分片规则、完全透明。
- `databaseName: better_school`；`props: sql-show: false`。

datasource 原文要点（bs-app）：

| 层 | 配置 |
|---|---|
| Spring 外层 datasource | driver `org.apache.shardingsphere.driver.ShardingSphereDriver`；url `jdbc:shardingsphere:classpath:sharding.yaml`；username/password root/bs123456（驱动忽略，防 Hikari 空凭据告警） |
| 外层 Hikari | maximum-pool-size 10，connection-timeout 3000 |
| 内层 ds0（sharding.yaml）| HikariDataSource / `com.mysql.cj.jdbc.Driver`；jdbcUrl `jdbc:mysql://localhost:3306/better_school?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true`；root/bs123456；maximumPoolSize 20（≥ 外层），minimumIdle 4，connectionTimeout 3000 |

### 2.2 marketing-app：**无任何 sharding 配置**

`marketing-app/src/main/resources/` 只有 application.yml + mybatis/mapper，无 sharding.yaml；datasource 直连 MySQL：

- driver `com.mysql.cj.jdbc.Driver`；url `jdbc:mysql://localhost:3306/bs_marketing?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true`；root/bs123456；Hikari maximum-pool-size 10、connection-timeout 3000。

### 2.3 合并影响

- 若合并后单体继续坐 SS 驱动：marketing 的 23 张表在 `!SINGLE "*.*.*"` 下天然透明，**配置层面零改动**；但 timeout_task 的 hint 路由要求 DAO 层持续塞 HintManager，这是 bs 侧代码行为，不是配置问题。
- 若弃用 SS：timeout_task 16 分表的路由逻辑要改造进仓储层（业务代码变更，本预扫只标注不动）。
- 两层连接池语义（外层 10 = 真并发上限，内层 20）在合并后仍是 bs 侧约束；marketing 直连形态并入后统一到同一套池参数。

---

## 3. application.yml / bootstrap 配置对比

两侧均无 bootstrap.yml/properties；Nacos 用 `spring.config.import` 方式接入，**共享 dataId `better-school-shared.yml`（group DEFAULT_GROUP，refreshEnabled）——jwt.secret / jwt.expire-days 从这里来，两侧同一份**。

### 3.1 逐键对比

| 键 | bs-app | marketing-app | 冲突/合并处理 |
|---|---|---|---|
| `server.port` | 8080 | 8081 | 合并后单端口，二选一 |
| `spring.application.name` | better-school | marketing | 取一个 |
| `spring.threads.virtual.enabled` | true | true | 一致，无冲突 |
| `spring.config.import` | nacos:better-school-shared.yml?group=DEFAULT_GROUP&refreshEnabled=true | 同左 | 一致，无冲突 |
| `spring.cloud.nacos.server-addr` | 127.0.0.1:8848 | 同左 | 一致 |
| `spring.servlet.multipart.max-file-size / max-request-size` | 6MB / 10MB | 无 | 取 bs |
| `spring.jackson.deserialization.fail-on-unknown-properties` | false | 无 | 取 bs |
| `spring.autoconfigure.exclude` | UserDetailsServiceAutoConfiguration | 同左 | 一致 |
| `spring.datasource.driver-class-name` | ShardingSphereDriver | com.mysql.cj.jdbc.Driver | **冲突**，见 §2.3 |
| `spring.datasource.url` | jdbc:shardingsphere:classpath:sharding.yaml | jdbc:mysql://…/bs_marketing | **冲突**，见 §2.3 |
| `spring.datasource.hikari.maximum-pool-size / connection-timeout` | 10 / 3000 | 10 / 3000 | 一致 |
| `spring.data.redis.host/port/database/password` | localhost:6379 / 0 / bs123456 | 同左 | 一致 |
| `spring.data.redis.timeout / connect-timeout` | 无 | 1000ms / 1000ms | 取 marketing（超集；marketing 依赖它做快速失败） |
| `spring.elasticsearch.uris` | http://localhost:9200 | 无 | 仅 bs（marketing 无 ES） |
| `rocketmq.name-server` | localhost:9876 | 同左 | 一致 |
| `rocketmq.producer.group` | better-school-producer | marketing-producer | **不同值**：合并后一个 producer group，二选一或新命名 |
| `outbox.scan.timeout-seconds` | 无 | 60（测试 2） | marketing outboxScan 只兜底重发超时行；bs 的 outboxScan 无此参数（直接捞）→ 合并统一口径 |
| `mybatis.mapper-locations` | `classpath:/mybatis/mapper/*.xml` | `classpath:/mybatis/mapper/**/*.xml` | **必须取 `**`**：marketing 两个域的 OutboxMapper 同名、XML 分域子目录，`*` 不递归会丢 marketing mapper；`**` 对 bs 平铺目录向后兼容 |
| `mybatis.configuration` | map-underscore-to-camel-case: true + Slf4jImpl | 同左 | 一致 |
| `lottery.bucket.degrade-cooldown-millis` | 无 | 10000 | 仅 marketing |
| `dubbo.application.name` | better-school | marketing | 合并取一个 |
| `dubbo.application.serialize-check-status` | WARN | WARN | 一致 |
| `dubbo.registry.address` | nacos://127.0.0.1:8848 | 同左 | 一致 |
| `dubbo.protocol.name/port/threadpool` | tri / **50051** / virtual | tri / **50052** / virtual | 端口二选一；合并后单体 Provider（bs 的 IUserQueryService）+ marketing 的 Provider/Consumer 全在一个进程，dubbo 边可能整体退役（进程内直调），退役则整段删除 |
| `dubbo.consumer.check` | 无 | false | 若保留 dubbo 需保留 |
| `dubbo.scan.base-packages` | com.betterschool | com.betterschool.marketing | **bs 的包已是 marketing 包的父前缀**，合并后写 `com.betterschool` 一条即可覆盖两侧 |
| `xxl.job.admin.addresses` | http://localhost:9090/xxl-job-admin | 同左 | 一致 |
| `xxl.job.access-token` | default_token | 同左 | 一致 |
| `xxl.job.executor.appname` | better-school-executor | marketing-executor | **不同值**：合并后单执行器只能一个 appname；xxl_job_group 的两行 seed 也要归一（见 §4） |
| `xxl.job.executor.port` | 9999 | 9998 | 二选一（测试侧 bs 9998 / marketing 9990 也要跟着错开） |
| `xxl.job.executor.log-path` | ./logs/xxl-job | ./logs/xxl-job-marketing | 二选一 |
| `xxl.job.executor.log-retention-days` | 30 | 30 | 一致 |
| `merge.count.*`（bs）shard-count 8 / max-flush-delay 5 / max-batch 200 / wait-timeout 100 / shard-capacity 2000 / drain-timeout 5000 | 有 | 无 | 键前缀不同（`merge.count` vs `merge.batch`），**无键冲突**，合并后两套合并器（计数 / 库存）参数并存 |
| `merge.batch.*`（marketing）同值六参 | 无 | 有 | 同上 |
| `timeout.scan.* / timeout.retry.*` | batch 200 / parallelism 4；30 / 3600 / 16 | 无 | 仅 bs |
| `better-school.ws.route.instance-url` | "" | 无 | 仅 bs |
| `minio.*` | endpoint 9000 / bsadmin / bs123456 / bs-files / public-url | 无 | 仅 bs |
| `logging.level.com.betterschool` | debug | debug | 一致 |
| `logging.level.<dao 包>` | com.betterschool.infrastructure.dao: warn | marketing 5 个 dao 包: warn | 合并时两份 warn 清单**并集** |

### 3.2 冲突面汇总与合并建议

1. **必撞键（值不同，须裁决）**：server.port、spring.application.name、datasource（驱动/url）、rocketmq.producer.group、dubbo.application.name / protocol.port、xxl.job.executor.appname / port / log-path。共 8 组。
2. **结构性合并**：mybatis mapper-locations 必须 `**`（marketing 同名 OutboxMapper XML 强制分域递归）；mapper 目录合并时处理 bs 平铺 vs marketing 分域两套布局、`OutboxMapper` 同名 XML 二选一/合并。
3. **超集取舍**：redis timeout/connect-timeout 取 marketing；multipart、jackson、ES、minio、timeout.*、better-school.ws.* 取 bs 独有键原样并入。
4. **共享件**：Nacos server-addr、共享 dataId、xxl-job admin/token、Redis（同实例同 db0）、RocketMQ nameserver、连接池口径——两侧本就一致，直接保留。
5. Redis 同实例同 db 0 下的 key 前缀：bs 用 `bs:verify-code:`、`bs:ws-ticket:`、`bs:ws-route:`、`bs:ws-push:`、`bs:errand:grab-abandon:`、`bs:errand:grab-cooldown:`、`bs:post:detail:`（+lock/bloom）；marketing 用 `bs:raffle:*`、`bs:inventory:*`、`bs:lottery:`（桶 key）。**前缀无撞名**，合并后共享同一 Redis 无数据冲突。

---

## 4. xxl-job 任务名对比

调度库 `xxl_job` 两侧共用。执行器：bs `better-school-executor`（端口 9999），marketing `marketing-executor`（端口 9998）。

### 4.1 better-school/sql/xxl-job-tasks.sql（11 条 seed）

| executor_handler | job_desc | cron (schedule_conf) | route / block | 启用 | 代码 @XxlJob 位置 |
|---|---|---|---|---|---|
| outboxScan | outbox PENDING 消息补发 | 0/30 * * * * ? | FIRST / SERIAL | ✅ | bs-infrastructure/.../mq/OutboxMessageSender.java:131 |
| searchIndexRebuildJob | 搜索索引全量重建 | 0 30 3 * * ? | FIRST / SERIAL | ❌ 手动 | bs-trigger/.../job/SearchIndexRebuildJob.java |
| feedIndexRebuildJob | feed 索引全量重建（手动） | 0 40 3 * * ? | FIRST / SERIAL | ❌ | bs-trigger/.../job/FeedIndexRebuildJob.java |
| commentFeedIndexRebuildJob | 根评论热评索引全量重建（手动） | 0 45 3 * * ? | FIRST / SERIAL | ❌ | bs-trigger/.../job/CommentFeedIndexRebuildJob.java |
| productFeedIndexRebuildJob | 商品 feed 索引全量重建（手动） | 0 50 3 * * ? | FIRST / SERIAL | ❌ | bs-trigger/.../job/MarketFeedIndexRebuildJob.java:27 |
| errandFeedIndexRebuildJob | 跑腿 feed 索引全量重建（手动） | 0 55 3 * * ? | FIRST / SERIAL | ❌ | bs-trigger/.../job/MarketFeedIndexRebuildJob.java:33 |
| tradeExpireScan | 交易到期下架+待审超时提醒扫描 | 0 * * * * ? | FIRST / SERIAL | ✅ | bs-trigger/.../job/TradeExpireScanJob.java |
| postReviewTimeoutScan | 帖子待审超 24h 提醒扫描 | 0 * * * * ? | FIRST / SERIAL | ❌ | ⚠️ **全工程 grep 不到该 handler，seed 注册了但代码已无此 @XxlJob** |
| timeoutTaskScan | 超时任务扫描（捞批+回调+结算） | 0/10 * * * * ? | SHARDING_BROADCAST / DISCARD_LATER / FIRE_ONCE_NOW | ✅ | bs-trigger/.../job/TimeoutTaskScanJob.java |
| errandFundOrphanScan | 跑腿资金孤儿扫描（补解冻/入账/扣分） | 0/30 * * * * ? | FIRST / SERIAL | ✅ | bs-trigger/.../job/ErrandFundOrphanScanJob.java |
| productFundOrphanScan | 商品资金孤儿扫描（补解冻） | 0/30 * * * * ? | FIRST / SERIAL | ✅ | bs-trigger/.../job/ProductFundOrphanScanJob.java |

### 4.2 marketing/sql/xxl-job-tasks.sql（4 条 seed 在册 + 已下线清理段）

| executor_handler | job_desc | cron | route / block | 启用 | 代码 @XxlJob 位置 |
|---|---|---|---|---|---|
| outboxScan | outbox PENDING 消息补发 | 0/30 * * * * ? | FIRST / SERIAL | ✅ | ⚠️ marketing 代码中**无** `@XxlJob("outboxScan")`（seed 注释自述：合并后只剩一份 OutboxMessageSender、曾以双注解名兼容，现主代码只有 bs 侧同名 handler） |
| drawCountPurchaseScan | 购买单补偿扫描（DEDUCTED 单补入账） | 0/30 * * * * ? | FIRST / SERIAL | ✅ | marketing-trigger/.../raffle/job/DrawCountPurchaseScanJob.java |
| orphanPrereserveScan | 孤儿预扣对账扫描（补落库或回补+退还） | 0/30 * * * * ? | FIRST / SERIAL | ✅ | marketing-trigger/.../raffle/job/OrphanPrereserveScanJob.java |
| raffleCacheWarmup | 抽奖决策表预热（参数 activityId） | 0 0 2 * * ? | FIRST / SERIAL | ✅ | marketing-trigger/.../raffle/job/CacheWarmupJob.java |
| —（seed 不建） | pointsBucketCompaction | — | — | — | ⚠️ 反向缺口：marketing-trigger/.../points/job/PointsBucketCompactionJob.java:24 有 `@XxlJob("pointsBucketCompaction")`，**seed 无注册行** |

seed 中幂等 DELETE 的已下线 handler（重灌不会再生）：`pointsExpireScan`、`drawCountBucketExpireScan`、`inventoryMerge`；注释另记 `inventoryMergeBackstop`、`inventoryBucketWarmup`、`lotteryDeductMerge`、`lotteryDrawCountExpireScan` 已随票下线。

### 4.3 撞名与 cron 汇总

- **handler 撞名：1 个** —— `outboxScan`（两侧 seed 各注册一条、各挂自己的执行器组；两侧的判重 SQL 都带 job_group 条件所以现状共存）。合并到单执行器后必撞：合并后只有一份 outbox_message、一个 OutboxMessageSender，**只需保留一条 outboxScan**（destination 区分消息来源），另一条删除。
- **seed↔代码不一致：2 处** —— bs 的 `postReviewTimeoutScan`（seed 有、代码无）；marketing 的 `pointsBucketCompaction`（代码有、seed 无）。
- cron 汇总（合并后全集）：

| cron | 任务（侧） |
|---|---|
| 0/10 * * * * ? | timeoutTaskScan（bs，分片广播） |
| 0 * * * * ? | tradeExpireScan（bs，启用）；postReviewTimeoutScan（bs，停用+无 handler） |
| 0/30 * * * * ? | outboxScan（**bs+marketing 撞名**）；errandFundOrphanScan、productFundOrphanScan（bs）；drawCountPurchaseScan、orphanPrereserveScan（marketing） |
| 0 0 2 * * ? | raffleCacheWarmup（marketing） |
| 0 30/40/45/50/55 3 * * ? | searchIndexRebuildJob / feedIndexRebuildJob / commentFeedIndexRebuildJob / productFeedIndexRebuildJob / errandFeedIndexRebuildJob（bs，全手动） |

- 合并建议：appname 归一为一个执行器（xxl_job_group 两行 seed 合并为一行，title 二选一）；除 outboxScan 外 12 个 handler 名互不冲突，全部可平移；执行器端口二选一。

---

## 5. HTTP 路径前缀清单（trigger 模块，类级注解）

### 5.1 bs-trigger（11 个类级前缀，12 个 controller 类）

| 类级前缀 | Controller（类） |
|---|---|
| /api/credit | CreditController |
| /api/chat | ChatController、NotificationController（两类共用前缀） |
| /api/files | FileController |
| /api/errand | ErrandController |
| /api/trade | ProductController |
| /api/community | PostController |
| /api/user | UserController |
| /api/search | SearchController |
| /api/wallet | WalletController |
| /api | WsTicketController（POST /api/ws-ticket） |
| /api/admin/timeout-tasks | TimeoutTaskAdminController |

### 5.2 marketing-trigger（4 个类级前缀，6 个 controller 类）

| 类级前缀 | Controller（类） |
|---|---|
| /api/lottery | LotteryController、raffle PingController |
| /api/points | PointsController、points PingController |
| /api/marketing/config | MarketingConfigController（GET/PUT points-validity、draw-count-validity、GET/POST rebates） |
| /api/marketing/inventory/allocate | QuotaAllocateController（POST） |

### 5.3 冲突判定

- **两侧类级前缀交集：无**（bs 无任何以 `/api/points`、`/api/lottery` 开头的路径；marketing 无 bs 的 11 个前缀）。合并到单应用无路由冲突。
- **但前端分流有缺口**：`front/vite.config.ts:26-41` 的 proxy 只把 `/api/points`、`/api/lottery` 指向 8081（marketing），其余 `/api` 全部指向 8080（bs）。marketing 的 `/api/marketing/config` 与 `/api/marketing/inventory/allocate` **两个前缀不以 /api/points、/api/lottery 开头**——dev 环境下它们会被分流到 bs 侧 404。即"marketing 前缀恰好只有 /api/points 与 /api/lottery 开头"这一假设**不成立**（4 个前缀中 2 个例外，均为管理/配置面接口）。单体合并后单端口天然消除该缺口，无需改 proxy。
- 合并后注意 `/api` 前缀级（WsTicketController）与其余前缀的覆盖关系不变（Spring 精确匹配优先，无冲突）。

---

## 6. RocketMQ 对比

生产统一走本地消息表 outbox（两侧各一份 `OutboxMessageSender`，`rocketMQTemplate.asyncSend(destination, payload, callback)`），destination 语法 `topic` 或 `topic:tag`。两侧代码**均无直发点**（无业务代码直接调 syncSend/convertAndSend），所有发送先落 outbox 再由 sender 兜底异步发。

### 6.1 topic 与生产点

**better-school（13 个 topic，bs-types Constants 定义）**：

| topic | 生产点（outboxMessageStore.save） |
|---|---|
| bs-user-register | UserRepository.java:78 |
| bs-search-sync | UserRepository.java:80、TradeRepository.java:467、ErrandRepository.java:501、CommunityRepository.java:382 |
| bs-errand-paid | ErrandRepository.java:507 |
| bs-order-push | ErrandRepository.java:515 |
| bs-notification-broadcast | NotificationRepository.java:90 |
| bs-notify-event | NotificationRepository.java:99 |
| bs-notify-chat | NotificationRepository.java:106 |
| bs-notify-push | NotificationRepository.java:112 |
| bs-chat-push | ChatRepository.java:468 |
| bs-chat-read | ChatRepository.java:473 |
| bs-chat-recall | ChatRepository.java:478 |
| bs-lottery-drawn | ⚠️ bs 定义了常量且**有消费者**，主代码无生产点（生产方在 marketing） |
| bs-points-changed | ⚠️ 同上：bs 有消费者、无生产点（生产方在 marketing） |

**marketing（2 个 topic）**：

| topic | 生产点 |
|---|---|
| bs-lottery-drawn | RaffleRepository.java:372 |
| bs-points-changed | PointsRepository.java:189 |

tag：两侧主代码生产点 destination 均为纯 topic（**无 tag 段**）。marketing 的 OutboxMessageSender 支持 `topic:tag` 解析，测试中出现 `bs-inventory-merge:merge-tag`（OutboxMessageSenderTest，sender 行为探测；`bs-inventory-merge` 主代码已不生产——库存合并随票 09 下线）。

### 6.2 consumer 清单

**better-school（13 个消费者）**：

| topic | consumerGroup | 类（bs-trigger/listener） |
|---|---|---|
| bs-user-register | bs-user-register-group | UserRegisterConsumer |
| bs-search-sync | bs-search-sync-group | SearchSyncConsumer |
| bs-points-changed | bs-monolith-points-changed-group | PointsChangedConsumer |
| bs-order-push | bs-order-push-group | OrderPushConsumer |
| bs-notify-push | bs-notify-push-group | NotifyPushConsumer |
| bs-notify-event | bs-notify-event-group | NotifyEventConsumer |
| bs-notify-chat | bs-notify-chat-group | NotifyChatConsumer |
| bs-notification-broadcast | bs-notification-broadcast-group | NotificationBroadcastConsumer |
| bs-lottery-drawn | bs-monolith-lottery-drawn-group | LotteryDrawnConsumer |
| bs-errand-paid | bs-errand-paid-group | ErrandPaidConsumer |
| bs-chat-recall | bs-chat-recall-group | ChatRecallPushConsumer |
| bs-chat-read | bs-chat-read-group | ChatReadPushConsumer |
| bs-chat-push | bs-chat-push-group | ChatPushConsumer |

**marketing（2 个消费者）**：

| topic | consumerGroup | 类 |
|---|---|---|
| bs-lottery-drawn | bs-points-lottery-drawn-group | points/listener/LotteryDrawnConsumer |
| bs-lottery-drawn | bs-raffle-shipping-group | raffle/listener/LotteryShippingConsumer |

（另有两侧各自的测试探针 @RocketMQMessageListener：VtConsumeThreadProbe，组名 bs-*-vt-consume-probe-group / bs-asset-vt-consume-probe-group，非生产路径。）

### 6.3 撞名判定

- **topic 撞名：2 个** —— `bs-lottery-drawn`、`bs-points-changed`。这是**既定的跨服务事件流**（marketing 生产 → bs 消费），不是配置错误；两侧 Constants 里同名字面量值一致。合并单体后这两条 MQ 链路变成进程内事件/本地事务后调用，可整体退役（连同 2 个 marketing 生产点、3 个消费者），或短期保留 broker 不动也无冲突。
- **consumerGroup 撞名：0**（bs 13 组、marketing 2 组，名字全不同）。
- **producer group 撞名：0**（better-school-producer vs marketing-producer，不同值，合并二选一，见 §3）。
- tag 撞名：无（主代码无 tag）。

---

## 附：合并 checklist（按本报告冲突推导，供后续票参考）

1. `outbox_message` 取 marketing 形状（含 delay_level/delay_ms），bs DAO/PO 适配；mapper 目录处理两域同名 OutboxMapper XML。
2. mybatis mapper-locations 改 `**` 递归。
3. xxl-job：appname/port/log-path 归一，xxl_job_group seed 合并，outboxScan 只留一条；处理 postReviewTimeoutScan（seed 幽灵行）与 pointsBucketCompaction（缺 seed）两个不一致。
4. rocketmq.producer.group 二选一；bs-lottery-drawn / bs-points-changed 链路决定退役或保留。
5. dubbo 边（bs Provider IUserQueryService ↔ marketing Consumer；marketing Provider IPointsExchangeService）在单进程后建议退役，否则端口/scan base-packages 归一（`com.betterschool` 一条覆盖）。
6. server.port、spring.application.name、Redis key 前缀（无冲突，直接共存）。
7. 若继续用 ShardingSphere：marketing 23 表走 !SINGLE 透明；若弃用：timeout_task hint 路由改造（代码变更）。
8. init 脚本四库名归一；两份 schema.sql 种子段（bs 系统助手 + marketing 两行配置）都保留。
