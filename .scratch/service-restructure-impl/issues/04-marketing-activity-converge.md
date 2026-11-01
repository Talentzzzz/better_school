# 04: marketing-activity 收敛（lottery + referral 合并）

**Spec:** `.scratch/service-restructure/spec.md` §0（四个新核实事实）、§1、§2.1、§2.2（activity 规则）、§2.3、§2.4、§3.1-A/D/E、§3.3、§4（#3/#4/#5/#6/#7/#9/#10）

**What to build:** lottery 与 referral 两套代码收敛为 `backend/marketing-activity/` 一套七模块工程，共享一个 app 启动器；因同名类冲突**全层带域子包**，xxl handler 改名解冲突，前端代理与测试基类联动。完成后三个工程结构定型，`backend/` 根只剩三工程 + 根级公共物。

**Blocked by:** 03（activity 消费方要引 `com.betterschool.asset.api.*` 新 FQN，`PointsExchangePort` 已由票 03 切好）；不可与票 03 并行

**Status:** resolved

- [x] `backend/lottery/lottery-*` + `backend/referral/referral-*` → `backend/marketing-activity/marketing-activity-{types,api,domain,case,infrastructure,trigger,app}`；两个空壳 case 二合一；新聚合 pom artifactId `marketing-activity`，dependencyManagement 合并两份（版本矩阵一致，直接取 lottery 版）
- [x] 包名映射（§2.2 activity 规则，**全五层带域子包**）：`com.betterschool.lottery.{layer}.*` → `activity.{layer}.lottery.*`、referral 同理 → `activity.{layer}.referral.*`（§0-2 同名类冲突物理上必须；map 决策 5 示例相应细化为 `activity.api.lottery.ILotteryService`）
- [x] app 合一：主类 `com.betterschool.activity.MarketingActivityApplication`（一个，scanBasePackages = 包根）；两套同名配置类（`MybatisConfig`/`SecurityConfig`/`TransactionConfig`/`XxlJobConfig`）逐个 diff 取一致者合为一套 `com.betterschool.activity.config.*`（`XxlJobConfig` 取活动执行器参数）
- [x] `application.yml` 合一（§4-4）：HTTP 8082、Dubbo 50053、xxl 9997 + appname `marketing-activity-executor`（title `mktActivity`）、datasource `bs_activity`、producer group `marketing-activity-producer`（并两组）、`lottery.bucket.degrade-cooldown-millis` 原样保留、`referral.activity-config-table` 保留且值改 `lottery_activity`（去库前缀）；`application-test.yml` 合一连 `bs_activity_test`、Dubbo 50053；Nacos `better-school-shared.yml` dataId/group **不动**（四变三）
- [x] xxl（§3.1-D，§0-1 冲突解法）：handler 改名 `lotteryOutboxScan` / `referralOutboxScan`（仅 `@XxlJob` 注解值，语义不变）；`marketing-activity/sql/xxl-job-tasks.sql` 注册 4 handler（`lotteryDeductMerge`、`lotteryDrawCountExpireScan`、`lotteryOutboxScan`、`referralOutboxScan`）+ 活动执行器；executor `log-path` → `./logs/xxl-job-activity`
- [x] MyBatis（§3.1-E）：mapper XML 归 `marketing-activity-app/src/main/resources/mybatis/mapper/{lottery,referral}/*.xml`（`OutboxMapper.xml` 两份同名**必须分子目录**）；`mybatis.mapper-locations` 双路径；XML 内 FQN 全量替换；`ReferralMapper.xml` 提及 `bs_lottery` 的注释同步更新
- [x] sql 落位 `marketing-activity/sql/`：`schema-lottery.sql`、`schema-referral.sql`（保持分立，USE 改 `bs_activity`）、`init-{dev,test}.sql`（只建 `bs_activity`/`bs_activity_test`）、`migrations/023`+`024`（USE 改）、`xxl-job-tasks.sql` 活动段
- [x] 前端联动（§3.3）：`front/vite.config.ts` proxy `/api/referral` 目标 8083 → **8082**（`/api/lottery` 8082 不变、`/api/points` 8081 不变），注释「三条营销服务前缀」改「两条」
- [x] 测试联动：两个活动测试基类共存 `com.betterschool.activity.integration.support`（类名不改）；测试类归 `com.betterschool.activity.integration.*`（类名已带域前缀不冲突）；Dubbo 端口注入/连接串随新值全改
- [x] docs 落位 `marketing-activity/docs/`：`api/{lottery,referral}.md` + `db/` 同名两篇，README 活动段
- [x] 验收：`better-school` install 后本工程全量 `mvn test` 绿；三服务本地可启动；活动页/拉新页冒烟（HTTP URL 与响应不变）；`grep -rn "com\.betterschool\.(lottery|referral)\." backend --exclude-dir=target` 零命中

## 实现要点（票 04）

**落地**：git mv 保历史平移（lottery 七模块改名 + referral 五层源码并入 `activity.{layer}.{lottery,referral}.*` 域子包）；聚合 pom `marketing-activity`（dependencyManagement 取 lottery 版矩阵，含 redisson + marketing-asset-api）；app 合一（`MarketingActivityApplication`、四配置类 diff 后 MybatisConfig/TransactionConfig/XxlJobConfig 取 lottery 版、SecurityConfig 取已是超集的 lottery 版并注明合并口径）；yml 合一（8082/50053/9997/`bs_activity`/`marketing-activity-producer`/`referral.activity-config-table: lottery_activity` 去库前缀）；mapper XML 分 `mybatis/mapper/{lottery,referral}/` + 双路径 mapper-locations；xxl handler 改名 `lotteryOutboxScan`/`referralOutboxScan`；sql/docs 落位；vite `/api/referral` → 8082。

**运行期新发现（spec §0-2 只料到编译期同名冲突，共三处，均已解）**：
1. **Spring bean 名冲突**：默认 bean 名取简单类名，两域同名 `@Component/@RestControllerAdvice/@Repository` 与两域 `IOutboxDao` 会 `ConflictingBeanDefinitionException`——`@SpringBootApplication` 与 `@MapperScan` 都挂 `FqcnAnnotationBeanNameGenerator`（FQCN 做 bean 名，注入按类型不受影响）。
2. **advice 互相接异常**：两域 `GlobalExceptionHandler` 的 `AppException` 是两个类，不加限定会把对方域异常落进兜底 999——各自 `@RestControllerAdvice(basePackages = ...trigger.{lottery,referral})`。
3. **`@MapperScan` 基包非前缀坑**：机械改名后 dao 在 `activity.infrastructure.{lottery,referral}.dao`，`activity.infrastructure.dao` 不是它们的前缀（扫描零命中，测试 73 用例全错但报的是缺 bean，症状绕）；必须逐域列出。同理 yml 的 `logging.level` 降噪键也要逐域列（code-review 揪出原写法是死键）。

**联改超出票面的部分（防票 05 重复计）**：`scripts/test-env.sh` 活动域四行随迁（建库文件 + DDL 指向本工程 sql/，`bs_activity(_test)`）——票 05 的「test-env.sh 改写」只剩收尾口径核对；`docker-compose.yml` 头注释的营销建库段更新（旧 `init-marketing-*.sql` 指引已失效）；`backend/sql/init-marketing-{dev,test}.sql` 已删（拆进两工程）；根 `docs/{api,db}/README.md`、`docs/testing.md`、`better-school/docs/db/README.md` 的营销段指针/库名/表列同步。

**验收记录**：本工程 `mvn -o test` 73/73 绿（`bs_activity_test` 由 test-env.sh up 重建 + 双 schema 同库各拼一次）；三工程 grep（FQN/旧 artifactId/旧库名/8083/50054/9996）零命中（注释里有意保留的历史说明除外）；8082 起 jar 后 `/api/lottery/activity` 200、`/api/referral/invite-code` 401 信封正确；vite 5173 代理双前缀验证通过；登录态 UI 冒烟 `/marketing` 页全渲染（活动 banner/次数/邀请码/拉新数/奖品目录），截图存 `.scratch/service-restructure-impl/verify-04/`（积分段「加载失败」因冒烟时 8081 资产服务未起，预期内）。

**已知波及（不在本票范围）**：`backend/docs/architecture/marketing-services.md` 与 `backend/docs/README.md` 索引仍为三服务旧口径（spec 指定票 05 改写）；xxl 活动执行器 + 4 handler 的 admin 实际触发验证（spec §6-5）待票 05 全项验收；schema-lottery.sql 本无 USE（票面「USE 改」实指 migrations 023/024，已改）。
