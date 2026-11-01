# 05: 一条路径（segment）

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §7.1（接口清单）、§7.2（fallback 新形状）、
§7.3（Redis key 前缀）、§7.4（仓储与事务落位）

**What to build:** 把「虚拟一套 + 实物一套」的孪生代码合成**一条路径 + 一个 `segment` 参数**：
一个库存配额服务、一套按 `segment` 分派的仓储方法、一组收敛后的 Redis key 前缀。
两套表、两套 PO / DAO / mapper 保留（表就是两张）。**行为必须与 03 完全一致**：
合并还是每秒全表扫、确认还是同步转移。

**Blocked by:** 03

**Status:** resolved

**预算：≈170k / 200k。** 本票是纯形态重构里最大的一票，产出以「方法数减半、两条路变一条」为准。
预算告急时的止损线：服务合一 + 仓储合一 + key 前缀做完（这三件不可分割）；
`warmup` / `audit` 的对外形状与对账口径留给 06——它们可以先保留孪生入口，只改到能编译。

## 验收

- [x] `InventorySegmentVO`：`QUOTA("quota")` / `VIRTUAL("virtual")`，带 `code`；日志、消息、Redis key 都用 `code`
- [x] `InventoryDeductRequestVO(segment, invId, quantity, bizType, bizId)` 把散参收成一个值对象
- [x] 一个 `IInventoryQuotaService` + `InventoryQuotaServiceImpl`（`preDeduct` / `preDeductWithFallback` /
      `confirmIssued`）；删 `IVirtualInventoryService` / `IPhysicalQuotaService` 与两个 Impl
- [x] `preDeductWithFallback`：主档扣不出 → 换个 `inv_id`（可换段）再扣；结果仍是
      `DEDUCTED` / `FALLBACK` / `FAILED`；配额段零写入由「兜底路径不碰配额仓储」保证
- [x] `IInventoryRepository` 每个方法带 `segment`，方法数减半；实现按 `segment` 分派两个 DAO；
      **PO / DAO / mapper XML 不合并**
- [x] Redis key：`bs:inventory:bucket:{quota|virtual}:{invId}:{bucketId}`；`Constants` 收成
      `BUCKET_KEY_PREFIX` + `bucketKey(segment, invId, bucketId)`（**不叫 `mergeKey`**）
- [x] 抽奖侧两个端口实现只改委托目标，签名不变（`recordId` 在 03 已加）
- [x] `warmup` / `audit` 仍保留孪生入口、只改到编译通过（对外形状与恒等式留 06）
- [x] `inventoryMerge` 仍是每秒全表扫的 `mergeOnce()`；确认仍是同步转移
- [x] 两个库存集成测试 + 故障测试改调新服务并全绿（参数化合并留 07）

## 实现要点

（2026-10-18 落地。只做形态收口：一条路径 + 一个 `segment`；触发 / 合并算法 / 确认语义 / DDL 均未动。）

**`InventorySegmentVO` 落 `types.inventory`（不是 `domain...model.valobj`）**
- 理由：`Constants.bucketKey`（types）与 09 的合并消息载荷都要用它，而 types 不能反向依赖 domain
  （`backend/CLAUDE.md` §1）；types 本就允许放枚举（`DrawCountReasonType` 先例）。
- `code` = `quota` / `virtual`：Redis key、日志、消息都用 code，枚举名只活在代码里。
- `Constants` 只剩 `BUCKET_KEY_PREFIX = "bs:inventory:bucket:"` + `bucketKey(segment, invId, bucketId)`；
  `INVENTORY_KEY_PREFIX` / 两个段前缀 / 两个组装方法删除。新 key
  `bs:inventory:bucket:{quota|virtual}:{invId}:{bucketId}`；测试清理仍是 `bs:inventory:*`（覆盖新旧）。
- `lua/inventory_bucket_deduct.lua` 的键形状注释同步改（脚本行为不动；两键两参是 09）。
- 新增一条字面 key 形状用例（两种段），防止「测试和实现对同一个错误常量自洽」。

**仓储分派写法（26 → 10 个方法）**
- 每个接口方法第一参 `InventorySegmentVO`，实现里
  `switch (segment) { case VIRTUAL -> virtualDao…; case QUOTA -> physicalDao…; }`
  （switch 表达式，enum 全覆盖不写 `default`）。
- 只把「两段都有的单步写面」收成仓储私有方法（`deductBucketRedisQty` / `settleLedger` /
  `markDetailsMerged` / `transferWaitingToIssued`），不再暴露到接口。接口 10 个方法：
  `queryLedger` / `listBuckets` / `listOpenLedgers` / `insertDeductDetail` / `existsDeductDetail` /
  `listUnmergedDetailRows` / `mergeBucketGroup` / `confirmIssued` / `sumUnmergedQty` / `sumMergedQty`。
- 没有做 `Map<segment, DAO>` 分发表：两个 DAO 的同构方法名不同（`selectInventory` vs `selectQuota`…），
  分发表要先包一层适配接口，等于把 switch 挪进一个新类，票面已否决「合 DAO」。**13 处 switch 的重复是
  有意接受的**（review 的「Repeated Switches」判断）：它换来的就是「分派只在一个类里」。
- **PO / DAO / mapper XML 零改动**：本票只改仓储实现与 domain 形状。

**domain 形状：6 个按表命名的 entity → 3 个仓储投影 VO**
- 删 `VirtualInventoryQuota{,Bucket,Detail}Entity` / `PhysicalMarketingQuota{,Bucket,Detail}Entity`；
  新增 `InventoryLedgerVO`（invId / surplus / waiting / issued / state，虚拟 waiting 恒 0，
  `waiting + issued` 两段同表达）、`InventoryBucketVO`（bucketId / redisQty）、
  `InventoryDeductDetailVO`（invId / bucketId / quantity / bizType / bizId）。
- 实物明细多出来的 `type = 1` 与 `state = 0` 由仓储按段补（`toQuotaDetailPO` / `toVirtualDetailPO`），
  域服务不再认识「哪张表多一列」。这是「仓储每个方法带 segment」的必然结果：返回/入参必须是两段公共形状；
  票面保护的是 PO / DAO / mapper XML（表级），这三样原样两套。
- spec §7.4 的「实体同理」是**命名口径**（实体跟表名），不是要求保留两套 entity：仓储按 segment 返回公共
  形状后，6 个按表命名的 entity 已无任何调用方，留着只会是 6 份没有差异的样板。review 把这条记为 scope
  creep，落地按「一条路径」读；若后续票需要按表特化的字段（如 `decr` / `value`），再按段加回 VO 字段。

**服务合一**
- `IInventoryQuotaService`：`preDeduct(segment, invId, quantity, bizType, bizId)` /
  `preDeductWithFallback(InventoryDeductRequestVO primary, InventoryDeductRequestVO fallback)` /
  `confirmIssued(invId, bizType, bizId, quantity)`（spec §7.1 签名）。
- 预扣骨架与 03 逐行等价：查台账（不存在 → `INVENTORY_PARAM_ERROR`；非 open → 重放预检）→
  逐桶 Lua → 落明细；撞唯一键回补 Redis 后返回 true，其它异常回补后抛；扣不出再查一次业务幂等预检。
  段差异只剩仓储分派与 `bucketKey`。
- `preDeductWithFallback`：主档失败 → fallback 为 null 则 `FAILED` → 兜底段明细已存在则回放
  `FALLBACK` → 再扣兜底段（可换段）；两段都扣不出 `FAILED`。**配额段零写入**仍由「兜底路径根本不碰
  配额仓储」保证。
- `confirmIssued` 仍是同步守卫转移（4312 保留到 11）；仓储 `confirmIssued` 只接受 `QUOTA` 段
  （传 VIRTUAL 抛 `INVENTORY_PARAM_ERROR`——虚拟没有等待态），事务仍是仓储里的
  `TransactionTemplate`（REQUIRED）。`bizType` 本票只进日志（`type = 2` 行留给 11）。

**孪生入口保留（06 收）**
- `IInventoryWarmupService` 仍是 `warmupVirtual / warmupPhysical / warmupAll`，内部共用私有
  `warmupSegment`；`IInventoryAuditService` 仍是 `auditVirtual / auditPhysical`，内部共用
  `auditSegment`（`ledgerIssued = waiting + issued` 已两段同表达，字段 / 恒等式收口留 06）。
- `IInventoryMergeService.mergeOnce()` 不动；`InventoryMergeServiceImpl` 先扫完两段再逐组合并
  （与 03 的「一次扫两段」快照时点一致：合并虚拟段期间新落的实物明细不会被这一轮顺手带走），
  段只决定仓储分派与日志里的段名。
- `inventoryBucketWarmup` job 的参数解析（`[virtual|physical:]invId`）未动，06 才换
  `[quota|virtual:]invId`；`inventoryMerge` 仍是每秒全表扫。

**抽奖侧**
- `InventoryDeductPort` / `QuotaConfirmPort` 只换委托目标为 `IInventoryQuotaService`，并在实现里填
  `segment` 与 `InventoryBizTypes.LOTTERY_DRAW / LOTTERY_CLAIM`；两个域端口接口、
  `RaffleDrawServiceImpl` / `RaffleShippingCase` 零改动。

**验证**
- `mvn -o -f backend/marketing/pom.xml test` → **93 tests, 0 failures**（92 + 桶 key 形状 1）；
  schema 未动，测试库沿用 03 重建后的形状。
- 三个库存测试与 `RaffleApiTestBase` 的桶 key helper 已切新服务 / 新 key；未删任何用例，
  参数化合并留 07。三个测试类各自的 `preDeduct(...)`（及实物侧的 `preDeductWithFallback(...)`）
  是暂态小 helper：07 把两个类合并成一个参数化类时会一起收掉，本票不提前抽基类。

## 注意

- **这一票的价值是「让 08 的 diff 只有合并」**。不要顺手换触发、不要把确认改异步、不要动 `waiting` 语义。
- 事务仍只在仓储里（`TransactionTemplate`，REQUIRED）；域服务不注入模板。
- 每段两个 DAO 的同构方法不要塞进一个 mapper XML——表不同、索引不同，合了只会让 SQL 变成动态表名。
- 删类前先全局搜引用（`@MapperScan`、`RaffleMapper.xml` 的 `pointsAmount` JOIN 不属于库存仓储，不动）。
