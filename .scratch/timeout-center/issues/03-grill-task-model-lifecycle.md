# 03 grilling：超时任务数据模型与生命周期

Type: grilling
Status: resolved
Blocked by: 01

## Question

超时中心的任务抽象怎么定？（待调研票 01 结论回来后 grilling 拍板，落 spec 的"数据模型"节）

- 任务表结构：task_type / payload / 触发时间 / 状态 / 幂等键等字段设计（通用抽象，任意业务可接）
- 状态机：如 WAITING → FIRED → DONE / RETRY / FAILED 的迁移与终态语义
- 任务 API：注册、取消、续期（触发时间改期）、查询各要不需要、语义如何
- 时间精度承诺（秒级/分钟级）与幂等键约束（同一业务对象同类型任务的唯一性）
- 与现有 `delay_outbox_message` 表结构的对照：吸收其已有经验（delay_until 等）

## Answer

2026-09-18 grilling 一轮七问拍板（事实基础 = 票 01 调研报告 + 票 02 回调协议锚点 + `backend/better-school/sql/schema.sql:156` 现表 DDL 已核）。库 = 独立库（map Q2 已定同容器独立库），表名 `timeout_task`。

### 结论

| # | 决策 |
|---|---|
| 状态机 | **5 态，无 PROCESSING**：`WAITING → RETRYING → SUCCESS / FAILED`，加 `CANCELED` 第三终态。成功路径：WAITING/RETRYING 回调成功（双清单 successTaskIds）→ SUCCESS。失败路径：WAITING/RETRYING 回调失败 → `retry_count+1` → RETRYING（`next_fire_time` = now + 退避）；≥16 次 → FAILED（票 02 定的次数）。管理接口重发 FAILED → 置回 RETRYING、`retry_count` 清零重开一轮。扫描 WHERE 永远排除三个终态（票 02 锚点）（⚠️ 2026-09-18 票 04 反转：本行"双清单 successTaskIds"是 taskId 时代的死文字——清单项定稿 `(taskType, bizId)` 对，见票 02 Answer 响应行注记） |
| 无 PROCESSING 的理由 | 回调是同步闭环（扫描捞批 → 调用 → 按双清单就地置状态，票 02"同步快速返回"），状态翻转与回调同程，无"处理中"观测窗口；进程崩在"回调成功、状态未落"之间则行仍待触发，下轮重扫重发、业务幂等吞掉 = 票 02 at-least-once 口径。加 PROCESSING 反而要定义租约超时回收，复杂度全花在防一次无害的重复触发上 |
| 扫描时刻列 | **单列统一**：`action_time` 不可变（理论到期时刻，回调 DTO 携带供业务监控精度，票 02 语义保留）+ `next_fire_time` 可变（注册时 = action_time；失败后 = now + 退避）。扫描走单条复合索引 `(status, next_fire_time)`。相对票 02 是**列名回填**（`next_retry_time` → `next_fire_time`，票 02 Answer 已加注记），其锚点扫描条件 `status IN (待处理, 重试中) AND next_retry_time <= now` 本就隐含单列形状；语义不变 |
| taskId | **独立 `VARCHAR(64)` task_id，中心生成（雪花，趋势递增索引友好；具体实现属落地票）**；`id` BIGINT 自增仅作物理主键。**用户拍板，否掉"主键即 taskId"的推荐：自增 id 不应有任何业务含义**。幂等键另有 `uk_type_biz`（见下），回调幂等用返回的 taskId（⚠️ 2026-09-18 票 04 反转：**task_id 删除**——用户改拍"业务方没必要知道 taskId，标识 = 自然键 (task_type, biz_id)"，回调 DTO 三字段、取消/死信按 type+bizId、分片路由直接吃幂等键；本行决策作废，定稿见票 04 ## Answer"标识反转"节） |
| 幂等键 | **`UNIQUE KEY uk_type_biz (task_type, biz_id)`**：同一业务对象上同一种超时动作至多一个任务；同一对象多种超时（如过期 + 提醒）用不同 task_type 表达，不放宽约束。重复注册双分支语义：已有行 WAITING/RETRYING → 幂等成功**原样返回已有 taskId** 不改期；已有行终态（SUCCESS/FAILED/CANCELED）→ **复活**（status 置回 WAITING、action_time 更新为新值、retry_count 清零，返回原 taskId）。复活是必须的：MySQL 无"仅非终态唯一"的部分索引，否则终态行永久锁死 `(type,biz)` 组合。与管理接口的手动重发（FAILED → RETRYING，不动 action_time）并行不悖（⚠️ 2026-09-18 票 04 回填：uk 语义改为**单表内唯一 + 路由函数确定性 = 全局幂等**（16 分表，同 key 永远同表）；"返回已有 taskId"改为**幂等成功应答，无 id 可还**（终态复活分支的"返回原 taskId"同此作废——复活语义本身保留，只是无 id 可还）；多触发场景 = 新增 task_type + 注册 API 支持类型列表，"不放宽"口径不变。⚠️ 2026-09-18 票 05 补：幂等成功 / 复活两分支以 **callback_group 一致**为前提——同键不同 group（两业务撞 type 或应用改名）= 冲突失败 + 告警，不做静默吸收；uk 不加 callback_group（三列唯一会拆坏幂等键）） |
| API 面 | **注册 + 取消两个**。注册：入参 `taskType + bizId + callbackGroup + actionTime`（callbackGroup 进数据模型是票 02 直接推论），出参 taskId，幂等语义如上。取消：软置 CANCELED（终态不可恢复，想再触发走重新注册复活）；契约标注**可选优化**——接入方不取消也正确，回调到达时业务状态机已不在待支付态，按票 02"未命中状态机 = 幂等成功"吞掉；三个支付接入方首版可不调。**续期/改期不提供**（首版无场景；将来 = UPDATE 一列 + 契约加一方法）。**业务侧查询不提供**；运维面（死信列表/重发/取消）已随票 02，属中心管理接口不进业务契约（⚠️ 2026-09-18 票 04 反转：注册出参 taskId 删除，幂等双分支 = 成功应答；注册入参改为 `bizId + taskTypes 列表`（+callbackGroup、actionTime）一次挂多规则；取消入参定稿 `(taskType, bizId)`——业务方永不持久化中心 id） |
| 精度 | 口径沿用票 02："尽力按 actionTime 触发，精度 = 扫描周期"，具体数字随票 04 / 部署配置；`DATETIME` 秒级存储精度 |
| 归档 | **首版不自动归档**，终态行永久留在 `timeout_task`。spec 写量级论证：按 10 万任务/年、行宽约 200B 计约 20MB/年。将来量大了加"按 `updated_at` 搬历史表"的归档 job，数据模型与契约均不受影响。阿里口径支持终态保留是一等公民："任务被处理完成后，任务的状态被更新成已处理"（调研报告 §1.2） |
| 对照 delay_outbox | **吸收**：幂等唯一键（`msg_id` uk → `uk_type_biz` + `uk_task_id`）、`(status, 时间)` 复合扫描索引、`retry_count`、`created_at/updated_at`。**差异**：`topic` → `callback_group`（回调路由载体，票 02）；`payload` TEXT **删除**——引用 id 拍板后回调内容就是 DTO 四字段（taskId/taskType/bizId/actionTime），阿里模型的 `attribute` 列同理不收，复合引用编码进 `biz_id`（如 `userId:couponId`），将来真要加列是兼容变更；`delay_until` → `action_time`(不可变) + `next_fire_time`(可变)；`status` 用 **VARCHAR(16)**（跨服务排障与 SQL 可读，与 chat 域 `DIRECT/GROUP` 风格一致），不用老表 TINYINT（⚠️ 2026-09-18 票 04 回填：`uk_task_id` 不收——task_id 已删；回调 DTO 定稿三字段） |
| 分片键 | **不建列，留票 04**：若 04 选 `id % shardTotal` 则自增物理主键天然可用、零列起步；若 04 选桶分片再回填加列（SchedulerX 官方 Demo 的 bucket 路数，调研报告 §1.3）（✅ 2026-09-18 已由票 04 拍定：分库分表方案，路由 = `hash(taskType+bizId) % N` 由 ShardingSphere 分片算法计算，**仍零分片键列**——路由吃的是已有列的值，不落桶号） |

### 定稿 DDL（票 06 落 spec 用）（⚠️ 2026-09-18 票 04 反转：v1 作废——单表 → 16 分表、task_id 列与 uk_task_id 删除，落 spec 用票 04 Answer 的 **DDL v2**）

> 以下 v1 原文保留作历史记录。

```sql
CREATE TABLE timeout_task (
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '物理主键（无业务含义）',
    task_id         VARCHAR(64)  NOT NULL COMMENT '全局唯一任务ID（中心生成，雪花；回调原样返回，业务幂等键）',
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
    UNIQUE KEY uk_task_id (task_id),
    UNIQUE KEY uk_type_biz (task_type, biz_id),
    KEY idx_scan (status, next_fire_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '超时任务表';
```

### 决策依据

- 无 PROCESSING 而非加中间态：重复触发是 at-least-once 的正常代价（调研报告 §1.1 阿里原文"下一个批次还是可以捞出来处理"、SchedulerX 官方"不保证子任务绝对执行一次"（§1.3）），业务幂等吸收；同步回调下不存在需要租约保护的长窗口。
- 单列 `next_fire_time`：OR 双列扫描条件无法共用一条复合索引，且票 02 锚点的扫描形状本就只有单列才成立；`action_time` 不可变列保住回调 DTO 的精度监控语义。
- 终态复活语义：uk_type_biz 全局唯一 + MySQL 无部分唯一索引的必然推论；没有它，业务重开同单号的任务在首次终态后就注册不进来。
- 续期/查询不做：三个接入方均无场景，YAGNI；将来加是加法不是架构变更。
- status 用 VARCHAR：本表是跨服务契约载体（死信管理、业务对账都要直接读它），可读性优先于每行十几字节的节省。

用户驱动点：taskId 选独立 VARCHAR 列（否掉推荐的"主键即 taskId"），理由"自增 id 不应该有任何业务含义"；其余六问均按推荐。（⚠️ 2026-09-18 票 04 反转：taskId 决策被用户本人推翻——原话"业务方根本没必要知道 taskId"，其余理由为取消/路由直接用 type+bizId 即可，标识回归自然键；本票其余结论不受影响）

### 转票锚点（后续票必须承载，勿丢）

- **票 04（分片扫描）**：扫描 WHERE = `status IN ('WAITING','RETRYING') AND next_fire_time <= now`，复合索引 `idx_scan (status, next_fire_time)`；分片方式拍板时注意表上**未建分片键列**（id 取模零列可用，桶分片需回填加列）；批量 ≤200 与回调协议同一可配值（票 02）；扫描周期数字回填票 02"回调协议"节的精度口径。（✅ 2026-09-18 已由票 04 拍定：扫描 WHERE/索引沿用；分片 = 库×表级（同库 16 分表 + ShardingSphere，路由 hash(type+bizId)，仍零分片键列）；周期 10s；细节在该票 ## Answer）
- **票 05（接入契约）**：注册 API 字段 = `taskType + bizId + callbackGroup + actionTime`，出参 taskId；幂等双分支语义（WAITING/RETRYING 原样返回、终态复活）写进契约；取消为可选优化、接入方可不调；续期/查询不在契约。（⚠️ 2026-09-18 票 04 反转：出参 taskId 删除；注册入参 = `bizId + taskTypes 列表`；取消入参 = `(taskType, bizId)`——以票 04 ## Answer 转票锚点为准。✅ 2026-09-18 已由票 05 拍定：注册 API 定稿**批量信封**（`List<注册单>` + 成功/失败双清单响应，单条 = 单元素列表）；幂等双分支以 **callback_group 一致**为前提——同键不同 group = 冲突失败 + 告警（守卫））
- **票 06（spec）**："数据模型"节从本 Answer 定稿 DDL + 状态机落稿；"任务 API"节从本 Answer API 面落稿；归档量级论证一并写入。（⚠️ 2026-09-18 票 04 回填：DDL 落稿用票 04 的 **v2**（16 分表、无 task_id）；API 面以票 04 反转后口径为准）

spec"数据模型"节由票 06 汇总本 Answer 落稿。
