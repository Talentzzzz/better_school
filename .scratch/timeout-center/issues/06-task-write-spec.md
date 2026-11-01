# 06 task：汇总产出 spec.md

Type: task
Status: resolved
Blocked by: 02, 03, 04, 05

## Question

把 02–05 四张票的结论汇总成 `.scratch/timeout-center/spec.md`，即本 effort 的终点。spec 至少含：

1. 超时中心微服务设计：工程形态（独立 Spring Boot app、同容器独立库）、任务数据模型与状态机、扫描与调度、回调协议、可观测性（若拍板进 spec）
2. 接入契约：提交通道、SDK/公共模块接口面、降级行为
3. 首个接入方（商品订单/求购/跑腿支付超时）在契约下的接入点描述

不含迁移与旧链路下线步骤（见 map 的 Out of scope）。产出后此 effort 收口。

## Answer

2026-09-18 产出 `.scratch/timeout-center/spec.md`（本 effort 终点）。十章结构：目标与范围 / 工程形态 / 数据模型与状态机（DDL v2 + 5 态 + 幂等复活 + 归档）/ 任务 API（批量注册 + 取消）/ 扫描与调度（单 job 10s + ShardingSphere 同编号空间 + 虚拟线程 + 发送编排 + 防漏防重四层 + 量级）/ 回调协议（Dubbo 直调 + 三字段 DTO + 双清单 + 16 次重试死信）/ 接入契约（outbox 提交通道 + SDK 两模块 + 四步接入 + 降级 + outbox DDL）/ 三接入方接入点表 / 可观测性（已拍项 + 开放项）/ Out of scope。

- 汇总口径：冲突处以编号更大的票为准（task_id 删除、批量信封、callback_group 守卫等反转均已按定稿落稿，票 02/03 的 taskId 时代措辞未带入 spec）。
- 可观测性开放项（中心侧堆积监控、告警平台接入、部署细节落位）在 spec §9 显式标注"另议，不阻塞落地"。
- effort 收口：票 01–06 全部 resolved，map 无剩余 frontier。
