# 票 05 · 写 spec 并拆落地票

Type: task
Status: resolved
Blocked by: —

## Question

把票 01 / 02 / 03 / 04 的结论合成 `.scratch/marketing-inventory-refactor/spec.md`，并按 spec 拆出落地票（另起 `.scratch/marketing-inventory-refactor-impl/`，照 `marketing` → `marketing-impl` 先例）。

spec 至少覆盖：

- **表变更**：删 `physical_inventory` / `physical_inventory_detail`；`physical_marketing_quota*` 保留（明细加 `type` / `biz_type` / `biz_id` + `UNIQUE (type, biz_type, biz_id)`）；`virtual_inventory*` 改名 `virtual_inventory_quota*`（明细加 `biz_type` / `biz_id` + `UNIQUE (biz_type, biz_id)`）；两个 `(inv_id, bucket_id, state, create_time)` 索引。
- **DDL 重建口径**：`schema.sql` 是唯一 DDL 源，改表 = 改 `schema.sql` + 重建库（测试库必须重建，否则跑在过期 schema 上出假绿）。
- **合并**：时间格（`create_time`）+ `MIN` + `create_time < NOW()` + `affected == cnt` + 排干循环（上限 10 格）；结算按 `award_type` 分叉那一句。
- **延迟消息**：topic / 消费组命名（照 `bs-<service>-<topic>-group`）、outbox 的 delay 参数怎么加（共享 sender 不能把别的 topic 也拖慢）、去重的 Lua 改动、throttle 语义、兑换路径不走 SETNX。
- **兜底扫描**：频率、索引、直接调域服务而非发消息。
- **对账**：统一成 `waiting + issued == merged(type=1)`（虚拟行 `waiting` 恒 0）；Redis 恒等式只算 `type=1`；`state` 的 2/3 死值怎么收。
- **孪生代码合并**：两个 service / 仓储半 / warmup / audit 合成一条路径；`preDeductWithFallback` 的新形状；Redis key 前缀收成 `bs:inventory:bucket:`。
- **受影响的测试与文档**：`RaffleApiTestBase` 的 `insertPhysicalInventory` 夹具、`VirtualInventoryIntegrationTest` 与 `PhysicalQuotaIntegrationTest` 合并、`docs/db/` 库存页重写、`CONTEXT.md` 的「预扣」「奖品类型」、`docs/adr/0005` 的状态（superseded？补注？）、`.scratch/timeout-inline/map.md` 里 `inventoryMerge` 那条判据。
- **已知波及**：`OutboxMessageSender` 是 points / raffle 共用件；`inventoryMerge` 从 xxl-admin 摘掉（注册行停用/删除）；`docs/api/` 若有库存接口一并改。

## Answer

**spec 已定稿：[spec.md](../spec.md)**（13 节）。**落地票已拆到
[`.scratch/marketing-inventory-refactor-impl/`](../../marketing-inventory-refactor-impl/map.md)**，5 张、线性依赖：

| 票 | 标题 | 一句话 |
| --- | --- | --- |
| [01](../../marketing-inventory-refactor-impl/issues/01-schema-and-rebuild.md) | schema 收口 + 重建库 + 机械改名 | 删两张、改三张名、明细加列/唯一键/索引、outbox 加 `delay_level`；重建两个库 |
| [02](../../marketing-inventory-refactor-impl/issues/02-merge-twins-into-one-path.md) | 孪生合一（行为不变） | 两个 service / 仓储半 / warmup / audit 合成一条 `segment` 参数化路径 |
| [03](../../marketing-inventory-refactor-impl/issues/03-time-cell-merge-and-delay-message.md) | 时间格合并 + 1 秒延迟消息（核心） | Lua 去重、`bs-inventory-merge` 生产/消费、`mergeKey` 排干、删每秒 job、加 60s 兜底 |
| [04](../../marketing-inventory-refactor-impl/issues/04-async-claim-confirm.md) | 兑换确认异步化（`type=2`） | 确认 = 落 `type=2` 明细 + 每条发消息；转移进合并；4312 退休 |
| [05](../../marketing-inventory-refactor-impl/issues/05-docs-and-full-regression.md) | 文档收口 + 全量回归 | `docs/db` / `docs/api` / `sql.md` / `CONTEXT.md` / ADR / timeout-inline map + 重建库全量跑 |

### 写 spec 时补的三处口径（不是推翻上游票）

1. **兑换确认是异步的**。票 01 §2/§3/§4/§5 合起来读只有一个自洽解：`confirmIssued` 落一条 `type = 2` 明细 +
   **每条都发**合并消息（不走 SETNX），`waiting → issued` 由合并按时间格做。连带结论：
   `4312 INVENTORY_WAITING_SHORTAGE` 退休、`physical_inventory*` 的 OUT 行彻底消失、
   `RaffleShippingCase` 的「确认排在推进之前」不再承重（保留无害）。
2. **窗口尾巴要「自我续命」**。throttle 窗口 1000ms、消息到达 ≈1.0–1.1s：窗口内最后一笔若落在消息到达的
   同一秒，它自己没消息、这一格又没封口 ⇒ 只能等 60s 兜底（低流量 key 上「抽奖后立刻领奖」会真等一分钟）。
   补法：一次唤醒合完后若该 key 还有 `state=0` 行，再入队一条同 key 的消息；合完即空则不发。
3. **回补 Redis 时删掉去重 key**。赢家事务失败时窗口里被吞掉的那笔不能继续被吞；删 key 最多多一条无害消息。

另外把 `state` 的 2/3 死值收成两态（`CHECK (state IN (0,1))`），
并定了 ADR 处理：`0005` 标 `superseded by ADR-0011`（反转它否决的「MQ 延迟消息」与 RDQ 升级路径）、
新建 `ADR-0011`，由落地票 05 写。

### 没改的东西

`physical_marketing_quota` 保留原名与原列（不对称是有意的）；抽奖 / 积分 / 次数三域的业务语义不动
（只多 `recordId` 透传 + 确认异步）；前端零改动（库存无 HTTP 面）；`docs/`、`CONTEXT.md`、ADR 全部收在落地票 05。

