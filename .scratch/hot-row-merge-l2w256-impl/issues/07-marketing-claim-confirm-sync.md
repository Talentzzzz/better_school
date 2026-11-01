# 07: marketing 兑换确认换同步合并

**Spec:** `.scratch/hot-row-merge-l2w256/spec.md` §4.5、§2.2；判据见 `acceptance.md` B5 / B6

**What to build:** 兑换确认（中奖实物出库）从「落 `type = 2` 明细 + 2s 延迟消息 → 消费后翻账」换成「进同一个 `QuotaMergeBuffer` → 一组一事务 → 返回即已转移」。返回时 `waiting -= c, issued += c` 已提交，**没有第二步**。

落库是与预扣同形状的另一组，但不是同一条语句：确认 `waiting -= c, issued += c`，预扣 `surplus → waiting/issued`。**不合成一条三列 `UPDATE`**——预扣的 `surplus` 不足是业务（切兜底），确认的 `waiting` 不足是不变量破坏（要人看），两个失败域不能混在一句里。因此 `QuotaMergeBuffer` 的两个 `submitAndWait` 是重载、落到两个各自 `@Transactional` 的组方法。

确认只存在于实物段 ⇒ 方法**不带 `segment`**。`invId <= 0`（不限量档，`draw_record.inv_id` 落 `NULL`）先短路为「无事可做」，不与「账不平」共用异常。

明细**逐行 `INSERT IGNORE`，不批插**：确认批天然小（一份实物出库），逐行插把「哪几行是新增」的歧义整条删掉，转移量精确等于新增量。

`waiting` 不足 = 不变量破坏，不是业务失败：整组回滚（连确认明细一起）+ `error` 日志 + 抛可重试 ⇒ MQ 重投 ⇒ 死信。**不复活 `INVENTORY_WAITING_SHORTAGE(4312)`，不做部分确认。**

**Blocked by:** 06（同一缓冲区、同一分片、同一条 `flushGroup` 家族；且桶层与旧合并路径已在本侧的 06 里清掉）

**Status:** resolved

- [x] 确认返回时转移已提交：`waiting -= c`、`issued += c`，`type = 2` 明细新增恰好 c 行（B6；`ship_physicalPrize_*` 三条改写）
- [x] 幂等键仍是 `type = 2` 明细的 `uk_biz_type`：撞键 = 已确认过 = 按成功回放，不重复转移
- [x] `waiting` 不足：整组回滚（含确认明细）、`error` 日志、抛可重试，**不留半组、不落状态**（B5；`claimWaitingShortage_rollsBackWholeGroup`）
- [x] 不复活 `INVENTORY_WAITING_SHORTAGE(4312)`；不做部分确认；不为这条路径新增状态列
- [x] `invId <= 0` 短路为「无事可做」，与「账不平」是两个出口
- [x] 确认落库方法不带 `segment`、不批插、逐行 `INSERT IGNORE`
- [x] 预扣与确认共用同一实例与分片，但两个组方法各自 `@Transactional`，没有一条同时碰 `surplus` 与 `waiting` 的语句
- [x] 降级：缓冲满 / 停机中 ⇒ 调用方线程同步 `confirmIssued(invId, List.of(item))`，复用同一落库方法；等超时与 `FAILED` 合一，抛可重试
- [x] `RaffleShippingCase` 与确认端口**一行不动**（本票只换它下游的实现）
- [x] 中奖事件（`bs-lottery-drawn`）与 outbox **保留**——确认仍由消费者驱动，本票不碰这条投递
- [x] 本票范围内测试同批处置（先改测试再改实现）：`RaffleShippingConsumerIntegrationTest` 实物档三条改写为 B6 形状，`ship_physicalPrize_eventBeatsMerge_stillConfirmsAndSettlesInOrder` 退休（顺序不再由时间格决定，由同分片单飞决定），两条幂等用例保留；`RaffleShippingOutboxIntegrationTest` 里「库存合并消息 `delay_level = 1`」那半条退休，中奖事件那半条保留

## 实施记录（2026-10-23）

- 落库：`InventoryRepository.flushClaimGroup`（实现 `IQuotaMergeFlushHandler` 同名方法）——
  一个事务内逐行 `INSERT IGNORE`（新 DAO 方法 `insertIgnoreClaimDetail`，不写 `bucket_id` / `state`）
  → 转移量 = 新增行的**份数和**（撞键回放的行不计）→ `transferWaitingToIssued`（复用旧合并的
  `waiting >= c` 守卫 UPDATE）。守卫不过 = 不变量破坏：error 日志 + 抛 `IllegalStateException`
  → 合并器整组补 `FAILED` → 服务层抛可重试（`SYSTEM_ERROR`，不复活 4312）。
- 服务层：`confirmIssued` 换 `quotaMergePort.submitAndWait(invId, ClaimItem)` 重载；
  `invId <= 0` 短路为无事可做；`BUFFER_FULL / CLOSED` 降级为调用方线程同步
  `flushClaimGroup(invId, List.of(item))`（组大小 1，复用同一落库方法）。
- 删除：旧确认路径三件套（`queryDeductBucketId` / `insertClaimDetail` / DAO 的
  `insertQuotaDetail` / `selectDeductBucketId` / `selectDetailCreateTime`）与随之死亡的
  `InventoryDetailAggregate` / `InventoryDetailVO`；`InventoryRepository` 不再注入
  `OutboxMessageStore`。`RaffleShippingCase` / `IQuotaConfirmPort` 一行未动。
- 测试（先改后实现，测试抓出一个真 bug：首版转移量按新增**行数**算而非份数和，
  被 `confirmIssued(…, 3)` 用例逮住后修正）：`PhysicalQuotaIntegrationTest` 确认四条改写
  （B5 改名为 `claimWaitingShortage_rollsBackWholeGroup`、B6 `confirmIssued_transfersImmediately`），
  `preDeductAndMerge` / `markDetailCellsSealed` 夹具拆除（对账用例留 `pinDeductDetailMerged` 至票 08）；
  `RaffleShippingConsumerIntegrationTest` 实物三条改写、`eventBeatsMerge` 退休；
  `RaffleShippingOutboxIntegrationTest` 的 `delayLevel` 合并消息半条退休；
  `InventoryRepositoryTest`（旧 create_time 回读）整体退休。
- 验证：定向 26 条全绿；marketing 全量 130 条全绿（`mvn test` BUILD SUCCESS）。
