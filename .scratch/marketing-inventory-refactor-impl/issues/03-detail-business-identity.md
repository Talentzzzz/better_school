# 03: 明细业务身份 + 撞键回放

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §2.2（两张明细表）、§5.1（`create_time` 由 DB 写）、
§7.5（幂等与既有 bug）、§12（抽奖侧签名变更）

**What to build:** 每一行扣减明细都能回答「这笔扣减属于哪次业务」：虚拟表带 `(biz_type, biz_id)`，
实物表再带 `type = 1`，唯一键生效。由此顺带修掉既有 bug——同一 token 并发双击时，第二笔撞业务唯一键，
回补本次 Redis 扣减并按「成功回放」返回，净扣减只有一份。`create_time` 改由 INSERT 的 `NOW()` 写，
它是后续时间格合并的唯一依据。

**Blocked by:** 02

**Status:** resolved

**预算：≈140k / 200k。** 真正的难点只有两处：唯一键撞键必须**回补 Redis 后再返回 true**，
以及 `biz_id` 是 NOT NULL——每行都得写真实 `recordId`，不能留 0 占位。
若并发用例不稳定，先保证「串行重复预扣只落一行」，把并发用例标成 11 里复核。

## 验收

- [x] `physical_marketing_quota_detail` 加 `type TINYINT NOT NULL`（1 预扣结转 / 2 兑换确认，本票只写 1）、
      `biz_type VARCHAR(32) NOT NULL`、`biz_id BIGINT NOT NULL`，
      唯一键 `uk_biz_type (type, biz_type, biz_id)`
- [x] `virtual_inventory_quota_detail` 加 `biz_type` / `biz_id`，唯一键 `uk_biz (biz_type, biz_id)`
- [x] 两张明细表：`state` 注释收成 `0-未合并 1-已合并` + `CHECK (state IN (0,1))`；
      删 `idx_scan`，加 `idx_key_cell (inv_id, bucket_id, state, create_time)` 与
      `idx_pending (state, inv_id, bucket_id, create_time)`
- [x] 明细 INSERT 的 `create_time` 改写成 DB `NOW()`（应用不再传时间；`update_time` 同口径）
- [x] `types.inventory.InventoryBizTypes`：`LOTTERY_DRAW` / `LOTTERY_CLAIM`；
      `IInventoryDeductPort` 两个方法带 `recordId`，端口实现只透传，`RaffleDrawServiceImpl` 在预扣处传 `recordId`
- [x] `IQuotaConfirmPort` 的 `redeemOrderId` 正名为 `recordId`（它一直是抽奖记录 id，不是兑换单号）
- [x] 两个孪生预扣服务落明细时填 `bizType = LOTTERY_DRAW`、`bizId = recordId`，实物再填 `type = 1`；
      **撞唯一键 → 回补 Redis → 返回 true（成功回放）**，其它异常回补后照旧抛出
- [x] 新用例：同一 token 两线程并发预扣 → 明细一行、Redis 净减一份、`draw_record` 一行；两段各验证一次
- [x] 按 spec §3 重建两个库，全量集成测试绿

## 实现要点

（2026-10-17 落地。DDL + 业务身份 + 撞键回放；未动合并算法、未动 outbox / 触发、未动兑换确认的转移语义。）

**DDL（两张明细表统一形状）**
- `schema.sql` 与根 `sql.md` §2 同步：虚拟表加 `biz_type` / `biz_id` + `uk_biz`；实物表加
  `type` / `biz_type` / `biz_id` + `uk_biz_type`；两表 `state` 注释收成两态并各加
  `chk_*_quota_detail_state CHECK (state IN (0,1))`；删 `idx_scan`，加 `idx_key_cell` / `idx_pending`。
  类型宽度、列顺序、注释与 spec §2.2 逐字一致；`cmp_schema_marketing.py` → `20 identical / 逐表逐列全等`。
- 按 spec §3 重建 `bs_marketing` / `bs_marketing_test` 各 20 张表；用 `information_schema` 核过列、索引、
  CHECK 名（`idx_key_cell` / `idx_pending` / `uk_biz` / `uk_biz_type` / 两个 `chk_*_detail_state`）。
- `create_time` / `update_time` 仍由 INSERT 的 `NOW()` 写；本票只把实体注释固定成「应用不传」，
  不改 SQL 时间口径（票 02 前就已经是 `NOW()`）。

**撞键截获落点：域服务，不落仓储**
- 理由是补偿动作本身在域服务：`release(bucketKey, qty)` 是 Redis 侧的补偿，仓储只做「实体 → PO → SQL」，
  不该为了回补 Redis 去认识 bucket key；而两条孪生路径的 `try { insert } catch` 骨架本来就长在服务里
  （其它异常回补后抛出也在这里），撞键只是同骨架里的一个**成功分支**。
- 两段回补路径**完全同构、无差异**：都是 `release(bucketKey, quantity)` 后 `return true`；差异只在落行时
  实物多填 `type = DETAIL_TYPE_DEDUCT`（值 1，本票只写 1）。真正不同的只是 `uk_biz_type` 比 `uk_biz` 多一列
  `type`，两张表各自撞自己的键。
- 仓储侧只补了字段映射（`type` / `bizType` / `bizId`），没有新增事务；单条 INSERT 是自动提交，
  `DuplicateKeyException` 由 MyBatis-Spring 异常翻译抛出后即可安全回补。
- 没有改成 `INSERT IGNORE`（`DrawCountRepository` 的惯用法）：`INSERT IGNORE` 会把别的约束错误一起吞掉，
  而这里必须把「撞业务键 = 重放」与「其它错误 = 回补后抛出」分开；`DuplicateKeyException` 的类型判断更精确。
  这条是 judgement（`backend/CLAUDE.md` §1 的流水先行口径只点名资金/信用分）。

**撞键之外的失败路径回放（review 补的跨表缝）**
- 只靠 `INSERT ... catch (DuplicateKeyException)` 有一个照不到的窗口：同 token 的下一笔晚到，
  **桶已被上一笔扣空**或**台账已被合并置 `exhausted`**（`preDeduct` 在 `state != open` 直接返回 false）时，
  这一笔根本走不到 INSERT，拿不到撞键信号；若首笔是实物、这一笔因此改走兜底，落的是**另一张表**，
  `uk_biz_type` / `uk_biz` 各管一张表，跨表不会互相撞键 → 同一 `recordId` 两行（物理 1 + 兜底 1）、
  净扣两份，违反 spec §7.5「净扣减只有一份」。
- 补法：**只在扣不出的失败路径**做一次业务幂等预检（`existsQuotaDeductDetail` / `existsVirtualDeductDetail`，
  走唯一键索引且带 `inv_id`），命中即按成功回放、不再扣任何 Redis；成功路径仍靠撞键，不多付一次查询。
  带 `inv_id` 是为了让回放落到**原来那个池子**：若上一笔是兜底段，主段的重放判定不会误把这次判成主段成功。
  实物段在 `preDeductWithFallback` 里还会再查一次虚拟表（`fallbackInvId`）：上一笔走的是兜底段时直接回放 `FALLBACK`。
- 这条补丁覆盖了确定性可复现的两类：串行重试（桶被扣空 / 台账 exhausted）与「首笔实物、次笔本想走兜底」；
  并发下「物理行仍在提交中」的微秒级窗口无法在不引入锁/跨表认领态的前提下彻底关死（spec §1 的非目标），
  留作已知窄缝（本票测试只覆盖前两类，不把并发用例写成概率断言）。同族的残余窗口：首笔走兜底后，
  若管理端把物理桶重新预热（`inventoryBucketWarmup` 不自动跑）或换了奖品 inv，同一 token 的重试仍可能先扣到
  物理段——跨表唯一键照样拦不住；运维窗口内不致命，彻底关死需要跨表认领态/锁，属 spec §1 非目标。
- 合并扫描 SQL **未动**：两张 mapper 的 `selectUnmerged*` 投影保持原列，`type` / `biz_type` / `biz_id`
  留给 08 / 11 按需加；本票只加了两条建索引与此处的存在性查询（走唯一键，不碰 `idx_key_cell` / `idx_pending`）。

**抽奖侧签名（破坏性，按 spec §12 一次改透）**
- `IInventoryDeductPort.preDeductVirtual` / `preDeductPhysicalWithFallback` 各加 `recordId`；
  `InventoryDeductPort` 纯透传；`RaffleDrawServiceImpl.issueWithFallback(..., recordId)` 从 `draw()` 的
  `recordId = hash(userId, token)` 一路传到预扣。
- `IQuotaConfirmPort.confirmIssued` / `QuotaConfirmPort` / `IPhysicalQuotaService.confirmIssued` /
  `IInventoryRepository.confirmIssued` 的 `redeemOrderId` 全链路正名 `recordId`（只改名，转移语义不动，
  4312 仍保留到票 11）。
- `bizType` 由库存侧填 `InventoryBizTypes.LOTTERY_DRAW`：抽奖域只透传 `recordId`，不认识库存表列。

**测试**
- `VirtualInventoryIntegrationTest` / `PhysicalQuotaIntegrationTest` 各加**串行重复预扣**：
  同 `recordId` 第二次预扣返回 true、明细仍一行、Redis 净减一份（撞键回放的最小可验形态）。
- 失败路径回放各加两类：桶被上一笔扣空后重试、台账 `exhausted` 后重试 → 都按成功回放且不再扣减；
  实物段另加「主段桶空、首笔已走兜底」→ 第二次直接 `FALLBACK` 回放、兜底只扣一次；
  以及「主段最后一单位被首笔取走、次笔不能改走兜底」→ `DEDUCTED`、兜底段零写入。这几条是 review 后补的，
  用来堵「走不到 INSERT 就没有撞键信号」的缝。
- 既有用例的 `preDeduct` / `preDeductWithFallback` / `confirmIssued` 调用补上 `recordId`；
  预扣用例顺手断言 `biz_type` / `biz_id` /（实物）`type = 1` 与 `create_time` 非空。
- `RaffleDrawPipelineIntegrationTest` 各加一条**同 token 两线程并发双击**（虚拟 / 实物各一次）：
  两线程都从 HTTP 入口进 `draw`，断言两条响应同一 `recordId`、明细一行、Redis 桶净减一份、
  `draw_record` 一行。并发前先把 `draw_count_detail` 夹具铺好（等价于「这一笔已扣次」），
  否则败者可能在赢家落 `draw_record` 之前拿 `NO_CHANCE`，把库存幂等用例测成扣次竞态（用例注释里写明）；
  这条夹具断言的是库存幂等，不假装验证扣次路径。
- 并发用例连跑 3 次稳定（2 tests × 3 全绿）。

**验证证据**
- `mvn -o test-compile` 通过（未 `clean`：开发栈的 `marketing-app` 旧 jar 正被占用；mapper 文件名本票未变，
  无新旧 XML 并存问题）。
- 重建两库后 `mvn -o test` → **91 tests, 0 failures, 0 errors, 0 skipped**（票 02 基线 82 + 本票 9：
  串行撞键回放 2、失败路径回放 5、并发双击 2）。
- `cmp_schema_marketing.py` → 20 张表 `0 table(s) differ / 逐表逐列全等 ✔`。
- 开发库 `bs_marketing` 已按新 schema 重建，正在跑的旧应用进程需重启才会带上新 mapper（不在测试范围）。

## 注意

- **撞键不是错误**：撞键 = 这条流转已落过。回补 Redis 后返回成功，让抽奖继续走它自己的 `draw_record`
  撞键回放（`uk_record_id` 已有）。
- 本票之后**没有「双击返回 999」的中间态**了：唯一键和回放必须在同一票里落地，否则测试会红。
- 去重 key 的删除（`release` 多一个参数）在 09——本票的 `release` 仍是「只回补余额」。
- `idx_key_cell` / `idx_pending` 是给 08 / 10 用的，本票只建索引、不写查询；不要顺手改合并 SQL。
- 文档（`docs/db/inventory.md`、`CONTEXT.md`、ADR）按 map 口径留在 12，本票未动。
