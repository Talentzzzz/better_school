# 营销服务（marketing）

> 归属：营销域。需求以 `.scratch/marketing/spec.md` 为准，栈选型论证见 `.scratch/marketing/research-cloud-stack.md`。
> 本文只写「怎么跑起来、怎么改不翻车」。

## 1. 工程形状

`backend/` 下两个**平级独立 project**，彼此**没有 reactor 关系**：

```
backend/
├── better-school/           单体工程（bs-* 七模块，聚合器 pom artifactId = better-school；超时中心已内嵌在此工程）
└── marketing/               营销服务聚合器（parent = spring-boot-starter-parent:3.3.5）
    └── marketing-{types,api,domain,case,infrastructure,trigger,app}
```

营销服务一个 app 承载 **四个域段**：两个业务域 `points`（积分）、`raffle`（抽奖——票 11 表 + 责任链 + 预热，
票 13 主链路与读侧；**票 13 起取代原 lottery 旧域**，并**吸收抽奖次数**：`draw_count_bucket / detail /
config` 与次数账务、次数购买都归它），以及两个支撑域 `rebate`（行为返利规则）、`inventory`（票 12 两段式库存）。
（沿革：票 06 并入 points、票 07 并入 lottery 旧域、票 10 加 drawcount 与 config、
票 12 加 inventory、票 13 删 lottery 旧域并把它换成 raffle；**config 域后来拆掉**——
两张有效期表跟表走，`points_validity_config` 归 points、`action_rebate_config` 留给改名后的 `rebate` 域；
**drawcount 域后来并入 raffle**——抽奖次数是抽奖域内的资产，`draw_count_config` 随之归 raffle，
抽奖只扣次数、积分换次数改为 raffle 域内的「购买次数」用例。）

**domain 层没有 `common` 段**：跨服务 / 跨域的只读端口一律在**消费方域**声明（`domain/<域>/adapter/port/…`），
infrastructure 各自实现。因此用户信息这种「每个域都要一点」的读能力不设共享域——
points 与 raffle 各自有 `IUserQueryPort` + 域内读视图（照单体 chat / community 的先例）。
`marketing.{layer}.common.*` 只用**非域**公共件（框架装配、统一响应、JWT、outbox、wire 契约）。
`MarketingApplication` 一个启动器 + 一套 `marketing.config.*`。

每个工程 = 7 个模块，依赖方向照 `backend/CLAUDE.md` §1 严格单向。**每个工程自带一份 types**，
不复用 `bs-types`——改一个常量不该让两个 app 一起重发。对外 `Response<T>{code,info,data}` 在各 `-api/response/`。

包名口径：包根 `com.betterschool.marketing`，**所有层都带域子包**
（`marketing.{layer}.{points,rebate,raffle,inventory}.*`——多域同名类多，物理上必须；抽奖次数在 raffle 域内
落 `raffle.{model.entity,adapter.repository,service.drawcount}` 三段）：

- 域专属（`Constants`、`PointsReasonType`、`DrawRecordEntity`、`IRaffleRepository`…）落
  `marketing.{layer}.<域>.*`；
- 非域公共件（框架装配 / 统一响应 / JWT / MyBatis·事务 / RocketMQ 容器与虚拟线程 / VT 探针 /
  outbox / 跨域 wire 契约 `LotteryDrawnMessage`）落 `marketing.{layer}.common.*`，**只留一份**；
  注意 domain 层的 common 只此一条口径：**不放任何端口与域模型**（端口按消费方域声明）；
- 同名类的 bean 名冲突由 `FqcnAnnotationBeanNameGenerator`（组件扫描与 `@MapperScan` 都挂）解决；
  `@MapperScan` **逐域列出**（`infrastructure.{points,rebate,raffle,inventory}.dao` + `infrastructure.common.dao.outbox`），
  不能扫 `marketing.infrastructure.dao` 这种伪公共前缀。
  **抽奖域自票 13 起只有 `raffle` 一个包段**（旧 `lottery` 段已删）。

> `api` 与 `trigger.http` 层保留 `api.lottery` / `trigger.raffle.http` 的命名——`/api/lottery` 是**前后端契约路径**，
> 票 13 明确要求 URL 不变；域段名（`raffle`）与契约名（`lottery`）不一致是**有意保留的历史包袱**，
> 改名的收益低于动前端契约的成本。


跨 project 取契约只能经**本地仓库**：`com.betterschool:bs-api`（单体暴露的 `IUserQueryService`）。
`com.betterschool:marketing-api` 票 07 起是**同 reactor 内的模块依赖**（不再是跨工程契约）。

## 2. 端口与启动顺序

| app | HTTP | xxl 执行器 appname / port / log-path | Dubbo tri |
| --- | --- | --- | --- |
| better-school（单体） | 8080 | `better-school-executor` / 9999 / `./logs/xxl-job` | 50051 |
| marketing | 8081 | `marketing-executor` / 9998 / `./logs/xxl-job-marketing` | 50052 |

两个 app 同机跑，**上面每一列都必须错开**（xxl 执行器端口撞了会互相顶掉、Dubbo 端口撞了后起的起不来）。
RocketMQ dashboard 在 **8089**（避免与各服务的 HTTP 端口撞）。票 07 起 **8082 / 50053 / 9997 已释放**
（原 `marketing-activity` 的三个口）；超时中心内嵌化后 **8083 / 50054 / 9996 也已释放**。

启动顺序：`docker compose up -d`（含 `bs-nacos`）→ **在 Nacos 建共享配置** → 单体（Dubbo Provider）→ marketing。

顺序不是形式主义：营销服务先起、单体后起时，Dubbo consumer 的目录里还没有 provider，
**第一条请求会以「No provider available」失败**（`@DubboReference(check = false)` 保证的只是「起得来」，
不是「起得快也能调通」）。等 provider 注册上（`Register dubbo service … tri://…:50051` 那行）再打即可。

## 3. Nacos：只有一份共享配置

| DataId | Group | 内容 | 谁读 |
| --- | --- | --- | --- |
| `better-school-shared.yml` | `DEFAULT_GROUP` | `jwt.secret`、`jwt.expire-days` | 两个 app 全读 |

两个 app 都经 `spring.config.import: nacos:better-school-shared.yml?group=DEFAULT_GROUP&refreshEnabled=true`
导入（`bootstrap.yml` / `shared-configs` 均已废弃）。本服务的其余配置（端口 / topic）留在各自本地
`application.yml`，**不拆 per-service dataId**。

- **不加 `optional:`**：共享配置缺了就启动失败，比静默跑丢配置好。
  代价是「本机没起 Nacos，两个 app 全起不来」——该代价由「单体同时是 Dubbo Provider」付掉。
- **建配置**（compose 起来后手动一次，控制台 `http://localhost:8848/nacos` 或 HTTP API）：

  ```bash
  curl -X POST "http://localhost:8848/nacos/v1/cs/configs" \
    --data-urlencode "dataId=better-school-shared.yml" \
    --data-urlencode "group=DEFAULT_GROUP" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=jwt:
    secret: <原来的那一串>
    expire-days: 7"
  ```

- **轮换 `jwt.secret`：改 Nacos 一处，两个 app 同步生效——但要重启。**
  `refreshEnabled=true` 只保证「配置变更事件送达」；`JwtService` 是 `@Value` 注入的，
  不吃热刷新（实测：改完不重启，旧令牌仍 200、新令牌 401；重启后反过来）。要热轮换就给它加 `@RefreshScope`。
- 集成测试不吃外网 Nacos：测试基类对测试专用 Nacos（18848）把这份
  dataId 写进去（见 [../testing.md](../testing.md)）。

## 4. Dubbo：两条边

| 方向 | 接口 | 模块 | Provider | Consumer |
| --- | --- | --- | --- | --- |
| 用户只读 | `IUserQueryService` | `bs-api`（跨 project） | 单体 `bs-trigger/rpc/UserQueryDubboService` | marketing `-infrastructure/common/adapter/port/marketing/UserQueryPort` |
| 积分兑换 | `IPointsExchangeService` | `marketing-api`（**同 reactor 模块**） | marketing `-trigger/points/rpc/PointsExchangeDubboService` | 票 13 起**无 Dubbo consumer**（见下） |

**票 13 把「兑换」从 Dubbo 边折叠成进程内调用**：买抽奖次数是「积分扣分 + 次数入账」，
两域同进程同库，走 RPC 反而要在同进程里做跨「服务」的分布式一致性。票 C（ADR-0016）起
组合事务端口 `IPointsDeductPort` / `PointsDeductPort` 退役，链路改**三段式顺序编排**
（编排落 `marketing-case` 的 `DrawCountPurchaseCase`，每段各自仓储本地事务，无跨域事务）：
① 落单段——购买单 `CREATED`（raffle 仓储，`IRaffleDrawCountPurchaseService.createOrder`），
购买单仍是订单兼幂等键与对账源（ADR-0014 的表形态、唯一键、Stripe 幂等入口全保留）；
② 扣分段——积分域 `deduct`（points 仓储，流水先行，唯一键 `(userId, REDEEM_DRAW_COUNT, 购买单号)`
幂等），资金段先行、余额不足直接失败（单置 DEAD 等充值后原 token 重放自愈）；
③ 推进段——次数入账（桶唯一键幂等）+ 条件更新置 `DONE`（`IRaffleDrawCountPurchaseService.completeOrder`）。
「单在 ⇒ 分已扣」降级为「单在 ⇒ 扣分结果未知、扫描收敛」；收敛靠三路重试踩同一
`hash(userId, token)` 购买单号：客户端带 token 重放（Stripe 语义：DONE 返回原结果、
中间态顺势补齐三段）、xxl `drawCountPurchaseScan` 定时补偿（条件更新抢占 + 退避
1/2/4/8/16 分钟，5 次耗尽进死信；收敛动作 = 三段全量重放，两态分叉被幂等吸收）、
T+1 对账兜底（另开票）。决策依据与否决项见
[`docs/adr/0014-draw-count-purchase-eventual-consistency.md`](../../../docs/adr/0014-draw-count-purchase-eventual-consistency.md)
（其组合事务一条被 ADR-0016 修正）与 [`docs/adr/0016`](../../../docs/adr/0016-tx-boundary-is-repository.md)。
**drawcount 域并入 raffle 后这条边只剩两段**：case 层编排「落单 + 推进」（抽奖域
`IRaffleDrawCountPurchaseService`）与积分域扣分（`IPointsAccountService`），域服务不再互相注入；
抽奖主链路则**只扣次数**（同域 `IDrawCountAccountService.deductForDraw`），从不碰积分。
`IPointsExchangeService` 的 Dubbo Provider 仍在（对外契约不删），但**域内不再有 consumer**；
集成测试用的 `@Primary StubPointsExchangePort` 随之删除。跨域只读的 port 模式照 `backend/CLAUDE.md` §1
（domain 服务禁止互相注入）：抽奖域另有 `IInventoryDeductPort`（库存域，票 12）与
`IQuotaConfirmPort`（库存域兑换确认面，票 14）两条边。

## 4.1 MQ：一个 topic 三方订阅，各用一组

`bs-lottery-drawn`（生产 = 抽奖域，与 `draw_record` 落库**同事务**写 `outbox_message`）：

| 消费方 | 消费组 | 域 | 干什么 |
| --- | --- | --- | --- |
| 积分 | `bs-points-lottery-drawn-group` | 积分（本服务） | `prizeType == VIRTUAL` 时加分（幂等键 `(userId, LOTTERY_WIN, recordId)`） |
| 抽奖 | `bs-raffle-shipping-group` | 抽奖（本服务，票 14） | 发货状态机：虚拟 / 次数 → 已完成；实物 → 已发货 + 配额兑换确认 |
| 通知 | `bs-monolith-lottery-drawn-group` | 单体 | 落中奖通知 |

**同 topic 多方订阅必须各用一组**（同组会抢消息）。组名是域自有常量（`types.<域>.Constants` / `RaffleMqConstants`），
topic 是跨域契约（`types.common.Constants`）。生产 / 消费的字段口径见
`types.common.LotteryDrawnMessage`——三方各只见一半代码，字段改动必须两端同改。

`outbox_message` 重发（票 14）：`outboxScan` 只捞 `state = 0` 且 `update_time` 超过
`outbox.scan.timeout-seconds`（默认 60s）的行，`retry_count` 到 5 置 `state = 2`（死信）；
事务提交后的首次发送走 `afterCommit` 的 `asyncSend`（失败回调经专用标记线程批量落库）。


- 协议显式配 `tri`、每服务独立端口；**不要吃默认的 `dubbo://`**（官方对 new users 的明确建议）。
- **有 Provider 的那个 app 必须配 `dubbo.scan.base-packages`**：starter 只在配了它时才注册
  `ServiceAnnotationPostProcessor`，否则 `@DubboService` 根本不会被打扫到。症状极具迷惑性——
  应用正常启动、`@DubboService` 的 bean 也在，唯独没有任何 `Export dubbo service` 日志，
  consumer 侧报 `No provider available`。只有 marketing 是营销侧 Provider（scan `com.betterschool.marketing`）。
- **`serialize-check-status` 默认 `STRICT`**：未进 allowlist 的 DTO 首次调用直接被拒。
  本地联调统一设 `WARN`（两个 app 的 `application.yml` 里都已配）。
- Dubbo 会**顺带把注册中心当 config-center 用**，所以注册中心连不上会在启动期就抛
  `Failed to create nacos config service client` 直接炸上下文。集成测试因此也必须给它一个真 Nacos
  （见 [../testing.md](../testing.md)）。
- Consumer 一侧 `@DubboReference(check = false)`：**单体没起，营销服务照样能启动**，
  第一次真调用才报不可用。
- **域层与 case 层不认识 Dubbo 注解，也不认识对端的 api DTO**：翻译只在 `infrastructure` 做
  （`UserBasicDTO` ⇄ 域内 `UserBasicVO`）。

## 5. 契约变更纪律（最容易翻车的一处）

消费者解析 `1.0.0-SNAPSHOT` 时，Maven **直接用本地仓库里那份**，不会因为你改了源文件就重解析。
于是「改了 api 模块的接口 → 只 compile 单体 → 起服务」会得到 `NoSuchMethodError` 或反序列化对不上。

**改契约必做的固定前置**：

```bash
# 改了 bs-api / bs-types（在 backend/better-school/ 下执行；-am 连带装上游 bs-types，不能省）
mvn -o install -pl bs-api -am -DskipTests

# 改了 marketing-api（在 backend/marketing/ 下执行）
mvn -o install -pl marketing-api -am -DskipTests
```

装完**重启相关的服务**（改 `bs-api` 要重启 marketing；`marketing-api` 现在同 reactor，改了直接重编即可）。
首次构建若 `-o` 报缺包，去掉 `-o`。两工程完整构建顺序见 [`../README.md`](../README.md)（单体 → marketing）。

排查「到底解析到哪一份」：`mvn dependency:tree -Dincludes=com.betterschool`。
需要强一致时：`mvn -U ...` 或 `mvn dependency:purge-local-repository -Dinclude=com.betterschool:bs-api`。

## 6. 数据面：营销一个库

营销服务一个 schema（与单体**同 MySQL 实例、不同库**）：**`bs_marketing`**（测试 `bs_marketing_test`），
points / rebate / raffle（含抽奖次数）/ inventory 四段同库。跨 schema 的硬约定只对**单体库**成立：
**互相只存 id、不回查单体库、不存昵称头像快照**，用户信息一律经 `IUserQueryService` 取回后在应用层拼。
**同库内跨表展示的数据也一律多查一次应用层拼装**（如抽奖的中奖记录关联 `virtual_inventory_quota` 带出面额与库存），SQL 层零 JOIN。

| 文件 | 用途 |
| --- | --- |
| `marketing/sql/schema.sql` | DDL 主体（不含 `CREATE DATABASE` / `USE`，开发库与测试库共用；18 张表，全部 `IF NOT EXISTS` 幂等；hot-row-merge-l2w256 起为**最终形态**——无两张库存桶表、明细表无 `bucket_id` / `state`；uuid-biz-id 票 08 起存量库退役，历史切换包已删，改库表 = 改 schema.sql + 删库重建） |
| `marketing/sql/init-{dev,test}.sql` | 建库文件：只建 `bs_marketing` / `bs_marketing_test`，**不切库** |
| `marketing/sql/xxl-job-tasks.sql` | xxl 执行器 + 任务注册落点（executor `marketing-executor`，2 handler：`outboxScan`（票 14 起只重发超时行）、`raffleCacheWarmup`；桶过期清零的两个 handler 已下线，过期改纯读路径懒判。表里的 appname/cron 以此为准）；新增/改名 handler 时改这里并重灌 admin 库 |

> 营销侧**尚未接入超时中心**（零接入方；内嵌化票 08 只定规格）：将来落表 / 落码的确定落法，
> 以及它与桶过期清零（已下线）的边界判据，
> 见 [`marketing/docs/architecture/timeout.md`](../../marketing/docs/architecture/timeout.md)。
> 库存合并已整条进进程（hot-row-merge-l2w256，ADR-0013）：无消息、无桶、无兜底，
> `inventoryMergeBackstop` / `inventoryBucketWarmup` 两个注册行在切换窗口停用、D9 收口删除。

> **票 13 删除了 `schema-legacy.sql`**（旧域九张表与旧活动配置行一并退出）：
> 本工程现在**只有 `schema.sql` 一份 DDL**，改表 = 改 `schema.sql` + `scripts/test-env.sh reset-test-db`。
> 同时删掉 `xxl-job-tasks.sql` 里的 `lotteryDeductMerge` / `lotteryDrawCountExpireScan` / `lotteryOutboxScan`
> 三行，以及 `OutboxMessageSender` 的 `lotteryOutboxScan` 别名（合并期的兼容名不再需要）——
> **`xxl_job_info` 里已注册的旧行要由 admin 手工停用/删除**（本脚本幂等重灌不会再建出它，但也不会删旧行）。

建库：`USE` 是连接级的，同一条命令里 `init-{dev,test}.sql`（建库）与 `schema-*.sql`（不含库名）
之间要插一句切库。`scripts/test-env.sh` 的 `apply_service_init <工程sql目录> <建库文件> <库名> <DDL文件>`
就是干这个的，各份 schema 按库名各拼一次；手工等价命令：

```bash
{ cat marketing/sql/init-dev.sql; echo 'USE bs_marketing;'; cat marketing/sql/schema.sql; } \
  | docker exec -i bs-mysql mysql -uroot -pbs123456 --default-character-set=utf8mb4
```

平时直接 `scripts/test-env.sh up`（幂等，建单体两库 + 营销两库 + 超时两库）即可；改过 schema 后用 `scripts/test-env.sh reset-test-db` 重建测试库。

## 7. 前端联调

`front/vite.config.ts` 前缀代理、**一个目标**：`/api/points`、`/api/lottery`
全部 → **8081**（票 07 起两域同属营销服务），**必须排在既有 `/api` 之前**（Vite 按 key 顺序匹配）。
服务端 controller 映射 `/api/<域>/**`、**不 rewrite**。
前端仍是单实例：`http.ts` 的 `baseURL: '/api'` 不变。

各服务探活：`GET /api/<svc>/ping`（需登录），返回经 Dubbo 取回的当前用户基本信息——
它是「Nacos 配置 → JWT 验签 → Dubbo 同步边」整条链路的端到端证人。
