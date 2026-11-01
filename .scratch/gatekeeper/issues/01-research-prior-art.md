# 01 调研：业界"本地桶 + 中心配额"方案对照

Type: research
Status: resolved
Blocked by:

## Question

为 [票 04（租约口径）](04-grill-lease-model.md) 与 [票 06（中心分片与高可用）](06-grill-center-sharding-and-ha.md) 提供事实基础。

背景：我们要做的是"客户端本地令牌桶 + 中心配额分配"——本地优先取令牌，不够时向中心批量领。图绘期已做一次定点核实（[research/00](../research/00-lease-reclaim-prior-art.md)，结论：主流靠"租约到期 + 续租时重算"回收，不存在运行期退零头，也不存在服务端召回）。本票把它扩成完整对照。

**只收事实与做法，选型判断留给票 04 / 06。**

请逐项给一手来源（官方文档 / 论文原文 / 源码）：

1. **配额分配算法**：中心怎么决定给某个客户端发多少？Doorman 的 `wants` / `gets` + apportionment（含"服务端每次请求都做 best possible apportionment"的具体含义）；Sentinel 集群流控的分片与阈值分配；有没有按历史用量自适应的实现
2. **租约口径对照**：速率租约（Doorman：`wants`/`gets` + `refresh_interval` + `lease_length`，续租 = 重新分配）vs 令牌租约（领 N 个 + TTL）。业界各自怎么选、为什么；有没有混用的
3. **超发 / 在飞量控制**：有没有实现给"已发出未消耗"设上限？没有的话，他们用什么替代机制保证突发可控（租约时长？batch 大小？）
4. **降级**：中心不可用时客户端怎么办——fail-open / fail-closed / 降级到本地估算限流。逐实现给出默认行为与可配置性
5. **分片与高可用**：中心多实例怎么分片（Sentinel 独立模式 token server 的分片与注册、多 token server 的协作）；单个分片挂了影响谁；扩缩容怎么迁移；**中心重启后额度状态怎么办**（内存丢失重置 vs 持久化）
6. **补齐 [research/00](../research/00-lease-reclaim-prior-art.md) 的四条 ⚠️ 未核实项**：Doorman 续租重算的实现细节、Netflix/Uber/Stripe/AWS 的一手材料、Bucket4j Redis 后端源码、那篇中文期刊的原文

产出：`research/01-prior-art.md`。每条结论必须能指回一手来源；找不到证据的写进 ⚠️ 未核实项，不要用常识补全。

## Answer

完整报告：[research/01-prior-art.md](../research/01-prior-art.md)（一手来源 + 逐字引用 + 行号，覆盖 Doorman / Sentinel / Envoy RLS / envoyproxy·ratelimit / Envoy RLQS / Redisson / Bucket4j / Cloudflare / Uber / Stripe / AWS / 中文期刊 DFMF）。

1. **最贴近本设计的不是 Doorman，是 Envoy 的 Rate Limit Quota Service（RLQS）**：协议级就是"客户端本地桶 + 中心下发带 TTL 的配额 + 客户端周期上报用量 + 服务端重算后主动推送"。它**有服务端主动召回**（`AbandonAction`：长时间没收到用量上报就命令客户端丢弃整个 bucket），并且**用 `assignment_time_to_live = 0` 显式处理"中心重启前让客户端降级"**。00 的"不存在服务端召回"要按这条修正——召回存在，但语义是**整桶作废，不是退零头**（这点 00 仍然成立）。
2. **配额分配算法有三类可对照口径**：Doorman 是每请求重跑 `wants/gets` 公平分配（`FAIR_SHARE` O(n²) / `PROPORTIONAL_SHARE` O(n)，硬性 `minF(gets, unusedCapacity)`）；Sentinel 是"单机阈值 × namespace 在线连接数"（默认 `AVG_LOCAL`，总量随连接数线性伸缩）；DFMF 用滑动窗口预测下一周期配额 + 按请求比例重分。**按历史用量自适应的部分都在客户端**（Doorman `AdaptiveQPS` 加权移动平均算 `wants`；Bucket4j 预测"他人消耗速率"）。
3. **"已发出未消耗"设上限的只有 Bucket4j 一家**（`DelayParameters.maxUnsynchronizedTokens` + `maxUnsynchronizedTimeoutNanos`，即"本地未同步令牌数 / 未同步时长"双上限）。Doorman 承认 `shortfall` 存在（仿真峰值 106%）并**明确放弃治理**（治理会拖慢收敛），靠"租约短"间接控制；Sentinel 用 `exceedCount` / `maxOccupyRatio`；Uber 靠概率丢弃。
4. **降级**：Doorman 设计上是 pessimistic/optimistic/safe 三档（`safe_capacity`：-1 放行 / 0 阻断 / 正数定额），但**开源 Go 客户端只实现了 pessimistic**（`TODO(ryszard): Handle safe capacities.` + `FIXME … This probably should be the safe capacity instead` → 到期即 0）；Sentinel **默认 fail→本地单机限流**（`fallbackToLocalWhenFail=true`）；Envoy RLQS 是 `no_assignment_behavior` / `expired_assignment_behavior`（`reuse_last_assignment` 或定额 + 超时），**不配即 fail-open**；Stripe / Uber 均显式 fail-open；AWS 只有服务端。
5. **中心重启后状态有两条对立路线**：Doorman **不持久化不复制**，靠 learning mode 在"一个 lease_length 内"从客户端自报的 `has` 重建（期间新客户端只能拿 0）；Sentinel 状态全在堆内存 `METRIC_MAP`，重启即清空、无学习模式；DFMF（期刊）**主从 + 快照持久化**，论文图 6 显示其主节点崩溃后的对外分发配额明显优于 Doorman。
6. **00 的四条未核实项**：Doorman 续租重算（✅ 已读到 `algorithm.go` 三个算法源码）、Bucket4j Redis 后端（✅ 已读到 `PredictiveOptimization`/`Sampling`/`DelayParameters` 源码）、中文期刊原文（✅ 已拿到全文 PDF，题名《基于微服务的预分配额度限流设计研究》，计算机科学 2024, 51(6): 346-353）、Netflix/Uber/Stripe/AWS 的 quota-leasing（❌ **一手材料找不到支持**：Uber 做过并**废弃了**本地令牌桶，Stripe/Netflix/AWS 都不做配额租赁）。

**未核实项（原样带出，勿当定论）**：① Sentinel `exceedCount` / `maxOccupyRatio` 的语义只有代码无官方文档，"倍数"一词来自二手博客，未采信；② Envoy RLQS **无开源服务端实现**，其分片/选主/持久化/重启重建**全部无从以一手材料回答**，本票关于 RLQS 的结论只覆盖协议与客户端 filter；③ RLQS 的分配算法是黑盒（"The logic is outside of the scope of the protocol API"）；④ Doorman 分层聚合请求 `PriorityBandAggregate` 只确认 proto 形状，未确认聚合语义；⑤ Uber GRL 的 3 层架构与运营数字只有科普体博客，drop ratio 公式**未采信二手版本**；⑥ DFMF 实验只有 4 台云主机规模、无方差/显著性，且作者自认优势部分来自"让节点尽量持有闲置配额"（即主动超发），其 Doorman 对照臂是否为上游原版未确认；⑦ Cloudflare Pingora `pingora-limits` 源码未读；⑧ 腾讯北极星"全量下发"限流文档未取到一手材料；⑨ Redisson 是否有事实上的本地降级路径未核实；⑩ Bucket4j `Optimizations.batching()` 的合并语义未逐行读。

> ⚠️ **2026-09-22 由 [票 04](04-grill-lease-model.md) 收口反转**：本票 Answer 第 3 条的"逆主流"论证已裁完——结论是该机制**不值得设**（令牌口径下可行条件 `B ≤ (λ−1)·K/(f·N)` 在 N=5000 时给 B<1），plan B（选 Doorman 路线）就是最终方案，**图绘期"在飞量上限默认 2×"撤销**。另：本票 Answer 第 1 条"最贴近本设计的是 Envoy RLQS"被票 04 直接采纳为口径依据（RLQS 下发的是 `rate_limit_strategy` 速率、不是令牌数）。另：本票 `## Question` 开头那句设计背景"本地优先取令牌，不够时向中心批量领"同样是**令牌口径**、一并作废（票 04 裁为速率口径）。
