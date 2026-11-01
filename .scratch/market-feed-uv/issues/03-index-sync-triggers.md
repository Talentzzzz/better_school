# 03 · 索引表同步触发点（两域状态机 + 浏览旁路）

Type: task
Status: resolved
Blocked by: 01

## Task

照帖子票 03 的先例，把两域索引表的同步护栏收进仓储本体（同事务直写，全库内）：

1. **闲置（商品）状态机全覆盖**：`ProductMapper.xml` 的每个条件 UPDATE 流转点——通过 ON_SALE（ODKU 行诞生/翻回，feed_time 只在 INSERT 侧落）、拒绝/撤回/转草稿/下架/到期/售出/锁定/在售改内容回审（state 快照 UPDATE，行不存在 0 行静默）、重提再通过翻回。逐点核对 `ProductStatusVO` 七态的可达边，漏一个就是索引漂移。
2. **跑腿状态机全覆盖**：先拍板**索引行驻留口径**（大厅公开可见 = PUBLISHED？还是行常驻、state 快照、读路径过滤？倾向后者——13 态流转频繁，照帖子做法行常驻 + state 快照，读路径 WHERE 挑状态），然后照口径在每个影响 state 的流转点（发布、锁定、确认、取消、拒绝、删除、超时扫描等）旁带同步写。跑腿流转点分散在 ErrandOrderServiceImpl 条件 UPDATE + XXL-Job 超时扫描 + MQ 消费者，逐点清点。
3. **浏览旁路 ±N**：票 02 的合并事务内旁带索引行 view_count ±N（纯 UPDATE 不 upsert，0 行落 warn 交票 06 重建 job，照帖子先例）。
4. **价格列同步**：商品在售改价立即生效不进审核（现状语义）——索引表 price 列必须同事务跟上，否则价格排序漂移；这是帖子没有的新触发点类型，重点测。
5. 集成测试：每个流转点一行断言（行诞生/翻回/快照/0 行静默），改价同步、浏览旁路 ±N、0 行 warn 各一用例。

## Comments

## Answer

### 驻留口径拍板（跑腿）

**行常驻 + state 快照**（票面倾向项成立）：索引行在**审核通过事务**诞生（唯一诞生点——彼时悬赏必已支付冻结，满足 `price NOT NULL`，与票 01 回填行集 PUBLISHED 口径一致），此后 13 态流转点全部旁带快照同步、行永不删除，读路径（票 04）`WHERE state = 'PUBLISHED'` 挑可见行。审核通过前的流转点**不带写**（行必然不存在，带了也只能 0 行）：`payById`（待审）、`reviewRejectById`（驳回，REJECTED 只可从待审可达）、`cancelPendingPayment`（待支付超时）、`submitDraft` / `updateDraft` / `softDeleteDraft`（草稿侧）。`cancelByPublisher` 例外地带写——它跨待审与公开可见两段，从 PUBLISHED/LOCKED/CONFIRMED 取消时行存在须快照，从 PENDING_REVIEW 取消时 0 行静默。

### 商品 approve 语句偏离票面文字的拍板

票面「feed_time 只在 INSERT 侧落」是从帖子先例复制的——帖子的 ODKU 分支不可达（NORMAL 不可重回待审）。商品的 ODKU 是**正常路径**（relist / 在售改内容回审 / 下架后重提都会再通过），照票 01 定稿的 schema 注释（「行诞生落第一次、**重提再通过刷新**」）与票 06 重建 job 的 MAX(APPROVE) 口径，**ODKU 分支刷新 feed_time 与 price 快照**——不刷新反而是违规（与回填/重建口径打架，且回审期混提的新价恢复可见即生效会漂移）。SQL 形态 `INSERT ... SELECT ... ON DUPLICATE KEY UPDATE`（从主表行取 price 快照，`VALUES()` 沿用 NotificationMapper 先例）；ODKU 不碰 view_count（浏览旁路独占维护）。跑腿侧照 schema 注释 ODKU 只翻 state（跑腿无重提再过审）。

### 交付物

- **新 SQL ×7**（`IProductDao` + `ProductMapper.xml`、`IErrandDao` + `ErrandMapper.xml`，索引语句与主表语句同 DAO 同 mapper）：
  - 商品 `upsertFeedIndexOnApprove`（INSERT...SELECT，ODKU 刷 state/feed_time/price）与跑腿同名语句（ODKU 只翻 state）；
  - 两域 `updateFeedIndexState`：状态快照镜像（商品 LOCKED/SOLD/PENDING_REVIEW/DRAFT/REJECTED/DELETED、跑腿 13 态减 PUBLISHED），纯 UPDATE 不 upsert，行不存在 0 行静默；
  - 商品 `updateFeedIndexPrice`：在售改价旁路（立即生效不进审核的现状语义，价格排序键同事务跟上）；
  - 两域 `updateFeedIndexViewCount`：浏览合并事务旁路 ±N，纯 UPDATE 不 upsert。
- **商品侧 14 个流转点带写（TradeRepository）**，全部 gated 在条件 UPDATE 命中后（**gating 是正确性要求不是优化**：release/markSold/lock 等多前态或防御性语句 0 行时若无条件写索引，会把已删/已售商品在 feed 复活——测试用例「删除后 release 不复活」钉死）：approve（诞生/翻回）、reviewReject、软删 deleteById、markSold、relist、submitDraft、withdraw、takeDown（到期扫描同走此口）、rejectToDraft、editOnSaleContent、updateOnSalePrice（价格同步）、lock、release、lockToSold。updateDraft 不带写（草稿态改价不漂移：期间行不可见，恢复可见由 approve 语句的 price 快照兜住）。多写后事务护栏收进仓储本体（REQUIRED 合流外层事务：reviewApproveWithSearchSync / createOrderWithLock / cancelOrderWithSearchSync 等），本次顺带把原先裸调 DAO 的 10 个方法补齐了事务。
- **跑腿侧 15 个流转点带写（ErrandRepository）**：approve（行诞生）、grab、acceptLockedById、abandonLockedById、releaseLockedByTimeout（超时中心回调）、pickupById、deliverById、confirmById、cancelByPublisher、cancelByTaker、disputeById、arbitrateCancelById、arbitrateCompleteById、markTimeout（XXL-Job 超时扫描走 markTimeoutWithSearchSync 同口）、autoCompleteById（送达超时扫描）。MQ 消费者链路（ErrandPaidConsumer → payWithPaidMessage）全在行诞生前，无需带写。原先裸写（条件 UPDATE + saveEvent 两写无事务）的方法一并在方法内补 `transactionTemplate.execute`，与类注释「每个写动作由仓储方法自身事务原子」对齐；code-review 顺带把未触及索引的 `reviewRejectById` 也补了事务（存量缺口，邻位反衬）。
- **浏览旁路 ±N**（照帖子 `syncFeedIndexLikeCount` 先例）：两域 `flushViewGroup` 合并事务内第 4 步——`view_count += INSERT 影响行数`，`inserted > 0` 才带写；命中 0 行（未过审商品被本人浏览、存量异常）落 warn 交票 06 重建 job，不在写路径凭空补行。`updateOnSalePrice` 的索引旁路 0 行同样落 warn（票面只对浏览旁路要求，方向一致无害）。
- **不触发清单**：商品 updateDraft（见上）、bindBizId、insert；跑腿 payById / reviewRejectById / cancelPendingPayment / submitDraft / updateDraft / softDeleteDraft（全部行诞生前）；两域浏览明细/计数表三步本身（票 02 既有）。

### 验证

- 新增 `ProductFeedIndexSyncIntegrationTest`（trade 包，4 用例）：① 通过诞生（price 快照随行）+ 回审期不可见 + 重提再通过 ODKU 刷新 feed_time（首轮拨回一小时前钉死刷新语义，DATETIME 秒级精度下同秒通过区分不出）与 price；② 状态快照逐点（takeDown→DRAFT / submitDraft→PENDING_REVIEW / lock→LOCKED / release→ON_SALE / lockToSold→SOLD / markSold→SOLD / relist→PENDING_REVIEW / 软删→DELETED）+ 删除后 release 不复活 + 从未过审全程不产行（驳回/转草稿/重提/撤回）；③ 在售改价索引同事务跟上；④ 浏览旁路 ±N + 全重复组幂等不动点 + 未过审商品浏览不补行（窄表照常推进）。
- 新增 `ErrandFeedIndexSyncIntegrationTest`（errand 包，3 用例）：① 通过行诞生（reward 快照、feed_time、view_count=0）+ 驳回不诞生；② 行常驻快照逐点（grab→LOCKED / abandon→PUBLISHED / releaseLockedByTimeout→PUBLISHED / pickup→PICKED_UP / deliver→DELIVERED / confirm→COMPLETED / cancelByTaker→PUBLISHED / cancelByPublisher→CANCELLED / dispute→DISPUTED / arbitrateCancel→CANCELLED / arbitrateComplete→COMPLETED / markTimeout→CANCELLED / autoComplete→COMPLETED）+ 过审前取消不产行；③ 浏览旁路 ±N + 幂等不动点 + 未过审订单浏览不补行。
- code-review 双轴：standards 轴无硬违例（reviewRejectById 事务补齐与 long/Long 统一已采纳；「execute+if 骨架域内重复」「state 字符串穿 DAO」为判断题，照帖子先例保留）；spec 轴确认 feed_time 刷新偏离票面文字是正确判断（不刷新反而与票 01/06 口径冲突）、补了仲裁完成向断言、updateOnSalePrice 的 0 行 warn 判定为可接受细化。
- 新测 7/7 绿；全量 bs-app 套件 **375 tests, 0 failures**（BUILD SUCCESS，368 存量 + 本票净增 7，见 `mvn-ticket03-full.log` / `mvn-ticket03-full2.log`；测试容器全量 Exited 需先 `docker start`，首跑 Nacos 未起报「写入 Nacos 共享配置失败」属环境故障）。
