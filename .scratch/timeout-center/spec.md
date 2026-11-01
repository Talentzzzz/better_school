# Spec：超时中心（timeout-center）

_Status: 定稿 2026-09-18（票 02–05 拍板汇总，票 06 收口产出）；修订 2026-09-18：删除 `timeout-center-client` 模块，提交通道由发送方按 §7.1 路径自实现_
_决策出处：[票 02 回调协议](issues/02-grill-callback-protocol.md) / [票 03 数据模型](issues/03-grill-task-model-lifecycle.md) / [票 04 分片扫描](issues/04-grill-shard-scan-design.md) / [票 05 接入契约](issues/05-grill-integration-contract.md)；调研基础见[票 01 报告](research/01-alibaba-timeout-center.md)。冲突处以编号更大的票为准（票 04 反转票 03 的 task_id，票 05 反转上游的注册 API 形状）。_

## 1. 目标与范围

超时中心是一个独立微服务，统一承载"到某个时刻触发一次业务回调"的超时任务：业务方注册任务（如 15 分钟支付超时），中心到点回调业务方完成关单。

- **覆盖**：超时中心微服务自身设计 + 业务接入契约 + 首个接入方（商品订单 / 求购 / 跑腿支付超时）的接入点。
- **不含**：业务迁移与旧链路（`delay_outbox_message`、`ProductPayTimeoutConsumer`、`productTimeoutScan`）下线步骤——另开 effort，已拍将来一次性下线、不留双跑观察期。
- **通用性口径**：数据模型与回调协议按任意业务超时抽象（task_type + biz_id + 回调），spec 只落三处支付超时接入；跑腿执行时限等非支付超时不落具体接入设计。

## 2. 工程形态

| 项 | 决策 |
|---|---|
| 工程 | **独立 Spring Boot app**，仓库新顶层工程（与 better-school 主工程平级） |
| 存储 | 同 MySQL 容器内的**独立库**（不独立容器），16 张分表，见 §3 |
| 中间件 | Dubbo（tri 协议）+ Nacos 注册中心；xxl-job 3.0.0 调度；ShardingSphere-JDBC 5.x 分片路由；JDK 21 虚拟线程；与仓库现栈一致（Spring Boot 3.3.5 / MyBatis） |
| 硬约束 | **所有接入方与中心共用同一 Nacos 集群**（动态 ReferenceConfig 订阅的前提，见 §6） |
| 模块 | `timeout-center-api`（纯接口 + DTO，中心与业务共享）；**不封装 client 模块**，提交通道由各发送方按 §7.1 路径自实现 |

部署细节（端口 / 启动脚本 / 建表脚本落位）归落地票，spec 不锁。

## 3. 任务数据模型与状态机

### 3.1 存储形态

同库 **16 张分表** `timeout_task_00..15`（库×表两维路由预留，DB_COUNT = 1 起步：槽位 g 展平编号，`db = g / TABLES_PER_DB, table = g % TABLES_PER_DB`；将来扩库只改配置，代码结构不动）。

**标识 = 自然键 `(task_type, biz_id)`，无中心生成的 taskId**。业务方永不持久化任何中心 id；`id` BIGINT 自增仅作物理主键（无业务含义）。`uk_type_biz` 语义 = **单表内唯一 + 路由函数确定性 = 全局幂等**（同 key 永远同表）；某行全部读写同库同表，无跨库事务问题。

### 3.2 DDL（× 16 张，`_00`..`_15`）

```sql
CREATE TABLE timeout_task_NN (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键（无业务含义）',
    task_type       VARCHAR(64)  NOT NULL COMMENT '任务类型（业务对象上的一种超时动作，如 PAY_TIMEOUT）',
    callback_group  VARCHAR(64)  NOT NULL COMMENT '回调路由 Dubbo group（= 业务方应用名）',
    biz_id          VARCHAR(64)  NOT NULL COMMENT '业务引用 id（不带快照；复合引用编码进此列）',
    action_time     DATETIME     NOT NULL COMMENT '理论到期时刻（不可变；回调 DTO 携带，供业务监控精度）',
    next_fire_time  DATETIME     NOT NULL COMMENT '下次可触发时刻（注册时=action_time；失败重试后=now+退避）',
    status          VARCHAR(16)  NOT NULL COMMENT 'WAITING/RETRYING/SUCCESS/FAILED/CANCELED',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数（16 次封顶进 FAILED）',
    created_at      DATETIME     NOT NULL COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (task_type, biz_id),
    KEY idx_scan (status, next_fire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表';
```

要点：

- **单列 `next_fire_time` 统一扫描时刻**：`action_time` 不可变（理论到期时刻，回调 DTO 携带供业务监控精度）；`next_fire_time` 可变（注册时 = action_time，失败后 = now + 退避）。扫描走复合索引 `idx_scan (status, next_fire_time)`，OR 双列无法共用一条复合索引，故单列。
- **无 payload 列**：引用 id 拍板后回调内容就是 DTO 三字段；复合引用编码进 `biz_id`（如 `userId:couponId`）。将来加列是兼容变更。
- **status 用 VARCHAR(16)**：跨服务契约载体（死信管理、对账直接读），可读性优先；`DATETIME` 秒级存储精度。
- 分片键不建列：路由直接吃已有列的值 `hash(taskType + bizId) % 16`，不落桶号。

### 3.3 状态机（5 态，无 PROCESSING）

```
WAITING ──┬─ 回调成功 ──▶ SUCCESS（终态）
          ├─ 回调失败 ──▶ retry_count+1，next_fire_time = now+退避 ──▶ RETRYING
          └─ 取消 API ──▶ CANCELED（终态）
RETRYING ─┬─ 回调成功 ──▶ SUCCESS（终态）
          ├─ 回调失败 ──▶ retry_count+1；≥16 次 ──▶ FAILED（终态）
          └─ 取消 API ──▶ CANCELED（终态）
FAILED  ── 管理接口重发 ──▶ RETRYING（retry_count 清零重开一轮，不动 action_time）
```

- 扫描 WHERE 永远排除三个终态（`status IN ('WAITING','RETRYING') AND next_fire_time <= now`）。
- **无 PROCESSING 的理由**：回调是同步闭环（扫描捞批 → 调用 → 按双清单就地置状态），状态翻转与回调同程，无"处理中"观测窗口；进程崩在"回调成功、状态未落"之间则行仍待触发，下轮重扫重发、业务幂等吞掉（at-least-once）。加 PROCESSING 要定义租约超时回收，复杂度全花在防一次无害的重复触发上。

### 3.4 幂等与复活

同一 `(task_type, biz_id)` 在途至多一个任务；同对象多种超时用不同 task_type 表达。注册撞 uk 后查行比 `callback_group`：

| 已有行 | 同 group | 不同 group |
|---|---|---|
| WAITING / RETRYING | **幂等成功应答**，不改期 | **冲突失败 + 告警** |
| 终态（SUCCESS/FAILED/CANCELED） | **复活**：status 置回 WAITING、action_time 更新为新值、retry_count 清零 | **冲突失败 + 告警** |

- **复活是必须的**：MySQL 无"仅非终态唯一"的部分索引，否则终态行永久锁死 `(type, biz)` 组合。
- **旁注（errand-lock-review-hub effort 回填）**：同一逻辑计时器反复重启（如跑腿锁单：放弃 → 重抢）**应使用不同 bizId**（复合轮次 `{orderId}:{lockSeq}`），不要依赖同键复位——在途键重注册是「幂等成功、不改期」，新计时会被旧到点静默吞掉（最坏永久卡死）；不同 bizId 让每次注册都是新键插入，陈旧任务只影响旧键，回调再以轮次守卫挡住。
- **不同 group 冲突从严**：两家业务撞 type 会被静默错误路由（第二家的回调打进第一家），守卫让冲突显式化；应用改名导致的 group 变化同样暴露。uk 不加 callback_group（三列唯一会让同键因 group 变化插两行 → 双回调，拆坏幂等键）。
- 管理接口的手动重发（FAILED → RETRYING）与复活并行不悖。

### 3.5 归档

**首版不自动归档**，终态行永久保留。量级论证：按 10 万任务/年、行宽约 200B 计约 20MB/年。将来量大了加"按 `updated_at` 搬历史表"的归档 job，数据模型与契约均不受影响。

### 3.6 与 `delay_outbox_message` 的对照（吸收 / 差异）

- **吸收**：幂等唯一键、`(status, 时间)` 复合扫描索引、`retry_count`、`created_at/updated_at`。
- **差异**：`topic` → `callback_group`；`payload` TEXT 删除（见 §3.2）；`delay_until` → `action_time`（不可变）+ `next_fire_time`（可变）；`status` 用 VARCHAR 不用 TINYINT。

## 4. 任务 API（中心对业务）

只有**注册**和**取消**两个；续期/改期不提供（首版无场景，将来 = UPDATE 一列 + 契约加一方法），业务侧查询不提供（运维面死信管理属中心管理接口，见 §6.5）。

### 4.1 注册（批量信封）

- **入参**：`List<注册单>`；注册单 = `bizId + taskTypes 列表 + callbackGroup + actionTime`（一次挂多规则，单条 = 单元素列表，接口面不增）。
- **响应**：**成功/失败双清单**（失败项按 `(taskType, bizId)` 对应回 outbox 行继续重试，见 §7.1）。
- **幂等语义**：§3.4 双分支 + 冲突守卫。
- **中心落库形态**：虚拟线程逐行处理，**不做多值 INSERT**——① ShardingSphere 多值 INSERT 不按行路由（整语句单分片，混键未定义）；② 幂等语义逐行四分支（新插入 / 幂等成功 / 终态复活 / 冲突失败），`INSERT IGNORE` 会把冲突失败吞成幂等成功、废掉守卫；③ 虚拟线程逐行毫秒级，注册不需要跨行事务——双清单语义本为部分失败设计。与回调侧"协议层批量、落库层逐行"同形状。

### 4.2 取消

- 入参 `(taskType, bizId)`；软置 CANCELED（终态不可恢复，想再触发走重新注册复活）。
- **契约标注为可选优化**：接入方不取消也正确——回调到达时业务状态机已不在待支付态，按"未命中状态机 = 幂等成功"吞掉（§6.4）。三个支付接入方首版可不调。

## 5. 扫描与调度

### 5.1 job 形态与调度分片

- **单 job**：扫描 = 重试 = 死信重发统一入口（单列 `next_fire_time` 已把重试统一进扫描条件，拆双 job 纯重复建设；退避天然把重试行推远）。管理接口重发也走同一 job，入口唯一。
- **xxl-job 分片广播**：`shardTotal` = 存活实例数动态重算；实例认领 `槽位 g % shardTotal == shardIndex`，模分自动尽量平分（16 表 3 实例 = 6/5/5）；misfire = `FIRE_ONCE_NOW`；约束：槽位数 ≥ 预期实例数（16 ≥ 4），否则多出实例空转。
- **扫描周期 10s**（cron `/10 * * * * ?`）。

### 5.2 分片路由（ShardingSphere-JDBC 5.x）

> ⚠️ **2026-09-19 票 08 落地时修订（用户拍板）**：本节前两条（写入 complex + 扫描 hint、两算法同一编号空间）
> **在 ShardingSphere 5.5.3 上做不到**——① 一张表只能配一个 `tableStrategy`；② complex 表上 `HintManager`
> 完全不生效（探针把 hint 指到错误槽位，扫描照样把那行捞了回来；字节码里 `ShardingStandardRouteEngine`
> 只在 hint 策略下读 `HintManager`）。**改为全量 hint 强制路由**：表策略 `strategy: HINT`，
> 仓储层在每次 DAO 调用前把槽位塞进 `HintManager`（写入按键算、扫描用分片 job 给的槽位号），
> 复杂算法删除。「两算法同一编号空间」因此收敛成 `TimeoutTaskShardSlots` 单点函数。
> 漏塞 hint 的后果是响的：写语句被路由校验当场拒（不会静默写错表），扫描退化成 16 表广播。
> 细节与实测证据见[票 08 ## Answer](issues/08-scan-and-callback.md)。

- ~~写入/更新 SQL 写逻辑表 `timeout_task`，走 **complex 自定义分片算法** `hash(taskType + bizId) % 16`（拼接防歧义：分段哈希或带长度前缀，落地票细化）。~~
- ~~扫描等**无分片键 SQL 走 HintManager 强制路由**，槽位 → 表号同一公式；**complex 与 hint 两算法必须同一编号空间**（本方案集成核心约束）。~~
- **落地形态（票 08）**：SQL 一律写逻辑表 `timeout_task`，路由全走 hint；槽位公式仍 = `hash(taskType + bizId) % 16`（带长度前缀防歧义），写入与扫描共用 `TimeoutTaskShardSlots`。
- 死信聚合等跨表查询由 SS 广播归并白送。具体小版本与 MyBatis 兼容验证归落地票。

### 5.3 扫描 SQL 与并行模型

- 每表 `SELECT ... WHERE status IN ('WAITING','RETRYING') AND next_fire_time <= now ORDER BY next_fire_time LIMIT 200`（批 = 回调协议同一可配值，见 §6.3）；**无时间窗**（终态行离场、重试行被退避推远，"老行"只存在于回调在途的几秒）；单 tick 每表循环拉批到空，不加单 tick 批数上限。
- **虚拟线程**（JDK 21）：扫描 fan-out 一槽一虚拟线程（hint 与 mapper 调用同线程，HintManager ThreadLocal 天然成立）；回调独立 executor 提交即返回，同样虚拟线程。否掉 CompletableFuture（默认借 commonPool，阻塞 JDBC 会饿死它）。Hikari 池 ≥ 并行度（16，成为真并发上限）。

### 5.4 回调发送编排

捞批 → 批内按 `callback_group` 分组 → **组间虚拟线程并行发送，每组合成一次 Dubbo 调用**（跨组批不合并）→ 按成功/失败双清单就地 settle 该表。

### 5.5 防漏与防重

- **防漏**：状态机保证未终态行永远满足扫描条件 → 下轮重扫必补（at-least-once）；xxl-job misfire 补偿；执行器全挂期间积压，恢复后自动追平。
- **防重四层**：① 表级归属唯一（SS 路由 + hint 同公式，同一行同 tick 只被一个实例扫）；② tick 重叠 = 阻塞策略 `DISCARD_LATER`（防漏靠下轮）；③ job 调度层失败重试设 **0**（周期重扫即重试）；④ 回调在途重发 → 业务幂等吸收。**不加领取标记列/乐观锁**——那等于变相加回 PROCESSING。

### 5.6 精度与量级

- **精度口径**：尽力按 `action_time` 触发，最坏延迟 ≈ 一个周期 + 批处理总时长（循环拉批到空口径；正常量级 10~20s）。优于阿里公开 30s SLA。
- **量级论证**：10 万任务/年、在途峰值数千行、每 tick 每槽一条索引查询 ≈ 0.2 QPS（2 实例 × 6 tick/min），轻于现链路"30s 扫 outbox + 1min 扫订单表"。15 分钟支付超时的下单时刻天然分散，无整点脉冲。**升级线**：百万行 → 归档 job（§3.5 预留）；千万行/大量业务接入 → DB_COUNT 扩库 / Redis ZSet 型低延迟方案。

## 6. 回调协议

精度口径（尽力按 `action_time`，最坏 ≈ 一个周期 + 批处理总时长，10~20s）见 §5.6，数字随扫描周期定稿回填本节引用。

### 6.1 通道与路由

- **Dubbo RPC 直调**（tri 协议，Nacos 注册中心）；不引 MQ——重试/死信/人工兜底是回调协议的核心内容，MQ 方案将其外包给消费重试，中心不感知最终结果；直调下中心全链路状态可查可改。
- 中心建 **`timeout-center-api` 模块**，定义泛化接口 **`ITimeoutTaskCallback`**；业务方引 jar、实现接口、`@DubboService` 暴露；中心按任务行 `callbackGroup` **动态构建并缓存 ReferenceConfig** 路由——**新业务接入中心零代码变动**。

### 6.2 回调内容（引用 id，不带快照）

`TimeoutTaskDTO` 三字段：**`taskType` / `bizId` / `actionTime`**。回调时刻业务状态必须以业务库为准（15 分钟窗口内可能已支付/已取消），快照既误导又是死重。副产品：回调线上跑的全是中心 api 的 DTO，新业务接入不触发中心序列化 allowlist 变动（Dubbo 3.3 STRICT）。

### 6.3 批量与响应

- **批量回调**：一次扫描批次按 group 合成一次调用，业务方批量条件 UPDATE；单批 ≤ 200 条（可配，与扫描侧同一配置值）。
- **同步快速返回**（建议 < 1s），中心侧单次调用 timeout 2s（可配），超时/异常一律按失败进重试。
- 返回**成功/失败双清单**（每项 = `(taskType, bizId)` 对；DTO 字段命名归落地票）：中心成功项置 SUCCESS、失败项逐个重试。

### 6.4 幂等与顺序

- 中心 **at-least-once**（重发同 `(taskType, bizId)`）；业务方负责"不重"，契约写"**必须幂等**"，推荐手段 = 状态机条件 UPDATE；收到重复回调幂等吞掉并返回成功；**未命中状态机（已终态）= 幂等成功**，不算失败。
- **不保证顺序**，业务方以自身状态机为准。

### 6.5 重试与死信

- **16 次，30s 起指数翻倍、单次封顶 1h**（约 10h 总窗，数字可配）；失败项 `retry_count+1`、`next_fire_time` 后移，下轮扫描捞起重调（重试机制复用扫描，零额外设施）。
- 重试超限置 **FAILED 终态** + 中心管理接口（死信列表 / 单条与批量重发 / 手动取消）+ 告警日志点；接入监控告警平台另议（§8）。

### 6.6 成功处理

**UPDATE 置 SUCCESS 终态，不删除**；归档策略见 §3.5。

## 7. 接入契约

### 7.1 提交通道：业务侧 outbox（推送型本地消息表）

- 业务事务内由发送方写业务库新表 **`timeout_register_outbox`** 一行（随业务事务提交，原子性本地事务白送）；事务提交后即时发送 + 扫描兜底。**推送形态**：发送方推给中心，中心零耦合业务库（非"中心连业务库拉取"）。
- **即时发送**：`TransactionSynchronization.afterCommit` 挂钩（**必须挂对**——写在事务方法 save 之后一行是提交前，回滚即幽灵发送）→ 发送方虚拟线程执行注册调用（单元素列表调批量注册 API）。
- **扫描兜底**：发送方自带 `@XxlJob` handler（30s 周期，与现链路一致），扫描条件 `status = 'PENDING' AND created_at <= now - 5s`——**5s 宽限期**覆盖即时路径在途（单次调用超时 2s），无双路径竞态，替代"发送中"标记；残余重复仅在"即时失败转扫描接管"边界，中心幂等吸收。
- **发送器形态**：虚拟线程 + **同步** Dubbo 调用 + 返回后就地批量 UPDATE 标记（SENT / 保持 PENDING）。不复制现链路的标记队列 + 专用线程（那是 RocketMQ 回调模型特有）；不用 Dubbo 原生异步（future 异常不接即静默失败、RpcContext ThreadLocal 跨线程丢失，且后台发送场景无线程可释放——负资产）。
- **重试：无上限持续重试，永不 DEAD**：注册无时效性代价——中心恢复后补注册，action_time 已过期则 `next_fire_time <= now` 立即触发补关单，自愈零人工；积压量告警兜底。
- **中心侧批量注册落库**：虚拟线程逐行（§4.1）。

### 7.2 模块边界：只提供 api，不封装 client

**中心只提供 `timeout-center-api` 一个模块**（纯接口 + DTO：`ITimeoutTaskCallback` + `ITimeoutTaskRegister` + `TimeoutTaskDTO` / `BatchCallbackResult` 等，中心与业务共享的序列化对齐面）。**不提供 client jar**——outbox 写入、即时/兜底发送、重试等提交通道行为由各发送方按 §7.1 路径自行实现；中心与发送方之间的契约只有 api 模块 + outbox 表形态（§7.6）。

### 7.3 接入动作（四步）

1. 引 `timeout-center-api` jar
2. 实现 `ITimeoutTaskCallback` 并 `@DubboService` 暴露（group = 应用名）
3. 创建事务内写 `timeout_register_outbox`（纯本地 INSERT，随业务事务提交）+ 事务提交后即时发送，按 §7.1 路径自实现
4. xxl-job 调度中心为发送方自实现的扫描兜底 handler 配一次调度

约束：接入方与中心共用同一 Nacos 集群（§2）。

### 7.4 taskType 命名规范

**业务域前缀命名，契约写死**（沿 `Constants` topic 命名风格）；biz_id 无需全局唯一（type 承担业务域隔离，复合引用编码进 biz_id）。

### 7.5 降级行为

**照常下单，不拒绝下单**：中心不可用 → 注册行积压 PENDING、扫描持续重试，恢复自动追平（零丢失）；回调方向业务方零降级（中心自重试）。不做内存重试队列（outbox 替代之）、不做本地兜底表（outbox 本身就是）。

### 7.6 `timeout_register_outbox` DDL（业务库 better_school）

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

成功置 SENT 保留行（量级 ≈ 订单量约 20MB/年，将来归档）。**不复活 `delay_outbox_message`**——那是待下线旧链路。

## 8. 首个接入方：三处支付超时

| 接入方 | 注册点 | 取消点（可选优化，首版可不调） | 回调点 |
|---|---|---|---|
| 商品订单 | 创建订单事务内写 `timeout_register_outbox`（替代现 `sendPayTimeoutDelayMessage` 写 outbox） | 支付成功后取消任务 | 现 `handlePayTimeout` 关单动作：条件 UPDATE 取消 + 商品回在售 + 搜索同步消息 |
| 求购 | 求购创建事务内写 `timeout_register_outbox`（**现状无注册、仅周期扫描兜底——契约下补注册**） | 求购成交/关闭后取消任务 | 取消求购（现 `handlePayTimeout`） |
| 跑腿 | 跑腿单创建事务内写 `timeout_register_outbox`（替代现 outbox 写入） | 支付成功后取消任务 | 取消跑腿单（现 `handlePayTimeout`） |

三接入方同在 better-school 单体 → callbackGroup 同 = bs 应用名 → **单回调实现按 taskType 分发三路**（契约层口径：单 group 单实现）。taskType 三常量 `PRODUCT_ORDER_PAY_TIMEOUT` / `WANT_TO_BUY_PAY_TIMEOUT` / `ERRAND_PAY_TIMEOUT` 落业务方 `bs-types` 的 `Constants`（taskType 是业务方语义，契约只定命名规范 + 此处记三取值）。

### 8.1 后续接入方（errand-lock-review-hub effort，2026-10-15 回填）

两个非支付超时类型以后续接入方身份引入；**中心零代码改动**（通用模型 `task_type + biz_id + 回调` 本就支持，复合 bizId 已有 §3.2 先例）：

| 接入方 | taskType | 注册点 | 回调点 | 复合 bizId | 计时起点 |
|---|---|---|---|---|---|
| 跑腿锁单超时 | `ERRAND_LOCK_TIMEOUT` | 抢单事务内（`PUBLISHED→LOCKED` 同事务写 outbox） | 释放锁单：条件 UPDATE `LOCKED→PUBLISHED`（轮次守卫，未命中即幂等成功） | `{orderId}:{lockSeq}` | 锁单时刻（5min 确认窗口；**≠** 支付超时自创建起算） |
| 审核租约超时 | `REVIEW_LEASE_TIMEOUT` | 领单（claim）事务内写 outbox | 租约回收：条件 UPDATE `CLAIMED→PENDING`（轮次守卫） | `{taskId}:{claimSeq}` | 领单时刻（5min 租约窗口） |

两者都**不调取消**（§4.2 的可选优化）：复合 bizId 使每次注册都是**新键插入**（绕开「在途键重注册不改期」），回调侧轮次守卫使陈旧回调幂等落空——§3.4 的同键复位语义因此用不上。

## 9. 可观测性与运维面

已拍进 spec 的：

- 死信管理接口（死信列表 / 单条与批量重发 / 手动取消）+ 告警日志点（§6.5）；
- 业务侧 outbox 积压量告警（无上限重试的兜底，§7.1）。

> ⚠️ **2026-09-19 票 09 落地时补记形态**（本节原只写「有哪些动作」，没定载体；决策与理由见
> [票 09 ## Answer](issues/09-retry-and-dead-letter.md)）：死信管理接口落成 **HTTP**（挂在中心的
> 8083 上：`/api/admin/timeout-tasks/dead-letters`，重发与取消是它的 `/resend` 与 `/cancel` 子路径）
> ——它是**运维面**，不是给业务方
> 调的东西，所以接口文件 + DTO + 统一响应体落在 **trigger 模块**，**不进 `timeout-center-api`**
> （§7.2 的「中心只提供 api 一个模块」是对**业务契约**说的，该 jar 仍是纯业务契约，本票没动它）。
> 另一个落地取舍：**本版管理接口无鉴权**（中心没有用户/JWT 设施，为它新造一套超出本票）——
> 归入本节的开放项，与「告警接入监控平台」同批另议。

**开放项（另议，不阻塞落地）**：中心侧任务堆积监控、告警接入监控平台、管理接口鉴权、部署细节（端口/建表脚本/启动脚本落位）。

## 10. Out of scope

- 业务接入迁移与旧链路下线（`delay_outbox` 表、`ProductPayTimeoutConsumer`、`productTimeoutScan` 兜底 job 的移除与切换步骤）：另开 effort，将来一次性下线、不留双跑。
- 跑腿执行时限、组队截止等非支付超时类型的实际接入：通用性只在数据模型与协议层面。**本句界定的是本 spec 自身不落这些类型的具体接入设计，不排斥后续新增接入方**——锁单超时与审核租约超时即由 errand-lock-review-hub effort 以后续接入方身份引入（§8.1），中心零代码改动、只回填文档。
