# 10: 消费 + 60s 兜底 + 删每秒 job + 续命

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §4.1（topic / 消费组 / 载荷）、§4.4（窗口尾巴与续命）、
§4.5（消费侧）、§4.6（兜底扫描）、§5.3（排干循环）、§12（xxl 摘除）

**What to build:** 把唤醒链路接上并换掉触发：`bs-inventory-merge` 消息被消费后按 key 合并；
丢消息 60 秒内被兜底扫描捞回；一次唤醒合不完（或只剩当前这一秒没封口）就**给自己再入队一条**；
`inventoryMerge` 每秒 job 消失。到本票结束，「每秒全表扫」这条路径彻底不存在。

**Blocked by:** 08, 09

**Status:** resolved

**预算：≈130k / 200k。** 三块：消费者、兜底 job + xxl 清理、续命。若续命的「窗口尾巴」用例难稳定复现，
允许用「直接构造一条 `create_time = NOW()` 的明细 + 调 mergeKey」来验证续命条件，不依赖真实时钟竞速。

## 验收

- [x] `types.inventory.InventoryMqConstants.INVENTORY_MERGE_CONSUMER_GROUP = "bs-marketing-inventory-merge-group"`
- [x] `InventoryMergeConsumer`：`@RocketMQMessageListener` 绑 topic + group；解析后调 `mergeKey`；
      **解析失败 / 未知 segment → log + 丢弃**（重试也不会成功）；**业务异常不吞**（抛给 broker 重投）
- [x] 消费线程模型沿用现有虚拟线程消费服务，不改注册器；本地**不加** synchronized / 队列锁
- [x] `InventoryMergeBackstopJob`：`@XxlJob("inventoryMergeBackstop")`，`0 * * * * ?`，调 `backstopOnce()`
- [x] 删 `InventoryMergeJob`（每秒）；确认 `mergeOnce` 已无残留（08 已删）。
      `sql/xxl-job-tasks.sql` 删 `inventoryMerge` 的 INSERT
      并补幂等清理（`DELETE` 或 `trigger_status = 0`），新增 `inventoryMergeBackstop`（`trigger_status = 1`）
- [x] 续命：`mergeKey` 合完后若该 key **还有 `state=0` 行** → `enqueueMergeMessage(segment, invId, bucketId)`
      （延迟级别 1、同 key）；**不变量破坏导致的失败不续命**；正常路径（合完即空）多发 0 条
- [x] 新用例：直接调消费者 Bean；兜底每段的 key 选取（升序、`LIMIT 200`）与直调 `mergeKey`；
      有未合行才续命、下一跳合完不再续命；重复投递空跑（`affected == cnt` 判重）
- [x] 全量集成测试绿

## 实现要点

（2026-10-18 落地。到本票结束，「每秒全表扫」路径彻底不存在：`InventoryMergeJob` 删除、
`mergeOnce` 全仓无残留；**07 仍未落地**，新用例暂放 `InventoryMergeEngineIntegrationTest`，
07/10 收口时再迁进唯一参数化类。）

**消费者（`trigger/inventory/listener/InventoryMergeConsumer`）**
- `@RocketMQMessageListener(topic = Constants.INVENTORY_MERGE_TOPIC, consumerGroup = InventoryMqConstants.INVENTORY_MERGE_CONSUMER_GROUP)`，
  `implements RocketMQListener<String>`；先 `objectMapper.readValue`，再 `InventorySegmentVO.fromCode`，
  最后 `mergeKey(segment, invId, bucketId)`。
- 丢弃口径：JSON 解析失败 / 缺 `invId` 或 `bucketId` / 未知 segment 都 `log.error` 后 `return`
  （重试也不会成功，不触发 broker 重投）；`mergeKey` 的异常不包 try——业务异常抛给 broker 重投。
- 线程模型零改动：注册器按注解全量接管，`CONCURRENTLY` 自动换成虚拟线程消费服务；同 key 的正确性
  靠 `affected == cnt`，不加本地锁。

**续命（`InventoryMergeServiceImpl.mergeKey` 收尾）**
- 循环里 `merged == 0` 置 `failed = true` 并 break；收尾只在 `!failed` 时做
  `existsUnmergedDetail(...)` → `enqueueMergeMessage(...)`。
  - 判据是「该 key 还有任何 `state = 0` 行」（新增 DAO 查询 `SELECT EXISTS(...)`），不是「本格没合完」：
    超 10 格剩下的、以及只剩当前这一秒没封口的行都走同一条路。
  - 正常路径判 false，一条都不多发；失败路径（竞态 / 桶守卫 / 台账缺失）不续命。
- `enqueueMergeMessage` 落仓储：单条 outbox 插入包一个 `TransactionTemplate`，载荷三字段、
  `delay_level = 1`——与首跳唤醒走同一条发送路径，兜底侧没有「延迟 0 直发」特例。

**兜底 job 与 xxl 清理**
- `InventoryMergeBackstopJob`：`@XxlJob("inventoryMergeBackstop")`，调 `backstopOnce()`；
  日志只在 `details > 0` 时打一行（quota key / virtual key / 行数），失败走 `XxlJobHelper.handleFail`。
- `xxl-job-tasks.sql`：删掉 `inventoryMerge` 的 INSERT，先
  `DELETE FROM xxl_job_info WHERE executor_handler = 'inventoryMerge' AND job_group = @group_id;`
  幂等清理旧注册（否则调度继续派给已删 handler），再以 `WHERE NOT EXISTS` 新增
  `inventoryMergeBackstop`，cron `0 * * * * ?`、`trigger_status = 1`。

**用例（`InventoryMergeEngineIntegrationTest` 追加 3 例 + 2 条既有断言）**
- 消费者直调：坏 JSON / 未知 segment 丢弃不抛；合法载荷合掉已封口格；同一载荷再投一次账本与桶副本零变化。
- 续命：已封口格 + 未来秒格 → 首跳合 1 格并落 1 条 outbox（载荷同 key、`delay_level = 1`）；
  把尾部格钉成已封口后再调 → 合完即空、outbox 仍 1 条。
- 兜底 key 选取：201 个 key（create_time 依次递减）→ `listUnmergedMergeKeys` 返回 200、升序、
  最年轻的被 LIMIT 截掉。
- 既有 10 格上限用例补「续命 1 条」；桶守卫失败用例补「不续命（outbox 0 条）」。
- 全量 **111 tests 绿**（108 + 3）。

## 注意

- **续命是合并侧的自我修复，不是兜底的替代**：兜底仍只负责「消息整条丢了」。
- **续命条件写「还有 `state=0` 行」，不要写「本格没合完」**：超 10 格时剩下的行也要能被下一跳接走。
- 兜底**逐 key 直调 `mergeKey`，不发消息**：并发已由 `affected == cnt` 扛住，发消息会多一次 outbox 写入
  和一个「延迟 0」的特例。
- `bs-raffle-shipping-group` 的历史命名例外不趁本次改；新组按 `bs-<service>-<topic>-group` 起。
