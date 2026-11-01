# map：营销库存扣减重构（两套 quota + 时间格合并）

Label: wayfinder:map

## Destination

`.scratch/marketing-inventory-refactor/spec.md` 定稿：营销库存收成**两套 quota**——`physical_marketing_quota*` 保留、`virtual_inventory*` 改名 `virtual_inventory_quota*`、`physical_inventory*` 删除；扣减明细加 `type` / `biz_type` / `biz_id`，合并从「每秒全表扫两段」改为「按 `(segment, inv_id, bucket_id)` 的 1 秒延迟消息 + 时间格合并 + 分钟级兜底」。spec 拆成可直接执行的落地票，交下一个 effort。

## Notes

- 范围：`backend/marketing/` 库存域（`domain.inventory`）。**本 effort 只出决策与 spec，不写业务代码**（票 01 Q1）；落地另起 `.scratch/marketing-inventory-refactor-impl/`，沿用 `marketing` → `marketing-impl` 先例。
- **反转 [ADR-0005](../../docs/adr/0005-inventory-merge-aggregate-scan.md) 两处**：它否决过「MQ 延迟消息单 inv 防抖合并」（Considered Options 第 2 条），并把 Redisson `RDelayedQueue` 定为升级路径（第 4 条）。这里选 outbox + MQ 延迟等级：至少一次、可由 outbox 扫描重投。**spec 要写清「什么变了」**，否则下一个人会按 ADR 翻回来。
- **中间讨论过的判读修正（留档，别再走一遍）**：最终是**两套表并存**——只删 `physical_inventory*`；`physical_marketing_quota*` 保留；虚拟那套改名 `virtual_inventory_quota*`。不要再理解成「并成一张表」或「删掉 physical_marketing_quota」。
- **先说实话**：换触发的主要收益是「单事务有界、消费者可并行、丢消息后能自愈」，**不是「不再扫 pending 行」**——两种方案都要为每条明细付一次扫描。
- 与超时中心的关系：`.scratch/timeout-inline/map.md` 把 `inventoryMerge` 排除在超时中心之外（理由：它是每秒结算流水线）。本次把那个 1 秒 job **直接删除**，改为「秒级消息 + 分钟级兜底」——那条判据随之失效，要在 timeout-inline 的 map 上补一行说明；`CONTEXT.md`「超时任务」的 Avoid 里那句「延迟消息（不是 MQ 的延迟等级）」也要补边界说明。
- 读代码得到的事实（`backend/marketing/`）：
  - 合并 = 单实例 xxl 每秒全表扫两段的 `state=0` → 内存按 `(inv_id, bucket_id)` 分组 → 每组「桶守卫递减 → 台账结算 → 按携带 id 翻转」（[InventoryMergeServiceImpl.java](../../backend/marketing/marketing-domain/src/main/java/com/betterschool/marketing/domain/inventory/service/InventoryMergeServiceImpl.java)、[InventoryRepository.java](../../backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/inventory/adapter/repository/InventoryRepository.java)）。
  - quota 与 `physical_inventory` 是**两份同量账本**，`confirmIssued` 各扣一次 `surplus_qty`。
  - `physical_inventory_detail` 的 OUT 行在**生产代码里没有读路径**（只有集成测试读）。
  - 预扣在 `draw_record` 事务之外；同一 token 双击会双扣一份（既有 bug，biz 唯一键顺带修掉；虚拟段同病）。
  - `rocketmq-spring` 2.3.6 的父 POM 定的是 **client 5.3.2**、broker 镜像 **5.5.0**；延迟 API 是 `setDelayTimeLevel`，异步链路只能用级别 1（≈1.0–1.1s）。**重投不保留延迟**（首次重投 10s 后）、**同 key 可并行消费**——两条都由票 02 查实。
- 用到的 skill：口径票 `grilling` + `domain-modeling`；事实票 `research`。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## Answer 里 -->

- [票 01 · 口径基线（charting 阶段 grill 定）](issues/01-baseline-decisions.md): 两套 quota 并存（`physical_marketing_quota*` 保留、`virtual_inventory*` 改名 `virtual_inventory_quota*`、删 `physical_inventory*`）；明细加 `type` / `biz_type` / `biz_id` + 唯一键；1 秒延迟消息 + 60 秒兜底；合并 = 时间格 + 最老一格 + affected 判重。**后续所有票的口径都以它为准。**
- [票 02 · RocketMQ 延迟与并发语义](issues/02-rocketmq-delay-and-consumption.md): client **5.3.2** / broker **5.5.0**（不是 4.9）；异步加延迟只能 `MessageBuilder.withPayload` + 5 参 `asyncSend(..., delayLevel)`；级别 1 ≈1.0–1.1s；**重投不保留延迟**（首次 10s 后）；**同 key 可并行消费**。全文 [research/rocketmq-delay-and-concurrency.md](research/rocketmq-delay-and-concurrency.md)。
- [票 03 · 虚拟段怎么办](issues/03-virtual-segment-merge-trigger.md): 只删 `physical_inventory*`；`physical_marketing_quota*` 保留；`virtual_inventory*` → `virtual_inventory_quota*`；两段共用一套触发，`inventoryMerge` 1 秒 job 删除。
- [票 04 · 兜底扫描](issues/04-backstop-scan-scope.md): 60s；两张明细表各加 `(state, inv_id, bucket_id, create_time)`；兜底直接调 `mergeKey` 不发消息，每轮 200 key。**票 05 已解锁**（frontier 只剩它）。
- [票 05 · 写 spec 并拆落地票](issues/05-write-spec-and-split.md): **spec 定稿**（[spec.md](spec.md)，13 节）+ 落地票拆到 [`.scratch/marketing-inventory-refactor-impl/`](../marketing-inventory-refactor-impl/map.md)（5 张、线性：schema 收口 → 孪生合一 → 时间格 + 延迟消息 → 异步确认 → 文档 + 回归）。**写 spec 时补了三处口径**：① 兑换确认是异步的（落 `type=2` 明细 + 每条都发消息，转移由合并做，`4312` 退休）；② 窗口尾巴用「合并侧自我续命」补（否则低流量 key 真等 60s）；③ 回补 Redis 时删去重 key。另：`state` 的 2/3 死值收成两态 + `CHECK`；ADR 走「0005 superseded + 新建 0011」。**本 effort 到此完成**（只出 spec + 票，不写业务代码）。

## Not yet specified

<!-- 见「Fog of war」：还在雾里、尚不能成票的 in-scope 问题 -->

- ~~`state` 的 2 / 3 两个死值要不要一起收~~ **已定（票 05，写进 spec §2.2）**：收成 `0/1` 两态，
  列注释改掉 + `CHECK (state IN (0,1))`（MySQL 9 真校验）；全仓从来没有写 2/3 的代码。
  将来真要「回收」态时再改注释 + 重建库，不预留空档。

## Out of scope

<!-- 见「Out of scope」：被明确划到本 effort 终点之外的工作；已关闭，永不毕业 -->

- **入库 / 盘点流水表**：quota 行 + 已合并明细就是账；真要「每次库存变动一行」时另立一张表，不在本期。
- **分布式锁 / 按 key 串行化**：合并的正确性由「时间格 + affected 判重」保证，锁只减少等待，且会把 Redis 变成合并的可用性前置——而合并正是 Redis 出事之后的收敛路径。
- **多桶分摊 / 关桶 / Redis 重建看门狗**：沿用 `marketing` map 既有的排除。
