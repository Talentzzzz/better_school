Status: resolved
Type: grilling
Blocked by: 03

# 04: 三个服务的工程骨架与跨服务链路

## Question

拿票 03 的选型结论，把「怎么搭、怎么连」定死并写进 spec。**链路怎么连**归本票，**接口长什么样**归票 06，别重叠。

**① 工程骨架**：三个 project 的目录与模块命名（建议 `backend/points|lottery|referral/`，模块 `<svc>-types/api/domain/case/infrastructure/trigger/app`）、groupId 与基础包名（建议 `com.betterschool.points` 等）、各自 `application.yml` 与端口（建议 8081/8082/8083）、各自 mapper XML 位置、各自启动类。确认「每个服务自带 types」在三个服务里的落地形态。

**② Nacos**：三个服务 + 单体是否都注册；**哪些配置进 Nacos**（至少 `jwt.secret`，轮换只改一处）；**单体是否也迁配置中心**——注意单体已有一整套静态 yml，迁配置中心是独立的大动作，可以只让它注册做服务发现。

**③ Dubbo 契约与服务**：新增 `IUserQueryService` 的方法签名（`getBasic(userId)` + 批量版本，返回昵称 / 头像 / `created_at`）与所在模块；`INotificationService` 复用哪几个方法（若通知改事件订阅，可能一条都不用）；三个服务各自的 port 接口（`IUserQueryPort` / `INotificationPort`）与 Dubbo 实现在 infra 的写法（照 `IWsTicketPort` 的既有形状）。

**④ 事件边的契约**：三个 topic 的命名（`bs-referral-grouped` / `bs-lottery-drawn` / `bs-points-changed`？）；事件体字段（只带 id 还是带快照）；消费者分组命名；每个服务自己的 outbox 表与扫描 job 怎么复制（`outbox_message` + `outboxScan`）；**消费侧幂等键**落在哪张表的哪个唯一键；失败重试与死信口径（`MAX_RETRY_COUNT`）。

**⑤ 通知投递方式**：**推荐事件订阅**——单体作为第四个消费者订阅营销事件后落 `notification` 表，营销侧不需要同步调单体，与「真解耦」一致。备选是营销服务同步 Dubbo 调 `INotificationService`（简单，但把单体拉回成团 / 抽奖的请求路径）。这条要与票 07 的第 ③ 问（通知的 type / refType）对齐。

**⑥ 鉴权与白名单**：三个服务各自的 JWT 校验过滤器（共享 secret）；活动信息 / 奖品目录这类**免登录**读接口在各服务 `SecurityConfig` 的白名单写法；单体侧新增的 Dubbo 服务是否需要 `X-Service-Token`（Dubbo 不走 HTTP，可能不需要）。

**⑦ 本地开发与构建**：4 个 JVM 的启动顺序与端口分配；`docker-compose.yml` 加 Nacos 容器；Vite 三个代理前缀（`/api/points`、`/api/lottery`、`/api/referral`）；前端 axios 实例的划分（三个实例 vs 一个带 baseURL 参数的封装）；构建 / 测试命令（每个 project 独立 `mvn`）；**改契约 → 重装 api jar** 的开发纪律要写成一句话给后续 session 看。

**⑧ 单体侧的改动清单**：新增 Dubbo 提供的 `IUserQueryService` 实现（只读，返回昵称 / 头像 / `created_at`）；新增三个（或四个）事件消费者；**明确不需要**任何反向调用营销服务；不改现有接口的语义。

**⑨ 落在哪里**：本票的结论进 spec 的「营销服务」节；`backend/docs/` 要有对应的架构说明页（照 `backend/CLAUDE.md` §9）。

## Answer

**2026-09-14 收口**：三轮 grilling（9 问）全部采纳助手推荐（第三轮＝ code-review 揪出「兑换边契约」这一漏问后补问）。开工前做了事实自核，**修正票面三处前提**；另有一处**用户反转的基线**（见文末「决策依据」第 4 条）。

### 1. 工程骨架

- 目录：`backend/points/`、`backend/lottery/`、`backend/referral/` —— 三个**平级独立 project**，根都在 `backend/` 下；单体 `backend/pom.xml` 原地不动。
- 每服务 = 一个聚合器 pom（parent 是 `spring-boot-starter-parent:3.3.5`，**不是** `better-school-backend`，与单体无 reactor 关系）+ 7 个模块 `<svc>-types/api/domain/case/infrastructure/trigger/app`，依赖方向照 `backend/CLAUDE.md` §1 严格单向。
- groupId `com.betterschool`；基础包 `com.betterschool.points` / `.lottery` / `.referral`；启动类在各自 `-app` 根包（如 `com.betterschool.points.Application`——必须在根包才能扫到其余模块）。
- **「每个服务自带 types」的落地形态**：各自 `-types` 内自带 `common/Constants`（本服务 topic / redis key 前缀）、`enums/ResponseCode`、`exception/AppException` —— **不复用 `bs-types`**（改一个常量会三个服务一起重发）。对外 HTTP 的 `Response<T>{code,info,data}` 信封放各自的 **`-api/response/`**（照 `backend/CLAUDE.md` §5 与 `bs-api/.../response/Response.java` 的位置），前端 `unwrap` 逻辑不变。
- mapper XML：各自 `-app/src/main/resources/mybatis/mapper/*.xml`，沿用 `classpath:/mybatis/mapper/*.xml`（`backend/CLAUDE.md` §8-3）。三份 mapper 天然隔离（三个独立 jar），不会串。

### 2. Nacos

- **只有一个共享 dataId `better-school-shared.yml`**（`DEFAULT_GROUP`），只放跨服务共享项：`jwt.secret`、`jwt.expire-days`。四个 app 一律 `spring.config.import: nacos:better-school-shared.yml?group=DEFAULT_GROUP&refreshEnabled=true`（`bootstrap.yml` 与 `shared-configs` 均废弃，票 03 已核）。
- **单体也接**（用户拍板）：删本地 `application.yml` 的 `jwt.secret` / `jwt.expire-days` 两行，改同样的 `spring.config.import` ⇒ **jwt.secret 单一来源，轮换只改 Nacos 一处**。代价「没 Nacos 单体起不来」**已由「单体做 Dubbo Provider」付掉**（Dubbo 默认启动即校验注册中心），不额外新增启动耦合。
- **各服务自己的配置留在各自本地 `application.yml`**（数据源 / schema 名 / 端口 / topic / redis database 等），**不拆 per-service dataId**。本期 Nacos 上只有 1 个 dataId，运维面最小；将来要动态刷新再拆。
- 引导信息（`spring.cloud.nacos.server-addr`、`spring.application.name`）必须留**本地**（鸡生蛋，DataId 里还引用了 `${spring.application.name}`）。
- `docker-compose.yml` 新增 `bs-nacos`：镜像 `nacos/nacos-server:v2.5.4`、`MODE=standalone`、端口 `8848` + `9848`、补 `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE` / `NACOS_AUTH_TOKEN` 三个引导变量（2.2.1 起默认值被移除）、**不设 `NACOS_AUTH_ENABLE`**（2.x 默认关鉴权，客户端裸连最省事；`nacos-client 2.4.3` 与 server 2.x 同大版本，避开票 03 记的 namespace bug）。
- **单体只迁共享项，不迁整套静态 yml**：数据源 / redis / rocketmq / minio / xxl-job / ES 全部原地不动。
- **仍采纳 SCA**（票 03 的「转下游 ①」把这条留给本票拍板）：用 `spring-cloud-starter-alibaba-nacos-config` / `-nacos-discovery`，**不**走「自引 `com.alibaba.nacos:nacos-client` 直连 Nacos」的替代路径 —— 保留 `spring.config.import` 集成与 `@RefreshScope` 联动。**已知风险**：SCA 处低维护状态（票 03 记 issue #4009 的社区原话）；若将来停更，替代路径即自引 `nacos-client`（Dubbo 侧本来就走它），届时只失去 SCA 的 Boot 集成。

### 3. Dubbo 契约与服务

- **新增 `IUserQueryService`（放 `bs-api`）**，DTO 也放 `bs-api/dto`：
  - `UserBasicDTO getBasic(Long userId)`
  - `List<UserBasicDTO> getBasicBatch(Collection<Long> userIds)`
  - `UserBasicDTO { Long userId; String nickname; String avatar; LocalDateTime createdAt; }`
  - **为什么不复用 `IUserService.getProfile(Long)`**：实测它返回 `PublicUserProfileResponseDTO`，字段只有 `userId/nickname/avatar/signature/gender/receivedLikeCount/showLiked/showComments` —— **没有 `createdAt`**（拉新判「活动开始后注册」的硬需求），且带三个营销用不到、还要跨表聚合的字段。
- **Provider（单体侧）**：新增 `bs-trigger/rpc/UserQueryDubboService` —— 实现 `IUserQueryService`、标 `@DubboService`、只读。**新增 `rpc/` 子包**（与 `http/job/listener/ws` 并列；Dubbo 不是 HTTP，不塞 `http/`），`backend/CLAUDE.md` §4 补一行。
- **Consumer（三服务侧）**：各服务自己的 port + Dubbo 实现，**域层与 case 层不认识 `bs-api` 类型**：
  - `<svc>-domain/adapter/port/marketing/IUserQueryPort`（返回域自己的类型）
  - `<svc>-infrastructure/adapter/port/marketing/UserQueryPort` —— 持 `@DubboReference(check = false) IUserQueryService`，把 `UserBasicDTO` 映射成域类型
  - `check = false`：单体没起也能启动，兑现票 01 §4 的「停一个服务另一个照跑」（反向同理）。
- **兑换边的 Dubbo 契约**（票 01 §2 定的唯一另一条同步边，票面 ③ 漏问、本票补定）：接口 **`IPointsExchangeService` 放 `backend/points/points-api`**（DTO 同模块），积分服务在 `bs-trigger/rpc/` 实现并标 `@DubboService`；**抽奖服务在 pom 里直接依赖 `com.betterschool:points-api:1.0.0-SNAPSHOT`**，域内定义 `IPointsExchangePort`、infra 用 `@DubboReference(check = false) IPointsExchangeService` 实现。**幂等键 = 兑换单 id**（票 01 §2）。⇒ **「三个营销服务零编译期依赖」由此取消**（见「决策依据」第 4 条）。
- **`-trigger/rpc/` 是新的统一约定**：各 project 的 Dubbo Provider 实现都叫 `XxxDubboService` 放这里（单体 `UserQueryDubboService`、积分 `PointsExchangeDubboService`），与 `http/job/listener/ws` 并列；`backend/CLAUDE.md` §4 各补一行。
- **不需要 `INotificationPort`、不复用 `INotificationService`**：通知改事件订阅（§5），**一条方法都不用**。另外 `INotificationService` 三个方法都依赖 HTTP 登录上下文，本身也不适合 Dubbo。
- Dubbo 版本自管 `3.3.6`，显式配 `dubbo.protocol.name: tri` + 每服务独立 `port`（票 03 建议「new users 从一开始显式配 triple」）。**`serialize-check-status` 默认 STRICT 是首次调用的炸点**：本地联调先把 `cn.betterschool.**` 加进 allowlist（或联调期设 `WARN`）。票 03 的两处未验证组合（SCA × Boot 3.3.5、Dubbo × Boot 3.3.5）**仍是落地票的硬前置冒烟项**。

### 4. 事件边契约

- **topic**（沿用票 01 命名）：`bs-referral-grouped`（成团成功）、`bs-lottery-drawn`（抽奖中奖）、`bs-points-changed`（积分变动）。注：`bs-errand-grouped` 已存在（跑腿拼团成团），不撞名。
- **事件体**只带「id + 消费方决策必需的值」，**不带昵称头像等展示快照**（展示一律走 Dubbo 查用户），消费方不反查生产方 —— 这是「缺一个服务也能跑」的前提：
  - `bs-referral-grouped`：`{ groupId, inviterId, inviteeId }`。**⚠️ 2026-09-14 票 05 反转**：原文带 `bonusPoints`（成团送积分配置，默认 0），用户拍板「**成团只加抽奖次数**」⇒ 该字段与「拉新 → 积分」这条消费边**取消**，本 topic 的消费方只剩**抽奖**与**单体**（见 [票 05](05-marketing-data-model.md) 决策依据第 6 条）
  - `bs-lottery-drawn`：`{ recordId, userId, prizeId, prizeType, pointsAmount }`（`pointsAmount` 仅积分档有意义）
  - `bs-points-changed`：`{ userId, delta, balanceAfter, reasonType, refId }`
- **消费者分组** `bs-<service>-<topic>-group`（票 03 对既有 `bs-<topic>-group` 约定的修订）：单体消费营销事件一律 `bs-monolith-<topic>-group`，如 `bs-monolith-referral-grouped-group`。**理由**：同一 topic 多方订阅时同组会抢消息、不同组各收全量。
- **每个服务自己的 outbox**：各自 schema 一份 `outbox_message`（`msg_id` 唯一、`status 0/1/2`、`retry_count`）+ 各自的 `OutboxMessageStore` / `OutboxMessageSender` / `@XxlJob("outboxScan")`。**handler 名可同名**（各服务是独立 xxl 执行器 appname，不冲突）；`MAX_RETRY_COUNT = 5` 照抄；死信 = `status = 2`（`markDeadBatch`）。
- **消费侧幂等键**（本票只定「**落在哪张表的哪个唯一键**」这一层；**列组合是建议、以票 05 的表设计定稿为准**）：
  - 积分服务 → 积分桶表（`points_lot`，票 05 定为桶化模型），uk `(user_id, reason_type, ref_id)`；`ref_id` = 中奖记录 id（积分档）。**成团送分那条边已由 [票 05](05-marketing-data-model.md) 反转取消**
  - 抽奖服务 → 抽奖次数账本，uk `(user_id, reason_type, ref_id)`，`reason_type = REFERRAL`、`ref_id = groupId`（成团双方各 +1 次）
  - 单体 → `notification.dedup_key`（已是 UNIQUE），形如 `MARKETING:GROUPED:<groupId>` / `MARKETING:DRAWN:<recordId>` / `MARKETING:POINTS:<txId>`
  - 与既有「流水先行 INSERT IGNORE，冲突即幂等跳过」铁律同构；**不额外建 consumed_message 表**。
- **失败重试**：生产侧 broker 自动重试 + outbox 扫描兜底（`outboxScan` 30s，注册形状照 `sql/xxl-job-tasks.sql`）；消费侧解析失败 = log + 丢弃（不重投，既有约定），业务异常 = 抛出交 broker 重试。

### 5. 通知投递方式

**事件订阅**（采用）：单体作为**第四个消费者**订阅三个营销 topic，case 层复用 `NoticeService.sendSystem(...)` 落 `notification` 表 —— 与 `ErrandPaidConsumer → ErrandOrderCase.notifyPaidReview` 完全同构。⇒ 营销侧不同步调单体、不引 Dubbo 通知口；单体停 → 营销照跑。
**边界**：营销通知的 `type` / `refType` 取值（现有 `NotificationTypeVO` 10 值无营销类）**仍归票 07 ③**；本票只定「经事件订阅投递 + dedup_key 形状」。**→ 2026-09-14 票 07 已定**：新增三枚举（`REFERRAL_GROUPED` / `LOTTERY_WON` / `POINTS_CHANGED`）+ `ref_type = MARKETING` + `scope = BOTTOM_SYSTEM`（进底部 system tab，不进市集消息页）。

### 6. 鉴权与白名单

- 每个服务**自带**一份 `JwtAuthenticationFilter` + `JwtService`（照 `bs-infrastructure/.../security/` 的形状，读同一个由 Nacos 下发的 `jwt.secret`）—— 不复用 `bs-infrastructure`（独立 project，无 reactor）。
- 各自 `SecurityConfig` 照单体白名单写法：`.requestMatchers(HttpMethod.GET, "/api/<svc>/**").permitAll()`。~~**具体哪些路径免登录归票 06 ④**（活动信息 / 奖品目录）~~ **票 06 已定**：白名单只 `GET /api/lottery/activity` + `GET /api/lottery/prizes`（见 [票 06 §7](06-marketing-api-contract.md)），本票只定机制。
- **Dubbo 不走 HTTP ⇒ 不需要 `X-Service-Token`**（那是 `bs-api` 的 `/api/internal/**` HTTP 通道的鉴权）。
- **已知取舍**：Dubbo tri 端口是本机明文暴露，本地开发可接受，生产需内网隔离（记录，不在本期做）。

### 7. 本地开发与构建

| app | HTTP | xxl 执行器 appname / port / log-path | Dubbo tri |
| --- | --- | --- | --- |
| 单体 | 8080 | `better-school-executor` / 9999 / `./logs/xxl-job` | 50051 |
| points | 8081 | `better-school-points-executor` / 9998 / `./logs/xxl-job-points` | 50052 |
| lottery | 8082 | `better-school-lottery-executor` / 9997 / `./logs/xxl-job-lottery` | 50053 |
| referral | 8083 | `better-school-referral-executor` / 9996 / `./logs/xxl-job-referral` | 50054 |

- **8082 冲突处理**：`docker-compose.yml` 里 `bs-rocketmq-dashboard` 宿主端口 `8082 → 8089`（一行改动；dashboard 是本地 UI，无其它引用），把 8081/8082/8083 让给三个服务。
- 启动顺序：中间件（`docker compose up`，含新 `bs-nacos`）→ 单体（provider）→ 三服务（consumer 因 `check=false` 不强制在单体之后）。
- Vite 三条代理，**必须排在既有 `/api` 之前**（Vite 按 key 顺序匹配）：

  ```ts
  proxy: {
    '/api/points':   { target: 'http://localhost:8081', changeOrigin: true },
    '/api/lottery':  { target: 'http://localhost:8082', changeOrigin: true },
    '/api/referral': { target: 'http://localhost:8083', changeOrigin: true },
    '/api':          { target: 'http://localhost:8080', changeOrigin: true },
    '/ws/chat':      { target: 'http://localhost:8080', ws: true },
  }
  ```

  三个服务 controller 映射在 `/api/points|lottery|referral/**`，**不 rewrite**。
- 前端**单实例**：保持 `front/src/api/http.ts`（`baseURL: '/api'`），新增 `api/marketing.ts` 等直接调 `httpGet('/points/…')`。
- 构建 / 测试：每 project 独立 `mvn`（单体在 `backend/`、三服务在各自目录）。**契约变更纪律一句话**：`改 bs-api / bs-types → 先在 backend/ 执行 mvn -o install -pl bs-api -am -DskipTests 重装 api jar，再重启三个服务`（票 03 §4.3 第 1 条）；**改 `points-api`（兑换契约）同理** —— 在 `backend/points/` 执行 `mvn -o install -pl points-api -am -DskipTests`，再重启抽奖服务。

### 8. 单体侧改动清单

- 新增 `bs-trigger/rpc/UserQueryDubboService`（`@DubboService`，只读，返回昵称 / 头像 / `createdAt`）。
- 新增三个事件消费者（`bs-trigger/listener/`）：`ReferralGroupedConsumer` / `LotteryDrawnConsumer` / `PointsChangedConsumer`，各落一条 notification。
- 加 Dubbo 依赖（`dubbo-spring-boot-starter` + `dubbo-nacos-spring-boot-starter:3.3.6`）+ SCA nacos-config 依赖；`application.yml` 删 `jwt.*` 两行、加 `spring.config.import` 与 `dubbo.*`。
- POM 照抄既有形状：rocketmq 的 `commons-logging` exclusion、`xxl-job-core` 沿用 `3.0.0`、Redisson 沿用 `spring.data.redis.*`。
- **明确不做**：任何反向调用营销服务（否则成环）；不改任何现有接口语义。

### 9. 落在哪里

- 本票结论进 **spec 的「营销域」节**（节名与索引行对齐；票面 §⑨ 原写「营销服务」，已统一为「营销域」），**节内带一个指向 [research-cloud-stack.md](../research-cloud-stack.md) 的指针**（票 03「转下游 ④」明写的要求）。表结构 / 接口口径 ~~分别待票 05 / 06 追加~~ **已分别由票 05 / 06 追加**。
- `backend/docs/` 的架构说明页：**内容**（本票决策）进 spec；**落到 `backend/docs/` 的动作归落地票** —— 本 effort 只产决策与 spec（map 明写「不落地代码」），此处若先写文档即是在描述尚不存在的代码。**落地面**＝`backend/docs/architecture/marketing-services.md`（照 `backend/CLAUDE.md` §9「像代码一样短」），五节：① 四个 JVM 与端口表 ② Nacos（1 个共享 dataId + 注册）③ Dubbo 契约（`IUserQueryService` / `IPointsExchangeService`，谁提供谁消费）④ 三条事件边（topic / 事件体 / 消费组 / outbox / 幂等键）⑤ 本地启动与「改契约先装 jar」纪律；`backend/docs/README.md` 索引加一行。

### 决策依据（事实自核，三处修正票面前提）

1. `IWsTicketPort` 实测是**纯 Spring DI**（domain 定义接口 / infra 实现 / 类名去 `I`），**不是 Dubbo 形状** —— 票面「照 `IWsTicketPort` 的既有形状」= 照这个**分层**；Dubbo 注解是本票新增的机制（全仓 0 处 `@DubboService` / `@DubboReference`）。
2. `IUserService.getProfile(Long)` 实测返回 `PublicUserProfileResponseDTO`，**无 `createdAt`** → 必须新增精简 `IUserQueryService`，不能复用（详见 §3）。
3. `bs-errand-grouped` topic **已存在**（跑腿拼团成团），票面建议的 `bs-referral-grouped` 不撞名；另全仓 18 个 consumer 一律 `bs-<topic>-group`，`bs-<service>-<topic>-group` 是对既有约定的修改。
4. **用户在本票 grill 中反转了 票 01 §4 的一条基线**（不是票面前提有错，是用户改主意）：原文「三个营销服务**零编译期依赖**」**取消**。用户原话：「抽奖直接依赖积分服务的 api jar，这种依赖可以成立，因为积分能兑换抽奖次数，可以说没有积分服务，抽奖服务也工作不了，所以这俩可以依赖；把票 01 §4『三个营销服务零编译期依赖』这个去掉，必要时可以依赖」。已同步改 [票 01 Answer §4](01-baseline-decisions.md)（留反转注记）；生效形态见 §3 的兑换边。「零互相注入」（不跨服务注入 Spring bean）不受影响。

### 转下游

- **票 05**：三个 schema 的表与 DDL（含本票 §4 的幂等键落点）、时序、迁移编号 022 起。
- **票 06**（**已 resolved**）：接口清单与免登录白名单的具体路径（本票 §6 只定机制）—— 见 [票 06 Answer](06-marketing-api-contract.md)。
- ~~**票 07 ③**：营销通知的 `type` / `refType` 枚举取值（本票 §5 只定投递机制）。~~ **2026-09-14 票 07 已定**：新增三枚举 + `ref_type = MARKETING` + `scope = BOTTOM_SYSTEM`。
- **落地票**（`.scratch/marketing-impl/`）：`backend/docs/` 架构页、`backend/CLAUDE.md` §4 补 `rpc/` 子包、docker-compose 加 Nacos 容器 + dashboard 端口 `8082 → 8089`。
