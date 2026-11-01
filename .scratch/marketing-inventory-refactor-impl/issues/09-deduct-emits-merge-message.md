# 09: 预扣发消息（Lua throttle + 同事务）

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §4.2（Lua 扣减 + 决定要不要发）、§4.3（延迟级别）、
§7.5（撞键与回补）、§10 ①②

**What to build:** 预扣在 Lua 里**原子地**完成「扣减 + 决定要不要叫醒合并」，扣成功且拿到 `notify` 时，
才在**同一个事务**里插明细 + 插 outbox（`delay_level = 1`）；`notify = 0` 只插明细。
去重 key 是固定窗口 throttle（从第一笔算 1000ms），不是 debounce。
本票**还没有消费者**——旧每秒 job 继续合并，消息只是被可靠地写进 outbox；接消费在 10。

**Blocked by:** 04, 05

**Status:** resolved

**预算：≈150k / 200k。** 三块：Lua + 端口返回 VO、明细/outbox 同事务、撞键回补要连去重 key 一起删。
若同事务探针用例难写，止损线是「outbox 行与明细同生共死已由代码结构保证（同一个 `TransactionTemplate`），
探针用例留给 10 补」——但 `insertDeductDetail` 里**不许**出现两次独立事务。

## 验收

- [x] `inventory_bucket_deduct.lua` 改成两键两参、返回 `{code, notify}`（spec §4.2 原文）；
      Redisson 侧用 `ReturnType.MULTI` 取 `List<Long>`；Lua **无返回**才抛 `BucketUnavailableException`
- [x] `IInventoryBucketPort.tryDeduct(bucketKey, mergeDedupeKey, quantity) : DeductAttemptVO(deducted, notifyMerge)`；
      `-1`（未预热）/ `-2`（余量不足）都返回 `deducted = false`
- [x] `release(bucketKey, mergeDedupeKey, quantity)` = `INCRBY` 回补 + `DEL` 去重 key；
      测试桩 `SwitchableInventoryBucketPort` 同步签名与行为
- [x] `Constants`：`MERGE_DEDUPE_KEY_PREFIX = "bs:inventory:merge:"` + `mergeDedupeKey(segment, invId, bucketId)`；
      `INVENTORY_MERGE_TOPIC = "bs-inventory-merge"`；`types.inventory.InventoryMergeMessage {segment, invId, bucketId}`
- [x] 预扣：`notifyMerge = true` 才在**同一个事务**里插明细 + 插 outbox（载荷三字段、`delay_level = 1`）；
      `false` 只插明细。明细没落上 → 回补余额 + **删去重 key** + 抛
- [x] 撞业务唯一键 → `release(bucketKey, mergeDedupeKey, qty)` → 返回 `true`（成功回放）；
      其它异常同样 release 后抛
- [x] 新用例：扣成功才发消息；同一窗口内第二笔不发（throttle）；明细 + outbox 同事务
      （探针回滚 → 两条都没有）；outbox 行 `delay_level = 1`
- [x] 全量集成测试绿

## 实现要点

（2026-10-18 落地。本票只把消息可靠地写进 outbox；**消费者与续命在 10**，旧每秒 job 本票未动。
**07 仍未落地**：新用例暂放 `InventoryMergeTriggerIntegrationTest`，07/10 收口时再迁进唯一参数化类。）

**Lua（`lua/inventory_bucket_deduct.lua`）**
- 严格按 spec §4.2：`GET` 桶 → 余量不足 `{-2, 0}` → `DECRBY` → `SET KEYS[2] '1' PX ARGV[2] NX`。
  `notify` 取 `SET` 的返回值：NX 成功是 status reply（Lua 里 truthy），已存在是 `false`，所以
  `notify and 1 or 0` 就是 0/1。
- 去重 key **只在扣成功后写**；扣失败（未预热 / 不足）没有明细，发了也是空跑。
- 窗口值是 `Constants.MERGE_DEDUPE_WINDOW_MILLIS = 1000`（固定窗口，从第一笔算；不是 debounce）。
  这个常量落在 types.inventory，Lua 的 `ARGV[2]` 由端口传入，脚本里不写死。
- 两键同脚本今天无同槽要求（单节点）；上 cluster 需同 hash tag `{invId}` 的提示写进了脚本头与端口类注释，
  本期不实现。

**`ReturnType.MULTI` 的返回形状**
- `redissonClient.getScript(StringCodec.INSTANCE).eval(..., ReturnType.MULTI, keys, args)` 返回
  `List<Long>`：`{code, notify}` 两个 RESP 整数由解码器直接给 `Long`，与 `StringCodec` 只影响字符串无关。
- 无返回（`null`）或长度不足两个元素 → `BucketUnavailableException`（故障）。`-1`/`-2` 是业务事实，
  统一折成 `DeductAttemptVO.REJECTED`（`deducted = false, notifyMerge = false`）。
- 端口方法名与签名按 spec §7.1：`tryDeduct(bucketKey, mergeDedupeKey, quantity)`；release 补第二个 key。

**回补 `release` 的两步**
- `redissonClient.getAtomicLong(bucketKey).addAndGet(quantity)` = `INCRBY`；
- 删 key 用 `getBucket(mergeDedupeKey, StringCodec.INSTANCE).delete()`，**显式与 Lua 的编码对齐**，
  不依赖 RedissonClient 默认 codec 的取键口径（脚本里 KEYS[2] 由 `StringCodec` 编码）。
- 两步非跨命令原子（spec 只要求 INCRBY + DEL）：先回补再删；即使 DEL 失败，key 也只有 1s TTL。

**同事务边界（`InventoryRepository.insertDeductDetail`）**
- 签名是 `insertDeductDetail(segment, InventoryDeductAggregate)`：聚合对象装
  「明细 + 可选合并消息」（0 或 1 条），按 `backend/CLAUDE.md` §2 的「多个 entity 一次事务存储封装为聚合」
  （先例：抽奖域 `DrawRecordAggregate`）；`mergeMessage == null` = 本笔在 throttle 窗口内，只落明细。
- 方法内一个 `TransactionTemplate.executeWithoutResult`（REQUIRED）包住「明细 INSERT + 可选 outbox INSERT」。
- outbox 走共享 `OutboxMessageStore.save(topic, message, delayLevel=1)`：级别随行持久化，
  `afterCommit` 才异步发；回滚时 `afterCommit` 不触发，明细与消息一起消失。
- 域服务只调一次仓储方法、不注入模板；`notifyMerge` 从 Lua 结果传到仓储，域服务不自己开事务。

**Lua 返回码只认契约值**
- `-1` / `-2` 是业务事实；`0` 才成功；**其它值抛 `BucketUnavailableException`**——未知返回码意味着
  脚本与调用方对不上（旧脚本 / 脏部署），按成功处理会落一条没扣过 Redis 的明细，直接造成漂移。

**预扣与回补路径（`InventoryQuotaServiceImpl.preDeduct`）**
- 逐桶：先算 `bucketKey` + `mergeDedupeKey` → `tryDeduct` → 扣成才构造 `InventoryMergeMessage`
  （`segment.getCode()` / `invId` / `bucketId`）→ `insertDeductDetail(segment, aggregate)`。
- `DuplicateKeyException` 与其它 `RuntimeException` 都走 `release(bucketKey, mergeDedupeKey, quantity)`；
  撞键仍按成功回放返回 true。撞键路径的明细与 outbox 已随事务回滚，不会留下第二份。
- **删去重 key 的语义**：撞键 / 事务失败时删掉，是让窗口里还活着的后续扣减重新发消息；
  合并时绝不删 key（spec §4.2），否则会吞掉窗口里的有效扣减。

**测试**
- 新增 `InventoryMergeTriggerIntegrationTest` 6 例：虚拟段首笔发消息 + TTL ≈ 1000ms + 窗口内第二笔
  不发/不刷新 TTL（证明固定窗口、非 debounce）、实物段 payload `segment = quota`、
  outbox 写入失败时明细一起回滚（`@SpyBean OutboxMessageStore` 直接抛在事务中间，比外层探针更硬——
  能区分「同一个事务」与「没有事务 / 两次独立事务」）、撞键回补删 key 后下一笔重新发消息、
  其它异常（超长 `biz_type` 触发的数据完整性异常）同样回补余额 + 删 key、
  `-1/-2` 都不设去重 key 且服务层扣不成时 outbox 为 0。载荷断言三字段 + `delay_level = 1`。
- throttle 用例不 sleep：首笔后先断言 TTL ∈ (0, 1000]，再把 TTL 拉到 30s 后发第二笔；
  第二笔后 TTL 仍 > 20s，证明 `SET NX` 没有刷新窗口（debounce 会把它打回 ~1000ms）。
- `@SpyBean` 会另起一个 Spring 上下文，按票 08 先例 `@MockBean XxlJobSpringExecutor` 避开进程级
  handler 注册冲突。
- `RaffleShippingOutboxIntegrationTest` 的合并 topic 字面量换成 `Constants.INVENTORY_MERGE_TOPIC`。
- 全量：`mvn -o -f backend/marketing/pom.xml test` → **108 tests, 0 failures**（102 + 6）。
  schema 未动，测试库无需重建。

## 注意

- **只扣成功才设去重 key**：扣失败的明细不存在，发了也是空跑。
- **去重 key 不在合并时删除**，只靠 TTL 过期；只有「回补」这条路径删它（否则会吞掉窗口里还活着的扣减）。
- 两键同脚本今天无同槽要求（Redis 单节点）；若上 cluster 需同 hash tag `{invId}`——写进代码注释，不实现。
- 测试栈没有 broker：可观察形态是 **outbox 行 + 载荷 + `delay_level`**，不要断言「消费者收到」。
