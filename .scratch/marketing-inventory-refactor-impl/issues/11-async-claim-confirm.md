# 11: 兑换确认异步化（type=2）

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §6（兑换确认）、§5.2 的 ⑥、§7.5（幂等）、
§12（`4312` 退休）

**What to build:** 兑换确认不再同步做 `waiting_qty → issued_qty`：落一条 `type = 2` 的实物明细
（`bucket_id` 从该记录的 `type = 1` 预扣行取）+ **每条都发一条合并消息**（不走 throttle），
转移由合并按时间格完成。`INVENTORY_WAITING_SHORTAGE(4312)` 退休。
做完后，发货消费里不再有「确认撞 4312 → 重投等合并」的剧情；失败只表现为 `pendingClaimQty > 0`。

**Blocked by:** 10

**Status:** resolved

**预算：≈140k / 200k。** 写入方 + 合并的 `s2` 分支 + 退休 4312 + 抽奖侧注释，四处都不大；
风险在「重复确认只能落一行、只能发一条消息」这条幂等。若 `waiting` 不足的用例难造，
用「手工改台账或跳过预扣」构造，必须覆盖 `pendingClaimQty > 0`。

## 验收

- [x] `IInventoryQuotaService.confirmIssued(invId, bizType, bizId, quantity)`：
      查 `type = 1 AND biz_type = ? AND biz_id = ?` 的 `bucket_id`，**取不到 → `INVENTORY_PARAM_ERROR`、不落行不发消息**；
      取到 → 同事务「插 `type = 2` 明细（`biz_type = LOTTERY_CLAIM`）+ 插 outbox（`delay_level = 1`、同 key）」
- [x] 撞 `uk_biz_type (type, biz_type, biz_id)` → 当成功回放、**不再发消息**（第一笔已经发过）
- [x] `IQuotaConfirmPort` 只透传 `recordId`；`RaffleShippingCase` 三步顺序保留，注释改成
      「确认已是一次本地写，顺序不再承重」；抽奖域不引入库存 VO
- [x] 合并的 `type = 2` 分支生效：`waiting_qty >= s2` 守卫、`waiting -s2 / issued +s2`；
      0 行 = 不变量破坏 → 整格回滚 + `log.error`（不抛、不续命）；`type = 2` **从不碰 Redis**
- [x] 删 `ResponseCode.INVENTORY_WAITING_SHORTAGE(4312)` 与同步转移/出库的残留编排；全仓无引用
      （只在 `docs/` 的旧文案里还留着，按 map 口径归 12）
- [x] 测试：实物档确认后 `type = 2` 明细 `state = 0` + outbox 行（`LOTTERY_CLAIM`、`delay_level = 1`）；
      调 `mergeKey` 后 `waiting` 减 / `issued` 增、明细 `state = 1`；重复确认只一行一消息；
      `waiting` 不足 → 格留 `state = 0`、`pendingClaimQty ≥ 1`
- [x] 全量集成测试绿（**114 tests**）

## 实现要点

（2026-10-18 落地。`4312` 从 `ResponseCode` 删除；**07 仍未落地**，新用例暂放
`PhysicalQuotaIntegrationTest` / `RaffleShippingConsumerIntegrationTest`，收口时再迁进唯一参数化类。）

**写入方（`InventoryQuotaServiceImpl.confirmIssued`）**
- 三步：查台账（不存在 → `INVENTORY_PARAM_ERROR`）→ 回查预扣行拿 `bucket_id`（取不到 → 同码，
  **不落行不发消息**）→ `insertClaimDetail`（`type = 2` 明细 + 恒 1 条 `delay_level = 1` 的合并消息，一个事务）。
- 不再动 `surplus_qty`、不再写出库行；`waiting → issued` 只在合并里发生。
- 撞 `uk_biz_type (type, biz_type, biz_id)` → 吞 `DuplicateKeyException` 当成功回放。**这是事务边界给的**：
  明细 INSERT 在前、outbox 在后，撞键时整笔回滚，消息一起没了，所以不需要额外判重。

**bucket 回查的索引**
- `SELECT bucket_id ... WHERE inv_id = ? AND type = ? AND biz_type = ? AND biz_id = ?` 走唯一键
  `uk_biz_type (type, biz_type, biz_id)` 三列等值命中（`inv_id` 只作余下过滤），**不新增索引**。
- 两个入参最容易写错、都落在服务注释里：`type` 是**预扣行**的 `1`；`biz_type` 是**预扣行**的 `LOTTERY_DRAW`
  ——确认行自己的 `biz_type = LOTTERY_CLAIM` 是另一列值，用它查不到。
- 带 `inv_id` 是刻意的：跨 inv 拿到别的桶号会把确认行写进错台账。

**`type = 2` 的 outbox 与 09 复用同一条路**
- `insertClaimDetail` 与 `insertDeductDetail` 共用 `TransactionTemplate` +
  `OutboxMessageStore.save(topic, payload, delay_level = 1)`；差别只有两点：消息**恒 1 条**
  （confirm 不吃 `SET NX` 去重窗口，spec §6），`type` 由 `toClaimDetailPO` 钉成 2。
- 明细行 VO / 聚合去重名：两种行的形状完全一样（数量 + 桶号 + 业务身份），差别只在 `type`，
  故 `InventoryDeductDetailVO` / `InventoryDeductAggregate` 改名成 `InventoryDetailVO` /
  `InventoryDetailAggregate`（`type` 由写入方法决定）；映射收成 `toQuotaDetailPO(detail, type)` 一个方法。
  触发用例的三处直接构造同步改名。

**合并的 `s2` 分支（`InventoryRepository.mergeCell`）**
- `CellAggregate` 补 `claimQty`；⑤ 之后加 ⑥：`segment == QUOTA && claimQty > 0` 时调
  `transferWaitingToIssued`（复用既有 SQL，不新增语句）；0 行 → `log.error` + rollback-only + 返回 0，
  与桶守卫同一条处置（**不抛、不续命**）。
- `type = 2` 不进 ④（不递减桶副本）：「从不碰 Redis」是代码路径保证，不是注释承诺。
- 同格内 ⑤ 在 ⑥ 之前、跨格靠「只合最老一格」；两种情形都有用例覆盖（同秒同格 / 先后两格）。

**4312 退休后的空位**
- `ResponseCode.INVENTORY_WAITING_SHORTAGE` 整条删除、**不留占位**（无全局注册表，码值只是前端分支的字符串）；
  库存段只剩 `INVENTORY_PARAM_ERROR("400")`，注释扩到「兑换确认找不到预扣结转行」。
- 抽奖侧注释一并换口径：`RaffleShippingCase`（顺序不再承重）、`IQuotaConfirmPort`、`ShippingPlanVO`、
  `IRaffleShippingService`、`LotteryShippingConsumer`、`DrawRecordActionVO`。
- `docs/api/lottery.md` 的错误码表与「发货消费的重投口径」、`docs/db/*`、`CONTEXT.md` 按 map 口径留给 12。

**用例（4 新 + 3 改）**
- `PhysicalQuotaIntegrationTest`：确认落 `type=2`（`state=0`、桶号取自预扣行）+ 一条 `delay_level=1` 消息 →
  合并后 `waiting -3 / issued +3`、`state=1`、桶余额不变；重复确认只一行一消息；无预扣行 →
  `INVENTORY_PARAM_ERROR` 且零副作用；`waiting` 不足 → 格留 `state=0`、`pendingClaimQty = 3`、
  `log.error`、不续命。既有对账用例改用真路径（不再手工插 `type=2` 行）。
- `RaffleShippingConsumerIntegrationTest`：实物档改成「先落明细、合并后转移」；原「4312 重投」用例换成
  「事件比合并先到一起照样落明细，合并按最老格先结预扣再结确认」；重复投递用例补「确认明细仍一条」。
- `VirtualInventoryIntegrationTest`：清表补上实物三表——它有一条断言 `backstopOnce().quotaKeys == 0`，
  前面用例留下的 `state=0` 行会让它变成 1（跨类卫生，不是行为变化）。

**评审收口（两轴）**
- standards 轴提的两点已收：① `insertClaimDetail` 去掉恒为 QUOTA 的 `segment` 形参（连带那条永远成立的
  段校验，也与 §7.4 的 `insertClaimDetail(claim, mergeMessage)` 对齐），改为 `Objects.requireNonNull(mergeMessage)`
  把「恒 1 条消息」写成契约；② `RaffleShippingCase` 注释里对 `4312` 的引用改成历史叙述，Java 侧已 grep 无残留。
- **刻意保留**「取桶号（读）+ 落明细（写）分两次仓储调用」：spec §6 把「取不到预扣行 → `INVENTORY_PARAM_ERROR`」
  定为域判据，§7.4 把「明细 + outbox」定为仓储事务，两段职责不同才拆开；`type = 1` 行只翻转 `state`、
  从不改删，且 `type = 2` 不参与桶结算（不递减 `redis_qty`、不走 Redis），所以这条读到的桶号即使陈旧
  也不会造成错账——没有需要合的 TOCTOU。合进一个仓储方法会把判据和消息组装推进 infra，反而更糟。
- **刻意保留** `confirmIssued` 这个方法名：票面验收与 spec §6/§7.4 都按这个名字写，改名会破契约；
  语义变化（同步转移 → 异步登记）全部写在接口 javadoc 里。
- 未采纳（记录在案）：`catch (DuplicateKeyException)` 理论上会把 outbox 的 `uk_msg_id` 撞键也当成功回放——
  `msg_id` 是 `UUID.randomUUID()`，实际不可能撞；且预扣路径（票 09）是同一个形状，单独给确认路径加机制
  会让两条路不一致。

## 注意

- **bucket 不新增列**：不往 `draw_record` 加 `bucket_id`，也不让端口回传 bucket（spec §6）。
- **顺序靠 `MIN`**：`type = 1` 的 `create_time` 必然早于 `type = 2`，所以 `waiting >= s2` 是结构保证；
  若把「只合最老一格」改成并行合多格，这个守卫会变成真业务失败——别改。
- 确认**不走 SETNX**：兑换低频，被窗口吞掉就只剩 60s 兜底；幂等靠明细唯一键。
- `4312` 只删 Java 常量与引用；`docs/api/lottery.md` 的同步在 12。
