# 营销域运行手册（单体收口后）

> 归属：营销域（points / raffle / rebate / inventory 四域，monolith-restructure 票 05/06/07 起已并入单体）。
> 需求以 `.scratch/marketing/spec.md` 为准，栈选型论证见 `.scratch/marketing/research-cloud-stack.md`。
> 本文只写「怎么跑起来、怎么改不翻车」。原「两个平级工程、营销独立 app」的口径已退役。

## 1. 工程形状

单体收口后 `backend/` 下只有**一个 Maven reactor**：

```
backend/
└── better-school/           单体工程（bs-* 七模块，聚合器 pom artifactId = better-school；超时中心已内嵌在此工程）
    └── bs-{types,api,domain,case,infrastructure,trigger,app}
```

营销四域以**平级领域包**并入 `com.betterschool`：`domain/{points,raffle,rebate,inventory}`、
`infrastructure` 侧对应域的 adapter/dao、`trigger/{points,raffle,config}`、`case` 层编排类。
`com.betterschool.marketing` 命名空间**已退役**；`marketing-api` 模块与营销独立 app 不复存在，
对外契约只剩 `bs-api`。HTTP 路径前缀不变（`/api/points`、`/api/lottery`、`/api/marketing/*`——
**前后端契约路径不因合码而改**），域段名（`raffle`）与契约名（`lottery`）不一致仍是有意保留的历史包袱。

沿革（营销域自身）：票 06 并入 points、票 07 并入 lottery 旧域、票 10 加 drawcount 与 config、
票 12 加 inventory、票 13 删 lottery 旧域换成 raffle 并吸收抽奖次数；config 域拆掉（两张有效期表跟表走，
`points_validity_config` 归 points、`action_rebate_config` 归 rebate）；drawcount 并入 raffle。

装配要点（同名类合流后的兜底与扫描口径）：

- bean 名冲突由全局 `FqcnAnnotationBeanNameGenerator`（组件扫描与 `@MapperScan` 都挂，`bs-app/config/`）解决；
  注入一律按类型，改名不影响装配。
- `@MapperScan` 单基包 `com.betterschool.infrastructure.dao`（基包递归扫子包，单体平铺域与营销并入域共用）。
- `MarketingApplication` 与 `bs-app` 的重复 config 六对已逐对归并进 `bs-app/config/`（票 04 拍板：同职责一律归并，
  不留改名共存）。

分层依赖方向照 [`../../CLAUDE.md`](../../CLAUDE.md) §1 严格单向；infra 包结构照 §3
（`adapter/{port, repository}` + 平级技术包，技术包→adapter 禁止反向依赖）。

## 2. 端口与启动顺序

| app | HTTP | xxl 执行器 appname / port / log-path | Dubbo tri |
| --- | --- | --- | --- |
| better-school（单体，唯一 app） | 8080 | `better-school-executor` / 9999 / `./logs/xxl-job` | 50051 |

原营销独立 app 的 **8081 / 50052 / 9998 已释放**（更早的 8082 / 50053 / 9997、超时中心的
8083 / 50054 / 9996 早已释放）。RocketMQ dashboard 在 **8089**（避免与 HTTP 端口撞）。

启动顺序：`docker compose up -d`（含 `bs-nacos`）→ **在 Nacos 建共享配置** → 单体（Dubbo Provider）。

## 3. Nacos：只有一份共享配置

| DataId | Group | 内容 | 谁读 |
| --- | --- | --- | --- |
| `better-school-shared.yml` | `DEFAULT_GROUP` | `jwt.secret`、`jwt.expire-days` | 单体 app |

单体经 `spring.config.import: nacos:better-school-shared.yml?group=DEFAULT_GROUP&refreshEnabled=true`
导入（`bootstrap.yml` / `shared-configs` 均已废弃）。其余配置留在本地 `application.yml`。

- **不加 `optional:`**：共享配置缺了就启动失败，比静默跑丢配置好。
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

- **轮换 `jwt.secret`：改 Nacos 一处，重启生效。** `refreshEnabled=true` 只保证「配置变更事件送达」；
  `JwtService` 是 `@Value` 注入的，不吃热刷新（实测：改完不重启，旧令牌仍 200、新令牌 401；
  重启后反过来）。要热轮换就给它加 `@RefreshScope`。
- 集成测试不吃外网 Nacos：测试基类对测试专用 Nacos（18848）把这份
  dataId 写进去（见 [../testing.md](../testing.md)）。

## 4. Dubbo：现状两条 Provider 保留，进程内调用为主

单体收口后，营销域与单体其他域同进程同库，跨域动作一律**进程内顺序编排**（case 层，无跨域事务）。
Dubbo 边只剩历史契约的 Provider，**现状保留、不再新增 consumer**：

| 接口 | 模块 | Provider | Consumer |
| --- | --- | --- | --- |
| 用户只读 | `IUserQueryService` | `bs-trigger/rpc/UserQueryDubboService` | 无（原营销侧 Dubbo consumer 随合并退役） |
| 积分兑换 | `IPointsExchangeService` | `bs-trigger/points/rpc/PointsExchangeDubboService` | 无（票 13 起已折叠成进程内调用） |

用户读域内口径：合并前营销域经 Dubbo 读单体用户中心；单体回归后该同步边退役——
探活 `/api/points/ping` 改为进程内直调 `IUserRepository`，chat / community 域各自的
`IUserQueryPort` 也是进程内实现（`infrastructure/adapter/port/<域>/UserQueryPort`）。

购买抽奖次数链路（票 C，ADR-0016）：组合事务端口 `IPointsDeductPort` / `PointsDeductPort` 退役，
链路改**三段式顺序编排**（编排落 bs-case 的 `DrawCountPurchaseCase`，每段各自仓储本地事务，无跨域事务）：
① 落单段——购买单 `CREATED`（raffle 仓储）；② 扣分段——积分域 `deduct`（流水先行，唯一键
`(userId, REDEEM_DRAW_COUNT, 购买单号)` 幂等，资金段先行、余额不足单置 DEAD 等原 token 重放自愈）；
③ 推进段——次数入账（桶唯一键幂等）+ 条件更新置 `DONE`。收敛三路：客户端带 token 重放、
xxl `drawCountPurchaseScan` 定时补偿（条件更新抢占 + 退避，5 次耗尽进死信；收敛动作 = 三段全量重放）、
T+1 对账兜底（另开票）。决策依据见
[`docs/adr/0016-tx-boundary-is-repository.md`](../../../docs/adr/0016-tx-boundary-is-repository.md)。

Dubbo 自身仍要留意的三条（单体自身作为 Provider / 测试里的 consumer 形态仍适用）：

- 协议显式配 `tri`；**不要吃默认的 `dubbo://`**。
- **有 Provider 的 app 必须配 `dubbo.scan.base-packages`**：starter 只在配了它时才注册
  `ServiceAnnotationPostProcessor`，否则 `@DubboService` 根本不会被打扫到。症状极具迷惑性——
  应用正常启动、bean 也在，唯独没有任何 `Export dubbo service` 日志。
- **`serialize-check-status` 默认 `STRICT`**：未进 allowlist 的 DTO 首次调用直接被拒。
  本地联调统一设 `WARN`（`application.yml` 已配）。Dubbo 会顺带把注册中心当 config-center 用，
  注册中心连不上会在启动期直接炸上下文——集成测试必须给它一个真 Nacos（见 [../testing.md](../testing.md)）。

## 4.1 MQ：一个 topic 三方订阅，各用一组

`bs-lottery-drawn`（生产 = 抽奖域，与 `draw_record` 落库**同事务**写 `outbox_message`）：

| 消费方 | 消费组 | 域 | 干什么 |
| --- | --- | --- | --- |
| 积分 | `bs-points-lottery-drawn-group` | points | `prizeType == VIRTUAL` 时加分（幂等键 `(userId, LOTTERY_WIN, recordId)`） |
| 抽奖 | `bs-raffle-shipping-group` | raffle | 发货状态机：虚拟 / 次数 → 已完成；实物 → 已发货 + 配额兑换确认 |
| 通知 | `bs-monolith-lottery-drawn-group` | notification | 落中奖通知 |

**同 topic 多方订阅必须各用一组**（同组会抢消息）。组名是域自有常量，topic 是跨域契约
（`types.common.Constants`）。生产 / 消费的字段口径见 `types.common.LotteryDrawnMessage`。

> 票 07 边界拍板：这两个撞名 topic（`bs-lottery-drawn` / `bs-points-changed`）**保留现状**
> （零配置冲突）；MQ 链路进程内化 = 代码语义变更，归后续 effort。

`outbox_message` 重发：`outboxScan` 只捞 `state = 0` 且 `update_time` 超过
`outbox.scan.timeout-seconds`（默认 60s）的行，`retry_count` 到 5 置 `state = 2`（死信）；
事务提交后的首次发送走 `afterCommit` 的 `asyncSend`（失败回调经专用标记线程批量落库）。

## 5. 契约变更纪律（最容易翻车的一处）

消费者解析 `1.0.0-SNAPSHOT` 时，Maven **直接用本地仓库里那份**，不会因为你改了源文件就重解析。
于是「改了 api 模块的接口 → 只 compile → 起服务」会得到 `NoSuchMethodError` 或反序列化对不上。

**改契约必做的固定前置**：

```bash
# 改了 bs-api / bs-types（在 backend/better-school/ 下执行；-am 连带装上游 bs-types，不能省）
mvn -o install -pl bs-api -am -DskipTests
```

装完**重启服务**。首次构建若 `-o` 报缺包，去掉 `-o`。完整构建见 [`../README.md`](../README.md)。

排查「到底解析到哪一份」：`mvn dependency:tree -Dincludes=com.betterschool`。
需要强一致时：`mvn -U ...` 或 `mvn dependency:purge-local-repository -Dinclude=com.betterschool:bs-api`。

## 6. 数据面：单库 better_school

营销四域与单体**同库**：`better_school`（测试 `better_school_test`）。营销 22 张表
（积分 / 次数桶、配置、抽奖、库存两段式、outbox）随票 07 并入
`better-school/sql/schema.sql` **尾段**；该文件是全库**唯一 DDL 事实源**（79 张表、单份
`outbox_message` DDL——取 marketing 超集形状，含 `delay_level` / `delay_ms`）。
`bs_marketing` / `bs_marketing_test` 库名退役，`marketing/sql/` 四件（init-dev / init-test /
schema / xxl-job-tasks）已删除；xxl 注册收敛为单体唯一 seed `better-school/sql/xxl-job-tasks.sql`。

| 文件 | 用途 |
| --- | --- |
| `better-school/sql/schema.sql` | DDL 主体（不含 `CREATE DATABASE` / `USE`，开发库与测试库共用；全部 `IF NOT EXISTS` 幂等；改库表 = 改 schema.sql + 删库重建） |
| `better-school/sql/init-{dev,test}.sql` | 建库文件：只建 `better_school` / `better_school_test` |
| `better-school/sql/xxl-job-tasks.sql` | xxl 执行器 + 任务注册落点（executor `better-school-executor`；营销任务已并入，`marketing-executor` 组退役；新增/改名 handler 时改这里并重灌 admin 库） |

跨域纪律**不因合库改变**（ADR-0016）：一个物理事务只写一个仓储（一个域）的表，跨域一致性 =
两段各自本地事务 + 资金先行 + 流水唯一键幂等 + 孤儿扫描补偿（`drawCountPurchaseScan` /
`errandFundOrphanScan` / `productFundOrphanScan` 口径）。表所有权原则也照旧：用户表归 user 域
独占写，营销域只存 `user_id`、读经消费方域的 port（进程内实现）或 case 层编排直调用户域。

同库内跨表展示的数据（如抽奖的中奖记录关联 `virtual_inventory_quota` 带出面额与库存）
仍**多查一次应用层拼装**，SQL 层零 JOIN。

建库：`USE` 是连接级的，同一条命令里建库文件与 schema 之间不用再切库（两文件都已指向
`better_school`）；平时直接 `scripts/test-env.sh up` 或 Windows 上
`powershell -File scripts/dev-stack.ps1 up`（幂等；改过 schema 用 `-ResetDb` 或
`reset-test-db` 重建）。

> 营销侧**尚未接入超时中心**（零接入方；内嵌化票 08 只定规格）：将来落表 / 落码的确定落法，
> 见 [`../architecture/timeout.md`](timeout.md)。
> 库存合并已整条进进程（hot-row-merge-l2w256，ADR-0013）：无消息、无桶、无兜底，
> 两个库存引擎（计数 `merge/count`、库存 `merge` 根）两套并存、功能不同不归并，
> 见 [`../marketing/architecture/merge.md`](../marketing/architecture/merge.md)。

## 7. 前端联调

`front/vite.config.ts` 单条 `/api` 代理 → **8080**（营销前缀分流规则已随单体收口删除；
Vite 按 key 顺序匹配，只剩 `/api` 与 `/ws` 两条）。服务端 controller 映射 `/api/<域>/**`、
**不 rewrite**。前端单实例：`http.ts` 的 `baseURL: '/api'` 不变。

探活：`GET /api/points/ping`（需登录），返回当前登录用户基本信息——
它是「Nacos 配置 → JWT 验签 → 用户域查询」整条链路的端到端证人
（合并前经 Dubbo 跨服务查用户中心，单体回归后改进程内直调 `IUserRepository`）。
