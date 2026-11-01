# 04 grilling：分片扫描设计

Type: grilling
Status: resolved
Blocked by: 01

## Question

多实例对超时中心 DB 的分片扫描怎么设计？（待调研票 01 结论回来后 grilling 拍板，落 spec 的"扫描与调度"节）

- xxl-job 分片广播的参数与路由选择；单 job 还是"扫描 job + 重试 job"多 job 拆分
- 扫描 SQL 设计：到期条件、批大小、时间窗、索引；分片键怎么选（按 id 取模？按 task_type 哈希？）
- 防漏（任务到点没被任何分片扫到怎么办）与防重（多分片/重试导致重复触发怎么办）
- 扫描压力评估：本作品的数据量级下扫表是否够用，何时才需要更重的方案

## Answer

2026-09-18 grilling 多轮拍板（事实基础 = 票 01 调研报告 §1–3 + 票 02/03 锚点；仓库已核：现有 job 全是普通单机 `@XxlJob`、无分片广播先例；xxl-job 3.0.0；JDK 21 + Spring Boot 3.3.5；现链路 `delayOutboxScan` 30s）。

### 结论

| # | 决策 |
|---|---|
| job 形态 | **单 job**（扫描 = 重试 = 死信重发统一入口）：票 03 单列 `next_fire_time` 已把重试统一进扫描条件，拆双 job 纯重复建设；退避（30s 起翻倍封顶 1h）天然把重试行推远，不会以扫描频率重发。管理接口重发 FAILED → RETRYING 也走同一 job，入口唯一 |
| 数据形态 | **同库 16 张分表** `timeout_task_00..15`（DB_COUNT = 1 起步），库×表两维路由预留：槽位 g 展平编号，`db = g / TABLES_PER_DB, table = g % TABLES_PER_DB`；将来扩库 = DB_COUNT 改 N + 连接配置，代码结构不动 |
| 分片路由 | **ShardingSphere-JDBC 5.x** 承载 库×表路由：写入/更新 SQL 写逻辑表 `timeout_task`，complex 自定义分片算法 `hash(taskType + bizId) % 16`（拼接防歧义：分段哈希或带长度前缀，落地票细化）；扫描等**无分片键 SQL 走 HintManager 强制路由**；**两算法同一编号空间**（本方案集成核心约束，spec 写死）；死信聚合等跨表查询由 SS 广播归并白送；具体小版本与 MyBatis 兼容验证归落地票 |
| 调度分片 | xxl-job 分片广播（shardTotal = 存活实例数，动态重算）：实例认领 `槽位 g % shardTotal == shardIndex`，模分自动"尽量平分"（16 表 3 实例 = 6/5/5）；misfire = `FIRE_ONCE_NOW`；约束：槽位数 ≥ 预期实例数（16 ≥ 4，spec 注明），否则多出实例空转 |
| 扫描 SQL | 每表 `SELECT ... WHERE status IN ('WAITING','RETRYING') AND next_fire_time <= now ORDER BY next_fire_time LIMIT 200`（批 = 票 02 同一可配值）；**无时间窗**（终态行离场、重试行被推远，"老行"只存在于回调在途的几秒）；单 tick 每表循环拉批到空（不满批即捞空），不加单 tick 批数上限参数 |
| 并行模型 | **虚拟线程**（JDK 21 已具备；否掉 CompletableFuture——CF 默认借 ForkJoinPool.commonPool，按 CPU 核定容，阻塞 JDBC 会饿死它，用对还得先手配池子，等于绕回手搓）：扫描 fan-out 一槽一虚拟线程（hint 与 mapper 调用同线程，HintManager ThreadLocal 天然成立）；回调独立 executor 提交即返回（票 02 锚点保留），同样虚拟线程——**发送编排**：捞批 → 批内按 `callback_group` 分组 → 组间虚拟线程并行发送，每组合成一次 Dubbo 调用（票 02 协议，跨组批不合并）→ 按双清单就地 settle 该表；Hikari 池 ≥ 并行度（16，成为真并发上限）；JDK 21 pinning（synchronized 钉载线）只影响吞吐不影响正确性，本量级无感，落地票冒烟注记 |
| 周期精度 | 扫描周期 **10s**（cron `/10 * * * * ?`）；精度口径 = "尽力按 actionTime 触发，最坏延迟 ≈ 一个周期 + 批处理总时长（循环拉批到空口径；正常量级 10~20s）"，优于阿里公开 30s SLA（调研报告 §1.1）；回填票 02/03 留白 |
| 防漏 | 状态机保证未终态行永远满足扫描条件 → 下轮重扫必补（at-least-once，调研报告 §1.1 阿里口径）；xxl-job misfire 补偿；执行器全挂期间积压，恢复后自动追平 |
| 防重 | **四层**：① 表级归属唯一（SS 路由 + hint 同公式，同一行同 tick 只被一个实例扫）；② tick 重叠 = 阻塞策略 `DISCARD_LATER`（上一次没跑完丢弃本次，防漏靠下轮）；③ job 调度层失败重试设 **0**（周期重扫即重试，调度层重试只放大重发）；④ 回调在途重发 → 业务幂等吸收（票 02/03 已定），**不加领取标记列/乐观锁**——那等于变相加回 PROCESSING，票 03 已论证不要 |
| 量级论证 | 10 万任务/年、在途峰值数千行、每 tick 每槽一条索引查询 ≈ **0.2 QPS**（2 实例 × 6 tick/min），轻于现链路"30s 扫 outbox + 1min 扫订单表"；15 分钟支付超时的下单时刻天然分散，无整点脉冲（滴滴案例不适用，调研报告 §1.4）；升级线：百万行 → 归档 job（票 03 预留）；千万行/大量业务接入 → DB_COUNT 扩库 / Redis ZSet 型低延迟方案（调研报告 §1.2） |

### 标识反转（用户驱动，覆盖票 03）

**删 `task_id` 列**。用户原话："业务方根本没必要知道 taskId……取消的时候直接传 type 和 bizid 即可，这俩就是幂等键""taskid 也没必要……直接用这俩字段做分库分表的索引"。展开定稿：

- 标识 = 自然键 `(task_type, biz_id)`：回调 DTO **三字段** taskType / bizId / actionTime；批量成功/失败双清单**成对返回**（每项 = type + bizId 对，改票 02 的 taskId 清单）；取消/死信管理按 `(taskType, bizId)` 定位；业务方永不持久化任何中心生成的 id。
- 分片路由直接吃幂等键 `hash(taskType + bizId)`，无需再造复合 id 携带分片段。
- **uk 语义修正**：`uk_type_biz` 从"全局唯一"改为"**单表内唯一 + 路由函数确定性 = 全局幂等**"（同 key 永远同表，表内 uk 照常生效）；某行全部读写同库同表，**无跨库事务问题**。票 03 其余语义不变：幂等双分支（非终态幂等成功原样应答 / 终态复活）、状态机 5 态、扫描列。（⚠️ 2026-09-18 票 05 补：双分支以 **callback_group 一致**为前提——同键不同 group = 冲突失败 + 告警，不做静默吸收）
- **多触发场景口径**：同对象多种超时 = 新增 task_type（类型是开放枚举，加类型不动表不动约束）；注册 API 支持 `bizId + taskTypes 列表` 一次挂多规则。真限制仅剩"同 type 同对象在途至多一条"——改在途任务触发时刻 = 票 03 已拍的将来续期 API（UPDATE 一列 + 契约加一方法），兼容演进，非锁死。
- "自增 id 零业务含义"原则保留：`id` 仍纯物理主键。

### 定稿 DDL v2（票 06 落 spec 用；覆盖票 03 v1）

× 16 张（`_00`..`_15`，同库；库×表两维路由，DB_COUNT=1 起步）：

```sql
CREATE TABLE timeout_task_NN (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键（无业务含义）',
    task_type       VARCHAR(64)  NOT NULL COMMENT '任务类型（业务对象上的一种超时动作，如 PAY_TIMEOUT）',
    callback_group  VARCHAR(64)  NOT NULL COMMENT '回调路由 Dubbo group（= 业务方应用名，票 02）',
    biz_id          VARCHAR(64)  NOT NULL COMMENT '业务引用 id（不带快照；复合引用编码进此列）',
    action_time     DATETIME     NOT NULL COMMENT '理论到期时刻（不可变；回调 DTO 携带，供业务监控精度）',
    next_fire_time  DATETIME     NOT NULL COMMENT '下次可触发时刻（注册时=action_time；失败重试后=now+退避）',
    status          VARCHAR(16)  NOT NULL COMMENT 'WAITING/RETRYING/SUCCESS/FAILED/CANCELED',
    retry_count     INT          NOT NULL DEFAULT 0 COMMENT '已重试次数（16 次封顶进 FAILED，票 02）',
    created_at      DATETIME     NOT NULL COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (task_type, biz_id),
    KEY idx_scan (status, next_fire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务分表';
```

### 决策依据

- **分库分表 vs 单表行分片**：用户驱动（"我希望是对不同库、不同表做分片处理"）。代价已对齐：N 冻结在路由函数、16 张 DDL、跨表聚合（SS 广播白送）；收益 = 真实生产形态——阿里"一张表只属于一台机器"的表级归属（§1.2）+ SchedulerX 库→表→页 map 展开（§1.3）同构。阿里原文坑"分表数量就是并发度"在"模分动态认领"（扩缩容重分）下不成立。
- **行分片 `MOD(id, shardTotal)` 被否**：用户明确要库表级分片而非行级；行分片版作为对照曾给出，未采纳。
- **task_id 删除的账**：task_id 的全部职责（回调幂等/取消/死信定位/路由寻址）在自然键下均有等价物；真损失仅"同 type 同对象多条在途"的演进项，票 03 已拍"不放宽约束"，非新增代价。"自增 id 零业务含义"与"业务键不超载充当中心身份"两条原则在自然键方案下统一为：中心不生成业务无关 id，身份即幂等键。
- **SS vs 手搓 Map\<Integer,DataSource\> + 表名拼接**：SS 是分库分表的业界标准件，承载核心叙事"知道生产上这活正经怎么做"；代价 = 依赖重（自带 SQL 解析器）+ 最高频的扫描 SQL 必须绕开其自动路由走 hint——接受。hint = SS 自带的路由旁路（`HintManager`，ThreadLocal；API 通道与 SQL 注释通道二选一，用 API）。
- **10s 周期**：本量级每 tick 一条索引查询，精度的钱近乎白捡；阿里传统扫库方案定时器同为 10s（§1.2），公开 SLA 30s——10~20s 精度是展示面加分点。

### 转票锚点（后续票必须承载，勿丢）

- **票 05（接入契约）**：注册 API 入参 = `bizId + taskTypes 列表 + callbackGroup + actionTime`，**无出参 taskId**（⚠️ 反转票 03 锚点；幂等双分支保留：非终态 = 幂等成功应答、终态复活不变——两分支都无 id 可还）；取消 API 入参 = `(taskType, bizId)`（业务方永不持久化中心 id）；接入约束同 Nacos 集群（票 02 已定）。（✅ 2026-09-18 已由票 05 拍定：注册 API 定稿**批量信封** `List<注册单>` + 双清单响应（单条 = 单元素列表）；提交通道 = 业务侧 outbox（client 发送器调批量注册 API，非业务方直调）；幂等双分支前提 = callback_group 一致，不同 group 冲突失败——细节在该票 ## Answer）
- **票 06（spec）**："扫描与调度"节从本 Answer 落稿（SS 配置形状 + hint/complex 同公式约束 + 广播认领 + 虚拟线程并行 + **发送编排**（拉批 → 按 callback_group 分组 → 组间并行发送 → 双清单 settle）+ 防漏防重四层 + 量级论证与升级线）；"数据模型"节 DDL 用本票 **v2** 并标注覆盖票 03 v1；精度口径回填"回调协议"节（10s）。

spec"扫描与调度"节由票 06 汇总本 Answer 落稿。
