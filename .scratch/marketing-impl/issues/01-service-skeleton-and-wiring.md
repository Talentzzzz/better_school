# 01: 三服务工程骨架与本地链路贯通

**Spec:** `.scratch/marketing/spec.md` §「营销域」→「服务拆分与工程骨架」「事件边契约」「同步边（Dubbo）」「Nacos」「鉴权」「本地开发与构建」「单体侧改动清单」

**What to build:** 积分 / 抽奖 / 拉新三个营销服务从零建起来（`backend/` 下的平级独立 project，与单体无 reactor 关系），接上 Nacos 配置中心与 Dubbo 同步边，单体新增只读的 `IUserQueryService` Provider。做完之后四个 app 能同时启动、改 Nacos 一处 `jwt.secret` 四处生效、三个服务能经 Dubbo 从单体取到用户基本信息、前端经 Vite 三个前缀代理能打到三个服务。

这是后续全部营销票的地基（**先做前置重构，再做容易的改动**）；也是本 effort 风险最高的一票——票 03 的两处「必须冒烟验证」在这里兑现。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] `backend/points/` `backend/lottery/` `backend/referral/` 三个独立 project，各 7 模块（`<svc>-types/api/domain/case/infrastructure/trigger/app`）；parent 直接 `spring-boot-starter-parent:3.3.5`；groupId `com.betterschool`、基础包 `com.betterschool.<svc>`、启动类在各 `-app` 根包
- [x] 依赖方向照 `backend/CLAUDE.md` §1 严格单向；单体 `backend/pom.xml` 原地不动、与三服务无 reactor
- [x] 每服务**自带** `-types`（`common/Constants` 放本服务 topic 与 redis key 前缀、`enums/ResponseCode`、`exception/AppException`），**不复用 `bs-types`**；对外 `Response<T>{code,info,data}` 信封放各自 `-api/response/`
- [x] mapper XML 在各自 `-app/src/main/resources/mybatis/mapper/`
- [x] `docker-compose.yml` 新增 `bs-nacos`（standalone，`8848` + `9848`，补三个 `NACOS_AUTH_*` 引导变量、不设 `NACOS_AUTH_ENABLE`）
- [x] **只 1 个共享 dataId** `better-school-shared.yml`（`jwt.secret` / `jwt.expire-days`）；四个 app 经 `spring.config.import: nacos:` 导入（不用废弃的 `bootstrap.yml` / `shared-configs`）；**单体也接**并删本地 `jwt.*` 两行
- [x] `bs-api` 新增 `IUserQueryService`（`getBasic(Long)` + `getBasicBatch(Collection<Long>)`）与 `UserBasicDTO { userId, nickname, avatar, createdAt }`；**不复用 `IUserService.getProfile`**
- [x] 单体 Provider 落 `bs-trigger/rpc/UserQueryDubboService`（`@DubboService`，只读，新建 `rpc/` 子包）；`backend/CLAUDE.md` §4 补一行
- [x] 三服务各 `-domain/adapter/port/marketing/IUserQueryPort` + `-infrastructure/.../UserQueryPort`（`@DubboReference(check = false)`，映射成域类型）；**域层与 case 层不认识 `bs-api` 类型**
- [x] Dubbo `3.3.6` 显式配 `tri` 协议 + 每服务独立端口；`serialize-check-status` 已调通（设 `WARN`）
- [x] 三服务各带一份 `JwtAuthenticationFilter` + `JwtService`（读同一个 Nacos 下发的 `jwt.secret`）+ `SecurityConfig`（白名单写法照单体；本票只需能起，具体白名单随各服务接口票加）
- [x] 端口按 spec 表落：HTTP `8081/8082/8083`、xxl 执行器 appname `better-school-<svc>-executor` / port `9998/9997/9996` / log-path `./logs/xxl-job-<svc>`、Dubbo tri `50052/50053/50054`、单体 tri `50051`
- [x] `bs-rocketmq-dashboard` 宿主端口改 `8082 → 8089`
- [x] Vite 三条代理 `/api/points → 8081`、`/api/lottery → 8082`、`/api/referral → 8083`，**必须排在既有 `/api` 之前**（按 key 顺序匹配）；controller 映射 `/api/<svc>/**`、**不 rewrite**
- [x] 三个服务各落一条**仅用于本票验收的探活接口**（`GET /api/<svc>/ping`，返回经 Dubbo 取到的当前用户基本信息）
- [x] 四个 app 同时启动无报错；经 Vite 代理分别打到三个探活接口并拿到真实昵称
- [x] 改 Nacos 的 `jwt.secret`，四个 app 同步生效（一处供给）
- [x] **两处「必须冒烟验证」实跑通过**（SCA 2023.0.3.4 × Boot 3.3.5、Dubbo 3.3.6 × Boot 3.3.5）
- [x] **契约变更纪律**写进文档（`backend/docs/architecture/marketing-services.md` §5）

## 实现要点（票 01）

### 落了什么

三个独立 project（各 7 模块）+ 单体侧 Dubbo Provider + Nacos 配置中心贯通。文件分布见 `git status`：
`backend/{points,lottery,referral}/` 各 26 个文件，单体侧 6 个新文件 + 13 个改动文件。

- **`backend/CLAUDE.md`**：§4 加 `rpc/` 一行，并补一段「Provider 在 `trigger/rpc/`、Consumer 的 `@DubboReference`
  在 `infrastructure/adapter/port/`、域层与 case 层不认识 Dubbo 与对端 DTO」的对称说明。
- **`backend/docs/architecture/marketing-services.md`**（新建）：工程形状、端口表、启动顺序、Nacos 单 dataId、
  Dubbo 两条边、**契约变更纪律**、前端联调。
- **`backend/docs/testing.md`**：新增「Nacos / Dubbo 在测试里的三条特殊处理」一节。
- **`bs-api`**：`IUserQueryService` + `UserBasicDTO`（`implements Serializable` + `serialVersionUID`）。
  宽松语义——`getBasic` 查不到返回 `null`、`getBasicBatch` 跳过不存在的 id，**不抛 404**（一个脏 id 不该打挂批量调用）。
- **`bs-domain`**：`IUserProfileService.listBasic(Collection<Long>)`（复用既有 `IUserRepository.listByIds`）。
- **`.gitignore`**：补 `backend/*/*/target/` 与 `backend/*/logs/`——原来的 `backend/*/target/` 只覆盖一层，
  三个新 project 的构建产物会被误纳入（实测漏了 45 个 `.class`）。

### 两处「必须冒烟验证」——都实跑通过

| 组合 | 证据 |
| --- | --- |
| SCA `2023.0.3.4` × Boot `3.3.5` | 四个 app 启动日志均有 `[Nacos Config] Listening config: dataId=better-school-shared.yml, group=DEFAULT_GROUP`；Nacos 里改 `jwt.secret` 后重启，四个 app **同时**从「旧令牌 200 / 新令牌 401」翻成「旧令牌 401 / 新令牌 200」 |
| Dubbo `3.3.6` × Boot `3.3.5`（Spring 6.1.x） | 单体 `Register dubbo service com.betterschool.api.IUserQueryService url tri://…:50051 … to registry 127.0.0.1:8848`；三个服务 `GET /api/<svc>/ping` 经 Dubbo 取回真实昵称（`{"userId":5,"nickname":"Talent","createdAt":"2026-08-31T01:18:34"}`），`LocalDateTime` 跨 hessian2/tri 序列化正常 |

**没有一项需要回头找用户**：两个组合都直接跑通。

### 落地中发现、票面没写但必须做的三件事

1. **`dubbo.scan.base-packages` 不能省**（单体 `application.yml`）。Dubbo 的 Boot starter **只在配了这个键**时才注册
   `ServiceAnnotationPostProcessor`；不配的症状极具迷惑性——应用正常启动、`@DubboService` 的 bean 也在，
   但**一条 `Export dubbo service` 日志都没有**，consumer 侧报 `No provider available`。
   已写进 `marketing-services.md` §4。
2. **`spring-cloud-starter-alibaba-nacos-config` 必须落在四个 app 的 classpath 上**（单体 `bs-app` + 三个 `<svc>-app`）。
   首次漏了三个服务，报的是 `Incorrect ConfigDataLocationResolver chosen`（`nacos:` 前缀落回
   `StandardConfigDataLocationResolver`），看不出缺依赖。
3. **Dubbo 顺带拿注册中心当 config-center**：注册中心连不上会在**启动期**抛
   `Failed to create nacos config service client` 直接炸上下文（不是"能用就行"的可选项）。集成测试因此也必须给它真 Nacos。

### code-review 采纳项

两轴（Standards / Spec）各出一批，采纳如下：

1. **生成残留（Spec 轴，HARD）**：`lottery/pom.xml` / `referral/pom.xml` 的注释被重命名脚本改成了
   `ILotteryExchangeService` / `IReferralExchangeService`——**兑换边的契约叫 `IPointsExchangeService`**，
   且拉新根本不参与兑换边。已改为按服务说清「为什么依赖 `bs-api`」。三个服务的 Constants 注释
   （topic 语义、redis 前缀的邻居服务名）同步逐服务改正。
2. **域类型外溢到 HTTP 契约（Spec 轴）**：`PingController` 原来直接返回域内 `UserBasicVO`，
   违反 `backend/CLAUDE.md` §5（api 层放 DTO）。已补 `<svc>-api/dto/UserBasicResponseDTO`，触发层做映射。
3. **硬编码响应码（Standards 轴）**：三个 `SecurityConfig` 的 401/403 从 `Response.fail("401", "未登录或登录已过期")`
   改用 `-types` 里已有的 `ResponseCode.UNAUTHORIZED` / `FORBIDDEN`（原来是有常量不用、写死字面量，且已复制 4 份）。
4. **死代码（Standards 轴）**：三个服务的 `JwtService` 删掉 `generateToken` / `expireMillis` / `jwt.expire-days` 入参——
   营销侧只验签不签发，`generateToken` 零调用者。Javadoc 改成显式说明「只验签、不签发」。
5. **注释措辞**：`SecurityConfig` 类注释原写「只有 lottery 的两个 GET 会加」，在 referral 的副本里是错的，
   改成服务中性的说法。
6. **格式**：`backend/CLAUDE.md` 新增段落与 `## 5.` 之间补空行。

未采纳（记录理由）：

- **`AppException` 形状与 `backend/CLAUDE.md` §7 的代码片段不一致**：§7 那段是示意（`@Data`/非 final 字段/无 `super(info)`），
  仓库**实际的** `BusinessException` 也没照它写（`@Getter` + final + `super(message)`）。本票跟的是实际代码，不是片段。
- **三服务之间 JWT/Security/Exception/Response 整套重复**：这是「每服务自带 types、不共享 `bs-types`」的直接代价，
  也是用户选独立 project 时接受的代价（改一个常量不该三个服务一起重发）；`marketing-services.md` §1 已写明。
- **`dubbo.version` / `spring.cloud.*` 在 4 个 pom 里各写一份**：无 reactor、无共享 parent 的必然结果，同上。
- **`SecurityUtils.currentRole()` 在三个服务里暂无调用者**：票 07 的 `POST /api/lottery/buckets/open` 要 ADMIN 校验；
  三个服务保留同一份与单体同形的安全组件，好过让三份各自长歪。
- **`getBasic` / `getBasicBatch`（api+port）与 `listBasic`（domain）命名不齐**：`listBasic` 跟的是
  `IUserRepository` 既有的 `listByIds` / `listAllUserIds` 命名族。

### 已知波及 / 不在本票范围

- **集成测试设施被迫改动**（用户拍板「严格 `nacos:` 导入」的直接后果）：`CommunityApiTestBase` 新增 Nacos Testcontainer
  （`nacos/nacos-server:v2.5.4`，与 compose 同镜像），启动前把 `better-school-shared.yml` 写进去。
  三处非显然处理记在 `backend/docs/testing.md`：Nacos 地址走 `System.setProperty`（`spring.config.import` 在 config data
  阶段解析，`@DynamicPropertySource` 那时还没挂上）、gRPC 端口 offset、Dubbo 的注册中心与 tri 端口都要覆盖
  （否则本机开着 IDE 里的单体时 `Address already in use`）。**全量 323 个测试通过**。
- **三个服务的 `SecurityConfig` 白名单为空**（`anyRequest().authenticated()`）：票面明说「本票只需能起，白名单随各服务接口票加」。
  后果是 `GET /api/lottery/activity` / `prizes` 现在会 401，票 07 落地时必须补上 spec §鉴权 的那两条。
- **三个服务没有 DB / Redis / MQ 依赖**：本票的验收不含任何持久化，三个 schema 与 outbox 由票 05/06/07 建；
  同理 `-trigger/rpc/` 目前三个服务都是空包（points 的 `PointsExchangeDubboService` 是票 08）。
- **探活接口 `GET /api/<svc>/ping` 保留**（map 的「Not yet specified」要求本票定）：它是「Nacos 配置 → JWT 验签 →
  Dubbo 同步边」整条链路的端到端证人，任何一环断了直接报错，比 `/actuator/health` 更能说明问题；后续票改契约后可继续拿它自检。
- **`refreshEnabled=true` 对 `jwt.secret` 是惰性的**：`JwtService` 是构造器 `@Value` 注入，不吃热刷新。
  实测「改 Nacos 不重启 → 旧令牌仍 200」；轮换要重启（或用 `@RefreshScope`）。已写进文档，未改实现。
- **Nacos 是四个 app 的启动硬前置**：`better-school-shared.yml` 不存在则四个 app 全起不来。
  建配置的命令写在 `docker-compose.yml` 头部注释与 `marketing-services.md` §3。
- **启动顺序有实际后果**：三个服务先起、单体后起时，Dubbo consumer 目录里还没 provider，**第一条请求会失败**；
  `@DubboReference(check = false)` 保证的是「起得来」，不是「谁先起都调得通」。
