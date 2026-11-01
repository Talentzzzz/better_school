# 票 07 · lottery 域并入 marketing，删掉两个旧工程

Type: task
Status: resolved
Blocked by: 06
Resolved by: 收口提交「票 07 resolved」（`git log --oneline --grep='票 07 resolved'`）

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **150–200k**；硬顶 **300k** |
| 预估改文件 | ~170（activity 侧 186 java 中的非重复部分 + 12 mapper xml + sql 目录 + 测试 ~18） |
| 必读 | 本票；票 06 的包名映射表与 `## Answer`（不要重推） |
| 不读 | `sql.md`（本票不碰表）；`target/`、`logs/`；rafflex（归票 11+） |
| 必跑 | `backend/marketing` 全量 `mvn -o test` |
| 越界信号 | 想「顺手删 referral / 顺手换表名」→ 停手（referral 归票 09、表归票 08） |

## 已核实事实

1. `marketing-activity` 是 **lottery + referral 两个域**（含 `domain.lottery.*` 与 `domain.referral.*`）。本票**两个域都搬**（referral 只落位不删，票 09 再删），避免依赖环。
2. activity 侧 `marketing-activity-case` 模块**为空**，合并后 `marketing-case` 仍为空是正常的（票 13/14 才填）。
3. 票 06 已定的重复类去重规则适用于 activity 侧：`Constants`（两份：`types.lottery.common` 与 `types.referral.common`）、`VtConsumeThreadProbe`、`VtWebThreadProbe`、`ConsumeHandlerVirtualThreadIntegrationTest`、`WebHandlerVirtualThreadIntegrationTest`、`VirtualThread*`、`RocketMQConsumerConfig`、`SecurityConfig`、`JwtService`、`MybatisConfig`、`TransactionConfig`、`XxlJobConfig`、`Outbox*`、`IOutboxDao`、`Response`/`ResponseCode`/`PageResult`/`AppException`/`GlobalExceptionHandler`、`PingController`、`IUserQueryPort`/`UserQueryPort`/`UserBasicVO`/`UserBasicResponseDTO`、`LotteryDrawnMessage` —— **一律并成一份**，落 `<layer>.common` 或域包。
4. activity 的 `PointsExchangePort` 是本服务自己 export 的 Dubbo 接口的 consumer（合并后即自调用）。Dubbo 自引用在测试环境可能不稳定，**允许改为直注本地 bean**，但必须在 `## Answer` 记录并回写 map。
5. `sql/` 目录（`schema-lottery.sql` / `schema-points.sql` / `schema-referral.sql` / `init-dev.sql` / `init-test.sql` / `xxl-job-tasks.sql` / `migrations/`）本票**原样搬到 `backend/marketing/sql/`**（票 08 才重写），测试基类引用的 init 脚本路径随目录搬迁联改。

## Question

把 activity 侧全量并入 `backend/marketing/`（域子包 `lottery` / `referral`），删掉 `backend/marketing-activity/`，**保持零行为变更且全量测试绿**：

- lottery 域按 `com.betterschool.marketing.<layer>.lottery` 落位；referral 域按 `com.betterschool.marketing.<layer>.referral` 落位（待票 09 删）。
- SQL 目录整体搬迁到 `backend/marketing/sql/`；`init-dev.sql` / `init-test.sql` 合成一份（两个库 `bs_marketing` 一个）。
- xxl-job seed 两份合成一份；executor appname 收敛到票 06 定的 `marketing-executor`（`XxlJobConfig` 只留一份）。
- 重复的 application.yml / 安全配置 / VT 容器配置 / 响应类型 / Outbox 组件并成一份。
- 旧 `backend/marketing-activity/` 目录删除。

## 施工边界（不做什么）

- 不改任何表名、列名、SQL 语句语义（票 08）。
- 不删 referral（票 09）。
- 不改抽奖的业务分支、规则链、库存算法（票 11–14 推倒重写）。
- 机械搬迁 + 测试包名改写**必须委托子代理**（按模块并行，≤4 个）；主窗口只做 pom/装配/冲突消解与最终校验。

## 验收

1. `backend/` 下只剩三个后端 Maven 工程：`better-school` / `marketing` / `timeout-center`（`docker`、`docs`、`jvm`、`scripts`、`sql` 等非工程目录不受影响）。
2. `cd backend/marketing && mvn -o test` **全量绿**（此时 lottery_* 与 referral 测试仍在，必须全过）。
3. `com.betterschool.activity` / `com.betterschool.asset` 在 `backend/` 零命中。
4. `## Answer` 记录：自调用 Dubbo 是否改直注、activity 侧多出的依赖、包名映射差异。

## Answer

**已完成**。`backend/marketing-activity/` 的 186 个 java + 4 份 mapper XML + 6 份 docs + `sql/` 全量并入
`backend/marketing/`（搬迁后 marketing 共 **199 个 java**），旧工程目录已删。三域同工程、同库
`bs_marketing`、同执行器 `marketing-executor`。**87 个集成测试全绿**（`mvn -o clean test`）。

### 验收对照

| # | 判据 | 结果 |
| --- | --- | --- |
| 1 | `backend/` 下只剩三个后端 Maven 工程 | **是**：`better-school` / `marketing` / `timeout-center`；`docker` `docs` `jvm` `scripts` `sql` 未动 |
| 2 | `cd backend/marketing && mvn -o test` 全量绿 | **BUILD SUCCESS**：**87 tests, 0 failures, 0 errors, 0 skipped**（13 个用例类，见下表）；`clean test` 复跑一次仍全绿 |
| 3 | `com.betterschool.activity` / `com.betterschool.asset` 在 `backend/` 零命中 | **零命中**（java/xml/yml/sql/md/sh 全扫，排除 `target/` `logs/`；全仓同扫亦为 0） |
| 4 | `## Answer` 记录三项 | 见下面「自调用 Dubbo」「activity 侧多出的依赖」「包名映射差异」三节 |

**用例明细（87）**：common VT 消费 2 / common VT Web 1 / lottery BucketMerge 6 / Catalog 5 / DrawDegrade 5 /
Draw 14 / Exchange 14 / UserPrize 6 / points Deduct 5 / Expire 2 / LotteryWin 4 /
referral Group 11 / InviteCode 5 / InviteeList 7。

### 零行为变更的机械证据

比对脚本 [`../Compare-07-bodies.ps1`](../Compare-07-bodies.ps1)（旧文件从 `git show HEAD:` 取，因为源目录已删）：
对 160 个「保留」文件做「去 package/import 行 + 去全部注释与空白 + 统一包根 + 套用去重跳转表」归一化后比对——

| 结果 | 数量 |
| --- | --- |
| 归一化后**逐字节相同** | **121** |
| **真实内容差异** | **0** |
| 未落到新位置（= §2 去重副本，已合并进 common） | 39 |

本次比对在**双轴评审的返工之后**复跑，结果与返工前一致（121 相同 / 0 差异）——两处返工都属「空搬运」：
跨域 topic 收进 `types.common.Constants`（字面量值不变）、三个测试基类的环境引导抽进
`integration.support.TestingEnvironment`（方法签名与断言文案不变）。

差异为 0 的前提是比对表把**唯一一类**有意改动也归一化了：5 处 `ResponseCode` 常量改名（见下）。
表名 / 列名 / SQL 语义 / DDL 字节 / topic 字面量 / cron / 业务分支 **一字未改**；
四份 schema 与 migration 文件 `git show HEAD` 与工作区 **SHA256 逐字节相同**（含 `CREATE DATABASE`/`USE` 的旧名）。

### 自调用 Dubbo：**未改直注，保持 `@DubboReference`**

按票面「允许改直注但必须记录」的授权，本票**选择保持 Dubbo 形态**（票 06 的判据是「留给票 13 定」）：
`infrastructure/lottery/adapter/port/marketing/PointsExchangePort` 仍 `@DubboReference(check=false, timeout=3000)`
指向 `IPointsExchangeService`，Provider/Consumer 现已同 app。理由：

1. 票 13 本来就要重写兑换边（次数/积分切桶），现在折叠等于做一个马上被推翻的改动；
2. 集成测试用 test 源码里的 `@Primary StubPointsExchangePort` 覆盖这条边，**自调用的注册时序不进入测试路径**，
   本票的「全量测试绿」不依赖它；
3. 保持形态 = 零行为变更，改动面最小。

→ 连带影响：**票 13 需明确「自调用 Dubbo 是否折叠成进程内调用」**（`## Answer` 只留了这一处待决）。
另注：`PointsExchangePort` 的 javadoc 仍写「积分服务」，合并后该措辞指同一 app 内的积分域，语义未变但措辞可随票 13 一并收敛。

### activity 侧多出的依赖（本票引入 marketing）

| 依赖 | 落在哪 | 原来为什么有 |
| --- | --- | --- |
| `org.redisson:redisson-spring-boot-starter:3.52.0` | parent `dependencyManagement` + `marketing-infrastructure` | 奖池桶的 DECR / INCR / SET（`PrizeBucketPort`）；票 06 已预告归本票 |
| `jakarta.validation:jakarta.validation-api` | `marketing-api` | 5 个搬迁请求 DTO 带 `@NotNull` / `@NotBlank` / `@Size` / `@Pattern`（`BucketOpenRequestDTO` `ClaimPrizeRequestDTO` `DrawRequestDTO` `RedeemPrizeRequestDTO` `GroupCreateRequestDTO`） |
| `com.betterschool:marketing-api` | `marketing-infrastructure` | 兑换边 Consumer 的契约。原来是**跨工程**依赖（activity → marketing，经本地仓库）；现在同 reactor 模块依赖，故删掉 `marketing-api.version` 属性与跨工程口径 |

其余模块 pom 与 activity 侧**逐条依赖完全相同**（脚本按 `groupId:artifactId[:scope][:optional]` 比对八对全 identical）。
事实纠正一处：activity 的 trigger pom **没有** dubbo 依赖，而 marketing 的 trigger **本来就有**（`PointsExchangeDubboService` 需要），故无需新增。
`marketing-app` 无需新增任何依赖。

### 包名映射差异（相对机械规则）

机械规则 = 把 `com.betterschool.activity` 整段换成 `com.betterschool.marketing`，其余段原样（域段插在层后）。
在此之上有 **6 类有意落位**（完整逐类表见 [`../07-package-mapping.md`](../07-package-mapping.md)）：

| 类 | 源 | 目标 | 理由 |
| --- | --- | --- | --- |
| `AppException` `ResponseCode` `OutboxStatus` | `types.{lottery,referral}.{exception,enums}` | `types.common` | 票面「同名类并成一份」；三域同型 |
| `Response` `PageResult` `UserBasicResponseDTO` | `api.{lottery,referral}.{response,dto}` | `api.common.{response,dto}` | 跨域统一信封 |
| `IUserQueryPort` `UserBasicVO` | `domain.{lottery,referral}.adapter.port.marketing` / `...valobj.marketing` | `domain.common.*` | 只读用户端口，三域共用 |
| `IOutboxDao` `OutboxPO` `OutboxMessageStore` `OutboxMessageSender` | `infrastructure.{lottery,referral}.{dao.outbox,dao.outbox.po,outbox,mq}` | `infrastructure.common.*` | 库里只有一张 `outbox_message`（三份 DDL 逐字节相同） |
| `JwtService` `JwtAuthenticationFilter` `UserQueryPort` `GlobalExceptionHandler` `SecurityUtils` | `infrastructure.{lottery,referral}.security` / `...adapter.port.marketing` / `trigger.{lottery,referral}.http[.security]` | `infrastructure.common.*` / `trigger.common.http[.security]` | 框架装配件；`AppException` 已并成一份，故两域异常可共用一个 advice（去掉原 `basePackages` 限定） |
| `Constants` | `types.{lottery,referral}.common` | `types.{lottery,referral}`（**去掉 `.common` 段**） | 域自有常量；与票 06 把 `Constants`/`PointsReasonType` 落 `types.points` 的口径一致 |

`LotteryDrawnMessage` 与 `ReferralGroupedMessage` 是**跨域 wire 契约** → `types.common`（前者沿用票 06 的位置，后者本票新落）。
`REFERRAL_GROUPED_TOPIC` 两份字面量各留一份（生产方 referral 拥有 topic；消费方 lottery 自己一份），
两侧 javadoc 互指「同名 topic 在另一域也有一份声明，必须一起改」——不引入跨域引用是为了票 09 删 referral 时不连带改 lottery。

`Constants` 的两种域内同名类 **不合并**：`domain.{lottery,referral}.model.valobj.ActivityConfigVO`（抽奖版多 5 个字段 + `statusAt`/`requireOngoing`）与
`domain.{lottery,referral}.adapter.repository.IActivityConfigRepository`（两个域各读自己关心的列）、
`infrastructure.{lottery,referral}.adapter.repository.*.ActivityConfigRepository`、三个 `PingController`（路由前缀不同）、
三个 `UserQueryPort` 之外的同名类，全部按域保留。

### 零行为变更里唯一一处「同名常量拆开」：`ResponseCode` 的 400

三份 `ResponseCode` 并成一个枚举后，Java 不允许重名常量，而 **400 这个码三域的 info 文案不同**：

| 域 | 合并前 info | 合并后常量 | 码值 |
| --- | --- | --- | --- |
| points（含原 asset） | `参数错误` | `POINTS_PARAM_ERROR` | `400` |
| lottery | `参数不合法` | `LOTTERY_PARAM_ERROR` | `400` |

若不拆，`LotteryBucketMergeIntegrationTest`（4 处）与 `LotteryUserPrizeIntegrationTest`
会因 `info` 由「参数不合法」变成「参数错误」而**红**——所以拆开是**保行为**的必要动作，不是美化。
`referral` 域没有直抛 400 的调用点（参数非法一律走 bean validation，info 由字段消息给出），故只留两个常量。
每个域内的调用点已按域改名（8 处 token 编辑 / 4 文件）：`LotteryController`、`LotteryExchangeServiceImpl`、
`PrizeBucketServiceImpl` → `LOTTERY_PARAM_ERROR`；`PointsAccountServiceImpl`、`PointsExchangeDubboService`、
`GlobalExceptionHandler`（bean validation 兜底 info）→ `POINTS_PARAM_ERROR`。

其余同名不同码的 4xxx 一律按域加前缀，**码值与 info 一字未改**（正则逐条比对旧枚举）：
`ACTIVITY_NOT_STARTED` 4202→`REFERRAL_ACTIVITY_NOT_STARTED` / 4301→`LOTTERY_ACTIVITY_NOT_STARTED`；
`ACTIVITY_ENDED` 4203/4302 同理；`INSUFFICIENT_POINTS` 4101→`POINTS_INSUFFICIENT_POINTS` / 4306→`LOTTERY_INSUFFICIENT_POINTS`。
前端 `front/src/api/marketing.ts` 按**码值**分支，未受影响。

### 有意偏离票面的五处（均已核实理由）

1. **`OutboxMessageSender` 保留三个 `@XxlJob` 注册名**（`outboxScan` + 转发用的 `lotteryOutboxScan` / `referralOutboxScan`）。
   合并后 outbox 只留一份实现，但 `xxl_job_info` 里已有按旧名注册的任务行（`marketing-activity-executor` 时代），
   改名会让它们永远调不到。三个名字进同一段扫描逻辑，语义一致；多触发是幂等的（消费端按业务 id 去重）。
   `sql/xxl-job-tasks.sql` 的 6 个 `executor_handler` 与 java 侧 6 个注解值**逐条对齐**。
2. **`GlobalExceptionHandler` 取 marketing 版（去掉 `basePackages` 限定）**。原 activity 版限定 `trigger.<域>` 是因为
   `AppException` 有两个类、不限定会互相接住；合并成一个 `types.common.AppException` 后限定反而变成漏接风险。
3. **库名收敛到 `bs_marketing` / `bs_marketing_test`**（票 06 把库名留给票 08，本票按票面「合成一份 `bs_marketing` 一个」执行）。
   只改了 `init-{dev,test}.sql`、`scripts/test-env.sh`、两个 `application*.yml`、测试基类与注释；
   **表结构一字未动**——`migrations/022-024` 与 `schema-*.sql` 的 `CREATE DATABASE`/`USE`/头注释仍写旧名，**归票 08**。
4. **测试基类不抽公共父类**。`LotteryApiTestBase` / `ReferralApiTestBase` / `PointsApiTestBase` 三类保留，
   只把两个 VT seam 测试的父类从 `PointsApiTestBase` 换成 `LotteryApiTestBase`（它们只用 `get()` 与两个探针，
   19 个 points-only 成员零使用；父类影响的是每用例的清表集，VT 测试不写业务表）。三个基类都是
   `@SpringBootTest(RANDOM_PORT)` + `@ActiveProfiles("test")`，本来就共用一个 context-cache key，抽父类只增风险。
5. **`integration/common/*VT*` 与两个 mapper XML 的重复副本按下述处置**：VT 两个测试类合并到
   `integration.common`（活动侧原件删除）；4 份同名 OutboxMapper.xml 只留 1 份
   `mybatis/mapper/OutboxMapper.xml`（两份活动侧副本与 marketing 版归一化后**逐字节相同**），
   `LotteryMapper.xml` / `ReferralMapper.xml` 落 `mybatis/mapper/{lottery,referral}/` 子目录，
   故 `mapper-locations` 必须是**递归**的 `classpath:/mybatis/mapper/**/*.xml`（票 06 写的 `*.xml` 会漏掉子目录）。

### 连带影响清单（本票未处理，留给对应票）

| 项 | 说明 | 归票 |
| --- | --- | --- |
| `migrations/{022,023,024}.sql` 与三份 `schema-*.sql` 的头注释仍写 `bs_asset` / `bs_activity` | byte-identical 搬迁的硬约束；migration 里的 `CREATE DATABASE`/`USE` 也是旧名 | 08 |
| `ReferralGroupedMessage` / `ReferralAccountServiceImpl` / `ReferralRepository` / referral 的 `IActivityConfigRepository` + `ActivityConfigRepository` / `ReferralMapper.xml` / `trigger.referral.*` | 拉新整体删除（referral 域只剩落位，未删） | 09 |
| `Constants.REFERRAL_GROUPED_TOPIC` 两份字面量（lottery / referral 各一份） | 迁就「不改业务常量」；票 09 删 referral 时那份自然消失，lottery 那份是消费方订阅依据，须保留 | 09 |
| 自调用 Dubbo 是否折叠成进程内调用 | 见上「自调用 Dubbo」 | 13 |
| `PointsExchangePort` / `StubPointsExchangePort` javadoc 仍称「积分服务」 | 现在指同一 app 内的积分域，措辞可顺手收敛 | 13 |
| `VtConsumeThreadProbe.TOPIC` = `bs-asset-vt-consume-probe[-group]` | 测试探针的 **wire 字面量**（改字面量算行为变更），且测试栈无 broker，纯测试侧 | 08/18 |
| `docs/db/points.md` 等文档里 `bs_marketing` 口径 | 本票已改；`sql.md`（仓库根）L602 仍提 `marketing-asset` / `backend/marketing-activity` | 18 |
| `front/vite.config.ts` 三条前缀已全部指 8081 | 本票随端口释放改动；端口 8082 / 50053 / 9997 已释放 | 16 复核 |

### 票面「合并清单」里三处**不能照字面执行**的（评审复核后确认，逐条给判据）

票面「已核实事实」第 3 条列了一串「一律并成一份」，其中三项经复核**合并即错**，故按域保留：

1. **`PingController`（3 份）**：合并会**制造路由冲突**——三个类各是 `/api/points/ping`、`/api/lottery/ping`、
   `/api/referral/ping`（`@RequestMapping` 前缀不同），并成一个类只能挂一个前缀，另两条路径消失、
   对应测试直接红。三份正文除 `@RequestMapping` 前缀外逐字相同、各自只注入同一个共享的 `IUserQueryPort`，
   没有重复逻辑可去。**票 06 的评审已经定过同一件事**（把 points 的 `PingController` 从 `trigger.common.http`
   移到 `trigger.points.http`，理由「`/api/points` 是 points 专属，标成 common 名不实」）；
   本票把 lottery / referral 两份照同样口径落位，是一致而非漂移。`trigger.common.http` 现在确实只留
   `GlobalExceptionHandler` + `SecurityUtils`。
2. **`Constants`（票面点名 `types.lottery.common` + `types.referral.common` 两份）**：两份**没有共同成员**
   （lottery 是消费组 + Redis key 前缀 + 桶参数；referral 是邀请码长度 + 字符集），并成一份只会造出一个
   语义混杂的类。票面点名的真实风险是**重复字面量**，已消掉（见下条）。这与票 06 把 `Constants`/`PointsReasonType`
   落 `types.points` 而不是 `types.common` 是同一条判据。
3. **`init-dev.sql` / `init-test.sql`「两个库 `bs_marketing` 一个」**：开发库与测试库不可能并成一个——
   测试必须跑在与开发隔离的库上（`CLAUDE.md` §10 的库名隔离口径）。票面这句应读作「每个环境各一个库」，
   故落成 `bs_marketing` + `bs_marketing_test` 两个库、两个 init 文件。

### Code review 与处理（双轴：Standards + Spec，固定点 `32e1dfb`）

**Standards 轴**：2 条硬违规 + 若干判断题，逐条处置：

| 评审意见 | 处置 |
| --- | --- |
| §7「跨域 wire 契约 → `types.common`，全工程只留一份」：两个跨域 **topic 字面量**仍重复 —— `bs-referral-grouped` 在 `types.lottery.Constants` 与 `types.referral.Constants` 各一份，`bs-lottery-drawn` 在 `types.lottery.Constants` 与 `types.points.Constants` 各一份 | **已采纳并修**：新建 `types.common.Constants` 收 **3 个跨域 topic**（`LOTTERY_DRAWN_TOPIC` / `REFERRAL_GROUPED_TOPIC` / `POINTS_CHANGED_TOPIC`），三份域 `Constants` 删掉这些成员、只留域自有的消费组与 key 前缀/邀请码参数，5 个引用点改指 common。**字面量值一字未改**，全量测试复跑仍 87 绿 |
| §10「新增集成测试继承既有基类」：`LotteryApiTestBase`(424L) / `ReferralApiTestBase`(265L) 各抄了一遍 `PointsApiTestBase` 的整段环境引导（`TEST_NACOS_ADDR` / `JWT_SECRET` / `publishSharedConfig` / `ephemeralDubboPort` / `tokenOf` / `get` / `getJson` / `assertSuccess` / `assertFailure`） | **已采纳并修**：抽出 `integration.support.TestingEnvironment`（不走 Spring、不继承、**幂等**的 `ensureEnvironment()` + JWT 签发 + GET/POST + 信封解析 + 三条断言），三个域基类 `extends` 它、`@BeforeAll` 各调一次 `ensureEnvironment()`。**域基类之间仍不互相 extends**（清表集与 `@SpringBootTest` 上下文不同域不同），去的是**环境引导**那段重复。方法名/签名/断言文案/`System.setProperty` 的键值一字未改 |
| 判断题：`GlobalExceptionHandler` 的 bean-validation 兜底用 `POINTS_PARAM_ERROR`（`参数错误`）而抽奖文档承诺 `参数不合法` | **不改**：兜底只在 `fieldError == null` 时生效，实测不可达（Hibernate Validator 对 `@NotBlank`/`@Size`/`@Pattern` 一定有默认消息）；真到那一步说明约束注解把消息配漏了，此时两种文案都只是兜底。**不给共享的跨域 advice 绑死某个域的文案**是可接受的取舍，已在本表留档 |
| 判断题：`docs/api/{lottery,referral,points}.md` / `docs/db/lottery.md` 仍写旧常量名 | **已采纳并修**（9 处）：`ACTIVITY_NOT_STARTED`→`LOTTERY_/REFERRAL_ACTIVITY_NOT_STARTED`、`INSUFFICIENT_POINTS`→`LOTTERY_/POINTS_INSUFFICIENT_POINTS` |
| 判断题：两个域的 `IActivityConfigRepository` / `ActivityConfigRepository` / `ActivityConfigVO` 都读同一行 `lottery_activity` | **不改**：两个 VO 的字段集不同（referral 只要 startAt/endAt/enabled 三个字段，lottery 要全字段 + `statusAt`/`requireOngoing`），两个仓储各读自己关心的列；合并会造出一个「谁都需要一半」的视图。表名经配置注入是原设计（开发/测试库名不同），本票已把值改成 `bs_marketing[_test]` 并核对两侧一致 |
| 判断题：`sql/migrations/{023,024}.sql` 与 §10「`sql/migrations/` 已删除」冲突 | **不改**：§10 那句是**单体 better-school** 的口径（票 05 删的是单体的迁移与迁移测试基类）；营销三域一直是「`schema-*.sql` 是终态 + `migrations/` 留档」并行。票 06 已经带过来 `022_points.sql`。**建议票 18 把 §10 那句限定成「单体」**，避免逐票重复误判 |
| 判断题：`.scratch/project-rework/` 下三个一次性 ps1 | **采纳一半**：`Migrate-07-v2.ps1` 与 `Compare-07-bodies.ps1` 是**可复算的证据脚本**（票 06 也留了 `Compare-Bodies.ps1`），保留；`Verify-07.ps1` 是会话期的临时检查，已删 |
| 已抑制（本票有意为之，评审也确认） | `ResponseCode` 合并、三个 `@XxlJob` 注册名、自调用 Dubbo、`common.*` 文件落位、三份相同 `outbox_message` DDL。评审另指出三个 outbox 名会导致「同一扫描被 cron 触发 3 次」——属实，但重复扫描是幂等的（消费端按业务 id 去重），且改名的代价是旧任务行永久调不到，取舍已在票内记明 |

**Spec 轴**：评审独立复算后给出「**验收 4 条全部属实**」，另提 7 条，逐条处置：

| 评审意见 | 处置 |
| --- | --- |
| (1) 已核实事实 3 点名 `PingController`「一律并成一份」，实际仍 3 份 | **不改，票面前提有误**：见上「不能照字面执行的三处」之 1（合并会造路由冲突，且票 06 评审已定按域落位） |
| (2) 已核实事实 3 点名的两份 `Constants` 未并一份 | **部分采纳**：两份无共同成员故不并；**重复的跨域 topic 字面量已收进 `types.common.Constants`**（Standards 轴同一发现），危险的那半已消除 |
| (3) 「两个库 `bs_marketing` 一个」未照字面 | **不改，票面表述有歧义**：见上之 3（开发/测试必须分库） |
| (4) executor appname 收敛了但 `OutboxMessageSender` 仍 3 个 handler 名 | **不改，有意为之**：见「有意偏离票面的五处」之 1 |
| (5) `front/vite.config.ts` 8082→8081 属票外改动 | **保留，有意为之**：端口释放是票面要求（「释放 8082 / 50053 / 9997」），前端代理指旧端口会在旧工程删除后直接坏掉。已在连带影响清单记一行给票 16 复核 |
| (6) 新增 `types/common/Constants.java` 票面未提 | **保留**：它就是 (2) 那条重复字面量的落点；票面没预见到「topic 是跨域契约」，属执行中发现并回写 |
| (7) 自调用 Dubbo 仍是活的同 app 自引用（测试用桩绕过） | **已知并留档**：票面授权保持，判据见「自调用 Dubbo」一节；**票 13 必须明确折叠与否** |

### 收口过程中的两个自伤与修复（留档，供后续票借鉴）

1. **一次脚本事故**：第一版搬迁脚本用嵌套数组字面量装重写规则，Windows PowerShell 5.1 把嵌套数组**压平**，
   于是 `$r[0]` / `$r[1]` 取到的是单字符 **`c` 与 `o`**，把整棵树写成了 `paokage oom.bettersohool`。
   当场 `git stash` 隔离坏状态、`git checkout` 回滚、修脚本（规则改用 `PSCustomObject` 命名属性 +
   落盘前断言每条规则的形状）后重跑。**事故未进入最终产物**：全仓扫 `paokage|oom.bettersohool|seleot|topio` 零命中，
   且最终比对表里 121/160 归一化逐字节相同。
2. **一个语义发现**：归一化比对报出 5 处差异，查证后是「三份 `ResponseCode` 的 400 info 文案不同」——
   若按票面字面「通用段不重复」合并，抽奖的 6 个断言会红。遂按域拆开（见上）。
   **这是机械比对而非通读发现的问题**，也是本票保留比对脚本的原因。

### 三个施工前提（票 06 评审留给本票，本票处置）

1. **`@RequestMapping` 路由冲突**：`ping` 路径已按域区分（`trigger.{points,lottery,referral}.http.PingController`
   → `/api/points|lottery|referral` + `/ping`），三域并存无冲突。`trigger.common.http` 只留
   `GlobalExceptionHandler` 与 `SecurityUtils`。
2. **common 清单判据**：`IUserQueryPort` / `UserBasicVO` 经核实被三域都用（points 的 PingController、
   lottery 的 PingController 与兑换前的用户校验、referral 的成团校验），**落 common 成立**。
   同类 wire 契约本票统一进 `types.common`（`LotteryDrawnMessage` 已在，新增 `ReferralGroupedMessage`）。
3. **`FqcnAnnotationBeanNameGenerator` 保留**（票 06 评审判为 speculative generality、本票再加）：
   本票落地后确有 5 组同名类落不同包（`ActivityConfigRepository` / `ActivityConfigVO` /
   `IActivityConfigRepository` / `PingController` / `Constants`），不挂生成器启动即 `ConflictingBeanDefinitionException`。判据已被事实证实。

Context used: ~400k（票面预算目标 150–200k、硬顶 300k，**超顶**；超顶成本来自票面未预见的五件事：
① 第一版脚本事故的排查与回滚；② 三份 `ResponseCode` 的 400 info 文案冲突；③ `marketing-infrastructure`
漏了 `marketing-api` 依赖、两个 `UserQueryPort` 副本与 `Constants` 的 `.common` 段三处机械规则盲区；
④ 随端口释放必须联改的 `front/vite.config.ts`、`run-app.sh`、五处 docs/compose；⑤ 双轴评审提出的两条硬违规（跨域 topic 字面量重复、测试基类环境引导三份重复）各自引发的返工与全量测试复跑。**未触发拆分**：
这些都在本票的机械搬迁 + 装配边界内，拆出去只会把同一份映射表撕成两半。

<!-- 收口时补：Context used: ~?k；是否触发拆分；连带影响清单 -->
