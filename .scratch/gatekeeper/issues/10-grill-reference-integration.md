# 10 grilling：参考接入器的形状

Type: grilling
Status: resolved
Blocked by: 05, 07

## Question

"通用件"得能挂到真实入口上。图绘期已定：spec 里含一节**参考接入器**（形状 + 一个能真拦下来的演示场景），但**不含** better-school 里改哪几行——那是落地票的事。

要裁决：

1. **接入形态**：三者是什么关系，各提供什么
   - Spring MVC 拦截器 / Filter（框架自动拦）
   - 注解（`@RateLimit(key = "...", rule = "...")`，声明式）
   - 显式 API（`limiter.tryAcquire(key)`，命令式——也是 MQ / 非 Web 场景唯一的接法）
   注解是显式 API 的语法糖？拦截器是注解的宿主？三者的取舍与共存方式
2. **key 从哪来、怎么规范化**：用户身份从哪取（Spring Security 上下文？自定义 resolver？）；`/api/post/123` 与 `/api/post/456` 要不要算同一个 key（路径参数模板化）——与票 07 的"key 表达式能力边界"对齐
3. **与 Spring 的接法**：SDK 怎么暴露为 starter（`@AutoConfiguration` + `@ConditionalOnClass` / 条件装配）；配置怎么写（`gatekeeper.center.addresses` / 规则源）；**接入方要不要引 `gatekeeper-center` 的依赖**（必须不要——客户端只引 `gatekeeper-api` + `-client`）
4. **被限流时的响应形状**：HTTP 429 + `Retry-After`？还是留一个可插拔的"拒绝处理器"让接入方决定响应体（通用件不宜规定业务错误码）；日志要打什么（key / 规则 / 本地桶水位）
5. **演示场景**：写进 spec 作为参考接入的验收——一个 HTTP 入口 + 一条规则，能真的拦下来；以及**如何证明"本地优先"确实生效**（观测点：单位时间内的中心请求次数远小于放行次数）

> ⚠️ **2026-09-22 [票 05](05-grill-local-bucket-and-admission.md) 已裁（本票前置已清），接住接入器形状的四条**：① 放行 API 是 **`tryAcquire` → `Probe{allowed, remainingTokens, nanosToWaitForNextToken}`**（票 05 Q2 / Q8），**拦截器与注解只用 `tryAcquire`**，布尔是 `probe.allowed()` 便捷方法；② 日志要打的三样（key / 规则 / 桶水位）**`Probe` 三个字段正好够，不必二次查询**（票 05 Q2）；③ `acquire(timeout)` 只给非 Web 场景——**纯本地等、不碰网络**（票 05 Q2）；④ Q5"证明本地优先生效"多了一个观测点：**首租之后，中心请求次数应该只剩"每实例每 `refresh_interval` 一次"**（票 05 Q9 的批量续租），与放行次数无关；⑤ Q4 的拒绝响应仍是本票定，但票 05 给了两个可直接用的输入：`Probe.remainingTokens` 与 `nanosToWaitForNextToken` 足以支撑 `Retry-After`（若接入方要）——**通用件本身只提供可插拔的拒绝处理器，不规定业务错误码**。

> ⚠️ **2026-09-22 [票 07](07-grill-rule-model-and-hot-reload.md) 已裁（本票前置已清），给本票 Q2 定了责任面**：**占位符模板（`user:{userId}`）与路径规范化（`/api/post/123` vs `/api/post/456`）是接入器层的职责**——核心 SDK 的 `tryAcquire(key)` 只收最终 key 字符串、规则按 `matchType: exact | prefix` 匹配 key（无通配语法；前缀 = 朴素 `startsWith`，文档惯例建议 pattern 以分隔符结尾，核心不做分段边界判定）。模板解析错了是接入器的映射问题、不是限流器的正确性问题——注解 / 拦截器持有模板上下文，这正是它该干的活。

依赖票 05（放行 API 的语义决定接入器形状）。结论落 spec 的"参考接入器"节。

## Answer

> **编号约定**：Q1–Q5 照票面；票面 Q3 裂出一个未列的子决策「模块布局」、票面 Q5 裂出「demo 位置」，编在对应问的子项（Q3-子 / Q5-子），不占新编号；Q2 裂成 (a)(b) 两个子决策。

**决策依据（事实自核，2026-09-22 对 better-school 实测）**：① better-school **有** Spring Security（STATELESS + JWT，principal 为 `Long userId`，取值先例 `SecurityUtils.currentUserId()`），所以「身份从 Spring Security 上下文取」不是假设、是接入方的真实形态；② better-school 的 Servlet MVC 侧**没有任何** HandlerInterceptor / 自定义注解 / AOP 先例（仅 WS 握手拦截器与 JWT 的 `OncePerRequestFilter`，都不是 MVC 拦截器），全靠显式调用——参考接入器是接入方要用的「第一个 MVC 拦截器」，形状要自含；③ 统一响应体先例 = `Response{code, info, data}`（GlobalExceptionHandler）——默认拒绝响应**不**绑这个形状（通用件不猜业务错误码），但换 SPI 时要能表达它；④ 票面 `/api/post/123` 是举例，真实路径形如 `/api/community/posts/{postId}`；⑤ `gatekeeper/spec.md` 尚不存在（票 11 未开）——本票结论供票 11 第 9 节「参考接入器」逐条落。

### Q1 接入形态：三者是什么关系

**选乙：显式 API 是底座，注解是显式 API 的语法糖，拦截器是注解的宿主 + URL 级规则的承载者。**

- **显式 API**：`client.tryAcquire(finalKey)` → `Probe`，布尔即 `probe.allowed()`（票 05 Q2 / Q8）；`acquire(timeout)` 纯本地等，**只给非 Web 场景**（票 05 Q2）。MQ 消费 / 定时任务扇出 map 已裁「不推荐接入」但 API 开放——显式 API 是它们唯一的接法。
- **注解**：`@RateLimit(rule = "...", key = "...")`，**只做方法级**（类级语义模糊，不做）。零限流逻辑，最终只是调 `tryAcquire`——注解与显式 API 之间**没有第二套放行路径**。
- **拦截器**：`HandlerInterceptor`，职责 = ① 方法上有 `@RateLimit` → 用它的 rule/key 模板；② 方法上没有注解 → 按 URL 规则匹配（票 07 的 `matchType: exact | prefix`）；③ 解析最终 key → `tryAcquire` → 拒绝走 `RejectionHandler`。拦截器让「URL 级规则不用改业务代码就能拦」成立。
- **Filter 不做**：Filter 拿不到 handler method，也就拿不到最佳匹配模板——票 07 已裁「路径模板化归接入器层」，选丙会把这条责任面弄丢；better-school 已有的 `OncePerRequestFilter`（JWT）先例与本件无关。
- **边界一句话进 spec**：参考接入器只做 Spring MVC（Servlet）侧，WebFlux 不做；非 Web 场景走显式 API。

### Q2 key 从哪来、怎么规范化

**（a）路径模板化：不自写分段判定，读 MVC 现成的最佳匹配模板。** 拦截器里 `request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)` 一行拿到 `/api/community/posts/{postId}`——Spring MVC 匹配完就放进请求属性了。这与票 07「核心不做分段判定、模板归接入器」对位，且接入器只借 Spring 自己算好的结果，没引入第二套路径解析。非 Web 场景没有模板，key 就是调用方给的原样字符串。

**（b）用户身份：`KeyResolver` SPI + 两个内置实现。** `PrincipalKeyResolver`（读 `SecurityContextHolder`，principal 是什么就用什么——Long 就 Long、String 就 String；better-school 的 `Long userId` 直接适用）与 `AnonymousKeyResolver`（取客户端 IP 兜底）。默认链 = 有身份用身份、无身份用 IP；接入方可整体换 SPI。key 形状由模板决定，如 `post-detail:{userId}` / `post-detail:{ip}`。占位符两类：**路径变量**（如 `{postId}`，取自 MVC 的 URI 变量）与**身份占位符**（`{userId}` / `{ip}`，由 KeyResolver 供值），可同用；注解不写 key 时默认 = 身份占位符。

### Q3 与 Spring 的接法（含 Q3-子：模块布局）

**Q3-子：新增第四个 Maven 模块 `gatekeeper-spring`，starter 与模块合一（否掉"代码 + 空壳 starter 两模块"）。** 拆两个的惟一收益是"接入方只引 starter、不直接看见实现类"，对本 effort 是纯 pom 开销。`gatekeeper-client` 保持**零 Spring 依赖**——与票 09「SDK 零观测依赖」是同一条纪律，Spring 依赖全收进 `gatekeeper-spring`，`gatekeeper-client` 谁都能引。**依赖方向的硬规则落死**：接入方依赖闭包 = `gatekeeper-api` + `gatekeeper-client` + `gatekeeper-spring`，**永不含 `gatekeeper-center`**（票面已定，spec 写成规则不是惯例）。

**装配与配置**：Spring Boot 3.3 的 `@AutoConfiguration` + `META-INF/spring/...AutoConfiguration.imports`；条件 = `@ConditionalOnClass(GatekeeperClient)` + `@ConditionalOnProperty(gatekeeper.enabled, matchIfMissing = true)`。配置前缀 `gatekeeper.*`：

```yaml
gatekeeper:
  enabled: true
  center:
    addresses: http://center-1:8080,http://center-2:8080
  rules:
    source: file          # file | nacos（票 07 双实现二选一，Nacos 依赖 optional）
    path: classpath:gatekeeper-rules.yml
  refresh-interval: 5s
```

### Q4 被限流时的响应形状

**`RejectionHandler` SPI，通用件不规定业务错误码（本票问头 ⚠️ 注 ⑤ 原样落死，其两个输入出自票 05 Q2）；默认实现 = HTTP 429 + `Retry-After`。**

- 接口收 `(request, response, rule, finalKey, probe)`——票 05 给的两个输入 `probe.remainingTokens` / `nanosToWaitForNextToken` 足以支撑 `Retry-After`。
- **默认实现**（在 `gatekeeper-spring` 内）：429 + `Retry-After: ceil(nanosToWaitForNextToken → 秒)、至少 1` + JSON body `{"code":"RATE_LIMITED"}`。body 是通用件自带的形状，**不借用也不猜测接入方的业务错误码**——想换成 `Response{code,info,data}` 同形，换 SPI 即可。
- **拒绝日志不逐条刷**：每实例每规则每 `refresh_interval` 最多一条 WARN，内容 = rule / finalKey / 窗口内拒绝计数 / `probe` 三字段（key / 规则 / 桶水位——`Probe` 三字段正好够、不必二次查询，票 05 Q2）；拒绝计数进 MXBean（票 09 三件套之一）——防刷场景靠计数不靠日志洪水。

```java
public interface RejectionHandler {
    void handle(HttpServletRequest req, HttpServletResponse resp,
                Rule rule, String key, Probe probe) throws IOException;
}
// 默认实现：429 + Retry-After: ceil(nanos→秒) + {"code":"RATE_LIMITED"}
```

### Q5 演示场景（含 Q5-子：demo 位置）

**Q5-子：`gatekeeper-demo` 模块放在 gatekeeper/ 仓内**（一个 Spring Boot 启动类 + 一个假接口 + 本地文件规则源，README 给 curl 步骤）——demo 是通用件自己的资产，放 better-school 里就违反图绘期「不读写 better-school 代码」的根决策；性质照票 09 `measure/` 先例：可重跑、产物不入库。

**「本地优先确实生效」的验收 = 两个观测点，都写进 spec：**

1. **比值口径**：压测窗内放行 N 次，中心 `/v1/lease` 请求次数只剩「首租 1 次 + 每实例每 `refresh_interval` 每分片子批 1 次」（票 05 Q9 批量续租；票 06 按分片拆批——单 key / 单分片时即每实例 1 次），与放行次数无关——中心侧计数从 center 自己的 `/metrics` 读（票 09 已裁 `/metrics` 常开）。
2. **断中心口径（更强）**：把 center 进程 kill 掉，demo 继续按最后份额放行——fail-open 就是"本地优先"的定义本身。演示脚本两步：**拦得下来 + 杀了中心还活着**。

**具体口径**（演示用，非 IT 断言）：一条规则 `rate=2/s, burstSeconds=1.0`（单实例份额 = 2/s，桶容量 = 2），10 个请求在半秒内连打 → 放行 ≤ 3（容量 2 + 半秒内自补 <1 的余量取整）、其余 429；demo 只配 1 个 key（单分片）时，约 10s 窗口内 lease 请求 ≤ 3（首租 1 + 两轮续租）。

### 传播站点（本次已逐处加注记）

- [map](../map.md)：Decisions-so-far 新增本票一行；图绘期「三个 Maven 模块」的形态行加 ⚠️ 增补注（第四模块 `gatekeeper-spring` + `gatekeeper-demo`，细化非反转）
- [票 05](05-grill-local-bucket-and-admission.md)「给下游票的硬输入·票 10」行：加 ⚠️ 已裁注（三条全部采纳 + 演示观测点扩为两个）
- [票 07](07-grill-rule-model-and-hot-reload.md)「给下游票的硬输入·票 10」行：加 ⚠️ 已裁注（模板化读 MVC 现成最佳匹配模板，未自写分段判定，责任面与票 07 预期一致）
- **无反转**：本票未反转任何上游口径；票面 Q3 的「接入方绝不引 center」原样落死进 Q3 结论
- spec 尚未存在（票 11 未开）：本票结论供票 11 章节建议第 9 节「参考接入器——接入形态、key 规范化、Spring 接法、演示场景」逐条落，该节清单无需改动
