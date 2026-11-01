# 02 grilling：回调协议拍板

Type: grilling
Status: resolved
Blocked by: 01

## Question

超时中心扫出到期任务后，以什么方式调用业务服务完成关单？候选（待调研票 01 结论回来后 grilling 拍板）：

- Dubbo RPC 直调
- internal REST（类似 agent sidecar 回调 Java 的既有先例）
- 发 MQ 消息由业务方消费
- 混合（MQ 为主 + 扫描重试）

要一并拍板的子问题：payload 形态（引用 id 还是快照）、回调失败的重试策略（次数/退避/死信）、幂等责任在超时中心与业务方之间怎么划分。结论落 spec 的"回调协议"节。

## Answer

2026-09-18 grilling 三轮拍板（事实基础 = 票 01 调研报告；仓库现状已核：better-school 与营销两服务 Dubbo 现役、Nacos 注册中心 + tri 协议、Provider 在 `trigger/rpc/`、`xxx-api` 模块放对外接口）。

### 结论

| # | 决策 |
|---|---|
| 回调方式 | **Dubbo RPC 直调**（tri 协议，Nacos 注册中心）；回调失败靠下轮扫描自然重试，不引 MQ |
| 接口形态 | 中心工程建 **`timeout-center-api` 模块**，定义泛化接口 `ITimeoutTaskCallback` + `TimeoutTaskDTO`/`BatchCallbackResult`；业务方引 jar、实现接口、`@DubboService` 暴露；中心按任务行 `callbackGroup` **动态构建并缓存 ReferenceConfig** 路由——新业务接入中心零代码变动 |
| payload | **引用 id，不带快照**：`taskId`（全局唯一，幂等键）/ `taskType` / `bizId` / `actionTime`（理论到期时刻，供业务监控精度）（⚠️ 2026-09-18 票 04 反转：**taskId 删除**，回调 DTO 定稿**三字段** taskType / bizId / actionTime；业务回调幂等 = 业务自身状态机条件更新，键为业务对象本身；定稿见票 04 ## Answer"标识反转"节） |
| 批量 | **批量回调**：一次扫描批次合成一次调用，业务方批量条件 UPDATE；单批 ≤ 200 条（可配，票 04 扫描侧取同一配置值） |
| 响应 | 同步快速返回（建议 <1s），中心侧单次调用 timeout 2s（可配），超时/异常一律按失败进重试；返回**成功/失败双清单**（`successTaskIds` / `failedTaskIds`），中心成功项置 SUCCESS、失败项逐个重试（⚠️ 2026-09-18 票 04 反转：清单项定稿 = `(taskType, bizId)` 对，不再是 taskId 标量清单——DTO 删 taskId 的连带） |
| 幂等 | 中心 at-least-once（重发 `taskId` 不变）；业务方负责"不重"，契约写"必须幂等"，推荐手段 = 状态机条件 UPDATE；收到重复 `taskId` 幂等吞掉并返回成功；**未命中状态机（已终态）= 幂等成功**，不算失败（⚠️ 2026-09-18 票 04 反转：taskId 删除，重发口径 = 同 `(taskType, bizId)`；批量成功/失败双清单**成对返回**（每项 = type + bizId 对）） |
| 顺序/精度 | **不保证顺序**，业务方以自身状态机为准；精度写口径"尽力按 actionTime 触发，精度 = 扫描周期"，具体数字随票 04 / 部署配置 |
| 重试 | **16 次，30s 起指数翻倍、单次封顶 1h**（约 10h 总窗，数字可配）；失败项 `retry_count`+1、`next_retry_time` 后移，下轮扫描捞起重调（重试机制复用扫描，零额外设施）（⚠️ 2026-09-18 票 03 回填：该列定稿名 `next_fire_time`——单列同时承载首触发与重试，见该票 ## Answer） |
| 死信 | 重试超限置 **FAILED 终态** + 中心管理接口（死信列表 / 单条与批量重发 / 手动取消）+ 告警日志点；接入监控告警平台另议（map Not yet specified） |
| 成功处理 | **UPDATE 置 SUCCESS 终态，不删除**；终态归档策略转票 03 |

用户驱动点：重试选 16 次对齐阿里（否掉推荐的 8 次）；批量回调（扫多条 → 一次调用 → 业务方批量 UPDATE）为用户提出；成功/失败双清单响应亦为用户提出；group 路由三方案对照后用户确认方案一（否掉泛化调用——丢双向类型安全且省的是不存在的依赖；否掉 REST+回调地址——本票第一问已定 Dubbo 直调且与仓库惯例相悖）。

### 决策依据

- 选 Dubbo 直调而非 MQ：重试/死信/人工兜底是回调协议的核心内容，MQ 方案将其外包给 RocketMQ 消费重试，中心不感知最终结果、设计增量薄；直调下中心全链路状态可查可改（阿里口径"可运维"），重试复用扫描机制边际成本低。
- 引用 id 而非快照：回调时刻业务状态必须以业务库为准（15 分钟窗口内可能已支付/已取消），快照既误导又是死重；副产品是回调线上跑的全是中心 api 的 DTO，新业务接入不触发中心序列化 allowlist 变动（Dubbo 3.3 STRICT）。
- 同步快速返回而非收单异步：收单异步在业务方内部重建可靠异步链路（内存队列丢任务 → 要再落库/发 MQ + 自养兜底 job），现链路 `productTimeoutScan` 兜底正是该模式不可靠的活证据；同步下兜底职责收归中心重试。
- 幂等分工为调研通行做法原文：SchedulerX 官方"**SchedulerX 不保证子任务绝对执行一次。在特殊条件下会 failover，可能导致子任务重复执行，需要业务方自行实现幂等。**"（调研报告 §1.3）；任务终态保留亦循阿里口径"**任务被处理完成后，任务的状态被更新成已处理。**"（调研报告 §1.2）。
- 16 次指数+封顶：纯指数翻倍第 16 次间隔 ≈4.5 天（总窗 9 天）不现实；封顶 1h 后 9 次每小时巡检，总窗约 10h，覆盖业务发版/重启级故障。

### 转票锚点（后续票必须承载，勿丢）

- **票 03（任务数据模型）**：任务行需承载 `retry_count`、`next_retry_time` 两列；状态机含 SUCCESS / FAILED 终态且**不物理删除**；终态归档策略在此拍。（✅ 2026-09-18 已由票 03 拍定：列名定稿 `next_fire_time`——单列统一承载首触发与重试；状态机 5 态，终态 SUCCESS / FAILED / CANCELED；归档 = 首版不归档、量级论证入 spec；细节在该票 ## Answer）
- **票 04（分片扫描）**：扫描 WHERE 条件排除终态行（`status IN (待处理, 重试中) AND next_retry_time <= now`；状态命名以票 03 状态机定稿为准——⚠️ 2026-09-18 票 03 回填：列名定稿 `next_fire_time`，状态定稿 `status IN ('WAITING','RETRYING')`）；回调异步于扫描线程（中心侧线程池隔离）；单批 ≤ 200 与回调协议同一可配值；扫描周期数字回填 spec"回调协议"节的精度口径。（✅ 2026-09-18 已由票 04 拍定：周期 10s；回调异步 = 独立虚拟线程 executor（扫描 fan-out 同用虚拟线程，两 executor 分离保观测面）；分片 = 库×表级 + xxl-job 广播认领；细节在该票 ## Answer）
- **票 05（接入契约）**：注册字段含 `callbackGroup`（= 业务方 Dubbo group）；约束**所有接入方与中心共用同一 Nacos 集群**（动态订阅前提）；接入动作四步 = 引 jar → 实现 `ITimeoutTaskCallback` → `@DubboService(group=应用名)` → 注册任务带 `callbackGroup`；SDK 载体 = `timeout-center-api` jar。（✅ 2026-09-18 已由票 05 拍定：SDK 载体定稿**两模块**（`timeout-center-api` + `timeout-center-client`）；提交通道 = **业务侧 outbox**（`timeout_register_outbox` + afterCommit 即时发送 + 扫描兜底），"注册任务带 callbackGroup" 的直接 RPC 口径被反转——注册动作变成本地 INSERT，经 client 发送器调批量注册 API；接入动作四步更新见票 05 ## Answer）

spec"回调协议"节由票 06 汇总本 Answer 落稿。
