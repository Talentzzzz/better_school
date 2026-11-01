# 01 调研：业界"本地桶 + 中心配额"方案对照

_调研日期 2026-09-22。承接 [00-lease-reclaim-prior-art.md](00-lease-reclaim-prior-art.md)，覆盖[票 01](../issues/01-research-prior-art.md) 的六项清单。_

**读法约定**：所有引用块 `>` 内为**逐字原文**（未做转述），后跟来源。行号对应本报告写作当天各仓库 `master`/`main` 分支的 HEAD。凡属推断的，一律显式标注「**推断**」。

**最重要的一个发现先说**：业界最贴近本设计（gatekeeper）形态的**不是** Doorman，而是 **Envoy 的 Rate Limit Quota Service（RLQS）**——它就是"客户端本地桶 + 中心下发带 TTL 的配额 + 客户端周期上报用量 + 服务端重算后主动推送"，并且协议里**显式定义了服务端主动 Abandon（召回）**、**显式定义了服务端重启前如何让客户端降级**。00 号结论"不存在服务端主动召回"需要按这条证据修正（见本报告 §2.4）。

---

## 0. 来源清单

| # | 实现 | 一手来源 | 本报告引用处 |
|---|---|---|---|
| A | Doorman（YouTube/Google） | 仓库 https://github.com/youtube/doorman —— `README.md`、`doc/design.md`、`doc/algorithms.md`、`doc/configuration.md`、`proto/doorman/doorman.proto`、`go/server/doorman/{algorithm,resource,store,server}.go`、`go/client/doorman/client.go`、`go/ratelimiter/adaptive_ratelimiter.go`（文件 URL 前缀 `https://raw.githubusercontent.com/youtube/doorman/master/`） | §1 |
| B | Alibaba Sentinel 集群流控 | 仓库 https://github.com/alibaba/Sentinel —— `sentinel-cluster/**`、`sentinel-core/src/main/java/com/alibaba/csp/sentinel/slots/block/flow/FlowRuleChecker.java`（文件 URL 前缀 `https://raw.githubusercontent.com/alibaba/Sentinel/master/`）；官方 wiki 中文页 https://github.com/alibaba/Sentinel/wiki/集群流控 ；官网 https://sentinelguard.io/zh-cn/docs/cluster-flow-control.html | §2.1 |
| C | Envoy RLS（逐请求）+ envoyproxy/ratelimit | https://github.com/envoyproxy/envoy —— `docs/root/intro/arch_overview/other_features/global_rate_limiting.rst`、`api/envoy/extensions/filters/http/ratelimit/v3/rate_limit.proto`；https://github.com/envoyproxy/ratelimit —— `README.md` | §2.2 / §2.3 |
| D | Envoy RLQS（配额租约） | https://github.com/envoyproxy/envoy —— `api/envoy/service/rate_limit_quota/v3/rlqs.proto`、`api/envoy/extensions/filters/http/rate_limit_quota/v3/rate_limit_quota.proto`、`docs/root/configuration/http/http_filters/rate_limit_quota_filter.rst` | §2.4 |
| E | Redisson `RRateLimiter` | https://github.com/redisson/redisson —— `redisson/src/main/java/org/redisson/RedissonRateLimiter.java`、`redisson/src/main/java/org/redisson/api/RRateLimiter.java` | §3.1 |
| F | Bucket4j（Redis 代理 + 优化器） | https://github.com/bucket4j/bucket4j —— `bucket4j-core/src/main/java/io/github/bucket4j/Bucket.java`、`.../distributed/proxy/ProxyManager.java`、`.../distributed/proxy/RecoveryStrategy.java`、`.../distributed/proxy/optimization/**`；`bucket4j-examples/src/test/java/example/distributed/optimizers/batching/RedisBatchingExample.java` | §3.2 |
| G | Cloudflare 滑动窗口 | https://blog.cloudflare.com/counting-things-a-lot-of-different-things/ （「How we built rate limiting capable of scaling to millions of domains」，2017-06-07，Julien Desgats） | §4.1 |
| H | Uber GRL | https://www.uber.com/blog/ubers-rate-limiting-system/ | §4.2 |
| I | Stripe | https://stripe.com/blog/rate-limiters | §4.3 |
| J | AWS API Gateway | https://docs.aws.amazon.com/apigateway/latest/developerguide/api-gateway-request-throttling.html ；可离线核对的快照：https://github.com/awsdocs/amazon-api-gateway-developer-guide/blob/master/doc_source/api-gateway-request-throttling.md | §4.4 |
| K | 中文期刊 DFMF | 郑旭, 范红杰, 柳军飞. 基于微服务的预分配额度限流设计研究[J]. 计算机科学, 2024, 51(6): 346-353. DOI 10.11896/jsjkx.231100125；全文 PDF：https://www.jsjkx.com/CN/article/openArticlePDF.jsp?id=22376 | §5 |

---

## 1. Doorman（YouTube）——速率租约 + 分层树 + 无持久化

### 1.1 配额分配算法

**算法输入输出口径**（`doc/algorithms.md`，第 3 段）：

> The actual apportioning of capacity is done by executing an Algorithm on every request. […] The role of the algorithm is to look at all the clients' resource requirements (*wants*) and outstanding leases (*has*), and determining what the capacity is that can be returned for the request (*gets*). Algorithms guarantee that they will not have more outstanding leases than there is available capacity. This means that sometimes a client can not get the capacity it has a "right" to because Doorman needs to reduce other clients' allocations to make that possible. In that case Doorman returns the capacity it can without overshooting the maximum configured capacity, however after one refresh cycle things should have converged to their equilibrium.

**"每次请求都做 best possible apportionment"的落地**（`README.md`）：服务端把每次 `GetCapacity` 当成一次重新分配的机会——

> The Doorman master server remembers all clients that currently have capacity and whenever a client asks for capacity it inserts the clients request into its memory and runs the algorithm to figure out what this client should get.

（这就是 00 号未核实项 1 的答案，源码级细节见 §7.1。）

**四个算法的形状**（`doc/algorithms.md`，均有配置示例）：
- `NO_ALGORITHM`：原样返回 `wants`。
- `STATIC`：`min(配置的静态容量, wants)`；注意 "For this algorithm the configured resource capacity is not the maximum capacity of the resource, but the maximum capacity to return on every single request."
- `PROPORTIONAL_SHARE`（O(n)）：先保底均分，未用满的余额按 `wants` 比例分给超额者。
- `FAIR_SHARE`（O(n²)）：逐轮满足，尽量让更多客户端被完全满足，剩下的在未满足者之间均分。

**源码口径**（`go/server/doorman/algorithm.go`）：
- `:239` — `unusedCapacity := capacity - store.SumHas() + old.Has`（可用量 = 总容量 − 已发出 + 本客户端自己原先那份）
- `:245-247` — `if store.SumWants() <= capacity || r.Wants <= equalSharePerClient { return store.Assign(..., minF(r.Wants, unusedCapacity), ...) }`
- `:283` — `gets = equalSharePerClient + (r.Wants-equalSharePerClient)*(extraCapacity/extraNeed)`
- `:290-291` — `return store.Assign(r.Client, length, interval, minF(gets, unusedCapacity), r.Wants, r.Subclients)` —— **硬性不超过当前未用量**：`minF(gets, unusedCapacity)`。注释原文（`:285-288`）："We cannot give out more than the currently unused capacity. If that is less than what the algorithm calculated we will adjust this in the next capacity refreshes."

**按历史用量自适应**：出在**客户端**，不在服务端。`go/ratelimiter/adaptive_ratelimiter.go` 的 `AdaptiveQPS` 用近期调用时刻的**加权移动平均**推算 `wants`（`GetWants()` 在 `:131-158`；权重随新近度线性递增，默认窗口 `window: 10 * time.Second` 在 `:100`；`run()` 每窗口调一次 `e.GetWants(arl.opts.window)` 后 `resource.Ask(wants)`，`:53-78`）。`README.md` 对应描述：

> However if you use the rate limiter objects provided by the Doorman clients the desired capacity is determined automatically by observing the behavior of the threads that want to access the resource. This automatic wants determination uses a moving average to smoothen out any spikes.

服务端本身**没有**按历史用量自动调参的算法——`doc/algorithms.md` 明确："None of the current algorithms support priorities or additional named parameters."

### 1.2 租约口径：速率租约（不是令牌租约）

`doc/design.md:106`：

> The clients contact their assigned servers to request a limit for a shared resource. The server responds with a limit L which is valid until a particular time T. This limit is in effect a lease on capacity. If it wants to continue using the resource the client needs to request a new (or changed) limit before its current lease expires.

`README.md`：

> Doorman only gives out capacity for a limited amount of time, in the form of leases. Each capacity grant comes with a lease length: The client is guaranteed that amount of capacity for the duration of the lease. A typical lease length is five minutes. On top of the lease length the Doorman server also returns a refresh interval. This is the interval after which the client is expected to check back in to get a new lease. A typical refresh interval is five seconds.

配置面（`doc/configuration.md`）：`lease_length` "A typical value is 300 seconds."；`refresh_interval` "A typical value is 5 seconds."

**续租 = 重新分配**，不是"再领 N 个"：每次续租都跑一遍算法，`has`（客户端自报当前持有）参与计算（`doorman.proto:30-35` 的 `ResourceRequest{resource_id, priority, has, wants}`、`:47-51` 的 `ResourceResponse{resource_id, gets, safe_capacity}`）。

**服务端对续租频率有硬下限**（`doc/design.md:391`）：

> That said a client may not send more GetCapacity requests for a given resource than once every five seconds. If it does send a request for capacity for a resource inside of five seconds since the last request it is silently ignored. This feature protects the server from running potentially expensive algorithms during client initialisation when multiple resources are created, each potentially leading to a GetCapacity request (depending on how the client library is structured).

### 1.3 超发 / 在飞量控制

**没有"已发出未消耗"的上限。** 找到的三条相关机制都不是这件事：

1. **算法层面的账**只是"未用量"：见 §1.1 的 `minF(gets, unusedCapacity)`。
2. **已知会超发，且明确不治**——`doc/design.md` 的 "Convergence and shortfall" 与 "Shortfall" 两节：

> As you can see from the graph there are a number of cases where the capacity handed out by the system rises above the capacity limit. We call this phenomenon "shortfall".
>
> The reason for shortfall is that when a server asks for a capacity from its downstream server it can potentially get a new capacity which is less than it currently has in outstanding leases to clients and upstream servers. During that situation (which will have corrected itself in a single refresh interval) that server has given out more capacity than it currently has.

仿真读数（同节）：`Shortfall occurred 14 times` / `The maximum capacity handed out was 530.24 (106.05%)` / `The average over capacity situation was 509.99 (102%)`。并且：

> Combatting shortfall would mean changing the system in a way which also slows down convergence. We looked at a number of ways of doing that, but each of them has a large impact on convergence.

3. **真正的替代机制是"租约短"**：`lease_length` 决定"客户端离线后多久，它手里的额度才算收回"——`doc/design.md:230`：

> Because clients and servers lease capacity from the lower level server capacity held by clients or servers in a cluster that goes offline will automatically expire after the lease runs out.

`doc/design.md:894` 还点名长事务是超发高危场景："In case of long running transactions there is a higher risk of shortfall (see previous chapter)."

### 1.4 降级

设计文档给出**三档模式**（`doc/design.md:285-289`，逐字）：

> When a client fails to obtain or renew capacity it can do three things:
> 1. In pessimistic mode it can behave as if a capacity of zero has been granted, and effectively stop using the resource. This is obviously safest, but could halt all resource usage if the rate limiting system is temporarily unavailable. This is right course of action for an off-line batch job or daemon which is not interacting with users.
> 2. In optimistic mode it can behave as if it got all of the capacity it requested. This means that it can continue to use the resource, but there is a danger that the resource will be overused. This is the right course of action for on-line components that interact with users and with resources that are protected against overloading by other means.
> 3. In safe mode the client is allowed to use the resource up to the per-client safe capacity limit of the resource. The safe capacity is configured in the resource template and returned as part of every request for capacity. If the safe capacity is not configured in the resource template the server calculates a dynamic safe capacity (the available capacity divided by the number of clients) and returns that as the safe capacity to use.

**服务端侧确实实现了 safe capacity**（`go/server/doorman/resource.go:81-96`）：

```
if res.config.SafeCapacity == nil {
    resp.SafeCapacity = proto.Float64(*res.config.Capacity / float64(res.store.Count()))
} else {
    resp.SafeCapacity = proto.Float64(*res.config.SafeCapacity)
}
```

配置口径（`doc/configuration.md`）：`safe_capacity` 设为 `-1` ⇒ 服务端不可用时**关闭限流**（unbounded，等价 fail-open）；设为 `0` ⇒ 全部阻断（fail-closed）；设为正数 ⇒ 客户端按该值限流；不设 ⇒ 动态值 = 容量 / 已知客户端数。

**但开源 Go 客户端没有接这档能力**——`go/client/doorman/client.go:19` 是 `// TODO(ryszard): Handle safe capacities.`，`ResourceResponse.safe_capacity` 字段在客户端从未被读取；RPC 失败 + 租约已过期的实际处理是（`client.go:343-346`）：

```go
if res.expires().Before(time.Now()) {
    res.lease = nil
    // FIXME(ryszard): This probably should be the safe
    // capacity instead.
    res.capacity <- 0.0
```

→ **开源客户端的事实默认行为是 pessimistic（到期即 0）**，safe/optimistic 只是设计意图 + 服务端返回值。

### 1.5 分片与高可用（含中心重启后状态）

**分片 = 分层树 + 每节点主选举。** `doc/design.md:208-216`：

> The rate limiting servers are distributed in a tree. […] The purpose of this tree is to divide capacity globally between a set of clients without creating a global hot spot that all clients need to talk to.
>
> Each node in the server tree is formed by a rate limiting server job consisting of a number of tasks. These tasks use a master election mechanism to elect one among them to be the active server for that node. The winner of the master election will be the task that services all requests for capacity from that node. If a non-master task gets a request if will respond with the address of the master, so that the client knows who to talk to.
>
> The root node is a bit special: It will consist of three jobs in different zones that will elect a single master from all the nine tasks in the three jobs.

（选举依赖外部锁服务；`README.md`：'For some high-availability features it needs a [distributed lock manager] … We currently support [etcd]'。）

**"谁被分到哪个分片"依赖外部 LB**（`doc/design.md` 的 "Handling cluster unavailability" 开头注）：

> ***Note***: The strategies described in this chapter are dependent on the availability of a global load balancing infrastructure that directs clients to the closest available server with capacity.

**单分片挂了影响谁**：客户端手上的租约继续有效直到到期（`doc/design.md:230`，见 §1.3）；挂掉那部分的服务端重新选举后进入 **learning mode**。

**中心重启后状态：不持久化、不复制，靠 learning mode 重建。** 这是全篇最硬的一条（`doc/design.md:538`、`:540`，逐字）：

> The server does not store its internal state on a durable medium and also does not replicate it to the runners-up in the election process. This means that when a server wins the election it has a clean internal state and it has no idea what leases are out there. It means also that it cannot safely give out capacities until it has rebuilt its internal state. Fortunately every client and higher level server (that is a client of this server) must contact this server regularly in order to refresh capacity leases and the server can use this to rebuild its internal state. This phase of rebuilding the internal state for a resource is called "learning mode".

> So while in learning mode the server just records the information provided by the clients in its internal state and hands out the same capacity back to the client. If the request does not have a "has" field this means that this request is from a new client or from a client with expired capacity. Unfortunately the only way the server can deal with this is handing out a capacity of zero to this client.

`README.md` 补充了时长选择理由：

> During the learning mode of a resource every request will be answered with a new lease for the same capacity the client currently has. […] However for reasons of safety the default learning mode duration is the same as the lease length. This decision ensures that when the learning mode duration expires we can be sure that there are no leases out there that we don't know about (because these would have expired by then).

实现层面对应 `algorithm.go:297-302` 的 `Learn`（原样回吐 `has`），以及 `resource.go:108-112` 的分支。**默认 learning mode 时长**：`go/server/doorman/server.go:54-64` 的 `defaultResourceTemplate` 里 `LeaseLength: proto.Int64(20)`（`:61`）、`LearningModeDuration: proto.Int64(20)`（`:62`），`Kind` 默认 `FAIR_SHARE`。

**状态回收点在服务端每次决策前**：`resource.go:106` 的 `res.store.Clean()` → `store.go:169-181` 遍历删过期租约。`ReleaseCapacity` 的语义是**整资源放弃**（`doorman.proto:109-112`）：

> // The ReleaseCapacity RPC instructs the server to release all leases
> // that this client has on one or more resources. The lease is expected
> // to expire as part of this RPC, so the client should not use the resource
> // anymore.

以及 `doc/design.md:412`：

> If the ReleaseCapacity RPC ends in an error the client can ignore this error. If the response contains information about a new master, the client need not retry the RPC with the new master because that new master will not have any state about these resources (because the rate limiting servers do not share state with each other). If a client crashes the server will automatically forget about the client when all of its leases expire.

---

## 2. 中心在环的三族：Sentinel / Envoy RLS / Envoy RLQS

### 2.1 Alibaba Sentinel 集群流控——逐请求领 token，无租约

**形态**：不是"本地桶 + 批量领"，而是**每个请求一次远程**（`sentinel-core/.../flow/FlowRuleChecker.java:147-156`）：

```java
private static boolean passClusterCheck(FlowRule rule, Context context, DefaultNode node, int acquireCount,
                                        boolean prioritized) {
    try {
        TokenService clusterService = pickClusterService();
        if (clusterService == null) {
            return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
        }
        long flowId = rule.getClusterConfig().getFlowId();
        TokenResult result = clusterService.requestToken(flowId, acquireCount, prioritized);
```

服务端 `sentinel-cluster-server-default/.../flow/DefaultTokenService.java:38-50` 就是查规则 + 查 token：

```java
public TokenResult requestToken(Long ruleId, int acquireCount, boolean prioritized) {
    if (notValidRequest(ruleId, acquireCount)) { return badRequest(); }
    FlowRule rule = ClusterFlowRuleManager.getFlowRuleById(ruleId);
    if (rule == null) { return new TokenResult(TokenResultStatus.NO_RULE_EXISTS); }
    return ClusterFlowChecker.acquireClusterToken(rule, acquireCount, prioritized);
}
```

**阈值分配（"分片"在这里其实是"命名空间"）**——`ClusterFlowChecker.java:38-48`：

```java
private static double calcGlobalThreshold(FlowRule rule) {
    double count = rule.getCount();
    switch (rule.getClusterConfig().getThresholdType()) {
        case ClusterRuleConstant.FLOW_THRESHOLD_GLOBAL:
            return count;
        case ClusterRuleConstant.FLOW_THRESHOLD_AVG_LOCAL:
        default:
            int connectedCount = ClusterFlowRuleManager.getConnectedCount(rule.getClusterConfig().getFlowId());
            return count * connectedCount;
    }
}
```

`getConnectedCount` 落到 `ClusterFlowRuleManager.java:310-319` → `ConnectionManager.getConnectedCount(namespace)`（`ConnectionManager.java:47-51`，按 namespace 统计**当前活跃长连接数**）。wiki 原文：

> token server 会根据客户端对应的 namespace（默认为 `project.name` 定义的应用名）下的连接数来计算总的阈值（比如独立模式下有 3 个 client 连接到了 token server,然后配的单机均摊阈值为 10,则计算出的集群总量就为 30）

→ 即"默认的单机均摊模式"里，**集群总量不是一个固定值，而是随连接数线性伸缩**。

**判定式**（`ClusterFlowChecker.java:62-71`）：

```java
ClusterMetric metric = ClusterMetricStatistics.getMetric(id);
if (metric == null) {
    return new TokenResult(TokenResultStatus.FAIL);
}
double latestQps = metric.getAvg(ClusterFlowEvent.PASS);
double globalThreshold = calcGlobalThreshold(rule) * ClusterServerConfigManager.getExceedCount();
double nextRemaining = globalThreshold - latestQps - acquireCount;
if (nextRemaining >= 0) { … OK … } else { … BLOCKED … }
```

`ClusterMetric` 是 `LeapArray` 滑动窗口（`ClusterMetricLeapArray.java`），窗口按 `sampleCount` / `windowIntervalMs` 配置。

**超发控制**：有两个旋钮，都在**服务端**：
- `exceedCount`（`ServerFlowConfig.DEFAULT_EXCEED_COUNT = 1.0d`）——直接乘在阈值上，>1 即允许超发。语义按代码即为"阈值倍数"；**默认 1.0 表示不有意超发**。（"倍数"这个称谓来自二手博客，一手只有代码与常量，见文末 ⚠️ 未核实项第 1 条。）
- `maxOccupyRatio`（默认 1.0）——只对**高优先级请求**生效，允许它预占未来窗口（`ClusterFlowChecker.java:84-98`，`if (occupyAvg <= ClusterServerConfigManager.getMaxOccupyRatio() * globalThreshold) { int waitInMs = metric.tryOccupyNext(...) }`），返回 `SHOULD_WAIT`，客户端 `Thread.sleep(waitInMs)` 后放行（`FlowRuleChecker.java:192-199`）。
- 另外 token server 自己有 `maxAllowedQps`（`DEFAULT_MAX_ALLOWED_QPS = 30000`，`ServerFlowConfig.java:31`），用 `GlobalRequestLimiter.tryPass(namespace)` 拦在 `acquireClusterToken` 最前面（`ClusterFlowChecker.java:50-53`），目的是"防止在嵌入模式下影响应用本身"（wiki）。

**降级**：默认 **fail → 回到本地单机限流**，且可配置成直接放行。`FlowRuleChecker.java:158-174`、`:200-207`：

```java
} catch (Throwable ex) {
    RecordLog.warn("[FlowRuleChecker] Request cluster token unexpected failed", ex);
}
// Fallback to local flow control when token client or server for this rule is not available.
// If fallback is not enabled, then directly pass.
return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
...
private static boolean fallbackToLocalOrPass(...) {
    if (rule.getClusterConfig().isFallbackToLocalWhenFail()) {
        return passLocalCheck(rule, context, node, acquireCount, prioritized);
    } else {
        // The rule won't be activated, just pass.
        return true;
    }
}
```

```java
case TokenResultStatus.NO_RULE_EXISTS:
case TokenResultStatus.BAD_REQUEST:
case TokenResultStatus.FAIL:
case TokenResultStatus.TOO_MANY_REQUEST:
    return fallbackToLocalOrPass(rule, context, node, acquireCount, prioritized);
```

默认值 `ClusterFlowConfig.java:40`：`private boolean fallbackToLocalWhenFail = true;`（wiki 同款注释："在 client 连接失败或通信失败时,是否退化到本地的限流模式"）——所以**默认是 fail → 本地兜底，不是 fail-open**；只有显式关掉才变纯 fail-open。传输层异常也会转成 `FAIL`（`DefaultClusterTokenClient.java:161-164`）：

```java
} catch (Exception ex) {
    ClusterClientStatLogUtil.log(ex.getMessage());
    return new TokenResult(TokenResultStatus.FAIL);
}
```

**连接重试**（wiki）：

> 当 token client 与 server 之间的连接意外断开时,token client 会不断进行重试,每次重试的间隔时间以 `n * 2000 ms` 的形式递增。

**分片与高可用**：
- Token server 两种形态（wiki）：**独立模式（Alone）**"作为独立的 token server 进程启动,独立部署,隔离性好,但是需要额外的部署操作"；**嵌入模式（Embedded）**"作为内置的 token server 与服务在同一进程中启动。在此模式下,集群中各个实例都是对等的,token server 和 client 可以随时进行转变"。
- 客户端与服务端的绑定是**静态地址**（`ClusterClientAssignConfig` 的 `serverHost`/`serverPort`，`requestTimeout` 默认 20ms，wiki）。**OSS 里没有客户端侧的多 token server 分片/故障转移**——wiki 把它们明确列为**使用方（管控端）自己的责任**：

> 若在生产环境使用集群限流,管控端还需要关注以下的问题：
> - Token Server 自动管理、调度（分配/选举 Token Server）
> - Token Server 高可用,在某个 server 不可用时自动 failover 到其它机器

**中心重启后状态**：token server 的运行时状态**全在堆内存**，无持久化。`ClusterMetricStatistics.java:32`：

```java
private static final Map<Long, ClusterMetric> METRIC_MAP = new ConcurrentHashMap<>();
```

窗口统计随进程消失；重启后规则重新加载时 metric 被重建为空窗口（`ClusterFlowRuleManager.java:361-362` 的 `putMetricIfAbsent`）。**恢复路径不是"学习模式"、也不是"原样回吐 `has`"**（Sentinel 根本不持有客户端额度，没有 `has` 可回吐）；它靠两端各自重连 / 重新加载规则自然恢复。

**推断（未实测）**：重启后 `metric.getAvg(ClusterFlowEvent.PASS)` 从小值起算，而 `calcGlobalThreshold` 用的是**当前**连接数（`ConnectionManager` 也是进程内静态表，重启即空），因此重启后存在一段**阈值与计数同时在低位爬升**的窗口——期间相对更容易放行。本报告只从 `ClusterFlowChecker.java:62-71` 的表达式推出这一形状，**未运行验证**。

### 2.2 Envoy RLS（逐请求）——没有本地桶，只有"两级限流"的拼装建议

`docs/root/intro/arch_overview/other_features/global_rate_limiting.rst`：

> * **HTTP level rate limit filter**: Envoy will call the rate limit service for every new request on the listener where the filter is installed and where the route table specifies that the global rate limit service should be called.

> Note that Envoy also supports :ref:`local rate limiting <config_network_filters_local_rate_limit>`. Local rate limiting can be used in conjunction with global rate limiting to reduce load on the global rate limit service. For example, a local token bucket rate limit can absorb very large bursts in load that might otherwise overwhelm a global rate limit service. Thus, the rate limit is applied in two stages. The initial coarse grained limiting is performed by the token bucket limit before a fine grained global limit finishes the job.

**这是"本地桶 + 全局中心"在 Envoy 里唯一推荐的组合方式：本地桶只当粗筛，配额不进本地桶**——本地桶容量与中心配额**没有任何数量的对应关系**，是两次独立限流串联。

降级开关（`api/envoy/extensions/filters/http/ratelimit/v3/rate_limit.proto:66-69`）：

```proto
// The filter's behaviour in case the rate limiting service does
// not respond back. When it is set to true, Envoy will not allow traffic in case of
// communication failure between rate limiting service and the proxy.
bool failure_mode_deny = 5;
```

（proto3 默认 `false` ⇒ **默认 fail-open**。）还可按比例二分（`failure_mode_deny_percent`，`:153-169`："This means that when the rate limit service is unavailable, 50% of requests will be denied (fail closed) and 50% will be allowed (fail open)."）。

### 2.3 envoyproxy/ratelimit（参考实现）——每请求 Redis，本地缓存只缓"已超限"

README「Local Cache」节：

> Ratelimit optionally uses [freecache](https://github.com/coocood/freecache) as its local caching layer, which stores the over-the-limit cache keys, and thus avoids reading the redis cache again for the already over-the-limit keys. The local cache size can be configured via `LocalCacheSizeInBytes` in the settings. If `LocalCacheSizeInBytes` is 0, local cache is disabled.

另有 `ShadowMode`（"whatever the outcome of the evaluation of the rule, the end-result will always be "OK""）与 `share_threshold`，都与配额分配无关。

**结论**：这一族**没有配额预分配**，也没有"已发未用"的概念——每请求现算。

### 2.4 Envoy RLQS（配额租约）——**最贴近本设计的形态**

**为什么它最重要**：`api/envoy/service/rate_limit_quota/v3/rlqs.proto` 就是"客户端本地桶 + 中心下发配额 + 客户端上报用量 + 服务端主动推送"的**协议级定义**。头部注释：

> The Rate Limit Quota Service (RLQS) is a Envoy global rate limiting service that allows to delegate rate limit decisions to a remote service. The service will aggregate the usage reports from multiple data plane instances, and distribute Rate Limit Assignments to each instance based on its business logic. The logic is outside of the scope of the protocol API.
>
> The protocol is designed as a streaming-first API. It utilizes watch-like subscription model. The data plane groups requests into Quota Buckets as directed by the filter config, and periodically reports them to the RLQS server along with the Bucket identifier, BucketId. Once RLQS server has collected enough reports to make a decision, it'll send back the assignment with the rate limiting instructions.

**（a）服务端主动推送 + 客户端订阅**：

> The first report sent by the data plane is interpreted by the RLQS server as a "watch" request, indicating that the data plane instance is interested in receiving further updates for the ``BucketId``. From then on, RLQS server may push assignments to this instance at will, even if the instance is not sending usage reports.

**（b）租约口径 = 令牌式配额 + TTL**（`QuotaAssignmentAction.assignment_time_to_live`）：

> A duration after which the assignment is be considered ``expired``. […]
> * If unset, the assignment has no expiration date.
> * If set to ``0``, the assignment expires immediately, forcing the client into the :ref:`"expired assignment"` state. This may be used by the RLQS server in cases when it needs clients to proactively fall back to the pre-configured :ref:`ExpiredAssignmentBehavior`, f.e. before the server going into restart.

`rate_limit_strategy`（`rlqs.proto:160-163`）：

> // Configures the local rate limiter for the request matched to the bucket.
> // If not set, allow all requests.
> type.v3.RateLimitStrategy rate_limit_strategy = 3;

**（c）服务端主动召回（AbandonAction）——00 号结论要修正的一条**：

`rlqs.proto:34-40`（协议头注释，跨行连读）：

> It's the responsibility of the RLQS server / to determine when the data plane instance didn't send ``BucketId`` reports for too long, / and to respond with the :ref:`AbandonAction` / […], / indicating that the server has now stopped sending quota assignments for the ``BucketId`` bucket, / and the data plane instance should :ref:`abandon` it.

（原文为 `//` 行注释，`/` 处为本报告为连读所加的换行标记。）

`rlqs.proto:166-178`（`AbandonAction` 消息 javadoc）：

> // If no requests are reported for a bucket, after some time the server considers the bucket
> // inactive. The server stops tracking the bucket, and instructs the the data plane to abandon
> // the bucket via this message.
> //
> // **Abandoning the assignment**
> //
> // The data plane is to erase the bucket (including its usage data) from the memory.
> // It should stop tracking the bucket, and stop reporting its usage.

并且 proto 显式区分两个动作：

> .. attention::
>   Note that :ref:`expiring` the assignment is not the same as :ref:`abandoning` the assignment. While expiring the assignment just transitions the bucket to the "expired assignment" state; abandoning the assignment completely erases the bucket from the data plane memory, and stops the usage reports.

**口径澄清**：这是**整 bucket 放弃**（≈ Doorman 的 `ReleaseCapacity` / 本设计语境下的"下线归还"），**不是"退回本批没用完的零头"**。所以 00 号"没有服务端主动召回"应改为：**召回存在（RLQS AbandonAction），但语义是整桶作废，不是退零头**。

**（d）上报内容 = 中心重算的输入**（`RateLimitQuotaUsageReports.BucketQuotaUsage`）：

```proto
// Time elapsed since the last report.
google.protobuf.Duration time_elapsed = 2 [...];
// Requests the data plane has allowed through.
uint64 num_requests_allowed = 3;
// Requests throttled.
uint64 num_requests_denied = 4;
```

filter 文档：

> In addition to enforcing rate limit quota assignments, this filter periodically reports request rates to the RLQS, allowing the RLQS server to rebalance quota assignments between Envoy instances based on the real-time individual load of each Envoy instance. When quota assignments change, the RLQS proactively pushes the new assignment to Envoy.

**（e）降级：三段式，全部可配**（`docs/root/configuration/http/http_filters/rate_limit_quota_filter.rst`「Failure modes」）：

> In case the connection to RLQS server fails, the filter will fall back to either the :ref:`no assignment behavior` if it has not yet received a rate limit quota or to the :ref:`expired assignment behavior` if connection could not be re-established by the time the existing quota expired.

`NoAssignmentBehavior`（`rate_limit_quota.proto:170-178`）只有一个选项 `type.v3.RateLimitStrategy fallback_rate_limit` —— "Apply pre-configured rate limiting strategy until the server sends the first assignment."；而 filter 文档说明**不配 `on_no_match` 就是 fail-open**：

> If the ``on_no_match`` configuration is not provided, all unmatched requests are not rate limited (i.e. fail-open).

`ExpiredAssignmentBehavior`（`rate_limit_quota.proto:181-223`）两个选项 + 超时（`expired_assignment_behavior_timeout` 在 `:204`）：

> // Reuse the last known quota assignment, effectively extending it for the duration
> // specified in the :ref:`expired_assignment_behavior_timeout` field.
> message ReuseLastAssignment { }

> // Limit the time :ref:`ExpiredAssignmentBehavior` is applied. If the server doesn't respond within this duration:
> // 1. Selected ``ExpiredAssignmentBehavior`` is no longer applied.
> // 2. The bucket is abandoned. […]
> // 3. If a new request is matched into the bucket that has become abandoned,
> //    the data plane restarts the subscription to the bucket. […]
> // If not set, defaults to zero, and the bucket is abandoned immediately.

**（f）中心重启**：协议给了明确手段——服务端可在重启前推 `assignment_time_to_live = 0`，把客户端推进 `expired_assignment_behavior`（见 (b) 的原文 "f.e. before the server going into restart"）。这比 Doorman 的 learning mode 更"预谋"，也更轻。本报告**没有**找到 RLQS 服务端自身的持久化/分片规范——文档明说服务端逻辑"outside of the scope of the protocol API"，且开源参考实现缺失（`arch_overview/.../global_rate_limiting.rst`）：

> Open source reference implementation of the rate limiting service is currently unavailable. The rate limit quota extension can be presently used with the Google Cloud Rate Limit Service.

**（g）超发控制**：协议只给了两个时间旋钮——`reporting_interval`（客户端上报周期，`rate_limit_quota.proto:396`）与 `assignment_time_to_live`。**没有**"已发未用上限"字段。

---

## 3. 单机/集中式库：Redisson 与 Bucket4j

### 3.1 Redisson `RRateLimiter`——桶在 Redis，有退券、有整键 TTL

**形态**：不是"本地桶 + 中心"，是**完全集中式**（每次 `tryAcquire` 一次 Lua 往返）。但它的两个语义值得对照。

**`release` 的 javadoc**（`redisson/src/main/java/org/redisson/api/RRateLimiter.java:248-258`）：

> Releases the given number of <code>permits</code>.
>
> Increases the number of available permits by the specified amount and completes immediately, causing any waiting acquirers that can now obtain permits to proceed.

**退券会被"上限钳位"**（`RedissonRateLimiter.java:319-323`，Lua）：

```lua
local newValue = currentValue + tonumber(ARGV[1]);
if newValue > tonumber(rate) then
    newValue = tonumber(rate);
end
redis.call('set', valueName, newValue);
```

**过期记录回收**（同文件 `:218-238`）：`local expiredValues = redis.call('zrangebyscore', permitsName, 0, tonumber(ARGV[2]) - interval);` 然后按 released 数加回；加回后若超过 rate，则用 `currentValue = tonumber(rate) - used` 重算。

**单次申请量上限硬断言**（`:213`）：`assert(tonumber(rate) >= tonumber(ARGV[1]), 'Requested permits amount cannot exceed defined rate');`

**整键 TTL**：`trySetRate(RateType, long rate, Duration rateInterval, Duration keepAliveTime)` 的 javadoc（`:60-66`）："@param keepAliveTime this is the maximum time that the limiter will wait for a new acquisition before deletion"。Lua 里对应 `redis.call('pexpire', KEYS[1], keepAliveTime)`（`:256-267`）——**冷却期一过整个 key 消失，状态归零**（相当于"重启后重置"在单键尺度的版本）。

### 3.2 Bucket4j——同一套桶 API 下有"本地预留"优化器，并**显式命名了超发上限**

Bucket4j 的分布式形态默认也是**每次 `tryConsume` 一次 Redis 往返**（示例：`bucket4j-examples/src/test/java/example/distributed/optimizers/batching/RedisBatchingExample.java` 直接 `proxyManager.builder().withOptimization(optimization).build("13", configuration)` 后在线程里刷 `bucket.tryConsume(1)`）。

但它提供了 **`PredictiveOptimization`**——功能上就是"客户端本地攒一批令牌、定期回中心同步"：

`.../proxy/optimization/predictive/PredictiveOptimization.java:35-45`（类 javadoc 逐字）：

> Optimization that can serve requests locally without synchronization with external storage until thresholds are not violated. This optimization is based on top of {@link BatchingOptimization} and {@link DelayOptimization}, and in additionally this optimization tries to predict aggregated consumption rate in whole cluster in order to reduce the risk of overconsumption that caused by {@link DelayOptimization}.
>
> Usage of this optimization can lead to temporal over-consumption because the synchronization with external storage is performed periodically when thresholds are violated, as well as under-consumption in case of the wrong prediction of aggregated consumption rate.

**超发上限是一个显式参数**——这是本次调研里**唯一找到的"给已发出未消耗设上限"的一手实现**。`.../optimization/DelayParameters.java:20-31`：

```java
public final long maxUnsynchronizedTokens;
public final long maxUnsynchronizedTimeoutNanos;

/**
 * Creates the new instance of {@link DelayParameters}
 *
 * @param maxUnsynchronizedTokens threshold that describes how many tokens can be consumed locally without synchronization with external storage. Must be a positive number.
 * @param maxTimeoutBetweenSynchronization threshold that describes how long bucket proxy can act locally without synchronization with external storage. Must be a positive duration.
 */
```

判定点（`PredictiveCommandExecutor.java:154-185`）：

```java
private boolean isLocalExecutionResultSatisfiesThreshold(long locallyConsumedTokens) {
    if (locallyConsumedTokens == Long.MAX_VALUE || postponedToConsumeTokens + locallyConsumedTokens < 0) {
        return false; // math overflow
    }
    return postponedToConsumeTokens + locallyConsumedTokens <= delayParameters.maxUnsynchronizedTokens;
}
```

```java
long lastSyncTimeNanos = sampling.getLastSyncTimeNanos();
if (currentTimeNanos - lastSyncTimeNanos > delayParameters.maxUnsynchronizedTimeoutNanos) {
    // too long period passed since last sync
    return true;
}
```

→ **两个旋钮正好对位本设计要讨论的"batch 大小"与"租约时长"**：本地未同步令牌数 ≤ `maxUnsynchronizedTokens`，本地未同步时长 ≤ `maxUnsynchronizedTimeoutNanos`。

> ⚠️ **2026-09-22 注记（[票 04](../issues/04-grill-lease-model.md) 选速率租约、[票 05](../issues/05-grill-local-bucket-and-admission.md) Q7 收口）**：这句里的"**batch 大小**"在速率口径下**不存在**（中心发的是份额速率，不是批量令牌），两个旋钮也**不在本设计的机制里**（不外露）。本节作为 **Bucket4j 的实现事实**仍然成立，"对位本设计"这个映射**已作废**。

**预测"别人消耗多快"**（`.../predictive/Sampling.java:51-63` 与 `:94-116`）：统计 `othersRate = (tokensConsumedByOthersDuringSamplingPeriod / timeBetweenSynchronizations)`，再 `predictedConsumptionByOthersSinceLastSync = othersRate * timeSinceLastSync`，把这个预测值**先从本地桶里预留掉**（`new ConsumeAsMuchAsPossibleCommand(predictedConsumptionByOthersSinceLastLocalCall)`，`PredictiveCommandExecutor.java:125`）——即"预测到的他人消耗算作自己已消耗"，以此压低超发概率。参数默认 `DEFAULT_MIN_SAMPLES = 2` / `DEFAULT_MAX_SAMPLES = 10`（`PredictionParameters.java:24-25`）。

**降级**：Bucket4j **没有**"后端不可用时放行/阻断"的开关。能找到的最近似物是 `RecoveryStrategy`，但它处理的是**状态丢失**不是**连不上**（`.../distributed/proxy/RecoveryStrategy.java`）：

> Specifies the reaction which should be applied in case of previously saved state of bucket has been lost. […]
> - RECONSTRUCT — Initialize bucket yet another time. Use this strategy if availability is more preferred than consistency.
> - THROW_BUCKET_NOT_FOUND_EXCEPTION — Throw BucketNotFoundException. Use this strategy if consistency is more preferred than availability.

**归还**：唯一的一手依据仍是 `Bucket.java:132-155` 的 `addTokens` javadoc：

> The "compensating transaction" is one of obvious use case, when any piece of code consumed tokens from bucket, tried to do something and failed, the "addTokens" will be helpful to return tokens back to bucket

且 `newTokens = Math.min(capacity, currentTokens + tokensToAdd)`——**与 Redisson 一样在容量处钳位**。

---

## 4. 大厂公开材料：Cloudflare / Uber / Stripe / AWS

### 4.1 Cloudflare——分片靠 anycast 定 PoP，中心是"每 PoP 内"的，判定结果在本地进程缓存

（blog.cloudflare.com「How we built rate limiting capable of scaling to millions of domains」）

**不做全局中心，分片边界由 anycast 决定**：

> Since Cloudflare has a vast and diverse network, reporting all counters to a single central point is not a realistic solution as the latency is far too high and guaranteeing the availability of the central service causes more challenges.

> This property is extremely valuable: we are sure that, under normal conditions, the traffic from a single IP address will always reach the same PoP. Unfortunately each new TCP connection might hit a different server inside that PoP. But we can still narrow down our problem: we can actually create an isolated counting system inside each PoP.

**算法**是滑动窗口计数（两个整数/键，误差实测 0.003%）：

> It is still very accurate, as an analysis on 400 million requests from 270,000 distinct sources shown: 0.003% of requests have been wrongly allowed or rate limited

**降级/加速的关键手法：判定结果下沉到本地内存**——

> This is why the increment jobs are run asynchronously without slowing down the requests. If the request rate is above the threshold, another piece of data is stored asking all servers in the PoP to start applying the mitigation for that client. Only this bit of information is checked during request processing.

> Even more interesting: once a mitigation has started, we know exactly when it will end. This means that we can cache that information in the server memory itself. Once a server starts to mitigate a client, it will not even run another query for the subsequent requests it might see from that source!

**明确的取舍记录（否决 CAS）**（文末脚注）：

> Memcache does support CAS (Compare-And-Set) operations and so optimistic transactions are possible, but it is hard to use in our case: during attacks, we will have a lot of requests, creating a lot of contention, in turn resulting in a lot of CAS transactions failing.

**对本案的意义**：Cloudflare **不做配额预分配**；它的"本地"只是缓存一个布尔/时长判定，不是本地桶。其"分片怎么切"的思路（用现成的路由不变量切）与本设计"按 key 分片"是同源问题。

### 4.2 Uber GRL——先做过"代理本地令牌桶"，后来**彻底删掉了它**

（uber.com/blog/ubers-rate-limiting-system）

**初版形态，与本设计高度重合**：

> When the GRL (Global Rate Limiter) project began, the team initially implemented a token-bucket algorithm for enforcement on each proxy in the network data plane. Each proxy locally tracked request counts and replenished tokens over time, allowing or rejecting requests based on the number of available tokens.

> The token rate was derived by comparing the proxy's local load to the global limit. The proxy calculated a ratio between its observed traffic and the global load target, and then replenished tokens accordingly (at ratio × limitRPS ).

> If a request arrived while tokens were available, it was allowed and one token was consumed. If not, the request was marked as rate-limited. To handle bursty or uneven traffic, the client stored unused tokens in a circular buffer, allowing them to be spent during brief surges.

**为什么放弃（这是对本设计最直接的一条"反面证据"）**：

> While this approach worked in early testing, it revealed scalability and fairness issues in production. One issue was unfair caller distribution. When the number of callers exceeded the configured limit, the token bucket couldn't fairly divide capacity among them. Another issue was instance-level spikes. Individual hosts with bursty traffic could start dropping requests prematurely, even when overall load was still below the global limit.

> As GRL matured, the team fully deprecated the token-bucket mechanism in favor of a control plane driven probabilistic dropping model, where all enforcement decisions are based on aggregated load rather than local counters. Maintaining both algorithms simultaneously had increased configuration complexity and network overhead as each client needed to exchange periodic load reports with the control plane to keep token rates aligned.

**现在的形态**：

> If the request matches a bucket that has a current drop ratio directive, the client probabilistically drops a percentage of requests according to that ratio. […] This approach keeps the hot path extremely lightweight—there's no local token accounting or per-request communication with the control plane. All decisions are made in-process, using simple probabilistic sampling.

> The trade-off was that rate-limit decisions now depend on globally aggregated data updated every second, meaning enforcement can lag by up to 2 to 3 seconds behind real-time traffic conditions.

**架构分层与降级**：

> Controllers (per region, global). Aggregate zone data to determine global utilization and push updated drop ratio directives back down to aggregators and clients. […] This hierarchical aggregation ensured low latency in the hot path (decisions are local) while maintaining global coordination within a few seconds. If the control plane became unavailable, clients failed open, allowing traffic to continue rather than risk self-inflicted outages.

**含金量**：Uber 的"本地桶"版本里，`ratio × limitRPS` 的补速 **不是按客户端自己的需要，而是按"自己占全局负载的比例"**——这正是"配额分配算法"的一种，而且它失败了，理由是**caller 间不公平**与**实例级突发误杀**。

### 4.3 Stripe——纯集中式 Redis 令牌桶 + 显式 fail-open

（stripe.com/blog/rate-limiters）

> We use the token bucket algorithm to do rate limiting. This algorithm has a centralized bucket host where you take tokens on each request, and slowly drip more tokens into the bucket. If the bucket is empty, reject the request. In our case, every Stripe user has a bucket, and every time they make a request we remove a token from that bucket.

> We implement our rate limiters using Redis.

**降级策略是明确写死 fail-open 的**：

> Hook the rate limiters into your middleware stack safely. Make sure that if there were bugs in the rate limiting code (or if Redis were to go down), requests wouldn't be affected. This means catching exceptions at all levels so that any coding or operational errors would fail open and the API would still stay functional.

**四类限流器**（博客小标题逐字）：`Request rate limiter`、`Concurrent requests limiter`、`Fleet usage load shedder`、`Worker utilization load shedder`——"At Stripe, we operate 4 different types of limiters in production."。四者全部部署在**服务端**，**没有客户端配额预分配**。`Concurrent requests limiter` 是有释放语义的那种（并发槽），与 Sentinel 的 `releaseConcurrentToken` 同类；`Fleet/Worker usage load shedder` 用的是"预留关键流量的份额"（"We always reserve a fraction of our infrastructure for critical requests. If our reservation number is 20%, then any non-critical request over their 80% allocation would be rejected with status code 503."）。

### 4.4 AWS API Gateway——纯服务端令牌桶，**没有客户端配额**

（`api-gateway-request-throttling.md`）

> You can configure throttling and quotas for your APIs to help protect them from being overwhelmed by too many requests. Both throttles and quotas are applied on a best\-effort basis and should be thought of as targets rather than guaranteed request ceilings.

> API Gateway throttles requests to your API using the token bucket algorithm, where a token counts for a request. Specifically, API Gateway examines the rate and a burst of request submissions against all APIs in your account, per Region. In the token bucket algorithm, a burst can allow pre\-defined overrun of those limits, but other factors can also cause limits to be overrun in some cases.

> *Per\-client throttling limits* are applied to clients that use API keys associated with your usage plan as client identifier.

**对本案的意义**：文档里唯一提到"客户端"的地方是"客户端收到 429 后自己按限速重试"（"Upon catching such exceptions, the client can resubmit the failed requests in a way that is rate limiting."）。**AWS 没有"客户端本地桶 + 中心配额"的公开一手材料**。

---

## 5. 中文期刊 DFMF——**唯一明确做了"服务端主动回收闲置配额 + 中心持久化"的方案**

郑旭, 范红杰, 柳军飞.《基于微服务的预分配额度限流设计研究》. 计算机科学, 2024, 51(6): 346-353.

**摘要（逐字）**：

> 针对现有微服务限流所存在的问题,文中提出了一种基于预分配额度进行主动推送配额更新的限流算法.该算法采用服务器主动向客户端广播的模式,服务器既可以接受客户端请求,也可以主动更新持有该资源配额的节点在处理请求后的最新结果.在服务器端分配所有节点配额时,可以采用灵活的分配算法进行分配.在估算限流节点配额时,采用滑动窗口的模式记录下一段时间内的请求数量和拥有的资源配额,通过自定义的算法来预估下一个周期的配额.

**客户端结构（§3 开头）**：

> 每个限流客户端内部含有对限流对象的抽象建模,表示为 Resource,表示该客户端持有的被限流对象.每个 Resource 都由一个对应的 RateLimiter 节点管理,RateLimiter 内部实现滑动窗口限流,窗口内采用令牌桶算法进行限流.

**（a）服务端主动推送 + 客户端不主动归还（§3.3「主动推送配额」，全文关键段）**：

> 主动推送配额是一种提升限流系统反应速度的措施.如果一个节点占用了太多的配额,其他需要配额的节点则会无限请求.在此情况下,每一个被拒绝的请求都必须至少重试几次,从而导致业务系统延迟增加.抢夺式的主动推送配额更新则可以解决节点间配额分配不合理的问题.引入自动推送配额机制后,系统可以围绕限流服务器和客户端的通信机制进行改进.服务器以固定间隔定期向限流客户端推送配额,间隔越小,配额的全局平衡效率就越快.此外,引入配额推送机制后,限流客户端可以尽可能多地保持配额,而无须主动释放.如果存在其他的客户端配额短缺,该客户端会向服务器报备自己的配额申请,在下一个分配时间点,服务器会自动从存在闲置配额的节点回收多余配额.

**（b）租约双字段（§3.2 接口设计）**：

> 在本模型中,如果资源配额出现短缺,则可以尽快申请配额,即租约更新时间是一个参考值而非绝对值,是更新时间最大值而非最小值.租约过期时间表示超过该时间还未刷新,服务器就会回收该配额,此时限流客户端则按照协议应该自动释放该配额.租约更新时间的意义在于,当限流客户端持有某些资源时,即使资源租约没有到期,依然要按时刷新.因为刷新配额可以反馈配额使用情况,从而使得服务器端知晓每个节点的资源闲置状况,取多补少.

> 主动推送配额更新机制的引入,使得一个节点不必尽快归还闲置的配额,从而更好地应对突发流量.租约更新时间根据不同的环境而定,在批处理任务中 […] 可以手动设置 1~5min 的配额租约持有时间,而对于外服务的线上流量,如某商城下单的请求,则突发流量难以预料,可以设置为 1~5s 的租约持有时间.

→ **两个时间参数是分开的**：`租约过期时间` = 硬 TTL（过期即回收），`租约更新时间` = 期望上报周期（"最大值而非最小值"——可以更频繁地紧急申请）。资源表的字段清单（表 2）："资源id / 资源名称 / 配额数量 / 配额分配算法 / 限流算法 / 租约过期时间 / 租约更新时间"，其中"租约过期时间"说明是"最长不更新时间,逾期收回配额"。

**（c）无法联系客户端即回收（§3.3 末）**：

> 限流服务器需要保存限流客户端监听推送的 RPC 地址,并且维护其可用性统计数据,如果多次失败或者连接超时,则需要对该配额进行回收.

（对应 §3.2 的动机段："依照分布式系统理论假设,网络分区是可能存在的,因此要考虑网络故障导致的节点不可访问,进而导致配额无法回收的问题,或者节点本身故障导致的同样的后果。"）

**（d）分配算法**：表 2 里"配额分配算法"的可选值是"均摊分配、按请求比例智能分配"；§3.3 末：

> 主动推送机制开启后,限流客户端依然需要上报配额信息,包括当前持有的配额数量、实际请求数量、下一周期预估配额数量.当限流服务器重新分配资源配额时,会按当前所有节点的请求比例重新分配.

**（e）中心分片与重启（§3.5「主从架构」）——本票 Q5 最直接的一手材料**：

> 本文参考主流分布式架构,采用主从架构设计服务器集群.主节点负责处理客户端的请求,维护限流客户端地址和连接信息,定期向客户端一对一推送配额信息,以及制作快照以增强可用性.从节点负责定期向主节点询问最新的快照版本,并且和本地快照进行对比.如果从节点数据落后于主节点,则主动发起同步请求,与主节点保持一致.

> 限流服务器的已分配配额数据作为系统的核心数据,存在于服务器进程中,随着进程的重启或者故障等问题得以解决,已分配数据就会被清除.为了增强系统的高可用性,提高抗故障能力,本文设计了持久化机制和主从复制机制.服务器作为主节点运行时,定期检查并且启动备份机制.当变化到达一定的阈值就需要开启一次快照.当达到系统的快照配置阈值之后,服务器会开启新的线程制作快照,这时会修改已分配配额信息.

**（f）对 Doorman 的批评（§2 相关工作）**：

> 此外,Doorman 系统的缺点也非常明显,包括收敛较慢和数据易失.收敛较慢表现为配额的申请需要等待其他节点归还配额;易失性则表现为新的主节点需要从客户端反馈过来的信息重新学习已经分配的配额,而在此期间无法提供分配配额的能力.

> Sentinel 限流的缺点在于,其单次限流在面对离线场景下大批量任务时造成的网络请求会对服务器产生极大的压力.

**（g）实验设置与 Doorman 对照（§4）**：论文做了实验，规模不大但可核对。

环境（§4.1）：

> 本文实验基于 4 台阿里云云主机构建的机器集群,每台云主机配置都是 4 个虚拟 CPU、8GB 内存,运行的操作系统为 Ubuntu18.04,研发语言使用 Go1.17.10. 在服务器实现层,定义了限流服务器对限流客户端暴露的接口以及重要的消息结构体,序列化协议是 ProtoBufferV2,微服务框架是 GPRCv1.48,虚拟化容器是 Docker20.10.16.
>
> 实验数据源于搜索引擎的文章分析应用模型集群,采集了一天时间内的生产环境日志 […] 同时,本文分别提取了 3 个线上服务请求序列和 3 个线下服务请求序列,分别用于测试配额算法的分配改良情况.

**公平性对比（表 8「全阶段公平性比较」，资源总配额 300，6 组请求序列）**：Doorman 分配算法 `0.8570 / 0.8903 / 0.8624 / 0.9366 / 0.9505 / 0.9455`，DFMF 分配算法 `0.8727 / 0.9089 / 0.8999 / 0.9494 / 0.9529 / 0.9505`（§4.5）。

**主节点崩溃对比（§4.6，图 6）——本题 Q5 的直接对照**：

> 本文模型使用 3 个限流节点请求同一个资源检测主节点发生故障时系统的可用性,该资源总共 300 的配额.主服务器节点在运行过程中崩溃后可以重新连接,从节点在主节点崩溃后进行选主,新任的主节点继续为客户端提供服务.
>
> 图 6 记录并对比了 Doorman 系统和 DFMF 系统分别在主节点崩溃后,整个系统整体对外分发的配额.Doorman 系统因为存在学习期,当旧主节点崩溃后,新的主节点必须在一定时间段内重新掌握各个节点的配额拥有情况,这个阶段整个系统分发的配额很少.而 DFMF 系统的从节点一直在拷贝来自主节点的已分发信息和客户端连接地址,因此可以在成为新的主节点后快速地继续工作.此外,DFMF 系统秉承的原则是把所有的配额都尽量分发出去,而 Doorman 系统因为采用按需分发的原则,因此分发配额的集群的整体效率低于 DFMF 系统.

**作者自己指出的代价（§4.5 末，很重要）**：

> 考虑公平性最大值为 1,实际上掩盖了 DFMF 的另外一个优势,即让节点尽量持有闲置配额以应对突发流量,从而弥补流量预测不够精确的缺点.

即：**DFMF 拿到更好指标的代价是"允许客户端长期持有闲置配额"**——公平性指标本身看不见这部分浪费。

**必须打的折扣**：论文自己的定位是"基于 Doorman 系统[4]和北极星系统[5]"的改进框架（DFMF）；实验是 **4 台云主机 + 一天生产日志**的规模，且 Doorman 侧是作者自建的对照实现、不是上游 `youtube/doorman` 原版。**因此它是"存在这样的做法、且在该规模下有小幅优势"的证据，不是"这样做在生产里成立"的证据**。

---

## 6. 横向对照

### 6.1 五问速查表

| # | 方案 | 配额分配算法 | 租约口径 | 超发/在飞上限 | 降级默认 | 分片 / 中心重启 |
|---|---|---|---|---|---|---|
| A | **Doorman** | 每请求重跑 `PROPORTIONAL_SHARE`/`FAIR_SHARE`/`STATIC`/`NO_ALGORITHM`；客户端侧 `AdaptiveQPS` 用近期加权移动平均算 `wants` | **速率租约**：`lease_length`(典型 300s) + `refresh_interval`(典型 5s)；续租 = 重新分配 | **无**。有已知且不治的 `shortfall`（实测峰值 106%） | 三档 `pessimistic/optimistic/safe`（`safe_capacity`，-1=放行 / 0=阻断 / >0=定额）；**但 Go 客户端只实现了 pessimistic** | 分层树 + 每节点 etcd 主选举；分片归属依赖外部 LB。**不持久化不复制**，重启后 learning mode 原样回吐 `has`，时长默认 = lease_length |
| B | **Sentinel 集群流控** | `GLOBAL`（固定总量）或 `AVG_LOCAL`（单机阈值 × namespace 在线连接数）；**窗口是 LeapArray** | **无租约**，每请求远程 | `exceedCount`（阈值倍数，默认 1.0）+ `maxOccupyRatio`（高优先级预占未来窗口上限，默认 1.0）+ token server 自身 `maxAllowedQps`（默认 30000） | **fail → 本地单机限流**（`fallbackToLocalWhenFail` 默认 true）；关掉则 fail-open | Alone/Embedded 两形态；客户端静态 host:port，**无多 server 分片/故障转移**（wiki 明确留给管控端）。状态全在堆内存 `METRIC_MAP`，**重启即清空** |
| C | **Envoy RLS（逐请求）** | 无（每请求算） | 无 | 无 | `failure_mode_deny` 默认 false ⇒ **fail-open**；可配比例 | 无 |
| C' | **envoyproxy/ratelimit** | 无（Redis 计数） | 无 | 无 | 同 Envoy 侧开关 | Redis 单/双实例 + Sentinel；本地 freecache 只缓存**已超限键** |
| D | **Envoy RLQS** | 服务端逻辑（协议外）；输入 = 各实例 `num_requests_allowed` / `num_requests_denied` / `time_elapsed` | **令牌式配额 + TTL**：`assignment_time_to_live`（不设 = 永不过期；`0` = 立即过期） | **无显式上限**；旋钮只有 `reporting_interval` 与 TTL | `no_assignment_behavior`（首个配额未到时）与 `expired_assignment_behavior`（`fallback_rate_limit` 或 `reuse_last_assignment` + 超时）；**不配 = fail-open** | 协议不规定；服务端可在**重启前推 TTL=0** 让客户端转入过期退避；开源参考实现缺失 |
| E | **Redisson RRateLimiter** | 固定 rate（集中式） | 无租约；有整键 `keepAliveTime` TTL | 单次申请 `rate >= permits` 硬断言 | 无降级开关（Redis 异常直接抛） | Redis 自身 |
| F | **Bucket4j** | 桶在 Redis；`PredictiveOptimization` 在本地按"他人消耗速率预测"预留额度 | **无租约，但有两个等价旋钮**：`maxUnsynchronizedTokens`、`maxUnsynchronizedTimeoutNanos` | **有，且唯一**：`postponedToConsumeTokens + locallyConsumedTokens <= maxUnsynchronizedTokens` | 无 fail-open/closed 开关；`RecoveryStrategy` 只管**状态丢失**（RECONSTRUCT vs 抛异常） | Redis 自身；`RECONSTRUCT` 即"重启后重建" |
| G | **Cloudflare** | 无配额；每 PoP 滑动窗口计数 | 无 | 无 | 判定结果下落本地进程内存（"已开始缓解"标记），不再查中心 | **anycast 定 PoP 即分片**；每 PoP 一个 Twemproxy+memcache 集群；显式否决 CAS |
| H | **Uber GRL** | 早期：`ratio × limitRPS`（按自己占全局负载的比例补速）；现在：控制面按秒算全局 drop ratio | 早期无租约（靠周期性负载反馈算 ratio）；现在无令牌账 | 早期无；现在无（概率丢弃） | **fail-open** | 三层：data plane 客户端 → zone aggregator → regional/global controller；每秒推送 |
| I | **Stripe** | 每用户一个 Redis 桶 | 无 | 无 | **显式 fail-open**（"catching exceptions at all levels"） | 集中式 Redis；无客户端配额 |
| J | **AWS API Gateway** | 无客户端配额；服务端令牌桶按 account/API/method/usage-plan 分层 | 无 | 无（"best-effort … targets rather than guaranteed request ceilings"） | 返回 429，客户端自行退避 | 服务端 |
| K | **DFMF（期刊）** | 均摊分配 / 按请求比例智能分配；用滑动窗口（3 格 × 1s）预测下一周期配额 | **双字段**：`租约过期时间`（硬 TTL，逾期回收）+ `租约更新时间`（上报周期，1~5s 线上 / 1~5min 批处理） | 未提出显式上限（用租约时长与上报周期控制） | 未读到明确的降级档位描述 | **主从 + 快照持久化**；主节点处理请求并定期推配额，从节点按快照版本追平 |

### 6.2 三条可复用的横向结论

1. **"逐请求远程"是主流**（Sentinel、Envoy RLS、envoyproxy/ratelimit、Stripe、AWS、Redisson）。真正做"客户端持有可消耗额度"的只有 **Doorman（速率租约）、Bucket4j 预测优化器（本地预留令牌）、Envoy RLQS（配额 + TTL）、DFMF（预分配额度）** 四家。
2. **"归还"在所有一手实现里都只有三种语义**：整资源/整桶放弃（Doorman `ReleaseCapacity`、RLQS `AbandonAction`）、失败补偿（Bucket4j `addTokens`、Redisson `release`）、并发槽释放（Sentinel `releaseConcurrentToken`）。**没有一家做"把本批没用完的零头按剩余量退回"**——DFMF 甚至把"客户端不必主动归还"当成主动推送带来的**优点**来陈述。
3. **"超额发放在飞量"被显式建模的只有 Bucket4j 一家**（`maxUnsynchronizedTokens`）；Doorman 承认 `shortfall` 存在并主动放弃治理；其余家靠"租约短 / 上报快 / 概率丢弃"间接控制。

---

## 7. 补齐 [00](00-lease-reclaim-prior-art.md) 的四条 ⚠️ 未核实项

### 7.1 ✅ 已补齐：Doorman 续租重算的实现细节

不再是"未逐行读实现"。落到源码就是 `algorithm.go` 的三个函数（`FairShare` `:95-206`、`ProportionalShare` `:213-293`、`NoAlgorithm` `:66-72` / `Static` `:78-84`），每次续租都跑一遍；`store.Assign` 每次都重置租约（`store.go:161`：`lease.Expiry = time.Now().Add(leaseLength)`）。

两点细节值得记：
- **客户端自报的 `has` 参与计算但服务端不信任它**（`algorithm.go:104-110`）：

  ```go
  old := store.Get(r.Client)
  // A sanity check: what the client thinks it has should match
  // what the server thinks it has. This is not a problem for
  // now, as the server will depend on its own version, but it
  // may be a sign of problems with a client implementation, so it's better to log it.
  if r.Has != old.Has {
      log.Errorf("client %v is confused: says it has %v, was assigned %v.", r.Client, r.Has, old.Has)
  }
  ```
- **可用量计算把"自己原先那份"加回来**（`unusedCapacity := capacity - store.SumHas() + old.Has`），即"续租时先把自己手上的还进池子再重新分"。

### 7.2 ✅ 已补齐：Bucket4j Redis 后端源码

不再是"依据 API 文档"。一手证据见 §3.2：`PredictiveOptimization` / `PredictiveCommandExecutor` / `Sampling` / `DelayParameters` 的源码与 javadoc，包括"本地未同步令牌上限"的判定表达式。默认每请求远程这点由 `RedisBatchingExample.java` 的用法佐证，并且 Bucket4j 用 `Optimizations.batching()` / `PredictiveOptimization` 来减少往返。

### 7.3 ✅ 已补齐：中文期刊原文

见 §5。全文 PDF 已读到（9 页，`https://www.jsjkx.com/CN/article/openArticlePDF.jsp?id=22376`）。00 号当时只看到摘要，现在拿到了：(i) 服务端在下一个分配时间点**主动回收闲置配额**；(ii) 明确"客户端无须主动释放"；(iii) 租约过期/更新双字段；(iv) 主从 + 快照持久化。

### 7.4 ❌ 未补齐：Netflix / Uber / Stripe / AWS 的"quota-leasing"一手材料

- **Uber**：找到了**官方博客**（第一手），但 GRL 现形态**已经不是配额租赁**（见 §4.2），只保留了"曾被实现过"的历史描述。
- **Stripe**：找到官方博客（第一手），是集中式 Redis 令牌桶，**没有**配额租赁。
- **AWS**：找到官方文档（第一手），是服务端令牌桶 + usage plan，**没有**客户端配额。
- **Netflix**：公开的一手材料是 `Netflix/concurrency-limits`，README 原文：

  > Java Library that implements and integrates concepts from TCP congestion control to auto-detect concurrency limits for services in order to achieve optimal throughput with optimal latency.

  > We accept that every system has an inherent concurrency limit that is determined by a hard resource, such as number of CPU cores.

  > Instead we'd prefer to measure or estimate the concurrency limits at each point in the network. As systems scale and hit limits each node will adjust and enforce its local view of the limit.

  这是**纯本地、按延迟反馈自适应**的并发上限，**没有中心配额**。

→ 结论：**"Netflix/Uber/Stripe/AWS 做过 quota-leasing"这一说法，在一手材料里找不到支持**。Uber 做过并放弃了它。

---

## ⚠️ 未核实项

1. **Sentinel `exceedCount` / `maxOccupyRatio` 的"官方语义"没有一手文档**。本报告只引到代码事实：`globalThreshold = calcGlobalThreshold(rule) * ClusterServerConfigManager.getExceedCount()`（`ClusterFlowChecker.java:68`）、`DEFAULT_EXCEED_COUNT = 1.0d`（`ServerFlowConfig.java:26`）、`DEFAULT_MAX_OCCUPY_RATIO = 1.0d`（`:27`）。"限制倍数/默认不允许超过"这一解释来自二手博客，**未在 Sentinel 官方 wiki 或 Javadoc 中找到出处**，故本报告只按代码陈述、不采信"倍数"这一措辞。

2. **Envoy RLQS 没有开源服务端实现**。`arch_overview/.../global_rate_limiting.rst` 原文确认 "Open source reference implementation of the rate limiting service is currently unavailable"，且只支持 Google Cloud Rate Limit Service。因此 RLQS **服务端侧**的以下问题全部无从以一手材料回答：它怎么分片、怎么选主、状态是否持久化、重启后如何重建。本报告关于 RLQS 的全部结论**只覆盖协议与客户端（filter）行为**。

3. **RLQS 的"配额分配算法"是黑盒**。协议原文 "The logic is outside of the scope of the protocol API"，所以"中心怎么决定给某客户端多少"在 RLQS 里**没有可对照的公开算法**，只有输入字段（`num_requests_allowed` / `num_requests_denied` / `time_elapsed`）。

4. **Doorman 的"分层聚合请求"（`GetServerCapacity`）在服务端聚合 `wants` 时如何按优先级分带（`PriorityBandAggregate`），未逐行核实**。`doorman.proto:73-77` 定义了 `PriorityBandAggregate{priority, num_clients, wants}`、`:79-83` 定义了 `ServerCapacityResourceRequest`，但本报告没有读完 `go/server/doorman/server.go` 里构造该请求的全部路径，也没有验证"叶子节点聚合多客户端 wants"时的实际取整/权重规则。**仅确认了 proto 形状，未确认聚合语义。**

5. **Uber GRL 的"3 层架构 / 每秒推送"只在博客正文出现，没有配套的协议或源码**。博客是官方第一手，但它是**科普文体**，没有给出 drop ratio 的计算公式原文（博客只说 "clients began probabilistically dropping a percentage of requests"，公式 `drop_ratio = (actual_rps - limit_rps)/actual_rps` 出现在二手转述里，**本报告未采信该公式**）。同样，"hundreds of millions of requests per second"、"15× traffic surge"、"P99.5 improved by up to 90%" 等运营数字未核实。

6. **DFMF（期刊 §5）的生产可用性未评估，且对照臂不是上游 Doorman**。论文**有**实验（4 台阿里云云主机、Go 1.17.10、gRPC v1.48、数据来自一天生产日志，见表 8 / 图 6），但：**未给出**"主从 + 快照"的切换时延、快照频率阈值、网络分区演练数据；表 8 的公平性差异在线上序列上仅约 `0.8727 vs 0.8570`、离线序列约 `0.9494 vs 0.9366` 这个量级，**没有方差/显著性**；且论文明确说 DFMF 的优势之一靠"让节点尽量持有闲置配额"，即**指标好看的一部分来自主动超发**。此外，本报告**无法确认**其 Doorman 对照臂是上游 `youtube/doorman` 原版还是作者自建实现。所以本报告只把它当作"存在这种做法的证据"。

7. **Cloudflare 的 Pingora `pingora-limits`（Count-Min Sketch）未读源码**。搜索结果提到它，但本报告未拉取 `cloudflare/pingora` 的 `pingora-limits` 源码核对，故不写入正文。

8. **腾讯北极星（Polaris）的"全量下发"限流模型未取一手材料**。期刊 §2 称其"限流服务的全量下发使得它无法提供精准限流"，但本报告只拉到 `polarismesh/polaris` 的 `README.md`（其中只有 "traffic control: customizable routing, load balance, rate limiting and access control" 一句），**未读到其限流文档**，故正文只在 §5 转述期刊对它的评价、不单列条目。

9. **Redisson 是否有"回退到本地限流"的能力未核实**。`RRateLimiter` 接口中未见降级开关，但本报告**未检查** `redisson` 的配置项（如 `RedissonClient` 的超时/重试策略）是否构成事实上的降级路径。

10. **Bucket4j 的 `Optimizations.batching()` 具体合并语义未逐行读**。本报告确认了 `BatchingOptimization` 类的存在与"合并并发请求以减少往返"的用途（示例里的 `merged` / `skipped` 统计），但**未读 `BatchingExecutor` 的实现**，故不描述其合并细节。
