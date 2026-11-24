# timeout 域表（timeout_task_00 .. timeout_task_15）

> 业务：内嵌在单体里的超时中心（原独立工程已随内嵌化下线，域与存储落地见 `.scratch/timeout-inline/issues/02-embedded-timeout-domain.md`）；归属模块：timeout（domain.timeout + infrastructure）
> DDL 唯一事实源：`sql/schema.sql`（16 张分表逐张写出）。逻辑表形状与运维口径另见根目录 [`sql.md`](../../../../../sql.md) §1、架构见 [`backend/docs/architecture/timeout.md`](../../../docs/architecture/timeout.md)

## timeout_task_00 .. 15（16 张同构分表）

业务/测试 SQL **只写逻辑表 `timeout_task`**，物理分表由 ShardingSphere 的 hint 强制路由（`bs-app/src/main/resources/sharding.yaml` + `bs-infrastructure/.../sharding/TimeoutTaskShardSlots`）。经 SS 连接点名物理表是 `TableNotFoundException`（物理表不在逻辑元数据里）；物理名只给分片算法与裸 MySQL 运维连接用。

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键（无业务含义）|
|task_type|varchar(64)|NOT NULL, `uk_type_biz` 首列|任务类型（业务对象上的一种超时动作，如 `PRODUCT_ORDER_PAY_TIMEOUT`）|
|biz_id|varchar(64)|NOT NULL|业务引用 id（复合引用编码进此列，如 `{orderId}:{lockSeq}`）；**分片 hash 输入**|
|action_time|datetime|NOT NULL|理论到期时刻（单轮内不可变；终态复活=重开一轮时刷新；回调时携带，供业务监控精度）|
|next_fire_time|datetime|NOT NULL|下次可触发时刻（注册时=action_time；失败重试后=落库时刻+退避，SQL `TIMESTAMPADD` 按数据库时钟换算）|
|status|varchar(16)|NOT NULL|WAITING/RETRYING/SUCCESS/FAILED/CANCELED（5 态，**无 PROCESSING**）|
|retry_count|int|NOT NULL, DEFAULT 0|已重试次数（16 次封顶进 FAILED）|
|created_at|datetime|NOT NULL|创建时间（**全库 create_time/update_time 口径的唯一例外**：按原样自原独立超时库迁入）|
|updated_at|datetime|NOT NULL|更新时间|

## 索引
- `uk_type_biz (task_type, biz_id)`：全局幂等键——自然键唯一 + 路由函数确定性（同 key 永远同表）；注册三分支（新插入 / 在途同键不改期 / 终态复活）的裁决权在它上面
- `idx_scan (status, next_fire_time)`：扫描唯一访问路径（`WHERE status IN ('WAITING','RETRYING') AND next_fire_time <= ?`）

## 路由与两维表达
- 槽位 `g = floorMod(hash(biz_id), 16)`，单点函数 `TimeoutTaskShardSlots.slotOf`（hash 输入**只有 biz_id**：同一业务对象的多种超时/多轮次落同一张表；uk 全局幂等不受影响）
- g 是「库 × 表」两维表达的全局展平编号：`db = g / 16`、`table = g % 16`，路由侧同时塞 `HintManager` 库维 + 表维两个 hint 值
- ⚠️ **`dbCount > 1` 不得启用**（保留的是表达，不是开关）：超时行落到另一个库 = 与业务写入不同事务，硬约束见架构文档 §0
- **不加 `user_id` 列**（票 02 显式结论，理由入档）

## 注意
- **谁在写**：业务仓储在**业务事务内直插**（超时行与业务行同生同死）：`TradeRepository.createOrderWithLock`；各分支幂等在 `TimeoutRepository.register`
- **谁在读**：扫描/结算（`TimeoutTaskScanCase` + `TimeoutTaskScanService`）与死信管理面（`TimeoutTaskAdminController`）；`selectDueBatch` 等按槽位捞批的语句由仓储统一塞 hint，**别绕开仓储直接调 `ITimeoutTaskDao`**——漏塞 hint 时写语句被路由校验拒掉、读语句退化成 16 表广播
- 死信没有独立表：回调失败到上限的行留在本表置 `FAILED`，管理面 `/api/admin/timeout-tasks/dead-letters` 直接读它（列表是唯一允许跨 16 表广播的查询）
- hint 是 TransmittableThreadLocal：纪律是「**hint 作用域先于子线程的创建/提交**」（票 01 界定），不是「必须同线程」
- 集成测试（`TimeoutShardingRouteIntegrationTest`）用裸 MySQL 连接逐张断言「槽位 = 实际落库物理表」；物理断言必须绕过 SS
