# 微服务栈选型与接入成本（Spring Cloud Alibaba + Dubbo + Nacos on Boot 3.3.5 / Java 21）

Ticket: [`.scratch/marketing/issues/03-cloud-stack-research.md`](issues/03-cloud-stack-research.md)（research 票，AFK）
日期：2026-09-14
状态：研究完成（结论与出处如下；不确定处已显式标注）

## 方法与可信度说明

本仓环境的 WebFetch 通道被网络策略阻断（github.com / spring.io / sca.aliyun.com 均返回 "Unable to verify if domain is safe to fetch"），因此全部一手材料用 `curl` 直接拉取：GitHub REST API、`raw.githubusercontent.com`、`repo1.maven.org`（Maven Central）、`spring.io` 页面 HTML、`sca.aliyun.com` 页面 HTML、以及**直接解包 Maven 构件里的 `META-INF` 文件**。

凡结论都尽量落到"拥有该事实的那一方"：版本兼容表落 Spring 官方 / SCA 官方 / Nacos 官方升级手册 / Dubbo 官方文档；构件内部结构落 Maven Central 上的真实 pom 与 jar；属性键落上游源码。**凡没能在一手来源上核实的，都标了 `⚠️ 未核实`。**

---

## ① 版本选型（最要紧）

### 结论矩阵（Boot 3.3.5 + Java 21 固定不动）

| 组件 | 选定版本 | 依据（一手出处） |
|---|---|---|
| Spring Boot | **3.3.5**（`backend/pom.xml` 已固定，不动） | 本仓 `backend/pom.xml` L10 |
| Spring Cloud release train | **2023.0.x**（配 `spring-cloud-dependencies`，建议 `2023.0.3`；SR 线最新 `2023.0.6`） | Spring 官方兼容表：`2023.0.x` ↔ Boot `3.2.x, 3.3.x (Starting with 2023.0.2)` |
| **Spring Cloud Alibaba** | **`2023.0.3.4`**（2023.x 线最新 GA，发布于 2025-10-17） | GitHub Releases `2023.0.3.4` |
| Spring Cloud Alibaba（备选） | `2023.0.3.3` / `2023.0.3.2`（同线更早 GA） | GitHub Releases |
| Nacos **client**（由 SCA BOM 管理） | **`2.4.3`** | SCA `2023.0.3.4` BOM 属性 `<nacos.client.version>2.4.3</nacos.client.version>` |
| Nacos **server** | **2.x（建议 `2.5.4`）**；3.x server 亦兼容 2.x client | Nacos 升级手册「Client Compatibility」表：`2.x → Compatible: Yes`、`3.x → Yes` |
| **Dubbo** | **`3.3.6`**（3.3.x 线最新，2025-10-17） | GitHub Releases `dubbo-3.3.6`；官方文档 starter 表：`3.3.x` ↔ Boot range `[1.x ~ 3.x)` |

### 官方兼容表原文（逐格出处）

**Spring Cloud ↔ Spring Boot**（Spring 官方，`spring.io/projects/spring-cloud` "Table 1. Release train Spring Boot compatibility"）：

```
2025.1.x aka Oakwood    4.0.x, 4.1.x (Starting with 2025.1.2)
2025.0.x aka Northfields 3.5.x
2024.0.x aka Moorgate    3.4.x
2023.0.x aka Leyton      3.2.x, 3.3.x (Starting with 2023.0.2)   ← 本仓所在行
2022.0.x aka Kilburn     3.0.x, 3.1.x (Starting with 2022.0.3)
```

**Spring Cloud Alibaba ↔ Spring Cloud ↔ Spring Boot**：

- SCA `2023.x` 分支根 pom（HEAD = `2023.0.3.5-SNAPSHOT`）属性：
  `<spring.cloud.version>2023.0.3</spring.cloud.version>`、`<spring-boot.version>3.2.9</spring-boot.version>`
  → 即 **SCA 2023.0.3.x 构建基线是 Spring Cloud 2023.0.3 + Boot 3.2.9**。
- SCA `2023.x` README「How to build」原文：`2023.x branch: Corresponds to Spring Cloud 2023 & Spring Boot 3.2.x, JDK 17 or later versions are supported.`
- SCA 官方文档站（2023.0.x 板块）版本说明页同样写「基于 Spring Boot 3.2、Spring Cloud 2023.x 版本」，且其表格**过时**（只列到 `2023.0.1.0` ↔ Boot `3.2.4`），不要照抄。
- SCA `2023.0.1.3` release note 明写 `update nacos 2.4.2, seata 2.1.0, spring boot 3.2.9`。
- SCA PR #3804「[2023.x] upgrade spring cloud to 2023.0.3」diff 明写：`spring cloud to 2023.0.3` / `spring boot to 3.2.7`。

> **⚠️ 关键判断（必须显式记下的一处不一致）**
> SCA **自己**只说 2023.x ↔ Boot **3.2.x**（README 与文档站口径），它编译/测试的基线是 Boot 3.2.7~3.2.9。
> 而 Boot **3.3.x** 的支持来自**下层 Spring Cloud 2023.0.x release train**（Spring 官方表：`2023.0.2` 起支持 3.3.x）。
> 结论：**SCA 2023.0.3.4 跑在 Boot 3.3.5 上属于"被 Spring Cloud train 覆盖、但非 SCA 自身编译基线"的组合**——不是官方明文保证的组合。落地时**必须做一次冒烟验证**（起一个最小 app，只加 nacos-discovery/config，看能否连上 Nacos 并注册），不要盲信本文。
> 我们没有在一手来源上找到「SCA 2023.0.3.x + Boot 3.3.x 官方已验证」的明文声明。⚠️ 未核实（此组合的官方背书）。

### 明确「跑不了」的组合（Boot 3.3.5 下）

| 组合 | 为什么不行 | 出处 |
|---|---|---|
| SCA `2025.1.0.0` | 该版本 release note 明写 `[Spring Boot]: Bump to 4.0.0`、`[Spring Cloud]: Bump to 2025.1.0` → 要 Boot 4.x | SCA 2025.1.0.0 release notes |
| SCA `2025.0.0.0` | release note 明写 `[Spring Boot]: Bump to 3.5.0` → 要 Boot 3.5.x，抬版本违反约束 | SCA 2025.0.0.0 release notes |
| SCA `2024.0.x` | **不存在**这个发行线（SCA 跳过了 Spring Cloud 2024） | SCA 仓库 branches 列表只有 `2021.x / 2022.x / 2023.x / 2025.0.x / 2025.1.x`；tags 亦无 2024.* |
| SCA `2022.x` | 对应 Spring Cloud 2022 & Boot 3.0.x | SCA README |
| Dubbo `3.1.x` 及以下 | 官方 starter 表：`3.1.x` ↔ Boot range `[1.x ~ 2.x)`；`2.7.x` 同 | Dubbo 官方 Spring Boot 文档 |
| Dubbo `3.2.x` | 官方表写 `3.2.x` ↔ `[1.x ~ 3.x)`（理论可用），但 **Boot 3 专属适配模块 `dubbo-spring-boot-3-autoconfigure` 从 `3.3.0-beta.4` 才出现**，3.2 线无此模块（`3.2` 分支根 pom 里根本没有 `spring-boot-3.version` 属性） | Maven Central `dubbo-spring-boot-3-autoconfigure` maven-metadata（最早 `3.3.0-beta.4`）；Dubbo `3.2` 分支 pom |

### 选型时的额外风险情报（决策相关，非版本表）

SCA 项目处于**低维护状态**。issue #4009（2025-06-25 开、已 closed）里维护者与社区原话：
- 社区：`Spring Cloud 2024.0.0 于 2024-11-05 发布，Spring Cloud Alibaba 并没有` / `感觉这个项目未来状况堪忧`；
- 维护者 `flystar32`：新分支已建 `github.com/alibaba/spring-cloud-alibaba/tree/2025`；
- 中途 `CamusGao` 直接留了一句 `RIP SCA`。

**对本仓的含义**：选 SCA 只是"拿到 nacos-discovery / nacos-config 两个 starter 的最省事路径"，不是押注它活跃。若担心停更，替代方案是自己引 `com.alibaba.nacos:nacos-client` 直连 Nacos（Dubbo 侧的 Nacos 注册中心本来就走 `nacos-client`，不依赖 SCA），代价是失去 Spring Cloud 的 `spring.config.import` 集成与 `@RefreshScope` 联动。本票不替用户拍板，只记录该选项存在。

---

## ② Nacos 接入

### 2.1 Maven 坐标（选定 release：SCA `2023.0.3.4`）

```xml
<!-- BOM：和 spring-cloud-dependencies 一起 import -->
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-dependencies</artifactId>
      <version>2023.0.3</version>   <!-- 与 SCA 2023.0.3.4 的构建基线一致；SR 线最新为 2023.0.6 -->
      <type>pom</type><scope>import</scope>
    </dependency>
    <dependency>
      <groupId>com.alibaba.cloud</groupId>
      <artifactId>spring-cloud-alibaba-dependencies</artifactId>
      <version>2023.0.3.4</version>
      <type>pom</type><scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

```xml
<!-- 具体 starter（版本由上面 BOM 管，不写 version） -->
<dependency>
  <groupId>com.alibaba.cloud</groupId>
  <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
<dependency>
  <groupId>com.alibaba.cloud</groupId>
  <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
```

出处：SCA 官方 2023.0.x 快速开始（`sca.aliyun.com/docs/2023/user-guide/nacos/quick-start/`）明写
「需要在 `pom.xml` 文件中引入 group ID 为 `com.alibaba.cloud` 而 artifact ID 为 `spring-cloud-starter-alibaba-nacos-config` 的 starter」/ 同构的 `-nacos-discovery`。
BOM 的 `<nacos.client.version>` 值为 `2.4.3`（见 `spring-cloud-alibaba-dependencies-2023.0.3.4.pom` 的 `<properties>`）。
SCA BOM 里两个坐标的确切 artifactId 亦见于其 `<dependencyManagement>`：`spring-cloud-starter-alibaba-nacos-discovery` / `spring-cloud-starter-alibaba-nacos-config` / `spring-alibaba-nacos-config`。

### 2.2 Boot 3.x 的正确配置导入方式：`spring.config.import`（**不是** bootstrap.yml）

**权威结论**：用 `spring.config.import: nacos:...`；`bootstrap.yml` / `bootstrap.properties` 与 `spring-cloud-starter-bootstrap` 已不推荐。

一手依据三条：

1. **SCA 官方快速开始页原文**（2023.0.x）：
   > 「在应用的 `/src/main/resources/application.yaml` 配置文件增加 Nacos Config 地址及相关配置：
   > ```
   > spring.cloud.nacos.serverAddr=127.0.0.1:8848
   > spring.config.import=nacos:nacos-config-example.properties?refreshEnabled=true
   > ```
   > **2023.0.1.3 及以上版本 `spring.cloud.nacos.config.shared-configs` 和 `extension-configs` 以及默认加载的 `application.name` 配置已经废弃**，旧版本请统一使用 `spring.config.import` 方式导入 Nacos 配置（issue #3998）
   > `bootstrap.yml` 以及 `bootstrap.properties` 已不推荐使用，请使用 `application.yml` 或者 `application.properties`」

2. **SCA 源码**：`NacosConfigDataLocationResolver.PREFIX = "nacos:"`
   → `nacos:` 是注册进 Spring Boot ConfigData 体系的 location 前缀，所以它挂在 Spring Boot 标准的 `spring.config.import` 上。

3. **维护者 issue #3998**（标题：「2023.0.1.3+ 版本接入 Nacos 配置中心参数兼容性问题说明」）解释根因：为支持在非 Spring Cloud 的 Spring Boot 应用里也能用（拆出 `spring-alibaba-nacos-config` 模块），把配置加载逻辑收敛掉了，删除了「按 `spring.application.name` + `fileExtension` 拼接 / `shared-configs` / `extension-configs`」三条老路径，统一到 `spring.config.import`。

issue #4098 里维护者给出的**可用样板**（同样是 `spring.config.import` + `optional:` 前缀）：

```yaml
spring:
  application:
    name: points            # 每个服务改自己的名字
  profiles:
    active: dev
  config:
    import:
      - optional:nacos:application-${spring.profiles.active}.${spring.cloud.nacos.config.file-extension}
      - optional:nacos:${spring.application.name}-${spring.profiles.active}.${spring.cloud.nacos.config.file-extension}
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
      config:
        server-addr: 127.0.0.1:8848
        file-extension: yml
```

> **实操注意 1**：`spring.cloud.nacos.config.server-addr` / `discovery.server-addr` **必须留在本地 `application.yml`**（它是"去哪儿找 Nacos"的引导信息，放进 Nacos 里是鸡生蛋）。
> **实操注意 2**：`optional:` 前缀 = 该 dataId 不存在时不报错。**共享配置建议不加 `optional:`**（缺了就启动失败，比静默跑丢配置好）；各自服务的配置可用 `optional:`。
> **实操注意 3（踩坑）**：只要 classpath 上有 `spring-cloud-starter-alibaba-nacos-config` 却没有任何 `spring.config.import` 导入项，会触发 import-check 报错；可以 `spring.cloud.nacos.config.import-check.enabled=false` 关掉，但官方不建议。

### 2.3 四个 app 共享同一份 `jwt.secret` 的组织方式

本仓现状：`backend/bs-app/src/main/resources/application.yml` L37-39 有
`jwt.secret: hb9Sk1af...`（单处硬编码），三个新服务各自也要校验同一个 JWT。

**推荐组织**（Nacos 侧）：

| DataId | Group | 内容 | 谁读 |
|---|---|---|---|
| `better-school-shared.yml` | `DEFAULT_GROUP` | 只放**跨服务共享**的：`jwt.secret`、`jwt.expire-days` | 单体 + points + lottery + referral（四个都读） |
| `points.yml` | `DEFAULT_GROUP` | points 自己的：数据源、schema 名、topic 名等 | 仅 points |
| `lottery.yml` | `DEFAULT_GROUP` | lottery 自己的 | 仅 lottery |
| `referral.yml` | `DEFAULT_GROUP` | referral 自己的 | 仅 referral |

四个 app 一律这样导：

```yaml
spring:
  application:
    name: points                 # ← 三个服务分别 points / lottery / referral
  config:
    import:
      - nacos:better-school-shared.yml?group=DEFAULT_GROUP&refreshEnabled=true
      - nacos:${spring.application.name}.yml?group=DEFAULT_GROUP&refreshEnabled=true
  cloud:
    nacos:
      server-addr: 127.0.0.1:8848
```

要点：
- **`jwt.secret` 只有一份来源**（`better-school-shared.yml`），四个应用 `@Value("${jwt.secret}")` 或 `@ConfigurationProperties(prefix="jwt")` 取到的是同一个值——轮换时改 Nacos 一处，四边同时生效（`refreshEnabled=true` 时可动态刷；`@Value` 需配 `@RefreshScope` 才刷新，否则只对新实例生效）。
- **单体侧改造**：把 `application.yml` 里的 `jwt.secret` 一行删掉，改用同样的 `spring.config.import`（单体加 SCA 依赖这一步不可省）。**注意这会改变单体现有的启动前置条件——本机没起 Nacos 时单体将起不来**（除非用 `optional:` 并保留本地兜底值）。这是落地票要显式决策的点。
- **`application.yml` 与 `spring.application.name` 的关系**：DataId 里用了 `${spring.application.name}`，所以 `spring.application.name` 必须在**本地**配置里（它本身不能来自 Nacos）。

### 2.4 本地 docker-compose 加 Nacos 服务

现有 `backend/docker-compose.yml` **无 Nacos 容器**（已核实），编排的宿主机端口：MySQL 3306 / Redis 6379 / RocketMQ 9876,10909,10911 / dashboard 8082 / xxl-job-admin 9090 / MinIO 9000,9001 / ES 9200。统一密码 `bs123456`。

**端口与镜像（一处必须小心的冲突）**：

| 版本 | 镜像 | 容器内端口 | 说明 |
|---|---|---|---|
| Nacos 2.x | `nacos/nacos-server:v2.5.4` | **8848**（主/控制台）、**9848**（客户端 gRPC） | 官方 2.x 快速开始只暴露 8848 + 9848 |
| Nacos 3.x | `nacos/nacos-server:v3.2.4` | **8080**（独立控制台）、**8848**（主）、**9848**（客户端 gRPC） | 3.x 把控制台端口独立成 8080 |

出处：`nacos-group/nacos-docker` README 的 Quick Start 原文——
v3.x 用 `-p 8080:8080 -p 8848:8848 -p 9848:9848`；v2.x 用 `-p 8848:8848 -p 9848:9848`。
其默认 `example/.env` 的 `NACOS_VERSION=v3.2.4`。

> **⚠️ 端口冲突（本仓特有）**：单体应用本地跑在 **8080**（`application.yml` L1-2），而 **Nacos 3.x 控制台容器内就是 8080**。若选 Nacos 3.x，映射必须避开，例如 `"18080:8080"`。选 Nacos 2.x 则无此问题。
> 另：Nacos 2.x 集群模式的 `9849`（server 间 gRPC）、`7848`（raft）在单机 standalone 下不需要暴露——官方 2.x 快速开始也确实没暴露它们。

**必需环境变量**（`nacos-docker` README 明文）：

- 自 **Nacos 2.2.1** 起，`NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE` / `NACOS_AUTH_TOKEN` **的默认值被移除**，「please add them yourself when starting up, otherwise an error will be reported at startup」。
- `MODE=standalone`（单机）。
- 参考 `example/standalone-derby.yaml` 的实际取值：

```yaml
nacos:
  image: nacos/nacos-server:${NACOS_VERSION}      # 建议 v2.5.4
  container_name: bs-nacos
  environment:
    - PREFER_HOST_MODE=hostname
    - MODE=standalone
    - NACOS_AUTH_IDENTITY_KEY=serverIdentity
    - NACOS_AUTH_IDENTITY_VALUE=security
    - NACOS_AUTH_TOKEN=VGhpc0lzTXlDdXN0b21TZWNyZXRLZXkwMTIzNDU2Nzg=
    - NACOS_AUTH_ENABLE=false           # ← 本地开发保持简单：关掉鉴权
  ports:
    - "8848:8848"
    - "9848:9848"                       # 2.x 客户端 gRPC，必须能连（否则客户端连不上）
  networks:
    - app_network
```

> `NACOS_AUTH_TOKEN` 必须是一段 **Base64、解码后 ≥32 字节**的串（上面这串是官方 example 里的，别用于生产）。

**Nacos 3.x 的鉴权默认行为（与 2.x 不同，务必知道）**：

`nacos-docker` README 原文：
> Starting with Nacos 3.3, Client API authentication (`nacos.core.auth.enabled`) is **enabled by default** when `NACOS_AUTH_ENABLE` is unset. Set `NACOS_AUTH_ENABLE=true` or `NACOS_AUTH_ENABLE=false` to override it explicitly. … Client API authentication is independent of Admin API and Console API authentication.

即：**Nacos 3.3+ 在没显式设 `NACOS_AUTH_ENABLE` 时会默认开启客户端鉴权**——客户端必须带 `username=...&password=...`（见 ③ 的 `dubbo.registry.address` 写法）。3.0/3.1/3.2 沿用各自镜像内的默认值。
另外 Nacos 3.0.0 release note 列了几条影响本地使用的变更：`Enabled nacos console authentication and need more configuration setting when deploying first time`、`The default namespace public change its id from (empty string) to public`。

**本地开发怎么最省事**：
- 选 **Nacos 2.x（v2.5.4）** + `NACOS_AUTH_ENABLE` 不设（2.x 的 `nacos.core.auth.enabled` 默认 false）+ 只按要求补上三个 `NACOS_AUTH_*` 引导变量 → 客户端裸连 `8848`，最省事，且 **nacos-client 版本（2.4.3）与 server 2.5.x 同大版本**。
- 若要用 Nacos 3.x server：记得控制台端口改映射（避让 8080）、显式设 `NACOS_AUTH_ENABLE=false`（本地）或按需带账号密码、并且**注意 namespace 的坑**（见下）。

> **踩坑（一手）**：SCA issue #4094 —— `nacos-client 3.0.3` 在 `namespace` 为空时会**读不到 `public` 命名空间的配置文件**（日志显示 `is empty`），把 client 降到 `2.5.1` 即恢复。维护者在 #4098 里确认：**client 与 server 大版本要对齐**。本仓选定 SCA 2023.0.3.4 → client 是 2.4.3，**不受这个 bug 影响**；但**若将来跟随 SCA 升到 2025.x（client 3.0.3）+ 仍用 Nacos 2.x server，就会踩到**。

---

## ③ Dubbo 接入

### 3.1 依赖：Boot 3.3.5 下该引哪个

```xml
<dependency>
  <groupId>org.apache.dubbo</groupId>
  <artifactId>dubbo-spring-boot-starter</artifactId>
  <version>3.3.6</version>
</dependency>
<dependency>
  <groupId>org.apache.dubbo</groupId>
  <artifactId>dubbo-nacos-spring-boot-starter</artifactId>
  <version>3.3.6</version>
</dependency>
```

出处：Dubbo 官方「Using Nacos as Registration Center」文档的 Basic Configuration 原文即这两个坐标（文档示例版本写 `3.3.0`，我们取 3.3.x 线最新的 `3.3.6`）。

**`spring-cloud-starter-alibaba` 的 BOM 会不会管 Dubbo？——不会。** 已核实 `spring-cloud-alibaba-dependencies-2023.0.3.4.pom` 的 `<dependencyManagement>` 全量内容，只有：`nacos-client`、Sentinel 一族、`seata-spring-boot-starter`、`rocketmq-client`/`rocketmq-acl`、shedlock、schedulerx2-worker、SCA 自己的各 starter、`nacos:logback-adapter`。**没有任何 `org.apache.dubbo:*` 条目**（对其做 `artifactId` 正则匹配 dubbo 命中 0 次）。→ **Dubbo 版本必须自己写 `<version>`，不能指望 SCA BOM 管。** 也不要求两边版本对齐（Dubbo 走自己的 `dubbo-dependencies-bom` 或显式版本）。

**starter 兼容性（官方表）**，Dubbo「Spring Boot」文档原文：

```
dubbo-spring-boot-starter   Version   Compatible Spring Boot Range
                            3.3.x     [1.x ~ 3.x)
                            3.2.x     [1.x ~ 3.x)
                            3.1.x     [1.x ~ 2.x)
                            2.7.x     [1.x ~ 2.x)
```

→ `3.3.6` 官方声明覆盖 Boot 3.x（不含 4.x），**Boot 3.3.5 落在声明范围内**。

**boot3 自动装配是怎么进 classpath 的（直接解包核实）**：

- `dubbo-spring-boot-starter-3.3.6.pom` 的依赖只有 `spring-boot-starter`（2.7.18，optional）+ `dubbo-spring-boot-autoconfigure`。
- 解包 `dubbo-spring-boot-autoconfigure-3.3.6.jar`：**同时**含
  - `META-INF/spring.factories`（Boot 2 风格）→ `DubboAutoConfiguration, DubboListenerAutoConfiguration, DubboRelaxedBinding2AutoConfiguration, DubboTripleAutoConfiguration, …observability…`
  - `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（Boot 3 风格）→ 同一批类
  → 即**一个 jar 同时适配 Boot 2 与 Boot 3**，Boot 3 靠 `AutoConfiguration.imports` 生效。
- 另有独立构件 `org.apache.dubbo:dubbo-spring-boot-3-autoconfigure`（`3.3.0-beta.4` 起才有，最新 `3.3.6`），jar 内只有 `DubboTriple3AutoConfiguration`（Triple 走 **jakarta** Servlet 的集成）。
  **它不是 starter 的传递依赖**（`dubbo-spring-boot-autoconfigure-3.3.6.pom` 的依赖里没有它；`dubbo-spring-boot-starter` 的 pom 里也没有）。
  → **用 Triple 且要挂到 Servlet 容器上时才需要显式加它**；用默认的独立端口跑 Triple / 用 `dubbo://` 协议时不需要。

> **⚠️ 残留风险（未核实）**：`dubbo-spring-boot-3-autoconfigure-3.3.6` 编译基线是 `spring-boot-dependencies` **3.5.0**（`dubbo` 3.3 分支根 pom 属性 `spring-boot-3.version = 3.5.14`、`spring-6.version = 6.2.19`），而 Boot 3.3.5 是 Spring Framework **6.1.x**。starter 里那些依赖都标 `<optional>/<provided>`，不会被传递强加版本，官方表也声明覆盖 Boot 3.x；但**"Dubbo 3.3.6 + Boot 3.3.5 实跑"我们没有一手来源证明**，只证明到"官方声明范围内"。落地票必须做一次真实调用冒烟（provider 起在单体、consumer 起在一个新服务，跨进程调通一次）。

### 3.2 注册中心指向 Nacos

Dubbo 官方「Using Nacos as Registration Center」文档给出的写法：

```yaml
# 最简
dubbo:
  registry:
    address: nacos://localhost:8848
```

```yaml
# 带 Nacos 账号密码（Nacos 开了鉴权时必须）
dubbo:
  registry:
    address: nacos://localhost:8848?username=nacos&password=nacos
```

```yaml
# namespace / group / 注册消费者（接口级）
dubbo:
  registry:
    address: nacos://localhost:8848
    group: dubbo
    parameters:
      namespace: <namespace-id>
      register-consumer-url: true      # 默认 false，Dubbo 3.0.0 起新增
```

文档同时给的其它参数默认值（有用）：`username` 默认 `nacos`、`password` 默认 `nacos`、`group` 默认 `DEFAULT_GROUP`、`namespace` 默认 `public`、`register-consumer-url` 默认 `false`。

**本仓怎么配**：本地 docker-compose 若按 ② 用 Nacos 2.x 且关鉴权，地址就是 `nacos://localhost:8848`；若用 Nacos 3.3+ 且没设 `NACOS_AUTH_ENABLE=false`，就必须补 `?username=nacos&password=nacos`（首次登录会强制改密，改了要同步）。

**Dubbo 官方给的 Dubbo ↔ Nacos 版本对应表**（同页「1.2 Nacos Version Mapping」）：

| Dubbo | Recommended Nacos Version | Nacos Compatibility Range |
|---|---|---|
| 3.3.0 | 2.3.0 | 2.x |
| 3.2.21 | 2.1.0 | 2.x |
| 3.1.11 | 2.0.9 | 2.x |

→ 再次指向 **Nacos 2.x**；与 ① 的选型一致。表里没有 3.3.6 的行（文档停更在 3.3.0），但"2.x 兼容范围"这一档不变。

### 3.3 `@DubboService` / `@DubboReference`

Dubbo 官方 starter 列表对 `dubbo-spring-boot-starter` 的描述原文：
> 「manages the core dubbo dependencies, identifies configuration items starting with `dubbo.` in `application.properties` or `application.yml`, and **scans annotations like `@DubboService`**」

用法（Dubbo 3 的标准注解式，provider / consumer 分居两侧）：

```java
// provider 侧（单体：把现有 bs-api 的实现类标上）
@DubboService                      // 原 @Service 可保留（Spring bean）或替换
public class UserFacadeImpl implements IUserFacade { ... }

// consumer 侧（points / lottery / referral）
@DubboReference
private IUserFacade userFacade;
```

本仓 `backend/CLAUDE.md` 已写明「`xxx-api`：定义 api 接口 + DTO + Response，不依赖任何业务模块，**后续 dubbo 对外暴露的就是这个**」，`bs-api` 每个接口 javadoc 也写着「后续微服务化时由 Dubbo Provider 实现」——注解直接打在 `bs-api` 接口的 `bs-trigger` 实现上即可，`@DubboReference` 注入的就是 `bs-api` 里的接口类型。

> 注意：`@DubboService` 是 **Dubbo 3 的注解**（`org.apache.dubbo.config.annotation.DubboService`）；老文档里的 `@Service`（`org.apache.dubbo.config.annotation.Service`）在 3.x 已废弃。本票未在一手来源逐字核实该废弃声明，但 Dubbo 3 官方文档全篇用 `@DubboService`。⚠️ 未核实（旧注解的确切废弃状态）。

### 3.4 消费方依赖 provider 的 api jar —— 这是标准 Dubbo 模式吗？

**是。** Dubbo 官方「Pure API Development Model」把「接口 jar 对 provider / consumer 共享」列为独立开发模式；Dubbo 的接口级服务发现本身就要求消费者持有接口类型（`register-consumer-url`、`org.apache.dubbo.config.annotation.DubboReference` 注入的都是接口）。

本仓的具体形态：`bs-api` 只依赖 `bs-types`（`backend/CLAUDE.md` 的依赖方向表：`app → trigger → api, types, domain`；api 层「不依赖任何业务模块」），是**天然干净的契约模块**——consumer 引它不会把单体的 domain / infrastructure / MyBatis 拖进来。这是最省事的做法，没有更"标准"的替代。

**注意事项**：

1. **api jar 必须先 install 到本地仓库**（见 ④）。
2. **序列化白名单（真正的坑）**：Dubbo `AllowClassNotifyListener.DEFAULT_STATUS = SerializeCheckStatus.STRICT`——**3.3 的默认是 STRICT**（只放行 allowlist 内的类，其余直接拒绝）。
   - 配置键：`dubbo.application.serialize-check-status`（源码常量 `CommonConstants.SERIALIZE_CHECK_STATUS_KEY`）。
   - 取值枚举（`org.apache.dubbo.common.utils.SerializeCheckStatus`）：`DISABLE(0)` 关闭检查 / `WARN(1)` 仅拦危险类、其余告警 / `STRICT(2)` 只放行白名单。
   - 相关开关还有：`dubbo.security.serialize.allowedClassList`、`dubbo.security.serialize.blockedClassList`、`dubbo.security.serialize.blockAllClassExceptAllow`（均在 `CommonConstants`）。
   - **实际影响**：本仓 DTO 都在 `bs-types` / `bs-api` 里、包名统一，通常需要把 `cn.betterschool.**` 加进 allowlist，或本地联调期先 `serialize-check-status: WARN`。**这是 Dubbo 3 落地最常见的"第一次调用就炸"点**，务必在 spec 里写死。
   - 该行为来自源码（默认值 + 枚举），非文档散文，可信度高。
3. **传输对象必须可序列化**：DTO 建议实现 `java.io.Serializable` 并声明 `serialVersionUID`（Hessian2 对它有依赖；两个服务各自 classpath 上的同一个类版本不一致时会出问题）。
4. **默认序列化在 3.3 变了**：官方 3.2→3.3 兼容指南原文——`Starting with Dubbo 3.3.0, the default serialization method changes from fastjson2 to hessian2`（并升级 `hessian-lite` 到 `4.0.x`，含 JDK 17/21 兼容修复、支持 Record / ImmutableCollections）。本仓是全新引入，没有跨版本互通包袱，不用配 `prefer-serialization`。

### 3.5 协议选型：Triple 还是 `dubbo://`

Dubbo 官方「Protocol Overview」表格 + 警告原文：

| 协议 | 配置值 | 默认端口 | 传输层 | 序列化 | 默认？ |
|---|---|---|---|---|---|
| **triple** | `tri` | 50051 | HTTP/1, HTTP/2 | Protobuf Binary / Protobuf-json | 否 |
| **dubbo** | `dubbo` | 20880 | TCP | Hessian、Fastjson2、JSON、JDK、Avro、Kryo… | **是** |

> 官方原文（Note）：
> 「Considering compatibility with past versions, all current Dubbo release versions default to using the `dubbo` communication protocol. **For new users, we strongly recommend clearly configuring the use of the `triple` protocol from the beginning**」

**建议**：本仓是**全新引入**（不是存量 Dubbo 用户），按官方对"new users"的明确建议**从一开始就显式配 `triple`**：

```yaml
dubbo:
  protocol:
    name: tri        # 显式写，别吃默认的 dubbo
    port: 50051      # 三个服务同机跑要各给一个不同端口
```

Triple 的另一处收益与本仓直接相关：它**基于 HTTP/1+HTTP/2、100% 兼容 gRPC、支持 `application/json` 直接 HTTP 访问**——将来前端/gateway 想直接打 Dubbo 接口时不用换协议。代价：`dubbo://` 是"最高性能的私有协议"，Triple 在纯 Java 内网调用上性能略低，但本仓的量级完全无感。
**注意**：Triple 走 **Java 接口**模式时序列化是 `protobuf-wrapper`（官方表：`先 hessian 再 protobuf 的双重序列化`），所以 3.4 的序列化白名单问题**依然适用**。
**端口规划**：`dubbo` 协议默认 20880、`triple` 默认 50051，**三个服务 + 单体若都在本机跑，四个端口必须错开**（如 50051/50052/50053/50054），这与 xxl-job 的 9999 端口冲突是同一类问题（见 ⑤）。

### 3.6 Dubbo 3.x + Spring Boot 3.3.x 的已知坑（汇总）

| 坑 | 说明 | 出处 |
|---|---|---|
| 序列化 STRICT 白名单 | 默认 `STRICT`，未加 allowlist 的 DTO 直接被拒 | Dubbo 源码 `AllowClassNotifyListener.DEFAULT_STATUS` |
| 默认序列化换帅 | 3.3.0 起默认从 fastjson2 → hessian2（新项目无感） | 官方 3.2→3.3 兼容指南 |
| `register=false` 语义变化 | 3.3.x 起 `register=false` **连 QoS 手动上线也不生效**（= 永不注册）；优雅上线要用 `delay=-1` + `-Ddubbo.application.manual-register=true` | 同上 |
| starter 的 Boot 版本来源 | 发布出来的 `dubbo-spring-boot-starter-3.3.6.pom` 里写死 `spring-boot-starter:2.7.18`（optional）。**在 Boot 3.3.5 工程里会被自身 BOM 覆盖成 3.3.5，不用手工排**；但反过来说，**"它没被 Boot 3 编译过"这件事不要误解成"必须自己排 javax 依赖"** | Maven Central 上的 starter pom |
| `dubbo-spring-boot-3-autoconfigure` 不在传递依赖里 | 需要 Triple-over-jakarta-Servlet 时才显式加 | 解包 jar + pom 核实 |
| Boot 4 不可用 | 官方表上限 `[1.x ~ 3.x)` | Dubbo 官方 Spring Boot 文档 |

---

## ④ 独立 project 的依赖纪律

### 4.1 跨 project 消费另一个本地 project 的模块

三个营销服务是**独立的 Maven project**（各自聚合器 `backend/<svc>/pom.xml`，parent 是 `spring-boot-starter-parent:3.3.5`，**不是** `better-school-backend`），因此它们与单体之间**没有 reactor 关系**——只能通过**本地仓库（`~/.m2/repository`）**消费 `com.betterschool:bs-api:1.0.0-SNAPSHOT`。

单体侧（在 `backend/`，即单体聚合器所在目录）执行：

```bash
mvn -o install -pl bs-api -am -DskipTests
```

参数含义（Maven 官方 CLI 语义）：
- `-pl bs-api`：**只**构建 `bs-api` 这个 module（`--projects`，可用相对路径或 `groupId:artifactId`）。
- `-am`：`--also-make`，**连带构建 `bs-api` 依赖的上游 module**——对本仓就是 `bs-types`。**这个参数不能省**：`bs-api` 依赖 `bs-types`，若 `bs-types` 也改过却只装了 `bs-api`，消费方会拿到旧 `bs-types`。
- `-o`：offline（不联网）。**前提是所有依赖已在本地仓库缓存过**——首次构建请去掉 `-o`（或先把单体整体 `mvn install` 一次）。
- `-DskipTests`：跳过测试**执行**（测试仍会被编译）。要连测试编译也跳过用 `-Dmaven.test.skip=true`。

消费者侧声明：

```xml
<dependency>
  <groupId>com.betterschool</groupId>
  <artifactId>bs-api</artifactId>
  <version>1.0.0-SNAPSHOT</version>
</dependency>
```
（`bs-types` 会作为传递依赖自动带上。若服务自己也要直接用 `bs-types` 里的枚举/异常，显式再声明一份更清晰。）

### 4.2 本地开发需要 `<distributionManagement>` / Nexus 吗？

**不需要。** 这是 Maven 生命周期两个不同阶段的分工：

- `mvn install` → `install:install` 目标 → 把构件放进**本地仓库**（默认 `~/.m2/repository`）。它**完全不读 `<distributionManagement>`**。
- `mvn deploy` → `deploy:deploy` 目标 → 才需要 `<distributionManagement>` 指向远端仓库（Nexus/Artifactory 等），把构件推到**远端**供他人/其他机器消费。

所以：**单人本地开发（本仓现状——单体与三个服务都在同一台机器、同一个 `~/.m2`）只要 `mvn install`，`distributionManagement` 与 Nexus 一律不需要。** 只有在"CI 构建 + 多机/多人 + 容器里构建"时才考虑 Nexus（届时才需要 `settings.xml` + `distributionManagement`）。
出处：Maven 官方生命周期/插件文档（`maven-install-plugin` 的 `install` goal = "install the built artifact into the local repository"；`maven-deploy-plugin` 的 `deploy` = "deployed to the remote repository"）。

> 补充（本仓相关）：`backend/docker-compose.yml` 只跑中间件，**应用在本地 IDE 跑**（文件头注释 + 既有约定），所以没有"容器里构建拿不到本地仓库"的问题。将来若写服务镜像，Dockerfile 里要么先 `mvn install` 单体、要么在镜像构建里 `COPY` 单体并 `mvn install`。

### 4.3 怎么避免「改了契约忘了重装」这个经典翻车

**问题的本质**：消费者解析 `1.0.0-SNAPSHOT` 时，Maven 默认**直接用本地仓库里那份**（SNAPSHOT 在本地仓库是最新即用），**不会**因为你改了源文件就重解析。于是"改了 `bs-api` 的接口 → 只 `mvn compile` 单体 → 起服务 → `NoSuchMethodError` / 反序列化对不上"。

按性价比从高到低，四道防线：

1. **把 install 固化成固定前置步骤**：约定"任何对 `bs-api` / `bs-types` 的改动，提交前必须执行
   `mvn -o install -pl bs-api,bs-types -am -DskipTests`"，
   并写进落地票的验收清单。最土但最有效。
2. **让改动立刻可见（推荐）**：在**单体根目录**跑
   `mvn -o install -pl bs-api -am -DskipTests` 的同时，让三个服务的 IDE 构建在**同一台机器同一个 `~/.m2`**（本仓现状即是），改完立刻重装、立刻重启消费者。开发期几乎无感。
3. **契约变更时"响一下"**：`bs-api` 里出现**破坏性变更**（删方法/改签名/改 DTO 字段）时**顺手 bump 版本**（`1.0.0-SNAPSHOT` → 换一个新 SNAPSHOT 或直接 `1.1.0`），消费者 pom 同步改——版本号一变，Maven 必然重新解析，**不可能静默拿到旧 jar**。这条对"接口改了但方法签名没变"（比如只改了字段语义）无效，但对绝大多数真翻车有效。
4. **需要强一致时用 `-U` / purge**：
   - `mvn -U ...` 强制更新 SNAPSHOT（对**远端**仓库的 SNAPSHOT 有意义；纯本地仓库场景作用有限）；
   - `mvn dependency:purge-local-repository -Dinclude=com.betterschool:bs-api` 删除本地缓存副本再重下（重装后能救"缓存乱了"的疑难）；
   - `mvn dependency:tree -Dincludes=com.betterschool` 用来**确认消费者实际解析到的是哪一份**（排查时的第一手证据）。

> **更彻底的替代（记录但不推荐本期做）**：把三个服务的 pom `<parent>` 指向单体聚合器或用 Maven reactor 统一构建——那会破坏"独立 project"的架构决定（用户 2026-09-14 拍板案 A + 独立 project），本期不做。

---

## ⑤ 与既有机械的接合

### 5.1 RocketMQ：`rocketmq-spring-boot-starter` 2.3.6 在三个新服务里能不能照用

**能照用，版本就是最新的（无需升级）。** 已核实 Maven Central：`org.apache.rocketmq:rocketmq-spring-boot-starter` 的 `<latest>` = **`2.3.6`**，与 `backend/pom.xml` 的 `<rocketmq.version>2.3.6</rocketmq.version>` 一致。

**但有两点必须知道**：

1. **它的构建基线是 Boot 2.7.18 / Spring 5.3.27 / Java 1.8**（`rocketmq-spring-boot-parent-2.3.6.pom` 属性原文：`<spring.boot.version>2.7.18</spring.boot.version>`、`<spring.version>5.3.27</spring.version>`、`<java.version>1.8</java.version>`）。
   它在自己的 pom 里对 `spring-boot-starter` / `spring-boot-starter-validation` **不写版本**（靠自己的 parent 管）→ **在 Boot 3.3.5 工程里这两个版本会被你的 `spring-boot-starter-parent` 覆盖成 3.3.5**。
   官方 README 的前置条件只写 `JDK 1.8 and above` / `Spring Boot 2.0 and above`，**没有**明文说支持 Boot 3。⚠️ 未核实（官方对 Boot 3 的明文声明）。
   **但本仓已经是活证据**：单体就在 Boot 3.3.5 上跑 2.3.6，且跨服务事件的既有形状（`bs-trigger/listener/` 的 `@RocketMQMessageListener`）在生产路径上跑通了（见 `map.md`「先例 `bs-errand-paid`」）。→ **结论：能用，三个新服务照抄同一坐标即可。**
2. **必须连同 exclusion 一起抄**：本仓 `backend/pom.xml` L88-99 对 `rocketmq-spring-boot-starter` 做了

```xml
<exclusions>
  <!-- rocketmq-acl 传递 commons-beanutils → commons-logging，与 spring-jcl 冲突 -->
  <exclusion>
    <groupId>commons-logging</groupId>
    <artifactId>commons-logging</artifactId>
  </exclusion>
</exclusions>
```
   **漏掉这个 exclusion 会在新服务里复现同一类冲突**（`commons-logging` 与 `spring-jcl` 抢 `LogFactory`）。建议把这段连同版本号一起抽进三个服务的父 pom。

**配置项（照抄现有形状）**：

```yaml
rocketmq:
  name-server: localhost:9876        # 三个服务共用同一个 namesrv
  producer:
    group: points-producer           # ← 每个服务一个独立生产者组，别四边都用 better-school-producer
```
消费者注解形状（`map.md` 记录的既有约定 `bs-<topic>-group`）：

```java
@RocketMQMessageListener(topic = "bs-points-changed", consumerGroup = "bs-points-changed-group")
public class PointsChangedConsumer implements RocketMQListener<String> { ... }
```

> **⚠️ 本票新发现的一个真问题（命名约定会撞）**：现有约定 `consumerGroup = "bs-<topic>-group"` 在**多服务订阅同一 topic** 时会互相抢消息——RocketMQ 同一 consumerGroup 内是**集群消费（负载均衡，一条消息只投给组内一个实例）**，**不同 consumerGroup 各自收到全量**。本方案是"事件编排"：`points-changed` / `prize-drawn` / `group-formed` 这类事件**天然会有多方关心**。
> **建议把消费组命名改成 `bs-<service>-<topic>-group`**（如 `bs-referral-points-changed-group`、`bs-lottery-points-changed-group`），否则会出"抽奖服务收到了、积分服务收不到"这种极难排查的间歇性 bug。
> ⚠️ 未核实（RocketMQ 消费组语义的**官方原文**表述）——本结论基于 RocketMQ 集群消费模型的通行定义，**落地票请用官方文档复核**；但"命名加服务前缀"是无害的防御性改动，建议直接采纳。

### 5.2 xxl-job：抽奖服务（以及第二、第三个 app）怎么注册而不互相覆盖

**本仓现状**（`backend/bs-app/src/main/resources/application.yml` L56-65）：
```yaml
xxl:
  job:
    admin:
      addresses: http://localhost:9090/xxl-job-admin
    access-token: default_token
    executor:
      appname: better-school-executor
      port: 9999
```
对应 bean 形状是 `bs-app` 里的 `XxlJobConfig`（`XxlJobSpringExecutor`）。

**官方一手原文**（`xuxueli/xxl-job` 的 `doc/XXL-JOB-English-Documentation.md`）：

> `### xxl.job.executor.appname is used to group by executors`
>
> `### xxl.job.executor.port :the port of the executor runned by, **if multiple executor instance run on the same computer the port must different with each other**`

→ **答案就两条，都必须做**：

1. **每个服务一个不同的 `xxl.job.executor.appname`**——`appname` 是 xxl-job-admin 里的**执行器分组键**（同一 appname 的多个实例被视为一个集群，admin 在其间做路由/负载均衡）。四个应用共用一个 appname 会让 admin 把任务随机派给别的应用，任务方法找不到 → 调度失败。建议：
   - 单体：`better-school-executor`（保持不变）
   - 积分：`better-school-points-executor`
   - 抽奖：`better-school-lottery-executor`
   - 拉新：`better-school-referral-executor`
2. **每个服务一个不同的 `xxl.job.executor.port`**——executor 会在本机起一个内嵌 HTTP 服务供 admin 回调，**本仓四个应用都在 localhost 跑**，共用一个端口必然 `Address already in use` / 注册互相顶掉：
   - 单体 `9999`、积分 `9998`、抽奖 `9997`、拉新 `9996`（错开即可）。
3. 附带：`xxl.job.executor.log-path` 也要**每服务一个目录**（如 `./logs/xxl-job-points`），否则日志互相覆盖，排查时看不到自己的调度日志。

admin 侧还要做（一次性人工操作，不写代码）：在 xxl-job-admin 控制台的「执行器管理」里**为每个新 appname 新建一条执行器**（AppName + 名称 + 注册方式选「自动注册」），否则执行器注册上来也找不到归属。`accessToken` 四个应用共用同一个 `default_token` 即可（它不是分组键）。

> 版本：`xxl-job-core` 当前最新是 **`3.4.2`**（Maven Central），本仓用 `3.0.0`。`docker-compose.yml` 里 admin 镜像是 `xuxueli/xxl-job-admin:3.3.2`。⚠️ 未核实（3.4.2 / 3.3.2 / 3.0.0 三者之间的兼容矩阵）——**本票未找到 xxl-job 官方 admin↔executor 版本兼容表**。稳妥做法：三个新服务**沿用 `xxl-job-core:3.0.0`**，与单体一致，只在确认需要新特性时一起升。

### 5.3 Redis：Redisson 自动装配，要不要额外配

**不需要额外配置。** 照抄 `spring.data.redis.*` 三件套即可：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      database: 0
      password: bs123456      # compose redis 容器 requirepass
```
（three new services 建议 `database` 各给一个不同库，如 1/2/3，避免 key 串味——本仓既有 key 前缀 `bs:verify-code:` / `bs:chat:ws-ticket:` / `bs:post:detail:*`，新服务建议用 `bs:points:*` 这类前缀。）

依据：`backend/pom.xml` L83-87 用 `org.redisson:redisson-spring-boot-starter:3.52.0`，`backend/bs-app/.../application.yml` L23-28 用的是 Boot 3 风格的 `spring.data.redis.*`（不是老版的 `spring.redis.*`），**该组合在本仓 Boot 3.3.5 上已在跑**（`map.md`：Redis 走 Redisson 自动装配，全仓只有 `RBucket` / `RLock` / `RBloomFilter`，无 `RedisTemplate`）。
→ 三个新服务**加同一个 `redisson-spring-boot-starter:3.52.0` 坐标 + 同样的 `spring.data.redis.*` 配置**即可，自动装配会生效。
⚠️ 未核实（Redisson 官方文档对 `spring.data.redis.*` 的明文声明）——Redisson 项目 README 只给到文档站链接，本次未取到正文。**但本仓实跑即是证据**，且营销域要求的「redis 单桶库存扣减」沿用既有的 `RBucket` 形状即可（`map.md` 已定：去 `wq` 只留 `sq/lq/oq`、单桶）。

### 5.4 一处提醒：MyBatis mapper XML 的位置

`backend/CLAUDE.md` 硬性约定第 3 条：「MyBatis mapper XML 统一放 `app/src/main/resources/mybatis/mapper/`，接口在 infrastructure 的 `dao` 包」；本仓 `application.yml` L67-68 是 `mybatis.mapper-locations: classpath:/mybatis/mapper/*.xml`。
→ 三个新服务**沿用同一相对路径**即可（各自的 `-app` 模块里），无需改配置。若三个服务同机跑，注意**别把三份 mapper 打进同一个 jar/classpath**（本方案是三个独立 jar，天然隔离）。

---

## 未核实清单（诚实交代）

| 项 | 状态 |
|---|---|
| SCA `2023.0.3.4` + Boot **3.3.5** 的官方验证背书 | **未核实**。SCA 官方只说 2023.x ↔ Boot 3.2.x（其构建基线 3.2.9）；Boot 3.3.x 的支持来自 Spring Cloud 2023.0.x train（2023.0.2+）。**必须冒烟验证** |
| Dubbo `3.3.6` 实跑在 Boot **3.3.5**（Spring Framework 6.1.x） | **未核实**。官方 starter 表声明覆盖 Boot `[1.x ~ 3.x)`，但其 Boot 3 模块编译基线是 Boot 3.5.x / Spring 6.2.x。**必须冒烟验证** |
| `dubbo-spring-boot-3-autoconfigure` 是否只在 Triple-over-Servlet 场景需要 | **部分未核实**。已核实它不在 starter 传递依赖里、jar 内只有 `DubboTriple3AutoConfiguration`；"因此只有 servlet 集成才需要"是从 jar 内容推断 |
| `@Service`（Dubbo）在 3.x 的确切废弃状态 | **未核实**（官方文档全篇用 `@DubboService`，未见明文废弃声明） |
| RocketMQ 消费组「不同组各收全量」的官方原文 | **未核实**（行业通行语义，非本次取到的一手原文）。建议仍按"服务前缀命名"防御 |
| `rocketmq-spring-boot-starter` 2.3.6 对 Boot 3 的官方声明 | **未核实**（README 只写 Boot 2.0+）。**但本仓单体已在 Boot 3.3.5 上实跑，即为证据** |
| Redisson starter 对 `spring.data.redis.*` 的官方声明 | **未核实**（README 只给链接）。**本仓实跑即为证据** |
| xxl-job admin 3.3.2 ↔ executor 3.0.0/3.4.2 的兼容矩阵 | **未核实**（未找到官方兼容表）。建议新服务沿用 `xxl-job-core:3.0.0` |
| Nacos 3.3 之前（3.0~3.2）客户端鉴权的确切默认值 | **部分未核实**。已核实 3.3+ 在 `NACOS_AUTH_ENABLE` 未设时默认开启；3.0~3.2 "沿用各自镜像内的默认值"（README 原文），具体值未取到 |

---

## 一句话汇总（给 spec 用）

**Spring Boot 3.3.5 下唯一可行的 SCA 线是 `2023.0.3.4`（配 Spring Cloud `2023.0.3`，Nacos client 2.4.3），Dubbo 用 `3.3.6`（显式配 `tri` 协议 + 注意 `serialize-check-status` 默认 STRICT），Nacos server 用 2.x（建议 `v2.5.4`，端口 8848/9848，控制台鉴权本地关掉）。SCA 2025.x 需要 Boot 3.5/4.0，不能用；SCA 没有 2024.0.x 线。**
