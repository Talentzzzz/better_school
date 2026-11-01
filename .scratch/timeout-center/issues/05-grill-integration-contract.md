# 05 grilling：接入契约与事务一致性

Type: grilling
Status: resolved
Blocked by: 01, 03

## Question

业务方怎么接入超时中心？（待 01 调研 + 03 任务模型定案后 grilling 拍板，落 spec 的"接入契约"节）

- **提交通道**：业务方本地事务里怎么把任务注册做到原子？候选：RPC 直调超时中心（有分布式事务问题）、业务侧写自己的 outbox 由超时中心拉取、超时中心提供 SDK 直写中心库（跨库事务问题）、混合
- **SDK / 公共模块形态**：Java 公共 module？依赖方式？业务方要实现的接口面最小集
- **降级行为**：超时中心不可用时业务方的表现（下单降级为本地兜底？拒绝下单？）
- 三个支付超时接入方（商品/求购/跑腿）在契约下的注册点与回调点各是什么（只落契约层面的接入点描述，迁移步骤不在此 spec）

## Answer

2026-09-18 grilling 多轮拍板（事实基础 = 票 02/03/04 锚点 + 仓库现状已核：商品 `ProductOrderServiceImpl:55`、跑腿 `ErrandOrderServiceImpl:63` 在创建事务内写 `delay_outbox_message`；**求购无任何注册、仅 `TradePublishCase:263` 周期扫描兜底**——map"现状基线"写"三处均下单事务写 outbox"有误，已在 map 修正；`DelayOutboxMessageSender` 现链路发送模型已读：xxl-job 30s 扫描 → RocketMQ asyncSend 回调线程丢标记队列 → 专用线程攒批 UPDATE）。

### 结论

| # | 决策 |
|---|---|
| 提交通道 | **业务侧 outbox（本地消息表 + 业务侧推送）**：业务事务内由 client 写 `timeout_register_outbox` 一行（随业务事务提交，原子性本地事务白送），事务提交后即时发送 + 扫描兜底。（⚠️ 反转：助手曾推荐"事务提交后直接 RPC 注册（afterCommit）+ client 内存重试队列"，用户反转原话倾向"复用之前的模型"——理由：仓库已有 delay_outbox 成熟模式、零丢失、请求路径零 RPC、回滚即无行（幽灵任务主路径消失，中心幂等吸收保留为纵深防御）。注意本形态 ≠ 票面候选里的"业务侧 outbox 由超时中心拉取"——那是中心连业务库；本形态是**推送**，经典本地消息表，中心零耦合业务库） |
| 即时发送 | `TransactionSynchronization.afterCommit` 挂钩（**必须挂对**：写在事务方法 save 之后一行是提交前，回滚即幽灵发送，outbox 原子性全靠这钩子挂对地方）→ client 内虚拟线程执行注册调用（单元素列表调批量注册 API） |
| 扫描兜底 | client 自带 `@XxlJob` handler（30s 周期，与现链路一致），扫描条件 `status = 'PENDING' AND created_at <= now - 5s`——**5s 宽限期**（用户提出）覆盖即时路径在途（单次调用超时 2s，票 02 同款），无双路径竞态，替代"发送中"标记（票 03 刚论证删掉 PROCESSING，不请回来）；残余重复仅在"即时失败转扫描接管"边界，中心幂等吸收 |
| 发送器形态 | 虚拟线程 + **同步** Dubbo 调用 + 返回后就地批量 UPDATE 标记（SENT / 保持 PENDING）；**不复制现链路的标记队列 + 专用线程**（那是 RocketMQ 回调模型特有——回调线程不能碰 DB；同步调用无此问题）；**不用 Dubbo 原生异步**（三种形态：接口方法声明 `CompletableFuture` / `RpcContext.startAsync()` / 自包线程池——坑：future 异常不接即静默失败、RpcContext ThreadLocal 跨线程丢失；且后台发送场景无线程可释放，原生异步是负资产）。与中心侧回调发送器（票 04 虚拟线程）同构 |
| 重试 | **无上限持续重试，永不 DEAD**（与现链路 5 次耗尽 → DEAD 的有意差异）：注册无时效性代价——中心恢复后补注册，action_time 已过期则 `next_fire_time <= now` 立即触发补关单，自愈零人工；照搬 5 次上限则中心挂 10 分钟就全 DEAD 还得造人工重推。积压量告警兜底 |
| 注册 API | **批量**：入参 `List<注册单>`，**注册单形状保留票 04 口径**（`bizId + taskTypes 列表 + callbackGroup + actionTime`，一次挂多规则不变——批量信封是在其外包一层 List，让多个 bizId 也可一次 RPC；单条 = 单元素列表，接口面不增），响应**成功/失败双清单**（与票 02 回调双清单对称）；失败项按 `(taskType, bizId)` 对应回 outbox 行继续重试 |
| 中心落库形态 | 批量注册中心侧 = **虚拟线程逐行处理，不做多值 INSERT**：① ShardingSphere 多值 INSERT 不按行路由（整语句单分片，混键未定义），要批量须应用层复刻路由函数分组——不值；② 幂等语义逐行四分支（新插入 / 幂等成功 / 终态复活 / 冲突失败），`INSERT IGNORE` 会把冲突失败吞成幂等成功、废掉守卫；③ 虚拟线程逐行毫秒级（批 200 ÷ Hikari 16 连接），且注册不需要跨行事务——双清单语义本为部分失败设计。与回调侧"批合成一次调用、业务侧逐行条件 UPDATE"同形状：协议层批量、落库层逐行 |
| 幂等与冲突守卫 | `uk(task_type, biz_id)` **不变、不加 callback_group**（三列唯一会让同键因 group 变化插两行 → 双回调，拆坏幂等键）。注册逻辑撞 uk 后查行比 group：**同 group** → 票 03 幂等双分支（非终态幂等成功应答 / 终态复活）；**不同 group** → **返回失败 + 告警**——两家业务撞 type 会被静默错误路由（第二家的回调打进第一家），守卫让冲突显式化；应用改名导致的 group 变化同此暴露（刻意从严）。终态复活分支同样先过守卫 |
| taskType 规范 | **业务域前缀命名，契约写死**（沿 `Constants` topic 命名风格）；biz_id 无需全局唯一（type 承担业务域隔离，复合引用编码进 biz_id 是票 03 既有口径） |
| outbox 表 | 业务库新表 **`timeout_register_outbox`**（**不复活 `delay_outbox_message`**——那是待下线旧链路）；成功置 SENT 保留行（同现链路；量级 ≈ 订单量约 20MB/年，将来归档）。定稿 DDL 见下 |
| SDK 载体 | **两模块**（类 dubbo api jar vs starter）：`timeout-center-api` 保持**纯接口 + DTO**（`ITimeoutTaskCallback` + `ITimeoutTaskRegister` + 各 DTO；中心与业务共享的序列化对齐面）+ **`timeout-center-client`**（业务方专用：outbox writer + 即时/扫描发送器 + Spring Boot 自动装配；`callbackGroup` 默认自动取 `spring.application.name`，可配置覆盖）。（⚠️ 曾讨论 client 合并进 api：技术可行，但三代价 = api 纯度破坏（仓库惯例 api 层 = 纯接口 + DTO）、中心进程背无用类 + 自动装配需 `@Conditional` 守卫排除、重试策略改动连带中心升级契约未变的 jar——用户拍板独立模块） |
| 降级 | **照常下单，不拒绝下单**：中心不可用 → 注册行积压 PENDING、扫描持续重试，恢复自动追平（零丢失）；回调方向业务方零降级（中心自重试）。不做内存重试队列（outbox 替代之）、不做本地兜底表（outbox 本身就是） |
| 接入动作 | 四步 = ① 引 api + client 两 jar ② 实现 `ITimeoutTaskCallback` 并 `@DubboService` 暴露 ③ 创建事务内调 client 的 `register()`（纯本地 INSERT，随业务事务提交）④ xxl-job 调度中心为 client 扫描 handler 配一次调度。约束同票 02：接入方与中心共用同一 Nacos 集群 |

### 定稿 DDL（票 06 落 spec 用）

业务库（better_school），与 `timeout_task` 分表同风格的 VARCHAR 状态：

```sql
CREATE TABLE timeout_register_outbox (
    id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_type      VARCHAR(64) NOT NULL COMMENT '任务类型（业务域前缀命名，与中心侧一致）',
    callback_group VARCHAR(64) NOT NULL COMMENT '回调路由 Dubbo group（= 业务方应用名）',
    biz_id         VARCHAR(64) NOT NULL COMMENT '业务引用 id（复合引用编码进此列）',
    action_time    DATETIME    NOT NULL COMMENT '理论到期时刻（原样传给中心）',
    status         VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT（成功置 SENT 保留行）',
    retry_count    INT         NOT NULL DEFAULT 0 COMMENT '已重试次数（观测用；无上限、永不 DEAD）',
    created_at     DATETIME    NOT NULL COMMENT '创建时间（扫描 5s 宽限期依据此列）',
    updated_at     DATETIME    NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (task_type, biz_id),
    KEY idx_scan (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务注册 outbox（业务库）';
```

### 三接入方注册点/回调点（契约层，迁移步骤不在本 spec）

| 接入方 | 注册点 | 取消点（可选优化，首版可不调，票 03 口径） | 回调点 |
|---|---|---|---|
| 商品订单 | 创建订单事务内调 client `register()`（替代现 `sendPayTimeoutDelayMessage` 写 outbox） | 支付成功后取消任务 | 现 `handlePayTimeout` 关单动作：条件 UPDATE 取消 + 商品回在售 + 搜索同步消息 |
| 求购 | 求购创建事务内调 `register()`（**现状无注册、仅扫描兜底——契约下补注册**） | 求购成交/关闭后取消任务 | 取消求购（现 `handlePayTimeout`） |
| 跑腿 | 跑腿单创建事务内调 `register()`（替代现 outbox 写入） | 支付成功后取消任务 | 取消跑腿单（现 `handlePayTimeout`） |

三接入方同在 better-school 单体 → callbackGroup 同 = bs 应用名 → **单回调实现按 taskType 分发三路**（契约层口径：单 group 单实现）；taskType 三常量 `PRODUCT_ORDER_PAY_TIMEOUT` / `WANT_TO_BUY_PAY_TIMEOUT` / `ERRAND_PAY_TIMEOUT` 落业务方 `bs-types` 的 `Constants`（taskType 是业务方语义，契约只定命名规范 + spec 记三取值）。

### 决策依据

- outbox vs 直接调用（业界对照）：跨服务原子性的教科书答案是 2PC/XA、TCC/Saga、事务消息、本地消息表四类，解决的都是"下游副作用最多执行一次"；本场景注册动作**双向容忍偏差**（多发 = 幽灵任务被幂等吸收、少发 = 补注册自愈），本可降级为 at-least-once 直调——但 outbox 把原子性从"钩子 + 重试队列"换成"本地事务白送"，且仓库已有同款模式经验，代价（一张表 + 发送器）远小于收益（零丢失 + 请求路径零 RPC + 幽灵任务消失）。
- 双路径发送（即时 + 扫描）：即时把注册延迟从 ≤30s 降到毫秒（用户要求）；5s 宽限期是便宜的在途防重——即时路径调用超时 2s 封顶，宽限内扫描摸不到，无需"发送中"标记。
- 同步 vs 原生异步：现状 `DelayOutboxMessageSender` 的标记队列 + 专用线程就是为"回调线程不碰 DB"而造；虚拟线程 + 同步调用下结果就地可得，该复杂度不需要复制。
- 两模块 vs 单 api：api 是 Dubbo 序列化对齐面，必须保持纯接口 + DTO（仓库惯例）；重试/装配等行为住业务方侧，独立成 client 才能独立发版。
- 无上限重试：注册与 MQ 消息的本质差异 = 无时效性代价（晚注册照样按 action_time 触发，过期即补触发），DEAD 态与人工重推在注册场景无存在必要。

用户驱动点：**outbox 反转**（否掉助手推荐的 afterCommit 直调 + 内存重试队列，理由"复用之前的模型"）、**扫描置后几秒宽限期**、**批量注册**（追问"批量好还是不批量好"后采纳批量）、**client 独立模块**（追问合并进 api 的可行性后拍板独立）、**type 冲突追问**（引出"同键不同 group → 失败 + 告警"守卫）、**逐行 INSERT 追问**——均用户提出或确认；其余按推荐。

### 转票锚点（后续票必须承载，勿丢）

- **票 06（spec）**：「接入契约」节从本 Answer 落稿，至少含：提交通道（outbox 形态 + afterCommit 即时发送 + 5s 宽限扫描兜底 + 无上限重试 + 积压告警）、SDK 两模块接口面与接入动作四步、降级口径、注册 API（批量 + 双清单 + 同键不同 group 守卫）、`timeout_register_outbox` DDL、三接入方接入点表。注意两处与上游锚点的差异以本票为准：① 注册 API 为**批量信封**（票 03/04 定的是单 bizId 的入参形状，票 05 外包一层 List，注册单内形状不变）；② 幂等双分支以 **callback_group 一致**为前提（票 03 未提守卫）。

spec"接入契约"节由票 06 汇总本 Answer 落稿。
