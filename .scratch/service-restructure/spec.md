# 服务结构与目录重构 spec

> 状态：**已定稿（2026-09-17 用户审阅：§4 的库名 / 包名域段 / 端口 / 架构文档四项分叉均按建议值，其余建议值默认生效）**。起草：票 02。
> 基线：map Notes 决策快照 1–8（不得推翻）+ 票 01 归属清单（全部经 Read/grep 核实）。
> 本 spec 是后续 impl effort 的唯一执行依据；定稿后由 to-tickets 另起 effort，本 effort 不动代码。

## 0. 范围与不变量

- 只动**结构**（目录 / 模块 / 包名 / 配置 / sql / docs 归属），不改任何业务行为。
- 三个工程保持**互相独立的 Maven project**（无 reactor），跨工程取契约仍经本地仓库（`com.betterschool:bs-api` 坐标不变，bs-api 本身一字不动）。
- 前端 HTTP URL（`/api/<svc>/**`）与 RocketMQ topic 名不变；Dubbo 接口 FQN 随包名直接切换（无生产流量、无过渡需求，map 决策 6）。
- 起草中**新核实**的事实（票 01 之外，均 grep 证实，已吸收进下文）：
  1. **xxl handler 冲突**：lottery 与 referral 各自的 `OutboxMessageSender` 都注册 `@XxlJob("outboxScan")`，收敛成一个执行器后重名（xxl-job 同执行器内 handler 名必须唯一）→ 见 §3.1-D。
  2. **同名类冲突**：lottery/referral 两套代码里 `GlobalExceptionHandler`、`PingController`、`SecurityUtils`、`OutboxMapper.xml`、api 层的 `UserBasicResponseDTO`/`PageResult`/`Response`、types 层的 `Constants`/`OutboxStatusEnum`/`ResponseCodeEnum`/`AppException` 同名 → 合并后所有层必须带域子包，见 §2.2。
  3. **case 模块空壳**：三营销服务的 `*-case` 均无 src（与单体骨架保持一致的占位）→ 合并后各留一个空壳模块。
  4. 三营销服务的 `*-app` 下配置类（`MybatisConfig`/`SecurityConfig`/`TransactionConfig`/`XxlJobConfig`）同名同层 → 合并时逐个 diff 取一致者，属结构联改。

## 1. 目标结构

`backend/` 最终目录树（Notes 决策 1、7）：

```
backend/
├── CLAUDE.md                      # 留根（编码规范，迁移后只更新其中目录示例）
├── docker-compose.yml             # 留根（纯中间件编排，无应用容器）
├── docker/                        # 留根（es/Dockerfile、rocketmq/broker.conf；data/ 运行时卷不动）
├── docs/                          # 留根：README.md（索引改写）、testing.md、architecture/marketing-services.md（改写两服务口径）
├── scripts/
│   └── test-env.sh                # 留根（跨服务：起容器 + 建库，建库数 6 → 4）
├── sql/
│   └── xxl-job.sql                # 留根（xxl-job-admin 官方库，属共享中间件）
├── better-school/                 # 单体工程（bs-* 七模块平移，零重命名）
│   ├── pom.xml                    # 原 backend/pom.xml（artifactId 建议改 better-school，见 §4）
│   ├── Dockerfile                 # 原 backend/Dockerfile（COPY --from 路径不变，构建 context 变为工程目录）
│   ├── bs-types / bs-api / bs-domain / bs-case / bs-infrastructure / bs-trigger / bs-app
│   ├── sql/                       # schema.sql、init-dev.sql、init-test.sql、migrations/001–021+026+027、xxl-job-tasks.sql（单体段）
│   └── docs/                      # api/{user,community,trade,errand,chat,files,moderation}.md + api/README.md 单体段
│                                  # db/{user,community,community_comment,trade,errand,chat,moderation}.md + db/README.md 单体段
├── marketing-activity/            # 营销活动服务（lottery + referral 收敛，主服务式一套模块）
│   ├── pom.xml                    # 新聚合 pom（parent 挂 Boot 官方 parent，与另两工程兄弟独立）
│   ├── marketing-activity-types   # activity.types.lottery.* / activity.types.referral.*
│   ├── marketing-activity-api     # activity.api.lottery.* / activity.api.referral.*
│   ├── marketing-activity-domain  # activity.domain.lottery.* / activity.domain.referral.*
│   ├── marketing-activity-case    # 空壳（原两个空 case 合一）
│   ├── marketing-activity-infrastructure
│   ├── marketing-activity-trigger
│   ├── marketing-activity-app     # 唯一启动器 MarketingActivityApplication（com.betterschool.activity）
│   ├── sql/                       # schema-lottery.sql、schema-referral.sql、init-dev.sql、init-test.sql、migrations/023+024、xxl-job-tasks.sql（活动段）
│   └── docs/                      # api/{lottery,referral}.md + README 活动段；db/{lottery,referral}.md + README 活动段
└── marketing-asset/               # 营销资产服务（points 收敛，主服务式一套模块）
    ├── pom.xml
    ├── marketing-asset-types      # asset.types.*
    ├── marketing-asset-api        # asset.api.*
    ├── marketing-asset-domain     # asset.domain.points.*
    ├── marketing-asset-case       # 空壳
    ├── marketing-asset-infrastructure
    ├── marketing-asset-trigger
    ├── marketing-asset-app        # 唯一启动器 MarketingAssetApplication（com.betterschool.asset）
    ├── sql/                       # schema-points.sql、init-dev.sql、init-test.sql、migrations/022、xxl-job-tasks.sql（资产段）
    └── docs/                      # api/points.md + README 资产段；db/points.md + README 资产段
```

要点：

- **主服务**：模块名、内部结构、包名全部不动（map 决策 2），只是从 `backend/` 根平移进 `backend/better-school/`。
- **营销两服务**：模块前缀 `marketing-activity-` / `marketing-asset-`（map 决策 3/4），七模块骨架与主服务同名对齐（types/api/domain/case/infrastructure/trigger/app）。
- activity 的 domain 模块下 `lottery` / `referral` 两个领域包（map 决策 3）；共享一个 app 启动器。
- `.gitignore` **无需改动**：`backend/*/*/target/` 规则迁移后仍命中（新路径深度不变），`*.log` 全局规则已覆盖 `front/vite-*.log`（Notes 决策 8 说的"加 .gitignore"实测已存在）。

## 2. 映射表

### 2.1 目录 / 模块映射

| 旧位置 | 新位置 | 说明 |
| --- | --- | --- |
| `backend/pom.xml` | `backend/better-school/pom.xml` | 聚合 bs-* 七模块；artifactId `better-school-backend` → `better-school`（§4-1） |
| `backend/bs-*`（七模块） | `backend/better-school/bs-*` | 原样平移 |
| `backend/Dockerfile` | `backend/better-school/Dockerfile` | `COPY --from=build /build/bs-app/target/...` 路径不变（jar 内相对路径不变） |
| `backend/lottery/lottery-*` | `backend/marketing-activity/marketing-activity-*` | 逐模块改名，见 §2.2 |
| `backend/referral/referral-*` | `backend/marketing-activity/marketing-activity-*` | 与 lottery 合并进同一套模块 |
| `backend/points/points-*` | `backend/marketing-asset/marketing-asset-*` | 逐模块改名 |
| `backend/lottery/pom.xml`、`backend/referral/pom.xml` | `backend/marketing-activity/pom.xml` | 新聚合 pom：合并两份 dependencyManagement（版本矩阵一致，直接取 lottery 版），modules 换新名 |
| `backend/points/pom.xml` | `backend/marketing-asset/pom.xml` | 同上，artifactId `marketing-asset` |
| `backend/lottery/lottery-case`、`backend/referral/referral-case`（均空壳） | `backend/marketing-activity/marketing-activity-case` | 二合一空壳 |
| `backend/points/points-case`（空壳） | `backend/marketing-asset/marketing-asset-case` | 空壳保留 |

模块内工件改名规则：`lottery-<layer>` → `marketing-activity-<layer>`、`referral-<layer>` → `marketing-activity-<layer>`、`points-<layer>` → `marketing-asset-<layer>`（layer ∈ types/api/domain/case/infrastructure/trigger/app）。app 模块内两套同名配置类（`MybatisConfig`/`SecurityConfig`/`TransactionConfig`/`XxlJobConfig`）合并为一套（diff 后内容一致者取一，`XxlJobConfig` 取活动执行器参数）。

### 2.2 包名映射

**规则**（Notes 决策 5 为基线，落细化）：

- better-school：包名零改动。
- marketing-asset：包根 `com.betterschool.asset`。**domain 层保留领域段**（map 原文示例 `asset.domain.points.*`）；其余层直接跟层名（map 原文示例 `asset.api.IPointsExchangeService`）。
- marketing-activity：包根 `com.betterschool.activity`。因 §0-2 的同名类冲突，**所有层都带域子包**（`lottery` / `referral`）。

| 旧包 | 新包 |
| --- | --- |
| `com.betterschool.points.domain.{rest}` | `com.betterschool.asset.domain.points.{rest}` |
| `com.betterschool.points.{types,api,infrastructure,trigger}.{rest}` | `com.betterschool.asset.{types,api,infrastructure,trigger}.{rest}` |
| `com.betterschool.points`（app：启动类、config） | `com.betterschool.asset` |
| `com.betterschool.lottery.{layer}.{rest}`（layer ∈ api/types/domain/infrastructure/trigger） | `com.betterschool.activity.{layer}.lottery.{rest}` |
| `com.betterschool.referral.{layer}.{rest}` | `com.betterschool.activity.{layer}.referral.{rest}` |
| `com.betterschool.lottery.config.*`、`com.betterschool.referral.config.*` | 合并为 `com.betterschool.activity.config.*`（一套） |
| `com.betterschool.lottery.LotteryApplication`、`com.betterschool.referral.ReferralApplication` | `com.betterschool.activity.MarketingActivityApplication`（一个） |
| `com.betterschool.points.PointsApplication` | `com.betterschool.asset.MarketingAssetApplication` |
| `com.betterschool.lottery.integration.support.LotteryApiTestBase`、`com.betterschool.referral.integration.support.ReferralApiTestBase` | `com.betterschool.activity.integration.support`（两类共存，类名不改，仅测试脚手架包名归位） |
| `com.betterschool.points.integration.support.PointsApiTestBase` | `com.betterschool.asset.integration.support.PointsApiTestBase` |
| 测试类 `com.betterschool.{lottery,referral}.integration.*Test` | `com.betterschool.activity.integration.*`（两类测试类名已带域前缀不冲突，共用一包）；`com.betterschool.points.integration.*` → `com.betterschool.asset.integration.*` |

注：map 决策 5 的示例写的是 `activity.api.ILotteryService`，按本表落地为 `activity.api.lottery.ILotteryService`（域子包是同名类冲突所迫的细化，非推翻——见 §4-3，定稿时确认）。

### 2.3 sql 映射（按票 01 §1 清单 + §4-2 库名决策）

| 旧文件 | 新位置 | 改动 |
| --- | --- | --- |
| `backend/sql/schema.sql` | `better-school/sql/schema.sql` | 无 |
| `backend/sql/init-dev.sql`、`init-test.sql` | `better-school/sql/` | 无 |
| `backend/sql/migrations/001–021`、`026`、`027` | `better-school/sql/migrations/` | 无（025 断号保持，后续编号续 028） |
| `backend/sql/migrations/022_points.sql` | `marketing-asset/sql/migrations/022_points.sql` | USE 改 `bs_asset`（`_test` 同） |
| `backend/sql/migrations/023_lottery.sql`、`024_referral.sql` | `marketing-activity/sql/migrations/` | USE 改 `bs_activity` |
| `backend/sql/schema-points.sql` | `marketing-asset/sql/schema-points.sql` | USE 改 `bs_asset` |
| `backend/sql/schema-lottery.sql`、`schema-referral.sql` | `marketing-activity/sql/` | USE 改 `bs_activity`（两文件保持分立，同库不同表集） |
| `backend/sql/init-marketing-dev.sql`、`init-marketing-test.sql` | **拆分**：`marketing-activity/sql/init-{dev,test}.sql`（只建 `bs_activity`/`bs_activity_test`）+ `marketing-asset/sql/init-{dev,test}.sql`（只建 `bs_asset`/`bs_asset_test`） | 票 01 争议 1 的落点 |
| `backend/sql/xxl-job.sql` | `backend/sql/xxl-job.sql` 留根 | 无 |
| `backend/sql/xxl-job-tasks.sql` | **拆三份**：单体段 → `better-school/sql/xxl-job-tasks.sql`（现内容原样）；新增 `marketing-activity/sql/xxl-job-tasks.sql`（活动执行器 + 4 handler）、`marketing-asset/sql/xxl-job-tasks.sql`（资产执行器 + 2 handler） | 见 §3.1-D |

数据侧：库改名 = 本地 dev/test 环境重建（`test-env.sh` 一键重建，无生产数据可迁移）；`migrations/022–024` 与 `schema-*.sql` 的 USE 跟随新库名。

### 2.4 配置映射

| 旧 | 新 |
| --- | --- |
| `lottery-app/application.yml` + `referral-app/application.yml` | `marketing-activity-app/application.yml` 合一（§4-4 合并口径） |
| `points-app/application.yml` | `marketing-asset-app/application.yml`（值随 §4 改） |
| `lottery-app/application-test.yml` + `referral-app/application-test.yml` | `marketing-activity-app/application-test.yml` 合一（连 `bs_activity_test`、Dubbo 50053、Nacos/Dubbo System.setProperty 注入口径不变） |
| `points-app/application-test.yml` | `marketing-asset-app/application-test.yml`（连 `bs_asset_test`） |
| Nacos `better-school-shared.yml`（jwt.secret / expire-days） | **不动**（dataId、group 不变，三服务仍同一份） |

### 2.5 docs 映射（按票 01 §4 清单）

| 旧 | 新 |
| --- | --- |
| `backend/docs/README.md`、`testing.md` | 留 `backend/docs/`（README 索引改写为三工程口径） |
| `backend/docs/architecture/marketing-services.md` | 留 `backend/docs/architecture/`，**改写为两服务口径**（跨服务运行纪律文档，单份留根比拆两份更贴主题——§4-8） |
| `backend/docs/api/{user,community,trade,errand,chat,files,moderation}.md`、`db/` 同名单体七篇 | `better-school/docs/api|db/` |
| `backend/docs/api/{lottery,referral}.md`、`db/` 同名两篇 | `marketing-activity/docs/api|db/` |
| `backend/docs/api/points.md`、`db/points.md` | `marketing-asset/docs/api|db/` |
| `backend/docs/api/README.md`、`db/README.md`（单体段+营销段混合） | 按段拆进各工程 `docs/api/README.md`、`docs/db/README.md`；根 `docs/README.md` 保留索引职责并改写 |

### 2.6 其余归属（票 01 §2/§3/§7）

| 项 | 处置 |
| --- | --- |
| `backend/scripts/test-env.sh` | 留根；`apply_marketing_init` 改为依次执行 activity/asset 两工程的 `init-{dev,test}.sql`（建库 6 → 4） |
| `backend/docker-compose.yml`、`docker/es/Dockerfile`、`docker/rocketmq/broker.conf` | 留根不动 |
| `backend/docker/data/` | 不迁移（gitignore 运行时卷） |
| `backend/logs/`、`backend/.idea/` | 删除 logs（零跟踪）；`.idea` 不迁移保持忽略 |
| `backend/CLAUDE.md` | 留根；更新其中目录/路径示例为三工程口径，规范条目本身不动 |

## 3. 联改清单

### 3.1 包名重命名的波及面

- **A. import 全量改写**：按 §2.2 规则机械替换；改完 `grep -rn "com\.betterschool\.(lottery|points|referral)\." backend --exclude-dir=target` 必须零命中（含 test 与 mapper XML）。
- **B. Dubbo FQN**：
  - Provider 侧：`points-trigger` 的 `PointsExchangeDubboService`（`@DubboService`）随包名切换；`asset-app/application.yml` 的 `dubbo.scan.base-packages: com.betterschool.points` → `com.betterschool.asset`（漏改则应用照常启动但一条 Export 都没有——现 yml 已有此坑注释）。
  - Consumer 侧：lottery 的 `PointsExchangePort`（`@DubboReference(IPointsExchangeService)`）与 `PointsDeductRequestDTO/ResponseDTO` 引用同步切到 `com.betterschool.asset.api.*`。双侧同一次重构内切换，Dubbo 按 FQN 寻址无灰度需求（map 决策 6）。
  - `IUserQueryService`（bs-api）三处 `@DubboReference` 不受影响。
  - `serialize-check-status: WARN` 策略不变。
- **C. Nacos**：`config.import: nacos:better-school-shared.yml` 四变三，dataId 不动；`spring.application.name` / `dubbo.application.name` 改 `marketing-activity` / `marketing-asset`（无消费方按名字寻址，安全）。
- **D. xxl-job**：
  - 执行器三合一为二：appname `marketing-activity-executor`（9997）+ `marketing-asset-executor`（9998）；旧 `better-school-{lottery,points,referral}-executor` 弃用。
  - **handler 改名（§0-1 冲突）**：activity 合并后两个 outbox 扫描重名 → lottery 侧 `outboxScan` → `lotteryOutboxScan`、referral 侧 → `referralOutboxScan`；asset 侧唯一，保留 `outboxScan`。语义不变，只改 `@XxlJob` 注解值。
  - 营销 handler 首次落 SQL 注册（原先手工在 admin 配、无落点，票 01 争议 2）：`marketing-activity/sql/xxl-job-tasks.sql` 注册 4 个 handler（`lotteryDeductMerge`、`lotteryDrawCountExpireScan`、`lotteryOutboxScan`、`referralOutboxScan`）；`marketing-asset/sql/xxl-job-tasks.sql` 注册 2 个（`pointsExpireScan`、`outboxScan`）。cron 建议值：outbox 类 `0/30 * * * * ?`（单体先例）、`lotteryDeductMerge` 与 `pointsExpireScan` 每分钟、`lotteryDrawCountExpireScan` 每分钟；⚠️ 原手工配置的 cron 无本仓落点，impl 时以 admin 现配置为准核对一次。
  - executor `log-path` 随执行器合并：`./logs/xxl-job-activity`、`./logs/xxl-job-asset`。
- **E. MyBatis**：
  - mapper XML 随 app 模块归位：`marketing-activity-app/src/main/resources/mybatis/mapper/{lottery,referral}/*.xml`（`OutboxMapper.xml` 两份同名，必须分子目录）；`mybatis.mapper-locations` 改 `classpath:/mybatis/mapper/lottery/*.xml,classpath:/mybatis/mapper/referral/*.xml`。
  - XML 内 namespace / resultType / parameterType 的 FQN 按包名规则全量替换。
  - 拉新活动配置表名运行时取自 `referral.activity-config-table`：主 yml 值改 `lottery_activity`（去库名前缀，§4-4，票 01 §6 的偏离自然消解）；`application-test.yml` 的覆盖值改 `bs_activity_test.lottery_activity`；`ReferralMapper.xml` 内提及 `bs_lottery` 的注释同步更新。
  - `dao` 包名随 §2.2（activity 侧形如 `activity.infrastructure.lottery.dao.lottery.*`，双层域名是机械规则的产物，接受）。

### 3.2 跨服务引用改写点（全部 4 个 Port + 4 条 pom 依赖，票 01 §6）

| 位置 | 改写 |
| --- | --- |
| `lottery-infrastructure/…/port/marketing/PointsExchangePort.java` | import `com.betterschool.points.api.*` → `com.betterschool.asset.api.*`（域层 `IPointsExchangePort` 本身不动） |
| `lottery/lottery-infrastructure/pom.xml` | `points-api` → `marketing-asset-api`（bs-api 依赖不变） |
| lottery/referral/points 三个 `UserQueryPort.java` | 仅包名随自家服务变化，bs-api 引用不动 |
| `points-infrastructure/pom.xml`、`referral-infrastructure/pom.xml` | bs-api 依赖不变，artifactId 随模块改名 |
| bs-* 七模块 | 零依赖营销（grep 证实），零改动 |

### 3.3 前端与测试联动（票 01 争议 3）

- `front/vite.config.ts` proxy：`/api/referral` 目标 8083 → **8082**；`/api/lottery` 8082 不变；`/api/points` 8081 不变；注释里"三条营销服务前缀"改"两条"。
- 测试基类：`PointsApiTestBase` / 合并后的两个活动测试基类的 Dubbo 端口注入、各 `application-test.yml` 连接串随 §4 端口与库名全改。
- RocketMQ **consumer group 常量值不改**（`Constants.*_CONSUMER_GROUP`，消费组改名会触发新组重放/跳过历史消息）；producer group 改名安全（见 §4-6）。

### 3.4 非代码收尾

- `backend/CLAUDE.md`：目录示例改三工程口径。
- `backend/docs/README.md`：索引改写。
- 仓库根杂物：见 §5。

## 4. 补充决策（2026-09-17 定稿：#2/#3/#5/#8 四项分叉经用户确认按建议值，其余按建议值生效）

| # | 决策 | 建议值 | 理由 |
| --- | --- | --- | --- |
| 1 | better-school 聚合 pom artifactId | `better-school`（原 `better-school-backend`） | 与 lottery/points 聚合 pom 命名先例对齐（artifactId = 工程目录名）；仅本地坐标，无消费方 |
| 2 | activity/asset 库名 | `bs_activity` / `bs_asset`（含 `_test`），弃 `bs_lottery`/`bs_points`/`bs_referral` | 与服务名对齐、延续 `bs_*` 前缀约定；本地库一键重建无可迁移数据（§2.3） |
| 3 | activity 包名域子包范围 | **全部五层带域段**（api/types/domain/infrastructure/trigger） | 同名类冲突（§0-2）物理上必须；map 示例 `activity.api.ILotteryService` 相应细化为 `activity.api.lottery.ILotteryService` |
| 4 | activity 两 app 配置合并口径 | 单份 `application.yml`：HTTP 8082、Dubbo 50053、xxl 9997 + appname `marketing-activity-executor`；datasource 指向 `bs_activity`；producer group `marketing-activity-producer`；`lottery.bucket.degrade-cooldown-millis` 原样保留；`referral.activity-config-table` 保留、值改 `lottery_activity`（去库前缀，避免动 `ActivityConfigRepository` 读配置的逻辑） | 两域同库同进程后配置自然合一；保留该配置项是最小改动（删项属可选顺手项，impl 定） |
| 5 | 三服务端口分配 | better-school 8080/50051/9999（不动）；marketing-asset 8081/50052/9998；marketing-activity 8082/50053/9997；**弃 8083/50054/9996** | 尽量沿用现值（points=8081、lottery=8082），前端/文档/心智迁移面最小 |
| 6 | producer / consumer group | producer：`marketing-activity-producer`（并 lottery+referral 两组）、`marketing-asset-producer`（原 points-producer）；consumer group 常量值一律不改 | producer group 仅发送方身份；consumer group 改名有重放风险（§3.3） |
| 7 | 启动类 | `com.betterschool.activity.MarketingActivityApplication`、`com.betterschool.asset.MarketingAssetApplication`；scanBasePackages = 各自包根 | 与包根一致 |
| 8 | `docs/architecture/marketing-services.md` | 留根单份，改写为两服务口径 | 跨服务运行/契约纪律主题，拆两份会互相引用；根 README 索引不变 |
| 9 | `spring.application.name` | `marketing-activity` / `marketing-asset` | 与工程名一致，Nacos/Dubbo 控制台可读性 |
| 10 | xxl executor 标题 | `xxl_job_group.title`：`mktActivity` / `mktAsset` | title 限 12 字符（现 SQL 注释明示） |

## 5. 仓库根杂物归置（Notes 决策 8 明细）

| 项 | 处置 |
| --- | --- |
| `55a4b0d413d2fb4ee8c844d69d906455.jpg`、`fcc256337cab8d96e8e6edcccf94ff87.jpg`、`示例.jpg`、`市集.png`、`ScreenShot_2026-09-11_022904_160.png` | 归档 → `docs/archive/`（保留原名） |
| `评论/`（3 张 png） | 归档 → `docs/archive/评论/` |
| `实现方案3.md` | 归档 → `docs/archive/实现方案3.md` |
| `front/vite-ticket01.log`、根目录 `vite-ticket07.log`、`vite-ticket08.log` | 删除（后两者被 `.gitignore` 全局 `*.log` 覆盖，故不出现在 git status；无需新增规则——Notes 决策 8 "加 .gitignore" 经核实已存在） |
| `CLAUDE.md`、`CONTEXT.md`、`docs/`、`.scratch/`、`front/`、`.claude/` | 不动 |

## 6. 验收标准

1. **编译**：`backend/better-school`、`backend/marketing-activity`、`backend/marketing-asset` 三工程各自 `mvn compile` 通过（营销两工程按序在 `mvn install` 单体后构建——契约经本地仓库，构建顺序写进各工程 README 或根 docs）。
2. **测试**：三工程各自全量 `mvn test` 通过；营销侧测试基类注入的新端口/新库名生效（`bs_activity_test` / `bs_asset_test` 由 `test-env.sh` 重建）。
3. **行为不变**：三服务可本地启动；HTTP 接口 URL 与响应不变（前端无感，活动页/我的奖品/消息页冒烟）；Dubbo 兑换边（activity→asset）与用户查询边（→bs）调通。
4. **残留为零**：`grep -rn "com\.betterschool\.(lottery|points|referral)\." backend --exclude-dir=target` 零命中；旧 artifactId（`points-api`、`lottery-*`、`referral-*`、`points-*`）零命中；旧库名 `bs_lottery|bs_points|bs_referral` 在 backend 下（除 gitignore 的 docker/data）零命中。
5. **xxl-job**：两执行器在 admin 自动注册；6 个营销 handler 触发一次成功（含改名的两个 outbox 扫描）；单体 9 handler 不受影响。
6. **前端**：Vite 代理改后活动页、拉新页、积分页全部走通（8082 双前缀 + 8081）。
7. **结构验收**：backend 根只剩 §1 目录树所列项；`git status` 无意外未跟踪产物。

## 7. 执行顺序建议（供 to-tickets 拆票，本 spec 不出正式票）

| 序 | 票 | 内容 | 依赖 |
| --- | --- | --- | --- |
| 1 | 根杂物归档 | §5 全表（独立小票，随时可做） | 无 |
| 2 | better-school 平移 | §1/§2 单体相关：bs-* 平移 + 聚合 pom 迁移改名 + Dockerfile + better-school/sql、docs 落位（含 xxl-job-tasks.sql 单体段） | 无；与 3/4 无交集可并行 |
| 3 | marketing-asset 收敛 | points 七模块平移改名 + 包重命名（§2.2 asset 规则）+ 配置/端口/appname + xxl SQL 资产段 + asset docs/sql 落位；**联改**：lottery 侧 `PointsExchangePort` import + pom 依赖两条（保持全树可编译） | 先于 4（activity 要引新 FQN） |
| 4 | marketing-activity 收敛 | lottery+referral 合并 + 包重命名（全层域段）+ 两 app 合一（主类/配置类/yml/mapper 目录）+ handler 改名 + xxl SQL 活动段 + vite 代理 + 测试基类/测试 yml + activity docs/sql 落位 | 依赖 3 |
| 5 | 收尾验收 | test-env.sh 改写（6→4 库）、`backend/CLAUDE.md` 与根 docs/README 改写、architecture/marketing-services.md 两服务化、logs 清理、§6 全项验收 | 依赖 2+3+4 |

拆票提示：票 3/4 都动 `backend/lottery|points` 目录，**不可并行**；票 1/2 与之并行安全。每票独立可编译、全树绿（票 3 的跨侧联改是关键）。
