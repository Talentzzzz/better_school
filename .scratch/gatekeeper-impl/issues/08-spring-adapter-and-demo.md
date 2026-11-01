# 08: 参考接入器（gatekeeper-spring）+ gatekeeper-demo

**What to build:** spec §9 的 Spring 接入器与演示模块。端到端行为：**一个 Spring MVC 应用加 `gatekeeper-spring` 依赖即接入限流——注解方法与 URL 级规则都能拦，被拒拿 429 + Retry-After；demo 应用一跑，「拦得下来 + 杀了 center 还活着」两个口径当场可见**。

机制逐条照 spec §9：

- **三者无第二套放行路径**：显式 API 是底座（`client.tryAcquire(finalKey)` → `Probe`）→ 注解 `@RateLimit(rule=…, key=…)` 是显式 API 的语法糖（**只做方法级**，不写 key 默认 = 身份占位符）→ 拦截器是注解的宿主 + URL 级规则承载者（方法上有注解用注解、没有按 URL 规则匹配 → 解析最终 key → `tryAcquire` → 拒绝走 `RejectionHandler`）。**Filter 不做；只做 Spring MVC（Servlet），WebFlux 不做**。
- **key 规范化**：路径模板化**读 MVC 现成的最佳匹配模板**（`HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE`），不自写分段判定；身份 = **`KeyResolver` SPI + 两内置**（读 Spring Security 上下文的 principal / 客户端 IP 兜底）；占位符两类（路径变量 + 身份）可同用。
- **合一 starter**：`@AutoConfiguration` + `@ConditionalOnClass(GatekeeperClient)` + `@ConditionalOnProperty(gatekeeper.enabled, matchIfMissing=true)`；`gatekeeper-client` 保持零 Spring 依赖（Spring 依赖全收在 spring 模块）；**接入方依赖闭包 = protocol + config + client + spring、永不含 center**。
- **拒绝响应**：`RejectionHandler` SPI（收 `(request, response, rule, finalKey, probe)`），默认实现 = HTTP 429 + `Retry-After: ceil(nanosToWaitForNextToken → 秒)、至少 1` + `{"code":"RATE_LIMITED"}`；拒绝日志每实例每规则每 `refresh_interval` ≤ 1 条 WARN（rule / finalKey / 窗口内拒绝计数 / Probe 三字段）。
- **演示模块 `gatekeeper-demo`**（gatekeeper/ 仓内、不碰 better-school）：一条规则 `rate=2/s, burstSeconds=1.0`（单实例份额 2/s、桶容量 2），10 个请求半秒内连打 → **放行 ≤ 3、其余 429**；单 key（单分片）约 10s 窗口内 `/v1/lease` 请求 ≤ 3（首租 1 + 两轮续租）。README 给 curl 步骤。**「本地优先」两观测点**：① 比值口径——放行次数 vs 中心 `/v1/lease` 次数与放行次数无关；② 断中心口径——kill center 后继续按最后份额放行。
- 配置面照 spec §9 的 yaml 块（`gatekeeper.enabled` / `center.addresses` / `rules.source` / `refresh-interval`）。

**Blocked by:** 02、05（底座 API 与规则匹配就位；demo 的断中心口径依赖端到端续租通道）

## 结构硬输入（2026-09-22 起，[ADR-0007](../../docs/adr/0007-gatekeeper-layering-and-protocol-module.md)）

- **命名**：契约模块已由 `gatekeeper-api` 改名 **`gatekeeper-protocol`**（包 `io.gatekeeper.protocol`），`Probe` 已迁入 `gatekeeper-client`（包 `io.gatekeeper.client`）。本票的 `RejectionHandler` 签名里那两个类型分别来自 client（`Probe`）与 protocol（`Rule`）。
- **`gatekeeper-spring` 不套 DDD**（它是接入件：注解 + 拦截器 + `AutoConfiguration`，没有域），**`gatekeeper-demo` 只套两节**（`backend/CLAUDE.md` §6 根包 `Application` + §4 `trigger/http`）。见 [`gatekeeper/CLAUDE.md`](../../gatekeeper/CLAUDE.md)。
- **⚠️ 必须给 `gatekeeper-spring` 挂 enforcer**：禁依赖 `io.gatekeeper:gatekeeper-center-*`——「闭包永不含 center」这条目前只由 `protocol`/`client` 守着，spring 一进来就成了新的漏水点。（`center-*` 侧已禁依赖 `gatekeeper-spring`，两侧夹住。）
- **⚠️ 需要 client 暴露「只读规则集快照」**：拦截器做「没有注解就按 URL 规则匹配」时得拿到当前 `RuleSet`，而规则订阅归 `client`（spec §2）。请让 `client` 暴露一个只读入口，**不要让 spring 自己再订阅一份配置**——两份订阅 = 两份热更新时序 = 规则版本错位的新来源（spec §5 只接受「一个 refresh_interval 的错位窗口、只观测不阻断」）。票 01 的 `GatekeeperClient` 对外的面里还没有这一格。

**Status:** resolved

- [x] starter 自动装配 IT：加依赖即生效、`gatekeeper.enabled=false` 全关、条件注解行为正确
- [x] 注解 + 拦截器 IT：方法级注解拦截、URL 级规则拦截、注解优先于 URL 规则、`{postId}`/`{userId}`/`{ip}` 占位符解析正确
- [x] 拒绝响应 IT：429 + Retry-After 上取整 ≥ 1 + `{"code":"RATE_LIMITED"}`；换 `RejectionHandler` SPI 可换形状
- [x] 依赖纪律核验：接入方闭包 = protocol + config + client + spring，永不含 center
- [x] demo 两观测点当场跑通：10 打放行 ≤3 其余 429；kill center 继续放行；README curl 步骤可照做
- [x] `mvn test` 全绿

## Answer

**落地点**：

| 模块 | 新增/改动 | 装什么 |
|---|---|---|
| `gatekeeper-spring`（新模块） | **`@RateLimit`**（方法级，`rule`/`key` 两属性）/ **`KeyResolver`** SPI + **`PrincipalKeyResolver`**（读 Spring Security，spring-security-core 为 optional 依赖、`@ConditionalOnClass` 才注册）/ **`AnonymousKeyResolver`**（`remoteAddr`，不解析可伪造的 XFF）/ **`RejectionHandler`** SPI + **`DefaultRejectionHandler`**（429 + Retry-After 上取整至少 1 + `{"code":"RATE_LIMITED"}`）/ **`RateLimitInterceptor`** / **`FinalKeys`**（key 合成，包内）/ **`RejectionWarn`** + `OnceWarner`（日志节流）/ **`GatekeeperAutoConfiguration`** + **`GatekeeperProperties`** + `AutoConfiguration.imports` | 拦截器是注解宿主 + URL 规则承载者，两条路汇到同一处 `tryAcquire`（无第二套放行路径）；URL 规则匹配输入 = **MVC 最佳匹配模板**（`BEST_MATCHING_PATTERN_ATTRIBUTE`，不自写分段判定）；enforcer 禁 `gatekeeper-center-*`（接入方闭包永不含 center 的 spring 侧新漏水点）；接入方自定义任一 `KeyResolver` ⇒ 两内置整体让位，`RejectionHandler`/client/规则源/拦截器都有 `@ConditionalOnMissingBean` |
| `gatekeeper-demo`（新模块） | `Application`（只套根包 + `trigger/http` 两节）+ `DemoController`（`/demo/hello` URL 规则拦、`/demo/post/{postId}` 注解拦，同一条规则）+ `gatekeeper-rules.yml`（一条规则 `rate=2/s, burstSeconds=1.0`）+ `gatekeeper-topology.yml` + README（curl 步骤逐条）| **`DemoObservationTest`**：真 center + 真 demo 同 JVM 走真 HTTP——① 10 打放行 2~3 其余 429；② 约 10s 窗口内 center `/v1/lease` 请求数 ∈ [2,4]（首租 1 + 两轮续租，与放行次数无关，从 actuator prometheus 文本抓 `http_server_requests_seconds_count{uri="/v1/lease"}` 差值）；③ `center.close()` 后继续放行、绝无 5xx |
| `gatekeeper-protocol` | `RuleSet.byId(String)`（code-review 采纳，见下） | id 唯一性构造期已有保证 ⇒ by-id 查询长在 RuleSet 上（O(1)），拦截器不再每请求 O(n) 扫表 |
| `gatekeeper-center-app` | spring-boot repackage 加 `classifier=exec` | repackage 会把类藏进 `BOOT-INF/classes`，demo 的 test 依赖拿不到任何类；`-exec.jar` 另存可执行 jar、主 artifact 保持普通 jar（部署面不变，`spring-boot:run` 照常） |
| `gatekeeper/pom.xml` | modules 加 spring/demo；dependencyManagement 补 spring | — |
| `gatekeeper/CLAUDE.md` §1 | 方向行改 `spring → client, protocol, config`（starter 装配配置源要用 config 实现）；记 demo test→center-app 这条唯一 test 作用域例外 | — |

**key 合成公式（票面没定 URL 规则的 key 公式，此处拍板、可反转）**：注解与 URL 规则同一公式——**exact 规则**：最终 key = pattern 逐字（整条规则一格，没有身份粒度；pattern 后拼了任何东西就再也匹配不回这条规则）；**prefix 规则**：模板解析值已以 pattern 开头（作者写全了形如 `post-detail:{userId}`，票 10 的例子）→ 就是它，否则补成 `pattern|解析值`——保证客户端与中心都按 `RuleSet.match(finalKey)` 命中同一条规则。注解 key 缺省 = 身份解析链第一个供上值的 resolver（有 principal 用 principal、否则 IP）。**URL 规则的 key 粒度 = 规则 pattern + 身份**（同前缀各路径共享一格——规则的 scope 是 pattern 本身，有专测钉住；要按路径/路径变量区分粒度请用注解写 key 模板）。占位符两类可同用：路径变量取 URI 模板变量属性、身份占位符由 KeyResolver 供值，都不认识的名字保留字面量 + 告警一次。

**验收**：`mvn test` **252 测试全绿**（票 07 收口时 232；本票新增 20 条：protocol 1（byId）+ spring 18 + demo 1）。测试布局：`StarterAutoConfigurationTest`（ApplicationContextRunner：默认装配/enabled=false/FilteredClassLoader 摘掉 GatekeeperClient 类验证 `@ConditionalOnClass` 退场/自定义 KeyResolver 整体换内置/未知规则源拒启）；`InterceptorAdmissionTest`（MockMvc + stub 中心录 key 形状：注解缺省身份、principal 供 `{userId}`、`{postId}` 路径变量、URL 规则、注解优先、exact 一格、同前缀共享一格、无规则与未知规则 id 都不问中心）；`RejectionResponseTest` + `CustomRejectionShapeTest`（真 Servlet 容器：默认形状 + Retry-After=1（rate 2/s）+ Retry-After=4（rate 0.25/s 钉上取整 >1 的确定路径：容量下限兜 1 ⇒ 第 2 发拒、攒令牌 4s）+ 换 SPI 换形状）；demo 的 `DemoObservationTest` 见上。

**实测平台事实 / 踩坑（重跑会再撞的）**：

1. **MockMvc 的 Security 过滤链会重置测试线程预设的 `SecurityContextHolder`**——`securityContext`/`authentication` 请求后处理器也照样被 `SecurityContextHolderFilter` 盖掉。IT 用 `@AutoConfigureMockMvc(addFilters = false)`（拦截器在 DispatcherServlet 内、不需要过滤链）+ 测试线程直接 setContext。
2. **javac 的 static import 与 `org.springframework.test.web.servlet.MockMvc` 的普通 import 同文件并存时**，`SecurityMockMvcRequestPostProcessors.authentication` 的静态导入解析失败（javap 看得到方法、最小复现 T2 通过、全源编译必挂——未深究根因）。绕开：用限定名调用，不用静态导入。
3. **`SpringApplicationBuilder.properties()` 是 defaultProperties，会被 application.yml 盖掉**（优先级低于配置文件）——测试里起第二个 Boot 上下文要用 `.run("--key=value")` 命令行参数（最高优先级）。
4. **同一 JVM 里起两个 Boot 上下文，两者共享 classpath**：demo 的 `application.yml` 会盖住 center 自己那份（actuator 配置因此丢失）⇒ center 的 actuator 三个开关在 IT 里显式用命令行参数传。
5. **RestTemplate 对 4xx/5xx 抛异常**：断言 429 的用例要 catch `HttpStatusCodeException` 取状态码。

**code-review 采纳项（两轴）**：

- **Standards 轴**：① spring 主代码直接 import protocol/config 却只声明 client（靠传递依赖）⇒ 显式声明三兄弟，方向行同步进 `gatekeeper/CLAUDE.md` §1；② demo test→center-app 边不在家庭层方向表 ⇒ 写进 §1 作为唯一 test 作用域例外（compile/runtime 闭包纪律由 enforcer 守，不受影响）；③ `ruleById` Feature Envy（每请求 O(n) 扫 `rules()`）⇒ `RuleSet.byId`（protocol，O(1) 查表）；④ `RejectionWarn` 窗口计数只写不读（日志里恒为 1）+ 判 1 在 compute 外有竞态（并发的第二条拒绝可能吃掉唯一那条 WARN）⇒ 改为**新窗口首条拒绝时报刚结束窗口的累计总数**（计数真实可读、稀疏拒绝不丢、判定在 compute 原子块内）；⑤ 默认身份两次解析（先解析选 resolver 再按模板重解）⇒ `defaultIdentityValue` 直接返回值（compose 对无占位符 suffix 恒等）；⑥ `StubCenter.leaseRequestCount()` 死代码 ⇒ 删。
- **Spec 轴**：① `@ConditionalOnClass` 行为无用例 ⇒ `FilteredClassLoader` 摘类验证退场；② Retry-After 上取整只被压到地板值 1 ⇒ 补 rate 0.25/s 的确定路径（见验收）；③ `addresses` 复数形只取第一支且静默 ⇒ 多支时 WARN 指路（v1 单分片，多分片走拓扑 bean）；④ URL 规则「同前缀共享一格」无测试无文档 ⇒ 专测 + FinalKeys javadoc 明示粒度语义。
- **判为 judgement call 记档不采纳**：demo IT 起真 center 改独立进程（同 JVM 夹具已把两观测点钉死且与票面「当场跑通」一致，独立进程是票 10 harness 的活）。

**自行拍板的空白（都可反转）**：① 未知 `rules.source` 值拒绝启动（与 center 的枚举同口径，配置错误早失败）；② `classpath:` 规则文件解析成文件路径，打进 jar 的资源给可读错误（mtime 轮询没法做）；③ `RejectionHandler` 默认实现 `Content-Type: application/json`、body 逐字 spec；④ 拒绝日志节流无专项测试（log-only 路径，语义已由实现注释钉住）；⑤ demo IT 用固定端口 18765（拓扑 `self` 要逐字进文件、随机端口进不去；撞车仅在同机已有 18765 服务时发生）。

**已知波及 / 不在本票范围**：拒绝计数进 MXBean 归票 09（`RejectionWarn` 的窗口计数即落点）；`client.remove` 在 IT 里当测试钩子用（正式的逐出口径归规则删除/unmatched/关停）；断中心口径只测了 ttl 内的「被动继续」（长窗口下份额耗尽后的行为归票 10 harness 的分区臂）；多分片接入面（拓扑 bean 装配 + 多支地址生效）留待真有多分片部署时验证。
