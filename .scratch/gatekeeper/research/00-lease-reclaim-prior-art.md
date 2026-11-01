# 00 前置调研：额度归还 vs 到期回收（业界做法）

_图绘期（2026-09-22）的一次定点核实，用来裁决"运行期归还要不要做"。正式调研票见 [01](01-prior-art.md) / [02](02-open-source-impls.md)。_

## 问题

在"客户端本地桶 + 中心配额分配"这一族里，用不完的额度（已发出、客户端未消耗）是靠**客户端主动归还 / 服务端召回**回收，还是只靠**租约到期 + 续租时重算**？

## 结论

**主流是"不还"。** 回收机制 = 租约到期（Doorman：`lease_length` 典型 5min）+ 续租时服务端重新分配（`refresh_interval` 典型 5s）。

**有显式归还路径的实现，语义全部不是"退回本批没用完的零头"**：

| 方案 | 额度回收机制 | 显式归还/召回 | 来源与逐字引用 |
|---|---|---|---|
| **Doorman**（YouTube） | 租约到期 + 每 refresh_interval 续租时服务端重算 | 有，**整资源放弃** | `The ReleaseCapacity RPC instructs the server to release all leases that this client has on one or more resources.`（[doorman.proto](https://github.com/youtube/doorman/blob/master/proto/doorman/doorman.proto)）；`If a client no longer wants to talk to a resource it can release it with the ReleaseCapacity RPC`；`If a client crashes the server will automatically forget about the client when all of its leases expire.`（[design.md](https://github.com/youtube/doorman/blob/master/doc/design.md)）；`Doorman only gives out capacity for a limited amount of time, in the form of leases.`（README）。触发 = 引用计数归零 |
| **Sentinel 集群流控** | 每请求向 Token Server 领 token；服务端滑动窗口（LeapArray）按秒滚动重置 | QPS：**无**；并发：有（`releaseConcurrentToken`） | `public static final int MSG_TYPE_CONCURRENT_FLOW_RELEASE = 4;`（[ClusterConstants](https://github.com/alibaba/Sentinel/blob/master/sentinel-cluster/sentinel-cluster-common-default/src/main/java/com/alibaba/csp/sentinel/cluster/ClusterConstants.java)）；release 体 `nowCalls.getAndAdd(-1 * acquireCount);`（[ConcurrentClusterFlowChecker](https://github.com/alibaba/Sentinel/blob/master/sentinel-cluster/sentinel-cluster-server-default/src/main/java/com/alibaba/csp/sentinel/cluster/flow/ConcurrentClusterFlowChecker.java)）——**注意：根本不存在租约，每请求都远程** |
| **Envoy RLS + envoyproxy/ratelimit** | 每请求/每连接远程查 Redis 计数器 | 无 | `HTTP level rate limit filter: Envoy will call the rate limit service for every new request on the listener where the filter is installed...`（[Envoy 文档](https://www.envoyproxy.io/docs/envoy/v1.17.4/_sources/intro/arch_overview/other_features/global_rate_limiting.rst.txt)） |
| **Redisson `RRateLimiter`** | 桶在 Redis，Lua 原子执行；`zrangebyscore permitsName 0 now-interval` 回收过期记录 | 有（信号量式退券） | javadoc `Releases the given number of permits.`；Lua `local newValue = currentValue + tonumber(ARGV[1]); if newValue > tonumber(rate) then newValue = tonumber(rate); end;`（[RedissonRateLimiter](https://github.com/redisson/redisson/blob/master/redisson/src/main/java/org/redisson/RedissonRateLimiter.java)） |
| **Bucket4j（Redis 后端）** | 每次 `tryConsume` 远程；令牌按时间补充 | 有（**补偿事务**） | `The "compensating transaction" is one of obvious use case, when any piece of code consumed tokens from bucket, tried to do something and failed, the "addTokens" will be helpful to return tokens back to bucket`（[Bucket.java](https://github.com/bucket4j/bucket4j/blob/master/bucket4j-core/src/main/java/io/github/bucket4j/Bucket.java)） |

**三个要点**：

1. **"运行期退零头"这个动作没有主流实现做。** 存在的归还全是：整资源放弃（Doorman `ReleaseCapacity`，引用计数归零时触发）、请求结束释放并发槽（Sentinel）、补偿失败事务（Bucket4j）、少要了退券（Redisson）。
2. **服务端主动召回（recall）在一手源码里找不到任何实现**。⚠️ **2026-09-22 由 [票 02](02-open-source-impls.md) 修正措辞**：Envoy **RLQS**（配额式协议）里**有** `AbandonAction`——服务端在"太久没收到上报"时让客户端**弃桶**，只是开源生态没有服务端实现。语义是**弃桶**、不是"退零头"，故本条结论不变，**放宽为：未见到任何开源实现提供服务端召回；协议层仅 Envoy RLQS 的 `AbandonAction`（弃桶语义）**。仍然不自造。
3. **意外收获**：Doorman 的租约是**速率租约**——客户端报 `wants`（我大概需要 X/s），服务端回 `gets`（给你 Y/s），每 5s 续租、服务端每次重新分配。这与"领 N 个令牌 + TTL 作废"的**令牌租约**是两种口径，本票未裁决，交 [票 04](../issues/04-grill-lease-model.md)。

## 对设计的影响

- **归还只做"下线归还"**（等价于 Doorman 的 `ReleaseCapacity`：实例不再服务这个 key / 实例关停时把残余还回去）。运行期归还不做
- 与之配套的旋钮是**租约时长**：租约越短，浪费越少、往返越多——归还真正买到的是"把浪费与往返频率解耦"，而这点用调租约时长能拿到大部分

## ⚠️ 未核实项

1. **Doorman 续租时"下调 wants 由服务端重算"的算法细节**未逐行读实现确认（README 只说 `on every request makes the best possible apportionment`）
2. **Netflix / Uber / Stripe / AWS** 的 quota-leasing 一手公开材料**未找到**（专利文本未采信）
3. **Bucket4j"每次都远程"**依据的是 API 文档而非 Redis 后端实现源码
4. 另有一篇中文期刊（jsjkx.com）摘要提到"服务端定期推送配额、需要时召回闲置配额"，**未读到原文**，不作为证据

以上四条并入 [票 01](../issues/01-research-prior-art.md) 的待办。
