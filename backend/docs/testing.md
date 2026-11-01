# 集成测试设施

仓库第一批测试设施（评论楼中楼 v2 票 01 建立），是后续所有功能测试的先例。

## 怎么跑

```bash
# 单个测试类（-am 连带构建依赖模块）
mvn test -pl bs-app -am -Dtest=PostCommentBaselineIntegrationTest

# 全量
mvn test -pl bs-app -am
```

前置：先起常驻环境（测试连的是它，不自建容器；幂等，可重复执行）：

```bash
scripts/test-env.sh up
```

环境没起时测试会直接连接失败——这是刻意的，不要加自动兜底。改过 `sql/schema.sql` 或跑了新迁移后，用 `scripts/test-env.sh reset-test-db` 重建测试库，否则测试跑在过期 schema 上出假绿。

## 约定

- **位置**：测试统一放 `bs-app`（唯一依赖全部模块的组装层），包名 `com.betterschool.integration.<业务域>`
- **单一 seam = trigger 层入口**：`@SpringBootTest(RANDOM_PORT)` 起完整上下文，用 `TestRestTemplate` 打真实 HTTP；spec 里的另一半 seam（审核消费者入口，测试直接驱动消费者）在异步审核票落地时补充，本票的同步审核链路用不到
- **只断言外部行为**：HTTP 响应 + MySQL/Redis 可观察状态（基类提供 `JdbcTemplate` / `RedissonClient` 直查）；不 mock 任何内部 Bean、不断言内部实现
- **运行环境**：测试进程**不创建任何容器**——连 `scripts/test-env.sh up` 备好的常驻栈。MySQL / Redis / MinIO 与开发共用容器，靠多租户机制隔离（MySQL 库 `better_school_test`、Redis `database: 1`、MinIO 桶 `bs-files-it`）；Nacos（18848 / 19848）与 RocketMQ namesrv（19876）是测试专用容器。地址的唯一事实来源是 `application-test.yml`；建库走 `sql/init-dev.sql` / `init-test.sql` + `schema.sql`（test-env.sh 起栈时执行），不用 JPA 建表
- **数据隔离**：基类 `@BeforeEach` TRUNCATE 业务表；`sensitive_word` 例外——词表在应用启动时加载进内存，测试直接用 init.sql 的种子词（如「作弊」）
- **登录态走真实链路**：`send-code` → 从 Redis 读验证码 → `register` 换 JWT（基类 `registerAndLogin`），不伪造 token
- **中间件缺席**：RocketMQ / xxl-job / ES 自动装配保持开启（`OutboxMessageSender` 硬依赖 `RocketMQTemplate`，排除会导致上下文起不来）；不可达的副作用 = 日志告警 + 发送失败被 catch，不影响被测行为。噪音日志在 `application-test.yml` 降噪
- **现状断言（baseline）**：锁定既有缺陷时，断言旁标注 `TODO(v2-04)`，替换实现时同步改写断言
- **不写增量迁移脚本**（票 05 起）：`sql/schema.sql` 是唯一 DDL 来源，改表就改 schema.sql + 重建库（`scripts/test-env.sh reset-test-db` / docker 直连灌 init-test.sql + schema.sql）；`sql/migrations/` 与迁移测试基类已整体删除，不要新增

## Nacos / Dubbo 在测试里的三条特殊处理（营销票 01 建立）

单体接 Nacos 之后，集成测试多了三项不能省的东西，都在基类里：

1. **上下文启动前把共享配置写进测试 Nacos**（`publishSharedConfig`）。
   单体的 `spring.config.import` 是严格导入（无 `optional:`），dataId 不存在就直接启动失败。
   写入内容 = 生产那份的 `jwt.secret` / `jwt.expire-days`；对固定地址 `127.0.0.1:18848` 发一个 HTTP POST，幂等。
2. **Nacos 地址走 `System.setProperty`，不走 `@DynamicPropertySource`**。
   `spring.config.import` 在 config data 阶段解析，那时 `@DynamicPropertySource` 的属性源还没挂上。
   gRPC 端口按默认 offset = 主端口 + 1000（18848 → 19848），compose 测试栈已按此暴露，无需额外配置。
3. **Dubbo 的注册中心与端口都要覆盖**：
   - `dubbo.registry.address` 指向测试 Nacos——Dubbo 顺带拿注册中心当 config-center，
     连不上会在启动期抛 `Failed to create nacos config service client` 炸掉上下文；
   - `dubbo.protocol.port` 取一个空闲端口——`application.yml` 里的 50051 是给 IDE 里的单体用的，
     本机同时开着它会 `Address already in use`。

## 营销服务的测试设施（营销票 05 建立，票 06 扩到积分外域，票 07 扩到抽奖并域合一，票 13 抽奖换 raffle 域段）

营销服务是**独立 project**（与单体无 reactor），测试因此放服务的组装层，不挤进 `bs-app`。
（票 07 起五个域段同属 `marketing` 工程一个 app：points / drawcount / config / raffle / inventory；
票 13 起抽奖由 `raffle` 域承载，旧 `lottery` 域段与它的测试基类已删除。）

```bash
# 在 backend/marketing/ 下跑本服务的测试类（同 project 的兄弟模块由 reactor 带上，不必 -am）
mvn -o test -Dtest=RaffleDrawPipelineIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

| 项 | 单体（bs-app） | 营销服务（marketing-app，points + drawcount + config + raffle + inventory） |
| --- | --- | --- |
| 测试库 | `better_school_test` | `bs_marketing_test`（同一 MySQL 容器，库名隔离）+ **redis 逻辑库 1**（key 前缀 `bs:points:` / `bs:raffle:` / `bs:inventory:`） |
| 基类 | `integration.support.CommunityApiTestBase` | `marketing.integration.support.{PointsApiTestBase, RaffleApiTestBase}`（两类按域共存；VT 两个 seam 测试跑在 `RaffleApiTestBase` 上） |
| 登录态 | 真链路 send-code → register 换 JWT | 服务没有登录链路，基类按共享密钥**直接签发** JWT（形状与单体 `JwtService.generateToken` 一致）；`RaffleApiTestBase` 另有 `ADMIN` 角色的签发方法 |
| MQ 消费 | 直调消费者 Bean | 同左（测试栈无 broker，构造 payload 直调消费者）；points 域直调 `LotteryDrawnConsumer`，raffle 域没有消费者（只发事件），断言只看 outbox 落行 |
| Dubbo | 真 Consumer 打单体 Provider | 用户只读边**换桩**：测试源码里 `@Primary StubUserQueryPort` 覆盖真 Dubbo Consumer（抽奖读侧依赖昵称头像，桩可给确定值）。**兑换边票 13 起不再经 Dubbo**（折成进程内 port），原 `@Primary StubPointsExchangePort` 已删；积分域自己的 Provider Bean（`PointsExchangeDubboService`）仍直接调用测 |

- 建库走 `marketing/sql/init-{dev,test}.sql`（只建 `bs_marketing` / `bs_marketing_test`）+
  `sql/schema.sql`（**唯一 DDL 主体**，22 张；票 13 起 `schema-legacy.sql` 已删除）。
  `scripts/test-env.sh` 的
  `apply_service_init <工程sql目录> <建库文件> <库名> <DDL文件>` 把 `USE` 插在中间——
  各份 schema 都不含库名，开发库与测试库才能共用一份 DDL。改过 schema 后同样用
  `scripts/test-env.sh reset-test-db` 重建。
- **抽奖测试要重置活动配置**：`RaffleApiTestBase` 的 `@BeforeEach` 会 `TRUNCATE` 17 张业务表
  （draw_* / draw_count_* / points_* / virtual_* / physical_* / outbox_message）并清
  `bs:raffle:*` / `bs:inventory:*` 两段 redis key；活动配置与奖品位由用例自己用夹具铺
  （`insertActivity` 是**先删再插**——活动是单行语义，重铺即改窗口与状态；活动窗口一律**相对 `now()`** 算，
  别拿夹具的绝对起点加天数）。
- **outbox 在测试里的可观察形态**：测试栈有 namesrv、没有 broker，发送必然报
  `No route info of this topic` 并被 `OutboxMessageSender` 兜住 → 记录保持 PENDING、`retry_count` 增长。
  所以「事件发出」的断言口径 = **outbox 落行 + 载荷正确**；真投递与单体消费归端到端票（营销票 09）。
- **mock 单测放 `marketing.unit.*`，不进集成包**：`marketing-app/src/test/java/com/betterschool/marketing/unit/`
  收「不需要 Spring 上下文」的用例（如 `unit.outbox.OutboxMessageSenderTest` 验「选了哪条发送分支」）。
  上面那条「只断言外部行为、不 mock 内部 Bean」管的是集成测试；发送分支这类**没有外部可观察面**的判据
  （测试栈无 broker，定时投递与直发都表现为发送失败）只能靠 mock `RocketMQTemplate` 断言调用形态。
  预算：能落到 MySQL/Redis 上的事实别写进单测。
- **抽奖测试的三个特有手法**（票 07 建立，票 13 沿用并换成新模型）：
  1. **把随机滚档做成确定**：`UPDATE draw_strategy_award SET award_rate = 0 WHERE type = 'normal'`
     后把目标档置 1，再 `warmupByStrategyId` 重预热，即必中该档——`ThreadLocalRandom` 在集成测试里注入不进去。
  2. **redis 停服换桩**：测试源码里的 `@Primary SwitchableInventoryBucketPort` 默认原样委托真实现，
     可以切成「抛 `BucketUnavailableException`」来表达 redis 挂了。
  3. **断言 redis 桶值前先判 key 是否存在**：`getAtomicLong().get()` 会把不存在的 key 建成 0，
     直接取值就永远看不出「这个桶从没被碰过」（`RaffleApiTestBase` 的 `*BucketValueInRedis` 已内置该判断）。

## WS 全链路测试（chat 票 07 建立）

- **真客户端直连**：`StandardWebSocketClient`（`spring-boot-starter-websocket` 传递引入的 tomcat-embed-websocket 自带客户端容器）连 `ws://localhost:{RANDOM_PORT}/ws/chat?ticket=`，入站帧反序列化为协议信封 `ChatWsFrame`；票据走真实 REST `POST /api/chat/ws-ticket`
- **推送触发不经 MQ**：读 `outbox_message` 的载荷后直调消费者（`ChatPushConsumer` / `ChatReadPushConsumer` / `ChatRecallPushConsumer`）——namesrv 必须起、broker 刻意不启，MQ→消费者一跳由 outbox 骨架背书
- **握手拒绝断言**：Tomcat 客户端把被拒响应码包进 `jakarta.websocket.DeploymentException` 消息，断言 `hasMessageContaining("401")`——只断言"抛异常"会把服务未起/超时放过
- **建连同步**：握手 future 完成早于服务端 `afterConnectionEstablished`（注册表写入），建连后 PING→PONG 同步一次往返，互踢/推送时序才稳定
- **注入时钟代替真等待**：`ChatSessionRegistry.sweep(now, ChatSessionRegistry.DEAD_THRESHOLD_MS)` 注入未来时间驱动判死清扫（常量已公开供对齐），不真等 60s；活跃会话不受影响的逆向分支同用例覆盖
- **清理**：`@AfterEach` 关闭本用例所有连接——注册表/会话表是共享上下文单例，连接不关会污染后续用例

## 文件

| 文件 | 职责 |
| --- | --- |
| `bs-app/src/test/java/com/betterschool/integration/support/CommunityApiTestBase.java` | 基类：Nacos 共享配置 + HTTP/DB/Redis 辅助 + 登录态 |
| `bs-app/src/test/java/com/betterschool/integration/community/PostCountMergeIntegrationTest.java` | 计数链路先例（hot-row-merge 票 03/04）：点赞返回即窄表精确、浏览 fire-and-forget 只判单调不减 + 丢弃可读 |
| `bs-app/src/test/java/com/betterschool/integration/community/PostCommentBaselineIntegrationTest.java` | 评论区现状基线（发布/列表/删除） |
| `bs-app/src/test/java/com/betterschool/integration/community/AnonymousRemovalIntegrationTest.java` | 匿名移除固化（v2 票 02）：旧客户端字段忽略、全实名 |
| `bs-app/src/test/java/com/betterschool/integration/chat/ChatWsIntegrationTest.java` | WS 全链路行为测试先例（chat 票 07）：真客户端 + 直调消费者 + 注入时钟 |
| `bs-app/src/test/java/com/betterschool/integration/errand/ErrandFullChainIntegrationTest.java` | 跑腿全链路先例：支付 → 消费者通知 → 裁决 → 解冻退款 + 订单事件流水断言 |
| `bs-app/src/test/resources/application-test.yml` | 测试 profile：只写覆盖项与日志降噪 |
