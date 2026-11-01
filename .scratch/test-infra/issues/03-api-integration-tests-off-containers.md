# 03: API 集成测试脱离容器（配置收边 + 基类退化）

**Spec:** `.scratch/test-infra/spec.md` → Implementation Decisions「基类改造」「ES 指向」「测试进程的 host 端口」「契约」

**What to build:** 44 个继承 `CommunityApiTestBase` 的 HTTP 集成测试类改成连**预先起好的常驻栈**，测试进程不再创建任何容器；`application-test.yml` 成为测试环境的唯一事实来源。做完之后，可以一边开着 IDE 里的 8080 单体一边跑全量测试，而且测试数据永远落在 `better_school_test` 与 Redis 逻辑库 1 上。

**Blocked by:** 01（常驻测试栈与拆段后的 SQL 是前置）

**Status:** resolved

- [x] `application-test.yml` 落全部固定地址：datasource（`13306` / `better_school_test`）、redis（`16379` / **`database: 1`**）、rocketmq name-server（`19876`）、dubbo registry（`nacos://127.0.0.1:18848`）、minio（`19000` / 桶 `bs-files-it`）、xxl executor port（**`9998`**）
- [x] **`minio.secret-key` 跟着改**：共用容器后用的是开发栈凭据（`bsadmin` / `bs123456`），只有桶名 `bs-files-it` 保留差异——现状配置里的 `bs123456-test` 属于测试专用容器时代，必须同步
- [x] `elasticsearch.uris` 由 `localhost:9200`（开发 ES）改成一个**必然不可达的端口**，让「测试不依赖 ES」从注释里的声明变成配置上的事实，消除测试索引写进开发 ES 的暗线
- [x] `CommunityApiTestBase` 去掉五个容器静态字段、`@BeforeAll startContainers()`、容器相关的 `@DynamicPropertySource`，以及 `resolveInitSqlPath()`（init 不再由测试容器执行）
- [x] `publishSharedConfig()` **保留**：改为对固定的测试 Nacos 发送（一次调用、幂等、免去手工建配置）。`spring.cloud.nacos.server-addr` 仍走 `System.setProperty`——`spring.config.import` 在 config-data 阶段解析，早于 profile 文档与 `@DynamicPropertySource`，搬进 yml 会启动失败；固定端口后 gRPC offset 那行可以删（默认 offset 即 1000，18848 + 1000 = 19848）
- [x] `dubbo.protocol.port` 仍取空闲端口（不与开发单体的 50051 撞）。落地口径：与 nacos 地址同路用 `System.setProperty`（系统属性从一开始就在 Environment 里），使「整个 `@DynamicPropertySource` 去掉」与端口表「保持随机取」两条同时成立
- [x] `@BeforeEach truncateTables()` 的 25 张表清单与 `deleteByPattern` 行为**不变**——库名换了，逻辑不动
- [x] 验证：单类跑通 → 全量集成测试全绿；**且与 IDE 里 8080 单体同时运行时全绿**（不再有 `Address already in use`）

**Notes:** Redisson 由 `redisson-spring-boot-starter` 读 `spring.data.redis.*`，故 `database: 1` 一处即同时隔离 Lettuce 与 Redisson，基类读验证码那条路径自动落在测试逻辑库上。

## Answer

### 落地清单

| 项 | 落点 |
| --- | --- |
| `application-test.yml` | 重写为测试环境唯一事实来源：datasource 13306/`better_school_test`、redis 16379/`database: 1`、rocketmq `localhost:19876`、dubbo registry `nacos://127.0.0.1:18848`、minio 19000/`bs-files-it`（凭据 `bsadmin`/`bs123456` 跟开发栈）、xxl executor `9998`、`elasticsearch.uris: http://localhost:19200`（必然不可达）；头注释写明两个例外（nacos 地址与 dubbo 端口走 System.setProperty）及「先备环境再启动」约定 |
| `CommunityApiTestBase` | 删五个容器静态字段、`startContainers()`、`@DynamicPropertySource`、`resolveSchemaSqlPath()` 及全部 testcontainers import；`@BeforeAll` 退化为 `prepareEnvironment()` 只发 `publishSharedConfig()`；`truncateTables()` 25 表清单与 `deleteByPattern` 一字未动 |
| `publishSharedConfig()` | 改发固定 `TEST_NACOS_ADDR = 127.0.0.1:18848`；gRPC offset 的 `System.setProperty` 删除（nacos-client 2.1.0 `GrpcSdkClient` 缺省 offset 1000，18848+1000=19848 与 compose `19848:9848` 吻合）；新增 `dubbo.protocol.port` 走 `System.setProperty`（随机空闲端口，与 8080 单体的 50051 错开） |

### 对票面的偏离

仅一处琐碎项：yml 里显式写了 `driver-class-name: com.mysql.cj.jdbc.Driver`（票面清单未列；原靠 url 隐式解析，行为等价）。其余按票面逐条落地。

### 验证（2026-09-15 实测）

- 常驻栈 10 容器全部 healthy（`docker ps`），测试侧端口 13306/16379/19000/18848/19876 在线。
- 单类 `AnonymousRemovalIntegrationTest`：2/2 绿（34.5s，含 Spring 上下文冷启）。
- **全量 `mvn -pl bs-app test` 与 8080 开发单体同机同时运行：323 条全绿、BUILD SUCCESS、11:24**（基线 828s，其中含与单体的资源竞争；spec 预估 ~600s 量级方向吻合）；日志 `Creating container for image` 零命中；全程无 `Address already in use`。
- 测试数据落点核实：MQ 告警（No route info of topic）为预期——测试栈只有 namesrv 无 broker，outbox 发送失败由 `OutboxMessageSender` 兜住，不影响被测行为。

### code-review 采纳项

- **Standards 轴**（判断级，均不改代码）：Nacos 地址在 Java 常量与 yml 各一份——票面明文要求的双机制落点（`spring.cloud.nacos.server-addr` 必须走 System.setProperty，`dubbo.registry.address` 落 yml），注释两侧已互指；`ephemeralDubboPort` 命名为改动前既有；ES 19200「必然不可达」靠 +10000 端口约定成立，票面刻意为之。
- **Spec 轴**：七条清单逐项吻合、零缺失零返工；gRPC offset 默认值与 `spring.data.redis.database` 的 Redisson/Lettuce 双隔离均经核实。

### 已知波及 / 不在本票范围

- Testcontainers 依赖本体、surefire 的 `DOCKER_HOST` / `TESTCONTAINERS_RYUK_DISABLED`、父 pom 的 BOM 属性、`backend/CLAUDE.md`「集成测试环境」一节，全部属票 04。
- `spring.cloud.nacos.server-addr` 与 `dubbo.protocol.port` 不在 yml 是 config-data 阶段时序的硬约束（票面第 5/6 条），「唯一事实来源」存在这两条例外，已在 yml 头注释声明。
