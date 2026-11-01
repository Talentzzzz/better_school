Status: ready-for-agent

# 本地测试基础设施：容器常驻 + 开发/测试共用一套栈

术语遵循根目录 `CONTEXT.md`。本 spec 触及"集成测试 seam"与"测试环境"两个既有约定，落定后需回填 `backend/CLAUDE.md`（见 Implementation Decisions 末节）。

## Problem Statement

从开发者视角，现在跑一次全量测试要付四份不必要的账：

1. **容器每次重建**。测试通过 Testcontainers 起五个容器（MySQL / Redis / RocketMQ namesrv / MinIO / Nacos），跑完即拆。**每次全量 run 都要重付 40.5s 冷启**（实测：mysql 18.05s、nacos 16.51s、rocketmq 3.55s、minio 1.77s、redis 0.64s），另加 Spring 上下文 refresh 18.9s。一天跑十次就重付十分钟。
2. **迁移测试各起一个一次性 MySQL**。`integration/sql/` 下 12 个类各持一个 `@Container static MySQLContainer`，**合计 186s，其中真正的断言只有 3.0s** —— 98.4% 是 `mysqld` 冷启。
3. **测试与开发环境割裂、且互相打架**。测试用的是随机端口容器，开发用的是 compose 固定端口，两边只能错开跑：跑测试得关掉 IDE 里的单体，否则 `dubbo.protocol.port` 撞 50051、xxl-job 执行器撞 9999。想边开发边验证做不到。
4. **测试配置里还漏了一条通往开发环境的暗线**：`application-test.yml` 的 `elasticsearch.uris` 指向 `localhost:9200`（开发 ES），于是跑测试会把测试数据索引进开发 ES —— 与注释里"测试不依赖 ES"的声明不符。

合计：全量 **828s（13.8 分钟）** 里，约 **28% 是纯基础设施开销**，且这份开销每次全量 run 都要重付。

## Solution

**环境提前备好、全天常驻，测试直接连上去用。**

- 一套常驻容器同时服务开发和测试：**MySQL / Redis / MinIO 共用一个容器**（各自的多租户机制天然隔离），**Nacos / RocketMQ 各自独立容器**（这两个的隔离需要 namespace 技巧，代价高，用物理隔离绕开）。
- 测试侧**彻底移除 Testcontainers** —— 不再有任何容器由测试进程创建或销毁。
- 12 个迁移测试**共用一个 MySQL**，每个用例建自己的临时 database。
- 环境没起时**明确失败**，不静默跳过、不自动兜底 —— "先备环境再启动"是约定的一部分。

收益（实测推算）：**828s → ~600s**，且此后每次全量 run 都按新值走，不再重付容器账。

## User Stories

1. 作为开发者，我想在开工时一次性把开发和测试用的容器都起好、一整天不再开关，以便把注意力留在代码上。
2. 作为开发者，我想跑测试时不必再等 40.5s 的容器冷启，以便更快拿到反馈。
3. 作为开发者，我想早上起的容器到下午还在，以便第二次、第三次跑测试几乎立刻开始。
4. 作为开发者，我想跑测试时不必关掉 IDE 里正在跑的单体，以便边开发边验证。
5. 作为开发者，我想测试数据写进独立的数据库，以便跑测试永远不会清空我的开发数据。
6. 作为开发者，我想测试使用独立的 Redis 逻辑库，以便测试缓存与开发缓存互不污染。
7. 作为开发者，我想测试使用独立的 MinIO 桶，以便测试上传的图片不混进开发文件。
8. 作为开发者，我想测试使用独立的 Nacos，以便测试进程注册的 Dubbo provider 不会和 IDE 里的单体抢同一个服务名。
9. 作为开发者，我想测试使用独立的 RocketMQ namesrv，以便测试产生的消息不会流进开发环境的消费者。
10. 作为开发者，我想测试 JVM 和开发单体能在同一台机器上同时运行，以便不用来回切换。
11. 作为开发者，我想测试用的 host 端口与开发全部错开，以便两边同时跑不发生 `Address already in use`。
12. 作为开发者，我想迁移测试不再每个类起一个 MySQL，以便省下那 186s 中的绝大部分。
13. 作为开发者，我想迁移测试仍然跑在干净的一次性 schema 上，以便迁移脚本的验证不被残留状态污染。
14. 作为开发者，我想环境没起来时看到一条明确的连接失败信息，以便立刻知道该先执行哪个命令。
15. 作为开发者，我想有一条命令起好全部所需环境，以便不用记两套启动步骤。
16. 作为开发者，我想在改了 `init.sql` 后有一条明确路径让测试库跟上，以便测试不会跑在过期的 schema 上。
17. 作为开发者，我想测试进程完全不创建容器，以便跑完测试后 `docker ps` 里只有我起的那几个。
18. 作为开发者，我想能直接 `docker exec` 进测试库查看状态，以便排查失败用例时不用先找容器名。
19. 作为调试者，我想测试栈的容器名和开发栈一眼可区分，以便不会连错。
20. 作为开发者，我想这套约定被写进后端文档，以便后续的 `/implement` 与 `/tdd` 自动遵循，不用每次重新解释。
21. 作为新加入的人，我想文档里写清楚"先起环境、再跑测试"的步骤，以便第一天就能跑通。
22. 作为开发者，我想测试不再往开发 ES 写索引，以便开发环境的搜索结果不被测试数据污染。
23. 作为开发者，我想去掉 Testcontainers 依赖后编译仍然干净，以便没有残留的死依赖。
24. 作为开发者，我想这套改动不触碰任何业务代码与业务测试的断言，以便它是纯粹的设施替换而非行为变更。

## Implementation Decisions

### 拓扑：共享三个、独立两个

| 中间件 | 归属 | 隔离机制 | 开发 | 测试 |
| --- | --- | --- | --- | --- |
| MySQL | **共用** | database | `better_school` | `better_school_test` |
| Redis | **共用** | 逻辑库 index | `database: 0` | `database: 1` |
| MinIO | **共用** | bucket | `bs-files` | `bs-files-it`（现状已如此） |
| Nacos | **独立** | 容器 + 端口 | 8848 / 9848 | 18848 / 19848 |
| RocketMQ | **独立** | 容器 + 端口 | 9876 | 19876 |

选这两个独立，是因为它们的隔离需要 namespace（Nacos 要改三处配置、RocketMQ 要改 19 处消费者注解且 group 必须同步改）。物理隔离一次性消除这两道税。前三个的隔离机制是各自设计出来干这件事的，可靠度接近物理隔离。

### 测试栈端口分配

测试栈端口整体偏移 `+10000`（Nacos 的 gRPC 端口按 `主端口 + 1000` 的偏移关系同步平移）：

```
MySQL      13306      Nacos      18848 / 19848
Redis      16379      RocketMQ   19876
MinIO      19000 / 19001
```

与开发栈（3306 / 6379 / 9000 / 8848 / 9876）及应用自身端口（8080 / 8082 / 50051 / 9999）无交集。

### 测试进程的 host 端口

| 项 | 开发 | 测试 | 说明 |
| --- | --- | --- | --- |
| `xxl.job.executor.port` | 9999 | **9998** | 现状测试未覆盖此值，共存时必撞，必须显式错开 |
| `dubbo.protocol.port` | 50051 | **保持随机取** | 基类现有 `ephemeralDubboPort()` 已正确处理，保留 |

### 测试栈只需要两个容器

测试栈**不复制** MySQL / Redis / MinIO，只补 Nacos 与 RocketMQ namesrv。RocketMQ **只要 namesrv，不起 broker** —— 测试不依赖 MQ 投递（消费者被当作普通 Bean 直接注入调用），outbox 发送失败由 `OutboxMessageSender` 捕获，与现状一致。

即：开发栈 5 个容器照旧，测试栈新增 2 个，**总计 7 个**（对比"全量两套栈"的 10 个）。

### `init.sql` 拆段

现状 `init.sql` 把"建库 + 用库"与 DDL 主体混在一个文件（`CREATE DATABASE IF NOT EXISTS better_school` + `USE better_school`）。共用 MySQL 需要两个库，故拆为：

- **`sql/schema.sql`** —— 纯 DDL + 种子数据，不含 `CREATE DATABASE` / `USE`
- **`sql/init-dev.sql`** —— 建 `better_school` 并 `USE`，再引入 schema
- **`sql/init-test.sql`** —— 建 `better_school_test` 并 `USE`，再引入 schema

**这是本 spec 唯一会改动既有文件的部分，且会波及文档**：`docker-compose.yml` 头注释里那条手工初始化命令（`docker exec -i bs-mysql mysql -uroot -p < sql/init.sql`）在去掉 `USE` 后会报 `No database selected`，必须同步补上库名并更新说明。

### 基类改造

`CommunityApiTestBase` 去掉五个容器静态字段、`@BeforeAll startContainers()` 与整个 `@DynamicPropertySource`（数据源 / Redis / RocketMQ / MinIO / Dubbo 注册中心 / Dubbo 端口）。这些地址改为落在 `application-test.yml` 的固定值。

**保留** `publishSharedConfig()`：它把一个 HTTP POST 写进 Nacos 的 `better-school-shared.yml`（`jwt.secret` / `jwt.expire-days`），一次调用、幂等、代价可忽略，且免去手工去 Nacos 控制台建配置。改为对固定的测试 Nacos 地址发送。

`@BeforeEach truncateTables()` 的 25 张表清单与 `deleteByPattern` 行为**不变** —— 库名换了，逻辑不动。

### ES 指向

`application-test.yml` 的 `elasticsearch.uris` 由 `localhost:9200`（开发 ES）改为一个**必然不可达的端口**，使"测试不依赖 ES"从注释里的声明变成配置上的事实，消除测试数据写进开发 ES 的暗线。ES 客户端是惰性连接，测试行为不变。

### 迁移测试：`MigrationTestBase`

新建共享基类，收敛 12 个类里逐字复制的样板（`MySQLContainer` 声明 ×12、`migrationScriptResource(...)` ×11、`scalar(...)` ×10、`columnComment(...)` ×8、`count(...)` ×6）：

- **一个共享 MySQL 连接**（指向测试栈的 13306），不再有容器
- **每用例一个独立 database**（`DROP DATABASE IF EXISTS` + `CREATE DATABASE`）—— 这是共用容器不互相污染的关键，提供与"每类一个新容器"等价的隔离
- 基类提供迁移脚本定位（两候选目录的解析逻辑）、`scalar` / `columnComment` / `count` 等既有辅助方法

12 个子类各保留自己的三样东西：迁移前的旧表 DDL 常量、存量数据插入、迁移后断言。

### 依赖与构建清理

- 移除 `testcontainers-junit-jupiter`、`testcontainers-mysql` 依赖（Testcontainers BOM 属性一并清掉）
- 移除 surefire 配置里的 `DOCKER_HOST` 与 `TESTCONTAINERS_RYUK_DISABLED` 环境变量（两者都只为 Testcontainers 服务）

### 约定的落点（`/implement` 与 `/tdd` 的可见性）

新增一节「集成测试环境」到 `backend/CLAUDE.md`，写明：

- 集成测试连的是**预先起好的常驻栈**，不是自建容器；跑测试前必须先起环境
- 开发/测试的隔离口径：MySQL 用 `better_school_test` 库、Redis 用 `database: 1`、MinIO 用 `bs-files-it` 桶、Nacos 与 RocketMQ 用独立容器
- 新增集成测试的写法：继承既有的基类，不自行创建容器
- 新增迁移测试的写法：继承 `MigrationTestBase`，用独立 database

`backend/CLAUDE.md` 是 `/implement` 与 `/tdd` 在后端目录工作时会读到的项目指令文件，因此约定写在这里即可被两者看见。

### 契约

- `application-test.yml` 成为测试环境的**唯一事实来源**（地址、库名、库 index、桶名、端口）
- 基类退化为"会话与断言工具"，不再承担环境装配

## Testing Decisions

- **好测试的标准**：只测外部行为 —— HTTP 请求/响应、数据库与 Redis 的可观察状态、通知与幂等效果。不测实现细节。
- **Seam**：**沿用现有 seam，不引入新 seam**。全部集成测试走 `CommunityApiTestBase` 的 HTTP 入口（单一 seam）；迁移测试走新的 `MigrationTestBase`（同一个 seam 概念在迁移场景的既有延伸，不是新增抽象层）。这是本 spec 唯一的 seam 决策，需确认。
- **先例**：`CommunityApiTestBase`（HTTP 集成基类）、`integration/sql/*MigrationTest`（迁移脚本离线验证，不起 Spring）。
- **验收**：
  - 323 条既有用例**全部通过**，断言一字不改（本 spec 不改任何业务测试的断言）
  - 全量 run 的日志中**不再出现** `Creating container for image`
  - 全量耗时从 **828s 降到 ~600s**；迁移测试部分从 **186s 降到 ~20s**
  - 环境未起时，跑测试得到明确的连接失败，而非静默跳过
  - 开发单体与测试 JVM 同时运行，全量测试全绿
- **覆盖面**：不新增测试用例。本次改动的正确性由既有 323 条用例的通过 + 耗时/日志两项量化验收共同保证。

## Out of Scope

- **那 9.6 分钟的用例执行耗时优化**。它是 70% 的大头，但目前没有相位拆解（TRUNCATE 25 张表占多少、493 次登录占多少、Dubbo 往返占多少均未测量）。在拿到数据前不动它。
- **Nacos / RocketMQ 的 namespace 方案**。本 spec 用独立容器绕开，不做 namespace 配置。
- **CI 适配**。仓库当前无远程、无 CI 流水线；将来引入 CI 时需另立票决定它是连常驻栈还是重新引入自足容器。
- **三个营销服务（points / lottery / referral）的测试**。它们尚无集成测试。
- **Testcontainers reuse**。本方案下不再需要。
- **业务代码、业务测试断言、数据库 schema 的任何变更**。

## Further Notes

**实测数据（2026-09-15，Windows 11 + Docker Desktop + Maven 3.9.11 + JDK21）**：

| 项 | 实测 |
| --- | --- |
| 全量耗时 | 828s（13.8 min） |
| 其中：323 条用例执行 | 575.9s（70%），均值 1.78s/条 |
| 其中：五个容器冷启 | 40.5s |
| 其中：Spring 上下文 refresh | 18.9s |
| 其中：12 个迁移容器 | 186s（断言仅占 3.0s） |
| 首个执行的测试类 | 88.2s（含全部冷启；其用例仅占 22.2s） |

**主要残留风险：共用 MySQL 的"静默清库"**。一个写错库名的用例、或一段连到 `better_school` 的代码，会静默清空开发数据；物理隔离的两套栈不可能发生这件事。缓解：测试侧配置里**彻底不出现** `better_school` 这个字面量，库名取 `better_school_test` 这种一眼可辨的形式。Redis 侧风险更低（key 全按逻辑库隔离）。

**第二个残留风险：schema 漂移**。常驻容器不会因为 `init.sql` 改动或新增迁移就自动跟上，测试可能跑在过期 schema 上并给出**假绿**。缓解：`backend/CLAUDE.md` 的那节里写明"改动 schema 后需重建测试库"的具体命令。

**为什么不用 namespace 换来共用 Nacos/RocketMQ**：namespace 方案本质是"把 topic 与 group 统一前缀"，但 RocketMQ 侧要改 19 处消费者注解，而这个 starter 的消费端 namespace **只能逐个注解指定**（生产端可以全局配、消费端不行），改动面与收益不成比例；漏改一处不会报错，只会静默串环境。
