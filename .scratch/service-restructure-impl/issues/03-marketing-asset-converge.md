# 03: marketing-asset 收敛（points → marketing-asset）

**Spec:** `.scratch/service-restructure/spec.md` §1、§2.1、§2.2（asset 规则）、§2.3、§2.4、§3.1-B/C/E、§3.2、§4（#2/#5/#6/#7/#9/#10）

**What to build:** points 服务收敛为 `backend/marketing-asset/` 工程——七模块改名、包根切到 `com.betterschool.asset`、端口/库名/执行器按定稿值切换；**同票内**完成 Dubbo 双侧切换（Provider 包名 + lottery 消费方引用），保证全树可编译。此票先于票 04（activity 要引新 FQN）。

**Blocked by:** None (can start immediately)；但票 04 依赖本票，**不可与票 04 并行**（都动 `backend/lottery|points` 目录）

**Status:** resolved

- [x] `backend/points/points-*` 七模块 → `backend/marketing-asset/marketing-asset-{types,api,domain,case,infrastructure,trigger,app}`（case 为空壳保留）；新聚合 pom artifactId `marketing-asset`，dependencyManagement 取原 points 版，bs-api 坐标 `com.betterschool:bs-api` 不变
- [x] 包名映射（§2.2 asset 规则）：`com.betterschool.points.domain.*` → `com.betterschool.asset.domain.points.*`（**domain 保留领域段**）；`{types,api,infrastructure,trigger}` 直接跟层名；`PointsApplication` → `com.betterschool.asset.MarketingAssetApplication`（scanBasePackages = 包根）；测试脚手架 `PointsApiTestBase` → `asset.integration.support`
- [x] `application.yml` / `application-test.yml` 按 §4 改值：`spring/dubbo.application.name` = `marketing-asset`、HTTP 8081、Dubbo 50052、xxl 9998 + appname `marketing-asset-executor`（title `mktAsset`，限 12 字符）、datasource `bs_asset`（test 连 `bs_asset_test`）、producer group `marketing-asset-producer`、`dubbo.scan.base-packages` → `com.betterschool.asset`（漏改则启动正常但零 Export——现 yml 已有此坑注释）；**consumer group 常量值一律不改**（改名触发重放）
- [x] Dubbo 双侧同票切换（§3.1-B/§3.2）：Provider `PointsExchangeDubboService`（`@DubboService`）随包名切；lottery 侧 `PointsExchangePort` import → `com.betterschool.asset.api.*`（`IPointsExchangePort` 本身不动）、`PointsDeductRequestDTO/ResponseDTO` 引用同步切；`lottery-infrastructure/pom.xml` 依赖 `points-api` → `marketing-asset-api`；`serialize-check-status: WARN` 不变
- [x] sql 落位 `marketing-asset/sql/`：`schema-points.sql`（USE 改 `bs_asset`，`_test` 同）、`init-dev.sql`/`init-test.sql`（只建 `bs_asset`/`bs_asset_test`，从 `init-marketing-{dev,test}.sql` 拆出）、`migrations/022_points.sql`（USE 改）、`xxl-job-tasks.sql`（资产执行器 + 2 handler：`outboxScan`、`pointsExpireScan`；cron 建议值见 §3.1-D，⚠️ 原手工 cron 以 admin 现配置核对一次）
- [x] mapper XML 随 app 模块归位，namespace/resultType/parameterType FQN 按包名规则全量替换
- [x] docs 落位 `marketing-asset/docs/`：`api/points.md`、`db/points.md`
- [x] 验收：`better-school` `mvn install` 后本工程全量 `mvn test` 绿（测试基类注入新端口/新库名，`bs_asset_test` 由 `test-env.sh` 现行脚本重建）；lottery/referral 工程编译通过；`grep -rn "com\.betterschool\.points\." backend --exclude-dir=target` 零命中；本地可启动、`/api/points/**` 响应不变

## 实现要点（票 03）

- 七模块与 sql/docs 全走 `git mv`（rename 保历史）；目录与内容分两步：先 mv 目录/包目录，再对 `*.java/*.xml/*.yml` 一轮 sed（包名按 §2.2 分层映射、artifactId、`PointsApplication`→`MarketingAssetApplication`、`bs_points`→`bs_asset`）。
- **机械联改（票面未列但 mvn 必需）**：七个子模块 pom 的 `<parent>` artifactId `points` → `marketing-asset`——不改则 parent 解析到本地仓库旧 `points` pom，dependencyManagement 认不出新模块名，报 version missing。聚合 pom 的 `<name>` 与「兄弟目录」头注释同步更新。
- **test-env.sh 联改（票面验收「`bs_asset_test` 由现行脚本重建」所迫，spec §2.6 的 6→4 全量收敛仍留票 05）**：`apply_marketing_init` 加工程 sql 目录参数（4 参），资产域两行走 `marketing-asset/sql/init-{dev,test}.sql`；旧 `bs_points`/`bs_points_test` 建库行随旧 points 服务删除（`init-marketing-{dev,test}.sql` 里的死 SQL 同步清掉，code-review 采纳项）；activity 段两行原样，票 04 随迁。`ASSET_SQL_DIR` 与 `MARKETING_*_DBS` 变量补 `bs_asset`。
- **xxl cron 核对**：票面要求以 admin 现配置核对——实测 bs-mysql 的 `xxl_job` 库只有 `better-school-executor` 的 9 条，**营销执行器/任务行不存在**（手工配置无落点可核），按 §3.1-D 建议值落：`outboxScan` `0/30 * * * * ?`、`pointsExpireScan` `0 * * * * ?`。SQL 已灌入 admin 库并验证（2 handler、appname/title 对）。判重条件比单体先例多限了 `AND job_group = @group_id`（`outboxScan` 与单体执行器重名，裸 handler 判重会误判）。
- `schema-points.sql` 票面写「USE 改」——该文件本就无 USE（库名由 test-env.sh 拼接时插入），只改了注释里的库名/建库文件指引；真带 `CREATE DATABASE + USE bs_asset` 的是 `migrations/022_points.sql`。
- 验收实测：`mvn -pl bs-api -am install -DskipTests`（better-school 与本票改动零交集，全量 341 用例没必要重跑）→ marketing-asset 全量 `mvn install` 绿（11 用例：Deduct 5 / Expire 2 / LotteryWin 4，包名已见新 `com.betterschool.asset.integration`）→ lottery/referral `mvn compile` 绿；`grep "com\.betterschool\.points"` 源码零命中。本地 `java -jar` 启动：`Started MarketingAssetApplication` 16s，`Export dubbo service com.betterschool.asset.api.IPointsExchangeService`（新 FQN、port 50052）；`/api/points/balance` 未登录 401、测试密钥签发 JWT 后 `{"code":"200","data":{"balance":0}}`——JWT 验签 + `bs_asset` 库链路通，响应形状与改前一致。
- docs 死链联改：根 `docs/api/README.md`、`docs/db/README.md` 删/改 points 行指向 `marketing-asset/docs/`（文件搬走后原链接悬空）。
- **已知波及、不在本票范围**（spec §2.6/§3.4/§6-4 划给票 05，均为存量文档旧值）：`backend/docs/README.md`、`docs/testing.md`（`bs_points_test`）、`docs/architecture/marketing-services.md`（points-api/`backend/points/`/bs_points 多处）、`docker-compose.yml` 头注释（init-marketing 三库口径）、`better-school/docs/db/README.md` 营销三库指针。xxl 6 handler 全量触发验收也在票 05。
- 收口提交显式 `git add` 票面文件与三个新建 sql（git mv 只 stage rename，内容改动与未跟踪新文件不会自动进）。
