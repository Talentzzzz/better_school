# 票 07 · 活动侧 → marketing 包名与落位映射表

> 施工契约：机械执行的规则全在这里；执行结果与评审处置见票 07 的 `## Answer`。

## 0. 包名映射（逐层）

源包根 `com.betterschool.activity` → 目标包根 `com.betterschool.marketing`。
**机械规则 = 把源包名里的 `com.betterschool.activity` 整段替换成 `com.betterschool.marketing`，其余段原样保留**
（域段插在层后，与票 06 的 points 同构），再叠加 §2 的去重跳转。

| 源（activity 侧） | 目标（marketing 侧） | 规模 |
| --- | --- | --- |
| `activity.api.{lottery,referral}[.dto]` | `marketing.api.{lottery,referral}[.dto]` | 22 |
| `activity.api.{lottery,referral}.{response,dto.UserBasicResponseDTO}` | `marketing.api.common.{response,dto}` | 0（去重） |
| `activity.domain.{lottery,referral}.*` | `marketing.domain.{lottery,referral}.*` | 54 |
| `activity.domain.{lottery,referral}.*`（同名公共类） | 见 §2 | 0（去重） |
| `activity.infrastructure.{lottery,referral}.*` | `marketing.infrastructure.{lottery,referral}.*` | 44 |
| `activity.infrastructure.*`（同名公共类） | 见 §2 | 0（去重） |
| `activity.trigger.{lottery,referral}.*` | `marketing.trigger.{lottery,referral}.*` | 6 |
| `activity.trigger.*`（同名公共类） | 见 §2 | 0（去重） |
| `activity.types.lottery.{enums,exception}.*`（域专属） | `marketing.types.lottery.{enums,exception}.*` | 12 |
| `activity.types.lottery.common.Constants` | `marketing.types.lottery.Constants`（**去掉 `.common` 段**） | 1 |
| `activity.types.referral.common.Constants` | `marketing.types.referral.Constants`（**去掉 `.common` 段**） | 1 |
| `activity.types.{lottery,referral}.{enums.*,exception.*}`（公共） | 见 §2 | 0（去重） |
| `org.apache.rocketmq.client.impl.consumer.VirtualThreadConsumeMessageConcurrentlyService` | **包名原样**，只换 module 目录 | 1 |
| app 模块（启动类 / config / integration 测试） | `marketing-app` | 27 |

## 1. 不用管的文件（主窗口自理）

mapper XML、`application*.yml`、`pom.xml`、`sql/`、`docs/`。

## 2. 去重落位（只留一份，落在 marketing 侧既有位置）

### §2-A types / api（12 个副本删除）

| 删除 | 保留并改用的目标 |
| --- | --- |
| `types.{lottery,referral}.exception.AppException` | `types.common.AppException`（构造器签名相同） |
| `types.{lottery,referral}.enums.ResponseCodeEnum` | `types.common.ResponseCodeEnum`（**需并入全部 4xxx，见 §4**） |
| `types.{lottery,referral}.enums.OutboxStatusEnum` | `types.common.OutboxStatusEnum`（逐字节相同） |
| `api.{lottery,referral}.response.{Response,PageResult}` | `api.common.response.*` |
| `api.{lottery,referral}.dto.UserBasicResponseDTO` | `api.common.dto.UserBasicResponseDTO` |

### §2-B domain（10 个副本删除）

| 删除 | 保留并改用的目标 |
| --- | --- |
| `domain.{lottery,referral}.adapter.port.marketing.IUserQueryPort` | `domain.common.adapter.port.marketing.IUserQueryPort` |
| `domain.{lottery,referral}.model.valobj.marketing.UserBasicVO` | `domain.common.model.valobj.marketing.UserBasicVO` |
| `domain.lottery.model.valobj.LotteryDrawnMessage` | `types.common.LotteryDrawnMessage`（需补 `@AllArgsConstructor`，lottery 侧用全参构造） |
| `domain.{lottery,referral}.model.valobj.ReferralGroupedMessage` | `types.common.ReferralGroupedMessage`（**新建**） |

**保留不动**（同名但语义不同）：`domain.{lottery,referral}.model.valobj.ActivityConfigVO`、
`domain.{lottery,referral}.adapter.repository.IActivityConfigRepository`。

### §2-C infrastructure（14 个副本删除）

| 删除 | 保留并改用的目标 |
| --- | --- |
| `infrastructure.{lottery,referral}.dao.outbox.IOutboxDao` | `infrastructure.common.dao.outbox.IOutboxDao` |
| `infrastructure.{lottery,referral}.dao.outbox.po.OutboxPO` | `infrastructure.common.dao.outbox.po.OutboxPO` |
| `infrastructure.{lottery,referral}.outbox.OutboxMessageStore` | `infrastructure.common.outbox.OutboxMessageStore` |
| `infrastructure.{lottery,referral}.mq.OutboxMessageSender` | `infrastructure.common.outbox.OutboxMessageSender`（**三个 `@XxlJob` 注册名见票 07 Answer**） |
| `infrastructure.{lottery,referral}.security.{JwtService,JwtAuthenticationFilter}` | `infrastructure.common.security.*` |
| `infrastructure.{lottery,referral}.adapter.port.marketing.UserQueryPort` | `infrastructure.common.adapter.port.marketing.UserQueryPort` |

**保留不动**：`infrastructure.lottery.adapter.repository.lottery.*`（8）、`infrastructure.lottery.dao.lottery.*`（12）、
`infrastructure.lottery.adapter.port.lottery.PrizeBucketPort`、`infrastructure.lottery.adapter.port.marketing.PointsExchangePort`、
`infrastructure.referral.adapter.repository.referral.*`（2）、`infrastructure.referral.dao.referral.*`（4）。

### §2-D trigger（4 个副本删除）

| 删除 | 保留并改用的目标 |
| --- | --- |
| `trigger.{lottery,referral}.http.GlobalExceptionHandler` | `trigger.common.http.GlobalExceptionHandler`（保留版**不带** `basePackages` 限定） |
| `trigger.{lottery,referral}.http.security.SecurityUtils` | `trigger.common.http.security.SecurityUtils` |

**保留不动**：三个 `PingController`（各挂 `/api/{points,lottery,referral}` + `/ping`，合并即路由冲突）。

## 3. app / 测试侧

| 源 | 目标 |
| --- | --- |
| `MarketingActivityApplication` + `config/{FqcnAnnotationBeanNameGenerator,RocketMQConsumerConfig,TransactionConfig,VirtualThreadRocketMQListenerContainerRegistrar,XxlJobConfig}` | 目标同名文件已存在（正文等价）→ 删源 |
| `config/SecurityConfig` | 用活动侧（lottery 白名单版）**替换**目标 |
| `config/MybatisConfig` | **合并**：`@MapperScan` 列 4 个域基包（points / lottery / referral / common.dao.outbox） |
| `integration/{Lottery*,Referral*}IntegrationTest`（9） | `integration.{lottery,referral}` 子包 |
| `integration/support/{LotteryApiTestBase,ReferralApiTestBase,StubPointsExchangePort,StubUserQueryPort,SwitchablePrizeBucketPort}` | `integration.support` 同名 |
| `integration/{ConsumeHandler,WebHandler}VirtualThreadIntegrationTest` + `support/{VtConsumeThreadProbe,VtWebThreadProbe}` | **删源**（目标已有） |
| `mapper/lottery/LotteryMapper.xml` / `mapper/referral/ReferralMapper.xml` | `mapper/{lottery,referral}/`（namespace 指向新 dao 包） |
| `mapper/{lottery,referral}/OutboxMapper.xml` | **删源**（与已有 `mapper/OutboxMapper.xml` 归一化后逐字节相同） |

`mybatis.mapper-locations` 必须**递归**：`classpath:/mybatis/mapper/**/*.xml`（票 06 写的 `*.xml` 漏子目录）。

## 4. `types.common.ResponseCodeEnum` 并入清单

```
SUCCESS("200") UNAUTHORIZED("401") FORBIDDEN("403") NOT_FOUND("404") SYSTEM_ERROR("999")

// 400 三域同码但 info 文案不同 → 按域拆开（保行为的必要动作）
POINTS_PARAM_ERROR("400","参数错误")    LOTTERY_PARAM_ERROR("400","参数不合法")

// points
POINTS_INSUFFICIENT_POINTS("4101","积分不足")

// referral（4201–4206，info 逐字照活动侧）
CODE_NOT_FOUND("4201") REFERRAL_ACTIVITY_NOT_STARTED("4202") REFERRAL_ACTIVITY_ENDED("4203")
OLD_USER("4204") ALREADY_INVITED("4205") CANNOT_INVITE_SELF("4206")

// lottery（4301–4309，info 逐字照活动侧）
LOTTERY_ACTIVITY_NOT_STARTED("4301") LOTTERY_ACTIVITY_ENDED("4302") NO_CHANCE("4303")
DAILY_LIMIT_REACHED("4304") PRIZE_NOT_FOUND("4305") LOTTERY_INSUFFICIENT_POINTS("4306")
PRIZE_NOT_REDEEMABLE("4307") OUT_OF_STOCK("4308") PRIZE_NOT_CLAIMABLE("4309")
```

**码值（字符串）是前端 `front/src/api/marketing.ts` 的分支依据，一字不改。**
调用点按域改名（8 处 / 4 文件）：`LotteryController`、`LotteryExchangeServiceImpl`、`PrizeBucketServiceImpl`
→ `LOTTERY_PARAM_ERROR`；`PointsAccountServiceImpl`、`PointsExchangeDubboService`、`GlobalExceptionHandler` → `POINTS_PARAM_ERROR`。

## 5. 装配决策

1. **自调用 Dubbo 保持 `@DubboReference`**（票面授权改直注；本票不动，留给票 13）。
2. **`OutboxMessageSender` 保留三个 `@XxlJob` 注册名**（`outboxScan` + 两个转发名），兼容 `xxl_job_info` 旧行。
3. **`GlobalExceptionHandler` 无 `basePackages`**（`AppException` 已并一份，限定反而漏接）。
4. **库名收敛 `bs_marketing` / `bs_marketing_test`**；测试基类 `ACTIVITY_TABLE` 与 `application-test.yml` 的
   `referral.activity-config-table` 都用 `bs_marketing_test.lottery_activity`。
5. **`XxlJobConfig` 只留一份**，`appname: marketing-executor`；`sql/xxl-job-tasks.sql` 注册 6 个 handler。

## 6. sql/ 目录

| 源 | 目标 |
| --- | --- |
| `marketing-activity/sql/{schema-lottery,schema-referral}.sql`、`migrations/{023,024}*.sql` | `marketing/sql/` **原样搬**（DDL 一字不改，SHA256 与 HEAD 相同） |
| 两个 `init-{dev,test}.sql` | 合成一份：只建 `bs_marketing` / `bs_marketing_test` |
| 两个 `xxl-job-tasks.sql` | 合成一份：执行器 `marketing-executor` + 6 个 handler |
| `scripts/test-env.sh` | 三份 schema 对同一库名各拼一次；`ACTIVITY_SQL_DIR` 删除 |

## 7. 执行中发现并补充的两处（票面未预见）

1. **两个跨域 topic 字面量重复** → 新建 `types/common/Constants.java` 收 `LOTTERY_DRAWN_TOPIC` /
   `REFERRAL_GROUPED_TOPIC` / `POINTS_CHANGED_TOPIC`（生产方与消费方分属不同域的 topic）；
   三份域 `Constants` 只留域自有的消费组 / Redis key 前缀 / 邀请码参数。字面量值一字未改。
2. **三个测试基类各抄了一遍环境引导** → 抽出 `integration/support/TestingEnvironment.java`
   （幂等 `ensureEnvironment()` + JWT 签发 + GET/POST + 信封解析 + 三条断言），三个域基类 `extends` 它；
   域基类之间仍不互相 extends（清表集与 Spring 上下文按域不同）。方法签名与断言文案一字未改。

## 8. 票面「合并清单」里三处不能照字面执行

1. **`PingController`**：三个类各挂不同 `@RequestMapping` 前缀，合并即路由冲突 → 按域保留。
2. **两份 `Constants`**：无共同成员，合并只会造语义混杂的类 → 按域保留；重复的跨域 topic 见 §7-1。
3. **「两个库 `bs_marketing` 一个」**：开发库与测试库必须分离（`CLAUDE.md` §10）→ `bs_marketing` + `bs_marketing_test`。

这三条与评审处置的完整判据见票 07 `## Answer`。
