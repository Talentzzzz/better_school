Status: resolved
Type: research
Blocked by:

# 03: 微服务栈选型与接入成本（Spring Cloud Alibaba + Dubbo + Nacos on Boot 3.3.5 / Java 21）

## Question

在本仓现有条件下把三个服务接起来，需要落定的事实。**AFK 票**，由 research 子代理查一手来源，每条结论带出处链接。

**① 版本选型（最要紧）**：Spring Boot **3.3.5** + Java **21** 下——Spring Cloud Alibaba 用哪个版本、它对应 Spring Cloud 哪个版本、Dubbo 用 3.x 的哪个版本、Nacos **server** 用 2.x 还是 3.x。给兼容矩阵与官方出处。**注意 `backend/pom.xml` 已固定 Boot 3.3.5，不能为了上云抬版本**。

**② Nacos 接入**：`spring-cloud-starter-alibaba-nacos-discovery` / `-config` 的坐标；配置从 Nacos 拉的写法（`spring.config.import` 还是 bootstrap，Boot 3.x 的口径）；**三个服务 + 单体共享同一份 `jwt.secret`** 的配置组织方式；本地 docker-compose 加 Nacos server 容器的镜像 / 端口 / 认证（现有编排：MySQL 9.7、Redis 8.8、RocketMQ 5.5、MinIO、xxl-job-admin、ES，统一密码 `bs123456`，见 `backend/docker-compose.yml`）。

**③ Dubbo 接入**：`@DubboService` / `@DubboReference` 的依赖与配置（注册中心指向 Nacos）；**消费方依赖 provider 的 api jar**（本仓即 `bs-api`，`bs-api` 只依赖 `bs-types`，是天然的契约模块）的可行性与注意事项；provider（单体）与三个 consumer 各自的配置样例；Spring Boot 3.3.5 下的坑（`dubbo-spring-boot-starter` 版本、Triple vs dubbo 协议、序列化白名单、是否要与 `spring-cloud-starter-alibaba` 版本对齐）。

**④ 独立 project 的依赖纪律**：跨 project 依赖本地仓库的写法与命令（`mvn -o install -pl bs-api -am -DskipTests`）；改契约后重新安装的时机；本地开发阶段是否需要 Nexus / `distributionManagement`。

**⑤ 与既有机械的接合**：RocketMQ 消费者在三个新服务里怎么配（沿用 `@RocketMQMessageListener` + `bs-<topic>-group` 命名、`rocketmq.name-server` 配置）；xxl-job 执行器在抽奖服务里怎么配（照 `XxlJobConfig` 的形状、`appname` 要不要区分）；Redis（Redisson 自动装配，无 `RedisTemplate`）与 MyBatis mapper XML 位置在新服务里的落法。

**产出**：一份 Markdown 落 `.scratch/marketing/research-cloud-stack.md`。

**为什么落这里而不是 throwaway 分支**（对 wayfinder 默认做法的一处偏离）：这是喂 spec 的**知识文档**不是代码，且工作区有无关的未跟踪文件；文档与 map 放一起才对后续 session 有用。

**约束**：本 effort 只产出文档与决策，**不写业务代码**。可以写最小验证片段贴进 md，但不要往三个服务的骨架里落文件。

## Answer

调研 findings（一手来源 + 逐条出处 + 未核实清单）：[research-cloud-stack.md](../research-cloud-stack.md)

- **版本矩阵（Boot 3.3.5 / Java 21 固定不动）**：Spring Cloud **2023.0.3**（train 2023.0.x，Spring 官方表 `2023.0.2` 起覆盖 Boot 3.3.x）＋ Spring Cloud Alibaba **2023.0.3.4**（2023.x 线最新 GA；其 BOM 的 `<nacos.client.version>` = **2.4.3**，已实测从 Maven Central 解包核实）＋ Dubbo **3.3.6** ＋ Nacos **server 2.x（建议 `v2.5.4`）**。**已排除**：SCA `2025.1.0.0` 要 Boot 4.0、`2025.0.0.0` 要 Boot 3.5（抬版本违反约束）、SCA **无 2024.0.x 发行线**（跳过了 Spring Cloud 2024）、`2022.x` 对 Boot 3.0；Dubbo `≤3.2` 不可用（Boot 3 适配模块 `dubbo-spring-boot-3-autoconfigure` 自 `3.3.0-beta.4` 才出现）。
- **Nacos 接入**：坐标 `spring-cloud-starter-alibaba-nacos-discovery` / `-nacos-config`（版本由 SCA BOM 管，不写 `<version>`）；Boot 3.x 一律用 **`spring.config.import: nacos:...`**，`bootstrap.yml` 已不推荐，且 SCA `2023.0.1.3+` 起**废弃 `shared-configs` / `extension-configs` 与按 `application.name` 自动拼接**（issue #3998）。`server-addr` 必须留在本地 yml（引导信息不能自举）。**四个 app 共享的 `jwt.secret` 收进 Nacos 的 `better-school-shared.yml`（DEFAULT_GROUP）**，单体 + 三服务同读一处；代价是**单体从此依赖 Nacos 才能启动**（除非用 `optional:` + 本地兜底值）——这条留给票 04 拍。docker-compose 加 `nacos/nacos-server:v2.5.4`，暴露 **8848 + 9848**（客户端 gRPC），`MODE=standalone`；自 2.2.1 起 `NACOS_AUTH_IDENTITY_KEY` / `_VALUE` / `NACOS_AUTH_TOKEN` 默认值被移除，必须显式给。**选 2.x 而非 3.x 的一条硬理由：3.x 把控制台独立成 8080，与单体本地端口直接冲突**（要改用 `18080:8080` 映射，且 3.3+ 默认开启客户端鉴权）。
- **Dubbo 接入**：`org.apache.dubbo:dubbo-spring-boot-starter` + `dubbo-nacos-spring-boot-starter`，均 **3.3.6**——**版本必须自己写：已核实 SCA `2023.0.3.4` 的 BOM 不含任何 `org.apache.dubbo:*` 条目**（也不要求两边版本对齐）。注解式 `@DubboService` / `@DubboReference`。**消费方依赖 `bs-api` 可行且就是标准做法**——`bs-api` 的**内部**依赖只有 `bs-types`（已核实其 pom：此外仅 lombok / jakarta.validation / jackson-annotations 三个非业务依赖），是天然干净的契约模块，引它不会把单体的 domain / infrastructure 拖进来。**协议显式配 `tri`**（官方对 new user 的明确建议；不配则吃默认的 `dubbo`）。**最要紧的坑：`dubbo.application.serialize-check-status` 默认 `STRICT`，未进 allowlist 的 DTO 第一次调用就被拒**——落地必须把 `cn.betterschool.**` 加白，或联调期先降 `WARN`。Dubbo 3.3 起默认序列化 fastjson2 → hessian2（全新引入，无跨版本互通包袱）。三服务 + 单体的端口要错开（tri 默认 50051）。
- **独立 project 的依赖纪律**：三个服务与单体**无 reactor 关系**，只能经本地仓库消费 `com.betterschool:bs-api:1.0.0-SNAPSHOT`；命令 `mvn -o install -pl bs-api -am -DskipTests`（**`-am` 不可省**，否则 `bs-types` 改动可能拿到旧 jar；首次构建去掉 `-o`）。**不需要 Nexus / `distributionManagement`**——`install` 落本地仓库、`deploy` 才读它，本仓单人单机同一 `~/.m2`。防「改了契约忘了重装」按性价比四道防线（固定前置步骤 → 同机即时重装 → **破坏性变更顺手 bump 版本**最可靠 → `-U` / `purge-local-repository` 兜底）写在文档 §4.3。
- **与既有机械接合**：RocketMQ `rocketmq-spring-boot-starter:2.3.6` 照用（**连同本仓 `backend/pom.xml` 的 `commons-logging` exclusion 一起抄**，漏了会复现 `spring-jcl` 抢 `LogFactory`；`rocketmq.name-server` 指向同一 namesrv，生产者组每服务各一个）；xxl-job **每服务不同 `appname` + 不同 `port` + 不同 `log-path`**（本仓四个 app 同在 localhost，共用端口必然打架），admin 控制台还要为每个新 appname 新建执行器；`xxl-job-core` **沿用 `3.0.0`** 与单体一致（三者 admin↔executor 兼容矩阵未找到官方表，不单独升）；Redis 照抄 `spring.data.redis.*` 三件套（Redisson 自动装配，无需额外配置，建议各给一个 database 与 `bs:<svc>:` 前缀）；mapper XML 沿用 `classpath:/mybatis/mapper/*.xml` 相对路径。**说明**：RocketMQ starter × Boot 3 与 Redisson × `spring.data.redis.*` 两处官方文档未明文背书，但**本仓单体已在 Boot 3.3.5 上实跑即是证据**（与下方的消费组语义不同，那条没有实跑证据）。
- **⚠️ 03 新发现的一个真问题（既有命名约定会撞）**：`bs-<topic>-group` 这个消费组命名在**多服务订阅同一 topic** 时会互相抢消息——同一 consumerGroup 是集群消费（一条消息只投组内一个实例），不同组才各收全量。事件编排下 `points-changed` / `prize-drawn` / `group-formed` 天然多方关心，会出「抽奖收到、积分收不到」这种极难排查的间歇性 bug。**建议改成 `bs-<service>-<topic>-group`**，归票 04 采纳。**⚠️ 未核实**：该「不同组各收全量」语义本票**未取到 RocketMQ 官方原文**，按通行定义得出；但「加服务前缀」是无害的防御性改动，建议直接采纳、不依赖该语义成立。
- **⚠️ 两处必须冒烟验证（本 effort 的命门，铺开前必做）**：① SCA `2023.0.3.4` × Boot **3.3.5**——SCA 官方只声明 2023.x ↔ Boot **3.2.x**（其 build 基线 3.2.9），Boot 3.3.x 的背书来自下层 Spring Cloud train，**不是 SCA 自身的编译基线**；② Dubbo `3.3.6` × Boot **3.3.5**——官方 starter 表只声明覆盖 `[1.x ~ 3.x)`，其 Boot 3 模块的编译基线是 Boot 3.5.x / Spring 6.2.x。两条均**无一手来源证明实跑可行**。落地票必须先起最小 app 冒烟（连上 Nacos 注册 + 跨进程调通一次）再铺开。
- **转下游（票 04 要接的）**：① SCA 处于**低维护状态**（issue #4009，社区与维护者原话见文档）——替代路径是自己引 `com.alibaba.nacos:nacos-client` 直连 Nacos（Dubbo 侧本来就走它），代价是失去 `spring.config.import` 集成与 `@RefreshScope` 联动，**本票不拍板，留 04 决策**；② `jwt.secret` 集中到 Nacos 后**单体是否也迁配置中心**（会改变单体的启动前置条件）；③ Nacos server 2.x 已在本票收敛为选定，04 只需确认；④ 04 写进 `spec.md` 的「营销域」节时（票 04 `## Question` ⑨ 记作「营销服务」节，同一节，落地时统一叫法），**节内要带一个指向本文件的指针**，否则 spec 作为终点产物会与栈选型的论证脱钩。
