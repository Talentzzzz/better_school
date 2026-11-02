# map：营销库存扣减重构落地（实施）

## Destination

按 [marketing-inventory-refactor spec](../marketing-inventory-refactor/spec.md)（唯一需求来源）完成编码落地：
营销库存收成**两套 quota**——`physical_marketing_quota*` 保留、`virtual_inventory*` 改名 `virtual_inventory_quota*`、
`physical_inventory*` 删除；扣减明细加 `type` / `biz_type` / `biz_id` 与业务唯一键；合并从「每秒全表扫两段」
改为「按 `(segment, inv_id, bucket_id)` 的 1 秒延迟消息 + 时间格合并 + 60 秒兜底」；兑换确认从同步转移
改成落 `type = 2` 明细、由合并转移。

## Notes

- **需求问题一律查 spec 对应章节**；本 effort 不再重开设计讨论。发现 spec 缺口回
  [marketing-inventory-refactor](../marketing-inventory-refactor/map.md) 补票。
- **拆票口径：12 张，每张票的落地预算 ≤ 200k token（一个新鲜上下文）**。每票文件顶部写预算与**止损线**：
  做到止损线还没收尾，就停在「已可验收的最小闭环」并把剩余部分留到下一票，不许把一票做成 300k。
  预算超了说明切分错了——回本 map 重切，不要硬做。
- **依赖是一条主链 + 两个支票**：主链 `01 → 02 → 03 → 05 → 06 → 07 → 08 → 10 → 11 → 12`；
  **04** 只被 01 的 `delay_level` 列门禁（可插在 02–08 任意时点），**09** 只被 04 + 05 门禁、与 08 互不依赖。
  推荐顺序仍是 `01…12`（08 与 09 都改仓储，顺序做省冲突）。每一票单独绿
  （`backend/marketing/` 全量集成测试）才动下一票。
- **测试库必须重建**：`schema.sql` 是唯一 DDL 源（`backend/CLAUDE.md` §10）。01 / 02 / 03 / 04 各改一次 schema，
  每次改完都要重建 `bs_marketing_test`（否则旧表还在 → 假绿；spec §3 有 Windows 手工命令）；05 起 schema 不再动。
- **测试栈没有 broker**（`application-test.yml` 只有 namesrv）：发送必然失败、被 outbox 兜住。
  「消息发出」的可观察形态 = outbox 落行 + 载荷 + `delay_level`；「消费」由测试直接调消费者 Bean
  （照 `lotteryShippingConsumer.onMessage(...)` 的先例）。
- **`OutboxMessageSender` 是 points / raffle / inventory 共用件**：04 之后它的行为只允许「多一个随消息传入的
  延迟级别」，默认 0。`RaffleShippingOutboxIntegrationTest` 的两条断言（回滚后没有第二条事件、重试耗尽进死信）
  是这条共用件的回归哨兵，**任何一票都不许把它们改绿成别的样子**。
- **前端零改动**：库存域没有 HTTP 面；抽奖目录的 `remaining` / `pointsAmount` 口径不变，只换表名。
- 领域词汇遵循仓库根 `CONTEXT.md`；后端分层与命名遵循 `backend/CLAUDE.md`（尤其一域一仓储、
  事务落位在仓储、domain 不注入 `TransactionTemplate`）。
- **文档滞后是刻意的**：01~11 只改代码与测试，`docs/`、`CONTEXT.md`、ADR 全部收在 12——
  避免中途反复改同一段文案，也避免「文档说改了、代码还没改」。

## 依赖图（Blocked by）

| 票 | 直接门禁 | 说明 |
| --- | --- | --- |
| 01 删进出库台账 | — | 起手 |
| 02 虚拟三表改名 | 01 | 先删表再改名 |
| 03 明细业务身份 + 撞键回放 | 02 | 唯一键加在改名后的表上 |
| 04 outbox 延迟级别 | 01 | 只依赖 `delay_level` 列，独立可验收 |
| 05 一条路径（segment） | 03 | 在「每行都有真实业务键」之后合并签名 |
| 06 预热 + 对账合一 | 05 | 收 05 刻意留下的孪生入口 |
| 07 测试收口（参数化） | 06 | 用 06 的新字段 |
| 08 时间格合并引擎 | 07 | 用例只往唯一的参数化类里加 |
| 09 预扣发消息 | 04, 05 | 只写消息、不消费；与 08 互不依赖 |
| 10 消费 + 兜底 + 续命 | 08, 09 | 消费 `mergeKey`，并给 09 的消息接上消费者 |
| 11 兑换确认异步化 | 10 | 确认要发的消息、`type` 分叉、兜底都已就位 |
| 12 文档 + 回归 | 11 | 文档写最终行为 |

**推荐执行顺序**：`01 → 02 → 03 → 04 → 05 → 06 → 07 → 08 → 09 → 10 → 11 → 12`。
04 只被 01 门禁，想先做也可以；08 与 09 互相独立，但都改仓储，**顺序做**比并行省冲突。

## 文件并发提示

链是线性的，没有真并发；以下是**同一文件被多票改**的热点，落地时按顺序改、别回退：

- `IInventoryQuotaService` / `InventoryQuotaServiceImpl`：05 建（`segment` + `InventoryDeductRequestVO`），
  09 把去重/消息接进预扣，11 把确认改成异步。
- `IInventoryRepository` / `InventoryRepository`：05 合成 `segment` 参数化，08 加 `mergeCell` / key 选取，
  09 加「明细 + outbox 一个事务」，10 加续命入队，11 加 `insertClaimDetail`。
- `InventoryMergeServiceImpl`：05 保留 `mergeOnce`，08 换成 `mergeKey` + `backstopOnce`，
  10 加续命，11 加 `type=2` 的 `s2` 分支。
- `schema.sql` + 根 `sql.md`：01 删两张，02 改名，03 加列/唯一键/索引，04 加 `delay_level`；12 复核比对脚本。
- `Application.yml` / `xxl-job-tasks.sql`：10 换 job 名，12 复核。
- `docs/db/inventory.md`、`docs/api/lottery.md`、`CONTEXT.md`、ADR：只在 12 改；01~11 里看到它们写着旧表名是正常的。
- 测试类：07 合并成 `InventoryQuotaIntegrationTest` 后，08~11 只往这一个类里加用例。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## 实现要点 里 -->

- 2026-10-17 落地口径定稿（写 spec 时定，均已进 spec）：① 兑换确认是异步的——`confirmIssued` = 落 `type=2`
  明细 + 每条都发合并消息，转移由合并做，`INVENTORY_WAITING_SHORTAGE(4312)` 退休（spec §6）；
  ② 窗口尾巴用「合并侧自我续命」补（spec §4.4）；③ 回补 Redis 时**同时删去重 key**（spec §4.2）；
  ④ ADR：`0005` 标 superseded、新建 `0011-inventory-merge-delay-message.md`（由 12 落）。
- 2026-10-17 **重切为 12 张**：原 5 张（01 schema 收口 / 02 孪生合一 / 03 时间格 + 延迟消息 / 04 异步确认 /
  05 文档回归）里 01、02 已逼近上限，03 明显超过 200k——它把 outbox、Lua、消息生产/消费、合并引擎、
  兜底与七条硬口径测试压在一票。按「一票一上下文」重切后：schema 拆成 01/02/03/04，孪生合一拆成 05/06/07，
  核心拆成 08（合并引擎，仍每秒驱动）/ 09（预扣发消息）/ 10（消费 + 兜底 + 续命），11、12 基本沿用。
  旧 5 张草稿作废并已删除，`spec §13` 索引同步成阶段表。
- 2026-10-17 **票 01 已落地**（[票 01](issues/01-drop-inventory-ledger.md) resolved）——
  `physical_inventory*` 两张表、4 个 PO/Entity、3 条 DAO/mapper 语句、3 个仓储方法与出库编排全部删除；
  `confirmIssued` 只剩 `waiting_qty → issued_qty` 守卫转移（0 行仍 4312）；`sql.md` §2 与 schema 各 20 张表
  （比对脚本逐表逐列全等）；两库已重建，`backend/marketing` 全量 **82 tests 绿**。触发 / 合并 / 虚拟段 /
  明细 `type` 列 / `4312` 均未动。
- 2026-10-17 **票 02 已落地**（[票 02](issues/02-rename-virtual-quota-tables.md) resolved）——
  虚拟三表更名 `virtual_inventory_quota{,_bucket,_detail}`，3 个 PO / 3 个 Entity / DAO / mapper XML
  跟着改名，`RaffleMapper.xml` 三处与全部测试夹具换表名；`sql.md` §2 与 schema 仍 20 张表（比对脚本全等），
  两库已重建，`backend/marketing` 全量 **82 tests 绿**。服务名 / 测试名（05、07 收）、Redis key（05 收）、
  触发 / 合并 / 明细列均未动。review 结论：spec 轴零缺陷；standards 轴唯一 hard 项是「docs 未同步」，
  由本 map 的「文档滞后是刻意的」覆盖，另把 `backend/docs/` 两处旧表名补进 [票 12](issues/12-docs-and-full-regression.md)。
- 2026-10-17 **票 03 已落地**（[票 03](issues/03-detail-business-identity.md) resolved）——
  两张明细表加业务身份（虚拟 `uk_biz (biz_type,biz_id)`、实物 `uk_biz_type (type,biz_type,biz_id)`）、
  `state` 收两态 + CHECK、`idx_scan` 换 `idx_key_cell` / `idx_pending`，`sql.md` §2 与 schema 仍 20 张表
  （比对脚本逐表逐列全等）；`InventoryBizTypes` 落 `LOTTERY_DRAW` / `LOTTERY_CLAIM`，
  `IInventoryDeductPort` 两个方法与两个孪生预扣服务带 `recordId`，**撞唯一键在域服务截获 → 回补 Redis → 返回 true**；
  另补「扣不出时的业务幂等预检」（桶被上一笔扣空 / 台账 exhausted 走不到 INSERT，含物理↔兜底跨表那一缝）；
  `IQuotaConfirmPort` 的 `redeemOrderId` 正名 `recordId`（转移语义未动，4312 仍留到 11）。两库已重建，
  `backend/marketing` 全量 **91 tests 绿**（82 + 串行撞键回放 / 失败路径回放 / 并发双击共 9）。
  合并算法、outbox、触发均未动。
- 2026-10-17 **票 04 已落地**（[票 04](issues/04-outbox-delay-level.md) resolved）——
  营销 `outbox_message` 加 `delay_level TINYINT NOT NULL DEFAULT 0`（`sql.md` §2 同步、比对脚本 20 张表全等，
  单体 outbox 未动）；`OutboxPO.delayLevel` + mapper `insert` / `baseColumns` 带上列；`OutboxMessageStore`
  保留两参重载并加 `save(destination, payload, delayLevel)`；`OutboxMessageSender.sendAsync(po)` 按
  `po.getDelayLevel()` 选路——0 走原两参，>0 走五参
  `asyncSend(dest, MessageBuilder.withPayload(payload).build(), callback, producer.getSendMsgTimeout(), level)`；
  `outboxScan` 重发读同一列（无 topic → 级别映射）。points / raffle 调用与载荷零改动，
  `RaffleShippingOutboxIntegrationTest` 两条回归断言原样绿，新增「合并消息 delay=1 / 中奖事件 0 /
  扫描重发仍 1」用例。两库已重建，`backend/marketing` 全量 **92 tests 绿**（91 + 1）。
- 2026-10-18 **票 05 已落地**（[票 05](issues/05-single-segment-path.md) resolved）——
  `InventorySegmentVO{QUOTA,VIRTUAL}` 落 `types.inventory`（`Constants` 在 types，不能反向依赖 domain），
  `Constants` 收成 `BUCKET_KEY_PREFIX + bucketKey(segment, invId, bucketId)`（新 key
  `bs:inventory:bucket:{quota|virtual}:{invId}:{bucketId}`）；`IInventoryRepository` **26 → 10** 个方法、
  每个带 `segment` 并 `switch` 分派两个 DAO（**PO / DAO / mapper XML 零改动**）；domain 6 个按表 entity
  换成 3 个公共投影 VO（ledger / bucket / deductDetail）；`IVirtualInventoryService` +
  `IPhysicalQuotaService` 与两个 Impl 删除，合成 `IInventoryQuotaService` / `InventoryQuotaServiceImpl`
  （`preDeduct` / `preDeductWithFallback(primary, fallback)` / `confirmIssued`）；warmup/audit 保留孪生入口、
  merge 仍是 `mergeOnce()` 每秒全表扫、确认仍是同步守卫转移（4312 留 11）；抽奖侧两个端口实现只换委托目标。
  全量 **93 tests 绿**（92 + 桶 key 形状 1）。触发 / 合并算法 / 确认语义 / schema 均未动。
- 2026-10-18 **票 06 已落地**（[票 06](issues/06-warmup-and-audit-unify.md) resolved）——
  `IInventoryWarmupService` 收成 `warmup(segment, invId)` + `warmupAll()`、`IInventoryAuditService` 收成
  `audit(segment, invId)`（两对孪生入口删除）；`InventoryAuditVO` 换成 spec §8 七字段，
  恒等式统一成 `waiting + issued == mergedDeduct(type=1)`（虚拟 waiting 恒 0）、Redis 等式只算 `type=1`，
  `pendingClaimQty` 作为独立读数先落地（本票恒 0，手工 `type=2` 行验证 > 0 且不进两条等式，供 11 对照）；
  对账聚合查询下推到 SQL（走 `idx_pending`，按桶单查、不引入非表 PO）；
  `inventoryBucketWarmup` 参数改 `[quota|virtual:]invId`（`physical:` 退休），段名反查收成
  `InventorySegmentVO.fromCode`。全量 **96 tests 绿**（93 + 实物段故障 1 + `type=2` 对账 1 + Redis 漂移三态 1）。
  触发 / 合并算法 / 确认语义 / schema 零改动。
- 2026-10-18 **票 08 已落地**（[票 08](issues/08-time-cell-merge-engine.md) resolved；**07 尚未落地**）——
  合并从「每秒全表扫 → 内存分组 → id 清单翻转」换成「一个 key 最老已封口格 + `affected == c1+c2` 判重」：
  仓储新增 `mergeCell`（聚合 → 条件翻转 → 桶守卫 → 台账结算，一格一事务；票 08 只结算 s1）、
  `queryOldestUnmergedCellTime`、`listUnmergedMergeKeys`；`mergeKey`（≤10 格、失败即停不续命）+
  `backstopOnce`（每段 200 key，`FORCE INDEX (idx_pending)`）替换 `mergeOnce`；`inventoryMerge` job 本票改成
  每秒调 `backstopOnce`；旧 `mergeBucketGroup` / `listUnmergedDetailRows` / `mark*DetailsMerged(id 清单)` 与
  对应 mapper SQL 全删。新增 6 个合并引擎用例（未封口不合 / 最老优先 / `affected != cnt` 回滚 / 10 格上限 /
  幂等 / 两段 key 选取），既有 4 个测试类改用 `sealUnmergedDetails` 造已封口格。全量 **102 tests 绿**
  （96 + 6）。`type = 2` 的 s2 分支、续命、消费者与 handler 改名仍留给 10 / 11。
  （**07 未落地**：新用例暂放 `InventoryMergeEngineIntegrationTest`，07/10 收口时再迁进唯一参数化类。）
- 2026-10-18 **票 09 已落地**（[票 09](issues/09-deduct-emits-merge-message.md) resolved；**07 仍未落地**）——
  预扣的 Lua 改成两键两参、返回 `{code, notify}`（Redisson `ReturnType.MULTI` 收 `List<Long>`），
  端口 `tryDeduct` 返回 `DeductAttemptVO(deducted, notifyMerge)`、`release` 连去重 key 一起删；
  `Constants` 新增 `MERGE_DEDUPE_KEY_PREFIX` / `mergeDedupeKey(...)` / `INVENTORY_MERGE_TOPIC` /
  1000ms 窗口 / 延迟级别 1，`types.inventory.InventoryMergeMessage` 落三字段载荷；
  `IInventoryRepository.insertDeductDetail(segment, InventoryDeductAggregate(detail, mergeMessageOrNull))`
  在**一个** `TransactionTemplate` 里插明细 + 可选 outbox（`delay_level = 1`），`notify = 0` 只插明细；
  `preDeduct` 撞键 / 异常都 `release(bucketKey, mergeDedupeKey, qty)`（回补 + 删 key）；Lua 未知返回码抛故障。
  新增 `InventoryMergeTriggerIntegrationTest` 6 例（发消息 / 固定窗口 TTL 与不刷新 / 同事务（outbox 失败 →
  明细回滚）/ 撞键回补删 key / 其它异常回补 / `-1 -2` 零副作用）；`RaffleShippingOutboxIntegrationTest`
  的 topic 字面量换成常量。全量 **108 tests 绿**（102 + 6）；schema 未动。消费者 / 续命 / 旧每秒 job 仍留 10。
- 2026-10-18 **票 10 已落地**（[票 10](issues/10-consume-and-backstop.md) resolved；**07 仍未落地**）——
  `types.inventory.InventoryMqConstants.INVENTORY_MERGE_CONSUMER_GROUP = "bs-marketing-inventory-merge-group"`；
  `trigger/inventory/listener/InventoryMergeConsumer` 直调 `mergeKey`（解析失败 / 缺字段 / 未知 segment
  log + 丢弃，业务异常不吞、抛给 broker）；`mergeKey` 正常收尾时若该 key 仍有任何 `state = 0` 行
  （超 10 格剩下的或只剩当前这一秒没封口），经仓储 `existsUnmergedDetail` + `enqueueMergeMessage`
  入队同 key、`delay_level = 1` 的续命消息；失败（竞态 / 不变量破坏）不续命。删 `InventoryMergeJob`，
  新建 `InventoryMergeBackstopJob`（`inventoryMergeBackstop`，60s）；`xxl-job-tasks.sql` 先 DELETE
  `inventoryMerge` 旧注册再加 backstop（`trigger_status = 1`）。`mergeOnce` 全仓无残留；注册器与消费线程
  模型零改动。新增 3 例（消费者直调 / 续命两跳 / 兜底 key 选取 200 上限升序）+ 2 条既有断言
  （10 格续命、守卫失败不续命）。全量 **111 tests 绿**（108 + 3）。07/12 待落地。
- 2026-10-18 **票 11 已落地**（[票 11](issues/11-async-claim-confirm.md) resolved；**07 仍未落地**）——
  `confirmIssued` 改成「回查预扣行的 `bucket_id`（走 `uk_biz_type`）→ 同事务落 `type = 2` 明细 + **恒 1 条**
  `delay_level = 1` 合并消息（撞唯一键当成功回放、不再发消息）」；仓储的 `confirmIssued` 换成
  `queryDeductBucketId` + `insertClaimDetail`，明细 VO / 聚合成 `InventoryDetailVO` / `InventoryDetailAggregate`
  （预扣与确认同形状、`type` 由写入方法决定）；`mergeCell` 补 ⑥ `waiting -s2 / issued +s2`
  （0 行 → `log.error` + 整格回滚、不续命；`type = 2` 不进桶递减）；`ResponseCode` 删 `4312`，
  抽奖侧六处注释去掉「4312 / 同步转移」口径。全量 **114 tests 绿**（111 + 4 新 − 1 改写）。
  文档仍留 12。
- 2026-10-18 **票 12 已落地**（[票 12](issues/12-docs-and-full-regression.md) resolved；**07 仍未落地**）——
  文档全部收口：`inventory.md` 重写成现状档（6 张表、明细新列/唯一键/两索引、`segment` 一条路径、
  触发 = 1 秒延迟消息 + 60 秒兜底、合并四条约束、两段恒等式、Redis 桶 key + `merge:` 去重 key、
  `state` 两态、确认异步化）；`README` / `draw.md` / `lottery.md`（定时任务、事件、重投口径、4312 退休、JOIN 表名）/
  `timeout.md`（两处 `inventoryMerge`、表数）同步；`backend/docs` 三处 **22 → 20 张** + 两处旧表名；
  `CONTEXT.md` 改 3 条、新增「库存配额」「时间格合并」；ADR `0005` 加 front matter 标 superseded
  （点明反转的是 Considered Options 第 2 条与第 4 条的升级路径，原文保留）、新建 `0011-inventory-merge-delay-message.md`
  （7 条决策 / 6 条被否 / 7 条 consequences，不抄 SQL）；`sql.md` §2 两段过期说明改写；比对脚本 20 张逐表逐列全等。
  回归：重建 `bs_marketing_test` 后 **114 tests 绿**（02:30），不重建再跑 **仍 114 绿**（01:48）；
  三条 grep 在源码/文档/SQL 上全 0（`mergeOnce` 只剩 gitignore 的运行时日志栈）。

## Not yet specified

- **多实例**：消费者同组共享、天然可并行；`inventoryMergeBackstop` 用 `FIRST` 单实例跑。本期不评估
  多实例下的兜底吞吐——单体阶段流量不需要。
- **Redis cluster**：今天单节点；上 cluster 时桶 key 与去重 key 需同 hash tag（`{invId}`），代码注释里已留提示。
- **`pendingClaimQty` 的告警阈值**：本仓库没有监控设施，先只作为对账读数；接监控时再定阈值与连续 N 轮策略。
- **`bs-raffle-shipping-group` 的历史命名例外**：不趁本次改名（会动 MQ 消费组和运维口径），新组按
  `bs-<service>-<topic>-group` 起。
- **实物档「兑换单」实体**：当前确认动作的 `biz_id` 就是 `draw_record.record_id`，没有独立兑换单行；
  真要独立单号时另立一票（本 spec 不为它预留列）。
- **跨表业务幂等的残余窗口**（票 03 已补失败路径预检与跨表兜底回放，但仍有两条不自动发生的运维/并发窗口）：
  ① 同 token 并发下，赢家的物理明细仍在提交中（微秒级），输家的失败路径预检可能没看见它而走到兜底；
  ② 首笔已走兜底后，管理端重新预热物理桶（`inventoryBucketWarmup` 不自动跑）或切换奖品 inv 时，
  同一 token 的重试可能先扣到物理段。要彻底关死需要跨表认领态/锁（spec §1 非目标），本 effort 不做。

## Out of scope

- 分布式锁 / 认领态（`state=2`）/ `FOR UPDATE` / 用 DB 唯一键做消息去重。
- 入库 / 盘点流水表、出库单追溯（`draw_record` + `quota.issued_qty` 已够）。
- 多桶分摊 / 关桶 / Redis 重建看门狗（沿用 `marketing` map 的既有排除）。
- 前端改动（库存无 HTTP 面）、抽奖域的业务语义改动（只加 `recordId` 透传）。
- 把 `points` / `raffle` 的 outbox 迁到新表或新 sender（只加一列，不动结构）。
