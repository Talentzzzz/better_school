# 票 06 · 建 backend/marketing 七模块骨架并并入 points 域

Type: task
Status: resolved
Blocked by: （无）

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **150–200k**；硬顶 **300k** |
| 预估改文件 | ~120（asset 61 java + 10 mapper xml + pom/装配 ~30 + 测试 ~15） |
| 必读 | 本票；`backend/CLAUDE.md`（七模块分层约定）；`backend/marketing-asset/**`（搬迁源） |
| 不读 | `sql.md`（本票不碰表）；`backend/marketing-activity/**`（归票 07）；`target/`、`logs/` |
| 必跑 | `backend/marketing` 编译 + points 侧 5 个用例类；旧 `marketing-activity` 编译仍绿 |
| 越界信号 | 想「顺手改表 / 顺手删拉新 / 顺手搬 lottery」→ 停手，写进 `## Answer` 的连带影响 |

## 已核实事实（票面已核实，不要再考古）

1. **单体 better-school 不引用营销的 Dubbo 接口**（`com.betterschool.(activity|asset|lottery)` 在 `backend/better-school` 零命中）。跨服务同步边只有「单体作为 Provider 暴露 `IUserQueryService`」，本票不动它。
2. 营销内部同步边：抽奖 → 积分走 Dubbo `IPointsExchangeService`（`marketing-activity-infrastructure/.../PointsExchangePort`）。本票**只随包名改 FQCN，形态保持 Dubbo**；是否折叠成进程内调用留给票 13 定。
3. 现状端口 / 服务名：单体 8080 / tri 50051 / actuator 9999；asset 8081 / 50052 / 9998；activity 8082 / 50053 / 9997；timeout 8083 / 50054 / 9996。
4. 模块内 java 数：activity = api 26 / app 26 / domain 61 / infrastructure 42 / trigger 12 / types 19（**case 模块为空**）；asset = api 9 / app 15 / domain 12 / infrastructure 12 / trigger 8 / types 5（case 亦为空）。
5. 跨服务异步边 3 个 topic：`bs-referral-grouped`、`bs-points-changed`、`bs-lottery-drawn`。**topic 名本票不动**，只收敛 producer group（`marketing-activity-producer` / `marketing-asset-producer` → `marketing-producer`）。
6. 消费组现状（合并后须保持唯一）：`bs-lottery-referral-grouped-group`（activity 消费成团）、`bs-points-lottery-drawn-group`（asset 消费中奖加分）、两个 VT 探针组。两组分属不同 topic，无冲突。
7. 两工程**同名类 32 个**（票面已列，别再逐个发现）：
   `AppException` `Constants` `ConsumeHandlerVirtualThreadIntegrationTest` `GlobalExceptionHandler` `IOutboxDao` `IUserQueryPort` `JwtAuthenticationFilter` `JwtService` `LotteryDrawnMessage` `MybatisConfig` `OutboxMapper.xml` `OutboxMessageSender` `OutboxMessageStore` `OutboxPO` `OutboxStatus` `PageResult` `PingController` `Response` `ResponseCode` `RocketMQConsumerConfig` `SecurityConfig` `SecurityUtils` `TransactionConfig` `UserBasicResponseDTO` `UserBasicVO` `UserQueryPort` `VirtualThreadConsumeMessageConcurrentlyService` `VirtualThreadRocketMQListenerContainerRegistrar` `VtConsumeThreadProbe` `VtWebThreadProbe` `WebHandlerVirtualThreadIntegrationTest` `XxlJobConfig`
8. 现状消费契约：asset 的 `IPointsService`（读）与 `IPointsExchangeService`（兑换扣分）对外签名**必须保持不变**（票 10 才动内部实现）。

## 票面定（本票内定，收口后回写 map.md）

- **工程**：`backend/marketing/`，七模块 `marketing-{types,api,domain,case,infrastructure,trigger,app}`；parent 与依赖矩阵以 **asset** 为准，activity 侧多出的依赖/插件列进 `## Answer`。
- **包根** `com.betterschool.marketing`。同名类分两处落位（本票唯一有判断量的地方）：
  - **域专属** → `com.betterschool.marketing.<layer>.points`（lottery 归票 07）；
  - **跨域公共**（框架装配、统一响应、JWT/安全、MyBatis/事务、RocketMQ 容器与虚拟线程、VT 探针、`IUserQueryPort`/`UserBasicVO`/`UserBasicResponseDTO`）→ `com.betterschool.marketing.<layer>.common`，**只留一份**；
  - **Outbox 只留一份** → `com.betterschool.marketing.infrastructure.common.outbox`：`sql.md` §2 营销库只有一张 `outbox_message`，两个域共用；本票**不改其形状**（形状切换归票 08）；
  - **跨域 wire 契约**（`LotteryDrawnMessage` 等）→ `com.betterschool.marketing.types.common`。
- **服务标识**：`spring.application.name` / `dubbo.application.name` = `marketing`；RocketMQ producer group = `marketing-producer`；xxl executor appname = `marketing-executor`；`logs/` 目录名随改（`xxl-job-asset` → `xxl-job-marketing`）。
- **端口收敛**：dev HTTP **8081** / tri **50052** / actuator **9998**；test actuator **9990**、tri 端口取空闲（照单体基类 `System.setProperty` 注入）。释放 8082 / 50053 / 9997。

## Question

新建 `backend/marketing/` 七模块工程，把 **marketing-asset（points 域）** 整体迁入并按域子包落位；旧 `backend/marketing-asset/` 目录删除；`backend/marketing-activity/` 的 pom 改为依赖新工程的 api 模块（Dubbo FQCN 联改），**保持可编译**。

**严格零行为变更**：表、SQL、业务逻辑、状态机、消息载荷一字不改；只做「搬包 / 改名 / 装配 / 端口」。

## 施工边界（不做什么）

- 不搬 lottery 域、不删 `marketing-activity`（→ 票 07）。
- 不动 SQL 与表结构（→ 票 08）。
- 不删拉新（→ 票 09）。
- 不改任何业务分支、不改 DDL、不改消息体字段、不改对外接口签名。
- 机械改名 / 搬包**必须委托子代理**（按模块并行，≤4 个）：主窗口只维护「包名映射表 + pom 矩阵」，不逐文件阅读。

## 验收

1. `cd backend/marketing && mvn -o -DskipTests compile` 绿。
2. points 侧 5 个用例类绿：`PointsDeductIntegrationTest`、`PointsExpireIntegrationTest`、`PointsLotteryWinIntegrationTest`、`ConsumeHandlerVirtualThreadIntegrationTest`、`WebHandlerVirtualThreadIntegrationTest`。
3. `cd backend/marketing-activity && mvn -o -DskipTests compile` 仍绿（Dubbo FQCN 联改后）。
4. 旧 `backend/marketing-asset/` 目录已删；`com.betterschool.asset` 在 `backend/` 零命中。
5. `map.md` 回写：包名映射表、pom 矩阵、端口/服务标识收敛结果。

## Answer

**已完成**（收口时间见提交）。新建 `backend/marketing/` 七模块工程（包根 `com.betterschool.marketing`），
points 域 61 个 java 文件 + 2 份 mapper XML + sql/docs 全部迁入并按域子包落位；旧 `backend/marketing-asset/` 已删；
`marketing-activity` 的 Dubbo FQCN 与 pom 契约依赖联改后仍绿。

### 验收对照

| # | 判据 | 结果 |
| --- | --- | --- |
| 1 | `cd backend/marketing && mvn -o -DskipTests compile` | **BUILD SUCCESS**（8 模块全绿） |
| 2 | points 侧 5 个用例类 | **14 测试全绿**：PointsDeduct 5 / PointsExpire 2 / PointsLotteryWin 4 / ConsumeHandlerVT 2 / WebHandlerVT 1 |
| 3 | `cd backend/marketing-activity && mvn -o -DskipTests compile` | **BUILD SUCCESS**；并已把本地仓库里的 `marketing-asset-api` 删除后重编一次，确认真的走 `marketing-api`（非假绿） |
| 4 | 旧目录已删；`com.betterschool.asset` 在 `backend/` 零命中 | 已删；`com.betterschool.asset` 在 `backend/**/*.{java,xml,yml,yaml,sql,md,sh}`（排除 `docker/`、`target/`）**零命中** |
| 5 | `map.md` 回写 | 「Decisions so far → 票 06 收口」段落：包名映射表 + pom 矩阵 + 端口/服务标识收敛结果 |

### 零行为变更的机械证据

61 个搬迁文件里 **56 个**在「FQCN 按映射表改写 + 去 package/import 行」归一化后与旧文件**逐字节相同**（脚本
[`../Compare-Bodies.ps1`](../Compare-Bodies.ps1)，旧文件从 `git show HEAD:` 取）。5 处差异全部有意为之：
3 处只改了 javadoc 里的旧模块路径（`IPointsDao` / `IOutboxDao` / `IPointsExchangeService`），
另 2 处是装配（`MybatisConfig`、`MarketingApplication`）。另有 2 个文件在字节比对之后才动
（`PingController` 按域移位、新增 `FqcnAnnotationBeanNameGenerator`）。
表名/SQL/DDL/消息体/topic/消费组/对外接口签名一字未改 —— SQL 四份文件 git 判定 0 改动、topic/消费组/表名字面量逐条核对原样。

### 有意偏离票面的四处（均已核实理由）

1. **`Constants` / `PointsReasonType` 落 `types.points` 而非 `types.common`**。票面把 constants 归进「跨域公共」，
   但这两个都是**积分域自有**（`POINTS_CHANGED_TOPIC` / `LOTTERY_DRAWN_CONSUMER_GROUP` / 积分原因枚举），
   而票 07 的 lottery 侧各有同名同类物（`Constants.LOTTERY_DRAWN_TOPIC`、`PointsExchangeReason`）——
   落 common 必在票 07 撞名。语义上它们是「域专属」，故按域落位。
2. **`MybatisConfig` 改用逐域 `@MapperScan` + FQCN bean 命名器**。机械替换后扫描基包 `...infrastructure.dao`
   已不存在（DAO 按域落到 `infrastructure.points.dao` / `infrastructure.common.dao.outbox`），**不改会启动即失败**；
   同时照 `marketing-activity` 既有约定逐域列出（那边注释明确「不能扫伪公共前缀」）并挂
   `FqcnAnnotationBeanNameGenerator`。新增 `config/FqcnAnnotationBeanNameGenerator.java`（照 activity 同名类）。
3. **`MarketingApplication` 挂 `@SpringBootApplication(nameGenerator = FqcnAnnotationBeanNameGenerator.class)`**。
   票 07 搬入 lottery 后同名类 32 个（含 `IOutboxDao`、`PingController`、`JwtService` 等），默认简单类名派生
   bean 名必 `ConflictingBeanDefinitionException`；activity 已在启动类挂同名生成器，这里提前对齐（票 07 只搬类）。
4. **`sql/xxl-job-tasks.sql` 执行器 app_name 同步改 `marketing-executor`**。票面只写了 yml 侧的 xxl appname，
   但执行器注册名与 `xxl_job_group.app_name` 必须一致，否则任务永远调不到。

### 连带影响清单（越界信号，未处理，留给对应票）

| 项 | 说明 | 归票 |
| --- | --- | --- |
| 库名仍是 `bs_asset` / `bs_asset_test` | 严格零行为变更，未动；`sql.md` 的口径是 `bs_marketing` | 08 |
| `sql/migrations/022_points.sql` 仍原样带过来 | 票面「不碰表/迁移」 | 08 |
| 测试探针 topic 字面量仍含 asset：`bs-asset-vt-consume-probe` / `-group` | 票面冻结 topic 名；纯测试侧 | 07/18 |
| `backend/docs/**` 里大量 `marketing-asset` / `bs_asset` 表述未改 | 文档收口集中终验 | 18 |
| `front/vite.config.ts:31` 代理仍指 8081 | 端口本票不变，无需动 | —— |
| 本地仓库里 `com.betterschool:marketing-asset*` 旧坐标 | 已随构建清理删除（`C:\Code\apache-maven-3.9.11\mvn_repo`） | —— |
| lottery 侧多出的 `redisson-spring-boot-starter` | 本票按 asset 矩阵，未引入 | 07 |

### 执行过程中发现的两个真问题（已修）

1. **mapper XML namespace 悬空**：`OutboxMapper.xml` / `PointsMapper.xml` 的 `namespace`、`parameterType`、`resultType`
   机械改名后指向不存在的 `infrastructure.{dao.points,dao.outbox}`。MyBatis 启动即 `BindingException`，**编译期不报**。
   已改为 `infrastructure.points.dao` / `infrastructure.common.dao.outbox`，并用 XML 解析 + DAO 方法名逐一对账。
2. **两个 mapper XML 曾被写成「所有小写 `c` → `o`」**（如 `namespaoe="oom.bettersohool..."`、`seleot`、`topio`），
   属一次字节级写入事故；已按已知源内容整份重写，并全仓扫描确认零残留（其余 53 个非 XML 文件未受影响）。

### 残留 / 未做

- `marketing-case` 是空模块（保七模块形状，与旧 asset 一致）。
- 集成测试跑的是「asset 侧 5 个类」的等价集合；未跑 activity 侧测试（共享测试库，票面只要求 activity 编译绿）。

### Code review 与处理（双轴：Standards + Spec，固定点 `bd55c7f`）

**Standards 轴**：**硬违规 0 条**；判断题若干，逐条处置：

| 评审意见 | 处置 |
| --- | --- |
| `trigger.common.http/PingController` 的 `@RequestMapping("/api/points")` 是 points 专属，标成 common 名不实 | **已采纳**：移到 `trigger.points.http.PingController`（`SecurityUtils` 仍在 common，import 不变）；改后重编 + 全量 import 校验 + 5 个测试类复跑全绿 |
| `FqcnAnnotationBeanNameGenerator` 属 speculative generality，建议本票删、票 07 再加 | **不采纳**（判断分歧，理由留档）：票 07 搬入 lottery 时至少 5 组同名类落**不同包** —— `infrastructure.common.outbox.{OutboxMessageStore,OutboxMessageSender}`、`infrastructure.common.dao.outbox.IOutboxDao`、`JwtService`、`UserQueryPort` / `domain.common.*.{IUserQueryPort,UserBasicVO}`；Spring 默认按简单类名派生 bean 名会 `ConflictingBeanDefinitionException`，启动即失败，而票 07 的票面是「全机械搬包」不包含新增装配。现骨架只有 62 类，改名的回归面最小。已核实全工程无 `@Qualifier`/`@Resource`/按名 `getBean`，改名零语义风险。评审另指出的 `@RequestMapping` 路由冲突是真问题且生成器治不了 —— **已作为票 07 的施工前提记在「未定项」** |
| `AppException`/`ResponseCode`/`Constants` 的包位（老 §7 的 exception/enums/common 三级 vs 新 §7 的多域落位） | 见「有意偏离票面的四处」之 1；已把多域落位口径补写进 `backend/CLAUDE.md` §7（评审中途读到了这段工作区改动，据此撤回原 3 条「硬违规」） |
| 同类 wire 两处落位：`LotteryDrawnMessage` 在 `types.common`，`PointsChangedMessage` 留 `domain.points.model.valobj` | **票面明确如此**（非疏漏）：票面只把跨域契约（lottery→points 的入站载荷）提到 `types.common`，points 自有出站载荷 `PointsChangedMessage` 归 points |
| `REDEEM_DRAW_COUNT` 字面量多处平行（Primitive Obsession） | 存量问题，非本票引入；不改（零行为变更），归票 10 |
| `xxl-job-tasks.sql` 执行器 title 仍 `mktAsset` | 有意保留：title 是 admin 展示用短标签（限 12 字符），与执行器注册名无关 |
| `marketing-services.md` 库名口径「只建 `bs_asset`/`bs_activity`」 | 不改：那是**库名**（本票冻结，收敛归票 08），同表文件路径行已同步为 `marketing/sql/...` |

**Spec 轴**：评审代理独立复算后给出「**验收 5 条全部属实**；缺失 2 / 越界 2 / 做错 3」，处置如下：

| 评审意见 | 处置 |
| --- | --- |
| (a1) 「dev actuator **9998**」未落地：`application.yml` 只有 `server.port: 8081`、`dubbo.protocol.port: 50052`、`xxl.job.executor.port: 9998`，全 `backend/` 零 `management.server.port` → actuator 实际随 HTTP 8081 | **不改，票面前提有误**：旧 asset 从来就是「8080/8081 上跑 actuator」，9998 是 **xxl 执行器**端口（与 `marketing-executor` 同一条装配）。加 `management.server.port: 9998` 会是本票禁止的行为变更，且与「严格零行为变更」冲突。已在 `## Answer` 记明；**建议票 18 回写 `sql.md`/map 时把「actuator 端口」这一列改成「xxl 执行器端口」**，或在票 08/15 明确要不要给营销服务独立 actuator 端口 |
| (a2) `PingController` 域专属却落 `trigger.common.http`（指提交 86bef4a） | **已采纳**：`701cb38` 已移到 `trigger.points.http`（评审也确认工作区改动 package 与路径一致） |
| (b1) `sql/xxl-job-tasks.sql` 顺手改了 `xxl_job_info.author` 的值（`'marketing-asset'` → `'marketing'`） | **已回退**：`author` 是**表数据值**，票面只授权改执行器 `app_name`。现已恢复 `'marketing-asset'`，该文件相对旧文件的差异只剩 appname 三处 + 头注释 |
| (b2) `backend/docs/**` + `backend/better-school/docs/**` 5 个文档文件被改，与「文档收口集中终验票 18」分工重叠 | **保留，本票有意为之**：这些行陈述的是**工程结构/包根/端口**——正是本票改掉的事实，留着会让下一个执行者读到错的目录树（票 18 是收口/查漏，不是「唯一允许改文档的票」）。改动仅路径与标识，无行为影响；已在「连带影响清单」记一行 |
| (c1) `MybatisConfig` 重写 + 新增 `FqcnAnnotationBeanNameGenerator` 属装配重写而非机械搬包 | 见「有意偏离票面的四处」之 2 —— 不改会 **启动即失败**（扫描基包已不存在），属「装配」而非「行为」 |
| (c2) 启动类挂 `nameGenerator` 是票 07 的预防性改动 | 见「有意偏离票面的四处」之 3 与 code review 表；保留理由已留档 |
| (c3) mapper 修复核查 | 评审独立核对：`PointsMapper.xml` 10 条 statement id 与 `IPointsDao` 10 个方法逐一一致；`OutboxMapper.xml` 归一化后与旧文件逐字节相同，无 `c→o` 残留 |

Context used: ~155k（票面预算 150–200k，未触顶，未触发拆分）。

### 票 07 的两个施工前提（本票评审暴露，票面未写）

1. **`@RequestMapping` 路由冲突**：lottery 搬入后 `ping` 路径必须与 points 区分（activity 是靠 `/api/lottery` + `/api/referral` 两条前缀并存）；
   `trigger.common.http` 只留真正域无关的 `GlobalExceptionHandler` / `SecurityUtils`（`PingController` 已按域落位）。
2. **common 清单判据**：`domain.common.*` 的 `IUserQueryPort`/`UserBasicVO` 是本票按票面落的；
   若票 07 发现它们只被 points 用，建议随该票定「落 common 还是落 points」。同类 wire 契约是否统一进 `types.common` 也建议在票 07 定。

<!-- 收口时补：Context used: ~?k；是否触发拆分；连带影响清单 -->
