# 08: 删兜底与时间格合并，读数瘦成台账三列

**Spec:** `.scratch/hot-row-merge-l2w256/spec.md` §5.2、§5.4、§4.6；判据见 `acceptance.md` B2 / B3 与 §4

**What to build:** 库存侧「消息驱动的合并」这一整套机制在本票被连根删除，同时把对账读数从「7 个字段的推导视图」收回「台账三列的直读」——因为台账现在就是唯一真相，`pendingDeductQty` / `pendingClaimQty` / `mergedDeductQty` / `ledgerConsistent` / `redisConsistent` 这些字段所描述的状态已经不存在。

删除面：合并服务（`backstopOnce` / `mergeKey` / 时间格两两合）、60s 兜底 job 与桶预热 job、`InventoryMergeConsumer` 与 `bs-inventory-merge` topic / 消费组 / 常量、Repository 与两个 DAO 里的合并与 `sum*` / `listBuckets` / 翻格 / 聚合方法、审计里的 `checkRedisConsistent` / `ledgerConsistent` / `sumPending*`。xxl handler 从 6 个收到 4 个（`outboxScan` / `pointsExpireScan` / `drawCountBucketExpireScan` / `raffleCacheWarmup`）。

注册行的**时序**归切换包（09）：窗口内先把两个 handler 置 `trigger_status = 0`，D9 才 `DELETE`；本票只保证代码侧不再引用它们。

守恒等式从「运行时读数」降级为「集成测试断言」（B2 / B3）——本工程没有 metrics 端点，运行时读数本来也没有自动信号。

**Blocked by:** 06, 07

**Status:** resolved

- [x] 合并机制删除干净：`IInventoryMergeService` 及其实现、`MergeBackstopResultVO` / `MergeKeyResultVO` / `InventoryMergeKeyVO` / `InventoryMergeKeyPO` / `InventoryCellAggregatePO`、两个 job、合并消费者与 topic / 消费组常量
  （`InventoryBucketWarmupJob` 已随票 06 删；本票删 `InventoryMergeBackstopJob` / `InventoryMergeConsumer` / `InventoryMergeMessage` / `InventoryMqConstants` / topic 与 4 个合并常量，全仓 0 残留引用）
- [x] Repository / DAO 的合并与读数方法全删（`mergeCell` / `queryOldestUnmergedCellTime` / `listUnmergedMergeKeys` / `selectMergeKeys` / `aggregateUnmergedCell` / `flipUnmergedCell` / 4 个 `sum*` / 虚拟段同构方法；含 `settle*` 与桶递减两个 mapper XML 对应语句）
- [x] `InventoryAuditVO` 收到 4 字段（`invId / surplusQty / ledgerWaitingQty / ledgerIssuedQty`）；审计服务只留台账直读，`checkRedisConsistent` / `ledgerConsistent` / `sumPending*` 删除
- [x] xxl handler 清单 6 → 4（代码侧只剩 `outboxScan` / `pointsExpireScan` / `drawCountBucketExpireScan` / `raffleCacheWarmup`；注册行停用与 DELETE 时序归票 09，`sql/xxl-job-tasks.sql` 种子未动）
- [x] B2 / B3 的守恒与明细等式作为集成测试断言存在（不留在运行时读数里），含「探一次再切」的部分成功用例（`InventoryQuotaConservationIntegrationTest`：`ledgerSumEqualsInitialSnapshot` / `detailSumEqualsWaitingPlusIssued_partialSuccess` 等 6 条，票 06/07 已落地、本票保持绿）
- [x] 白名单核对：`draw_count_bucket` / `points_bucket` 属用户资产，不在删除范围；本票不碰任何 `DROP`（`sql/` 零改动）
- [x] 本票范围内测试同批处置：`InventoryMergeEngineIntegrationTest`（12）与 `InventoryMergeMessageTest`（2，消息类删除的必然后件）整份退休；`InventoryMergeDeliveryLatencyTest` / `InventoryMergeTriggerIntegrationTest` / `SwitchableInventoryBucketPort` 已随票 06/07 退休；`InventoryFaultIntegrationTest` 处置已随票 06/07 完成（现为 `InventoryFlushFailureIntegrationTest`）；`InventoryMergeTestSupport` 改写为「等待排空」观察点并接入 B2 用例；`PhysicalQuotaIntegrationTest` 审计用例改 4 字段直读；outbox 断言改口径（不再引用已删 topic）。全量 116 条测试绿
- [ ] 模块构建绿；`CONTEXT.md` 里指向这些机制之外的词条不在本票改（归 09）