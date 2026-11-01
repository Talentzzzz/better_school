# 01 调研：阿里超时中心与业界延时任务方案

Type: research
Status: resolved
Blocked by:

## Question

为"超时中心微服务"的设计拍板（回调协议 / 任务数据模型 / 分片扫描 / 接入契约四张票）提供事实基础。请调研：

1. **阿里超时中心**的公开资料：架构、任务数据模型、扫描/触发机制、业务回调方式、失败重试与幂等。（关键词方向：阿里 超时中心、SchedulerX、延时任务平台；有赞/美团/滴滴等公开的延时任务/定时任务平台实践也收）
2. **延时任务主流方案对比**：DB 扫表 + 分片轮询、时间轮（Netty/Kafka 风格）、RocketMQ 延时消息、Redis ZSet——各自适用场景与坑，重点收"DB 扫表 + 分布式分片"这一路的已知问题（扫表压力、时间精度、防漏防重）。
3. **xxl-job 分片广播机制**细节：分片参数（shardIndex/shardTotal）如何传递、广播路由与失败转移语义、分片任务里的幂等要求。
4. **回调失败重试与幂等的通行做法**：回调方与被回调方的幂等责任划分、重试退避策略、死信/人工兜底。

只收事实与各方案的做法，不做本项目的选型判断——选型留给 02–05 的 grilling 票。

## Answer

调研报告：[research/01-alibaba-timeout-center.md](../research/01-alibaba-timeout-center.md)。核心结论：阿里公开口径的"超时中心" = 超时任务库（job_id 全局唯一 + status + action_time，分表/分桶）+ 周期扫描到期行、按分片分发给多实例执行；防重靠分片归属唯一与状态机，防漏靠下轮重扫 → 整链路 at-least-once，幂等责任在被回调业务方（SchedulerX/xxl-job 官方口径一致）；重试退避 + 死信 + 人工兜底是通行做法。xxl-job 分片广播（shardIndex/shardTotal 传递、分片粒度重试、misfire/阻塞策略）与 DB 扫表/时间轮/RocketMQ 定时消息/Redis ZSet 四方案事实与坑均已收，全程一手来源。
