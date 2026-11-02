# 01 盘点 backend 根下公共物与跨服务引用现状

Type: task
Status: resolved
Blocked by:

## Question

把 `backend/` 根下与三个服务迁移相关的"东西"逐一盘点清楚，产出一份**归属清单**，供票 02 起草 spec 时直接使用。这是事实盘点 + 逐项归属建议，不写代码。

需要覆盖：

1. **sql/**：`schema.sql`、`schema-lottery.sql`、`schema-points.sql`、`schema-referral.sql`、`init-dev.sql`、`init-test.sql`、`init-marketing-dev.sql`、`init-marketing-test.sql`、`migrations/`、`xxl-job*.sql`——每个文件（或文件内分段）归属哪个服务；混合内容的拆分方案。
2. **scripts/**：每个脚本干什么、被谁引用、留 backend 根还是进哪个工程。
3. **docker/ 与 docker-compose.yml**：哪些服务定义对应哪个后端工程；确认 compose 整体留根（Notes 决策 7）无异议。
4. **docs/（backend 下的）**：内容归属哪个服务，还是留根。
5. **各工程配置文件**：bs-app、lottery-app、points-app、referral-app 的 application*.yml / nacos 配置 / xxl-job executor 配置 / 日志配置——列出现状（端口、context-path、外部依赖地址），作为 spec 中"端口分配 + activity 两 app 配置合并"决策的输入。
6. **跨服务引用清单**：营销侧 import bs-api、lottery import points-api 的全部类与位置（已知方向：activity→asset-api + bs-api、asset→bs-api、bs-* 零依赖营销，需补全具体文件清单）。
7. **logs/、.idea、散落文件**：哪些删除、哪些 gitignore、哪些迁移。

产出：归属清单以表格形式写入本票的 `## Answer`，逐项给出"归属建议 + 一句理由"，发现归属有争议的项单独标注（它们可能毕业为新票）。

## Answer

（2026-09-17 盘点收口。所有事实均经 Read/grep 核实；「⚠️ 争议」项移交票 02 spec 定夺。）

### 1. sql/

| 文件 | 归属建议 | 理由 |
| --- | --- | --- |
| `schema.sql` | better-school | 全部是单体表（bs_user/post/trade/errand/chat/moderation…），单体唯一 DDL 主体 |
| `schema-lottery.sql` | marketing-activity（lottery 域） | bs_lottery 的 DDL 主体；含 `lottery_activity` 单行表（referral 跨库直读它，见 §6） |
| `schema-referral.sql` | marketing-activity（referral 域） | bs_referral 的 DDL 主体 |
| `schema-points.sql` | marketing-asset | bs_points 的 DDL 主体 |
| `init-dev.sql` / `init-test.sql` | better-school | 只建 `better_school` / `better_school_test` 并 USE |
| `init-marketing-dev.sql` / `init-marketing-test.sql` | ⚠️ 争议 → 拆 | 现状一把建三个库（bs_points / bs_lottery / bs_referral 及 `_test`），是跨服务文件；拆分方式取决于票 02 的「activity 两域库怎么合」决策（lottery+referral 合一库则 init 只建两个库），本票不替 spec 拍 |
| `migrations/001–021` | better-school | 单体增量迁移（落 `better_school` 库；003/004 不带 USE、靠同一连接继承库） |
| `migrations/026_notification_read.sql`、`027_room_source.sql` | better-school | 同上（营销票产出但落的是单体库） |
| `migrations/022_points.sql` | marketing-asset | 自建 bs_points，内容与 schema-points.sql 必须一致（文件头约定） |
| `migrations/023_lottery.sql` | marketing-activity（lottery 域） | 自建 bs_lottery |
| `migrations/024_referral.sql` | marketing-activity（referral 域） | 自建 bs_referral |
| `migrations/025` | —（事实记录） | **缺号**：`sql/migrations/` 无 025 文件，001–027 中唯一断号；无需处理，spec 编号续 028 即可 |
| `xxl-job.sql` | 留 backend 根 | xxl_job admin 官方库，属共享中间件（xxl-job-admin 容器）而非任何服务 |
| `xxl-job-tasks.sql` | ⚠️ 争议 | 现内容只有**单体段**：`better-school-executor` 执行器 + 9 个 handler（dailyCreditJob、outboxScan、delayOutboxScan、errandTimeoutScan、productTimeoutScan、wantToBuyTimeoutScan、searchIndexRebuildJob、tradeExpireScan、postReviewTimeoutScan）；但营销三执行器（appname 见 §5）的 handler `lotteryDeductMerge` / `lotteryDrawCountExpireScan` / `pointsExpireScan` **无任何 SQL 注册落点**（推测手工在 admin 控制台配的）。spec 须定：单体段进 better-school、营销段按收敛后执行器重写并落各工程 sql/ |

### 2. scripts/

| 文件 | 归属建议 | 理由 |
| --- | --- | --- |
| `scripts/test-env.sh` | 留 backend 根 | 唯一脚本，跨服务：一把起容器 + 建单体两库 + 营销六库（`apply_init` / `apply_marketing_init`）；被 docker-compose 头注释、`backend/CLAUDE.md` §10、四 app 的测试基类引用。拆掉任何一半都会断 |

### 3. docker/ 与 docker-compose.yml

| 项 | 归属建议 | 理由 |
| --- | --- | --- |
| `docker-compose.yml` | 留根（Notes 决策 7，无异议） | 纯中间件编排（nacos/mysql/redis/rocketmq 三件/xxl-job-admin/minio/es + 测试栈两容器），四个后端 app 全部 IDE 本地起，compose 里没有应用容器 |
| `docker/es/Dockerfile`（IK 分词镜像）、`docker/rocketmq/broker.conf` | 留根 | 被 compose 的 `es.build` / broker 卷引用 |
| `docker/data/` | 不迁移 | 运行时数据卷，已在 .gitignore（`backend/docker/data/`） |
| `backend/Dockerfile` | better-school 工程 | 多阶段构建只打单体 jar（`COPY --from=build /build/bs-app/target/bs-app-*.jar`），与 compose 无关（compose 不 build 它） |

### 4. docs/（backend 下）

| 项 | 归属建议 | 理由 |
| --- | --- | --- |
| `docs/README.md` | 留根 | 文档索引，已按单体/营销分块；重构后改写指向 |
| `docs/testing.md` | 留根 | 讲常驻栈 + `test-env.sh` 的跑测纪律，跨服务设施；单体测试先例、营销三服务测试同遵循 |
| `docs/api/{user,community,trade,errand,chat,files,moderation}.md` | better-school | 单体七模块接口文档 |
| `docs/api/{points,lottery,referral}.md` | points→asset；lottery/referral→activity | 各服务自己的接口文档 |
| `docs/db/{user,community,community_comment,trade,errand,chat,moderation}.md` | better-school | 单体库表文档 |
| `docs/db/{points,lottery,referral}.md` | points→asset；lottery/referral→activity | 各服务库表文档 |
| `docs/api/README.md`、`docs/db/README.md` | ⚠️ 拆 | 两个 README 都是「单体段 + 营销段」混合体；按所属拆进各工程，根 `docs/README.md` 保留索引职责 |
| `docs/architecture/marketing-services.md` | activity + asset 共享 → spec 改写 | 讲三服务怎么跑、端口/Nacos/Dubbo/契约纪律；收敛成两服务后由票 02 spec 决定改写成一份还是两份 |

### 5. 四 app 配置现状（票 02「端口分配 + activity 两 app 配置合并」的直接输入）

| 项 | bs-app（单体） | points | lottery | referral |
| --- | --- | --- | --- | --- |
| HTTP 端口 | 8080 | 8081 | 8082 | 8083 |
| context-path | 无（`/api/**` 由各 Controller 声明） | 同 | 同 | 同 |
| MySQL 库 | `better_school`（3306） | `bs_points` | `bs_lottery` | `bs_referral` |
| Dubbo（tri） | 50051，Provider（`IUserQueryService`） | 50052，Provider（`IPointsExchangeService`）+ Consumer | 50053，Consumer | 50054，Consumer |
| RocketMQ producer group | `better-school-producer` | `points-producer` | `lottery-producer` | `referral-producer` |
| xxl-job executor 端口 / appname | 9999 / `better-school-executor` | 9998 / `better-school-points-executor` | 9997 / `better-school-lottery-executor` | 9996 / `better-school-referral-executor` |
| Nacos | 同一 dataId `better-school-shared.yml`（jwt.secret / jwt.expire-days），四 app 共享，server-addr 留本地 | 同 | 同 | 同 |
| 独有配置 | multipart / jackson / ES 9200 / MinIO | — | `lottery.bucket.degrade-cooldown-millis`（redis 桶降级） | `referral.activity-config-table: bs_lottery.lottery_activity` |
| 日志配置 | **四 app 均无独立日志配置**（无 logback*.xml / logging.file），走 Spring Boot 默认 + yml 的 `logging.level`；xxl 执行器日志经各 yml 的 `xxl.job.executor.log-path` 写 `./logs/xxl-job*`（收敛后 log-path 随执行器合并） | 同 | 同 | 同 |
| 测试 profile | `application-test.yml`（测试连接唯一事实来源） | 各自一份 `application-test.yml`（13306/逻辑库 1/19876，Nacos+Dubbo 由测试基类 `System.setProperty` 注入） | 同 | 同 |

**activity 两 app 合并的联改点**（供 spec 直接抄）：

- 端口三组各二合一：HTTP 8082/8083、Dubbo 50053/50054、xxl executor 9997/9996；appname 两个合一（连带 §1 的 xxl 任务清单落点）。
- producer group `lottery-producer` / `referral-producer` 合一；消费边按 topic 注解不受影响。
- lottery 的 `lottery.bucket.*` 与 referral 的 `referral.activity-config-table` 都进合并后 yml；两域同库后 `referral.activity-config-table` 的库名前缀可去掉（是否保留该配置项由 spec 定）。
- ⚠️ 前端联动：`front/` 的 Vite 代理按服务前缀分发到 8080/8081/8082/8083（`docs/api/README.md` 口径），activity 收敛成单端口后代理目标要同步改——列进票 02 的搬运/联改清单。
- ⚠️ 测试基类注入的 Dubbo 端口、`application-test.yml` 的连接串随新端口全改（lottery/points/referral 三个 `*ApiTestBase` + 各自 test yml）。

### 6. 跨服务引用清单（全部经 grep 核实，`--exclude-dir=target`）

| 方向 | 文件 | 内容 |
| --- | --- | --- |
| activity→bs-api | `lottery/lottery-infrastructure/…/adapter/port/marketing/UserQueryPort.java` | import `com.betterschool.api.IUserQueryService` + `api.dto.UserBasicDTO`（@DubboReference） |
| activity→bs-api | `referral/referral-infrastructure/…/adapter/port/marketing/UserQueryPort.java` | 同上 |
| asset→bs-api | `points/points-infrastructure/…/adapter/port/marketing/UserQueryPort.java` | 同上 |
| activity→points-api | `lottery/lottery-infrastructure/…/adapter/port/marketing/PointsExchangePort.java` | import `com.betterschool.points.api.IPointsExchangeService` + `PointsDeductRequestDTO/ResponseDTO`（@DubboReference）；域层只认自家 `IPointsExchangePort`，`points-api` 的翻译全部收敛在这一个文件 |
| pom 依赖 | `lottery-infrastructure/pom.xml` | `bs-api` + `points-api` 两条 |
| pom 依赖 | `points-infrastructure/pom.xml`、`referral-infrastructure/pom.xml` | 各一条 `bs-api` |
| bs-\* → 营销 | **零依赖**（grep `com.betterschool.(lottery|points|referral).` 于 bs-api/bs-app/bs-case/bs-domain/bs-infrastructure/bs-trigger/bs-types 无命中） | 与 Notes 决策 6 一致 |
| referral→lottery | 无 java 依赖，仅**跨库直读** `bs_lottery.lottery_activity`：`referral-app/application.yml`（`referral.activity-config-table`）、`referral-infrastructure/…/ActivityConfigRepository.java`、`referral-infrastructure/…/dao/referral/po/LotteryActivityPO.java`、`ReferralMapper.xml`、测试基类 `ReferralApiTestBase` | 「跨 schema 不 join」的唯一有意偏离；收敛进 activity 单库单服务后此偏离**自然消解**（同库直读不再是跨服务问题） |

### 7. logs/、.idea、散落文件

| 项 | 处置建议 | 理由 |
| --- | --- | --- |
| `backend/logs/`（24 项：`app-*.log`、smoke/build 日志 + `xxl-job{,-lottery,-points,-referral}` 四个执行器日志目录） | 删除 | 已 gitignore（`backend/logs/`、`backend/*/logs/`），零跟踪；Notes 决策 7 已定清理 |
| `backend/.idea/` | 不迁移、保持忽略 | 已 gitignore（`.idea/`）、零跟踪；IDE 本地产物 |
| backend 根其余 | 无杂物 | 根下只有 `CLAUDE.md`（留根）、`pom.xml`（单体聚合器→better-school）、`docker-compose.yml`、`Dockerfile`（已列 §3）。仓库根杂物（图片、`实现方案3.md` 等）属 Notes 决策 8，不在本票范围 |

### 移交票 02 的争议/新发现（不毕业新票，票 02 spec 一并覆盖即可）

1. `init-marketing-{dev,test}.sql` 的拆分方式 ← 取决于 activity 两域库怎么合（schema-lottery + schema-referral 合一库？）。
2. xxl-job：营销 handler 无 SQL 注册落点 + activity 合并后执行器 appname/任务清单归属。
3. front/ Vite 代理端口与四 app 测试基类端口注入随端口分配联动。
