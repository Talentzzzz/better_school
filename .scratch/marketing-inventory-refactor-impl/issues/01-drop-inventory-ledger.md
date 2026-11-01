# 01: 删掉进出库台账（physical_inventory*）

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §2.1（表收口）、§2.5（为什么能删）、§3（重建库口径）

**What to build:** 让「出入库台账」这对表从库里和代码里同时消失：`physical_inventory` /
`physical_inventory_detail` 不再存在，兑换确认退成**一次纯转移**（`waiting_qty → issued_qty`）——
不再扣 `surplus_qty`、不再写 OUT 行、也不再校验「实物库存台账是否存在」。
单品追溯口径改成 `draw_record` + `physical_marketing_quota.issued_qty`（只记总数）。
本票不动触发、不动合并、不动虚拟段；`4312` 保留到 11 才退休。

**Blocked by:** None (can start immediately)

**Status:** resolved

**预算：≈100k / 200k。** 改动集中在 DDL、四个 PO/Entity、一个 DAO/mapper、确认路径和测试夹具。
若删代码时被「确认语义该怎么改」绊住就停手：语义在 11，本票只要求「少一套账、少一条写路径、测试绿」。

## 验收

- [x] `backend/marketing/sql/schema.sql` 删两张表；根目录 `sql.md` §2 同步（比对脚本零差异）
- [x] 删 `PhysicalInventoryPO` / `PhysicalInventoryDetailPO` / 两个 Entity，以及
      `IPhysicalQuotaDao` + mapper XML 里对应的语句
- [x] `IInventoryRepository` 删 `queryPhysicalInventory` / `issueInventory` / `insertInventoryDetail`；
      `confirmIssued` 只剩「`waiting_qty → issued_qty` 守卫转移」，0 行仍抛 `INVENTORY_WAITING_SHORTAGE`
- [x] 域服务的确认路径删掉「库存台账不存在」校验与出库编排；`log` 措辞改成「转移」而不是「出库」
- [x] 测试：`RaffleApiTestBase` 删 `insertPhysicalInventory`、`prepareInventory` 不再铺库存台账行、
      `TRUNCATE` 列表去掉两表；`PhysicalQuotaIntegrationTest` 的确认用例改断言配额两列
      （不再断言库存台账递减与 OUT 行）
- [x] 按 spec §3 重建 `bs_marketing` 与 `bs_marketing_test`，`backend/marketing/` 全量集成测试绿

## 实现要点

（2026-10-17 落地。纯删减：未动触发、未动合并、未动虚拟段、未动明细 `type` 列；`4312` 原样保留到 11。）

**DDL：两张表消失，本节 22 → 20 张**
- `backend/marketing/sql/schema.sql`：删 `physical_inventory` / `physical_inventory_detail` 两个
  `CREATE TABLE` 块（含 `chk_physical_inventory` 约束），其余表不动。
- 根 `sql.md` §2 同步删两节并改表数口径（22 → 20；全库 67 → 65）。
  `.scratch/project-rework/cmp_schema_marketing.py` → `sql.md sec2 tables: 20` /
  `schema.sql tables: 20` / `0 table(s) differ, 20 identical` / `逐表逐列全等 ✔`。
  注意：脚本最后那行 `✔` 在 GBK 控制台会 `UnicodeEncodeError`（exit 1），跑时设
  `$env:PYTHONIOENCODING='utf-8'` 即 exit 0；比对结论不受影响。
- 按 spec §3 的 Windows 手工命令重建 **`bs_marketing` 与 `bs_marketing_test`**：两库各 20 张表，
  `table_name LIKE 'physical_inventory%'` 均为 0。

**删掉的代码**
- 4 个类：`PhysicalInventoryPO` / `PhysicalInventoryDetailPO` / `PhysicalInventoryEntity` /
  `PhysicalInventoryDetailEntity`（连文件删除）。
- `IPhysicalQuotaDao`：删 `selectInventory` / `issueInventory` / `insertInventoryDetail` 及对应 PO import；
  兑换确认段只剩 `transferWaitingToIssued`。
- `PhysicalQuotaMapper.xml`：删对应三个语句（`selectInventory` / `issueInventory` / `insertInventoryDetail`）。
  收尾形状 = 预扣 3 条（`selectQuota` / `selectQuotaBuckets` / `insertQuotaDetail`）→ 合并 4 条
  （`selectUnmergedQuotaDetails` / `deductQuotaBucketRedisQty` / `settleQuota` / `markQuotaDetailsMerged`）
  → 兑换确认 1 条（`transferWaitingToIssued`）→ 预热 / 对账 3 条
  （`selectOpenQuotas` / `sumUnmergedQuotaQty` / `sumMergedQuotaQty`）。
- `IInventoryRepository` / `InventoryRepository`：删 `queryPhysicalInventory` / `issueInventory` /
  `insertInventoryDetail`，以及只为库存台账服务的 `toInventoryEntity(PhysicalInventoryPO)` 重载。
  `confirmIssued` 只剩「`waiting_qty ≥ qty` 守卫下 `waiting_qty → issued_qty`」一次 `UPDATE`；0 行仍抛
  `INVENTORY_WAITING_SHORTAGE(4312)`。事务边界保留（仓储内 `TransactionTemplate`，REQUIRED），
  留给 11 的异步确认改；本票不在语义上加东西。4312 的异常信息补上 `redeemOrderId`
  （原 OUT 行的 `ref_id` 是记录级排查线索，删行后由异常信息承担）。
- `PhysicalQuotaServiceImpl.confirmIssued`：删「实物库存台账不存在」校验与「转移 + 出库 + 流水」编排，
  只留配额存在性校验 + 一次仓储调用；日志 `兑换确认出库` → `兑换确认转移`。
  `IQuotaConfirmPort` 里「落一笔 OUT」的描述随之删掉。

**测试断言的替换**
- `RaffleApiTestBase`：删 `insertPhysicalInventory` 夹具与注释；`prepareInventory` 不再铺库存台账行；
  `TRUNCATE` 列表去掉两表（其余表名未动，02/03 再改）。
- `PhysicalQuotaIntegrationTest`：夹具不再铺 `physical_inventory` 行，`physicalInventoryRow()` 删除；
  确认用例改为断言配额自身——`waiting_qty = 0`、`issued_qty = 3`、`surplus_qty = 97`
  （合并已扣过 surplus，确认不再扣 = 不再双扣）；不再断言 OUT 行与库存台账递减。
- 「waiting 不足仍抛 4312」不改测试：既有
  `RaffleShippingConsumerIntegrationTest.ship_physicalPrize_eventBeatsMerge_retriesUntilMergeLands`
  覆盖，本票落地后仍绿。

**验证证据**
- `mvn -o -q test-compile`（`backend/marketing`）通过；重建两库后 `mvn -o test` →
  **82 tests, 0 failures, 0 errors, 0 skipped**（`PhysicalQuotaIntegrationTest` 5 个用例全绿）。
- 反查：`backend/marketing` 全部 `.java` / `.xml` / `.sql`（排除 `target/`）grep
  `physical_inventory|PhysicalInventory` **零命中**。
- 文档（`backend/marketing/docs/`、`CONTEXT.md`、ADR）按 map 口径留到 12，不在本票改。

## 注意

- **本票是纯删减**：不要顺手把确认改异步、不要动 `type` 列、不要改合并。
- `confirmIssued` 的转移守卫今天依赖 `transferWaitingToIssued` 返回 0 行；删出库步骤后它成为唯一守卫，
  错误码仍是 `4312`（11 才删）——不要提前退休，否则 11 的验收无从对照。
- 判据依据（spec §2.5）：`physical_inventory_detail` 的 OUT 行生产代码无读路径；两份同量账本各扣一次
  `surplus_qty` 是双扣。删它是修正，不是功能。
