# 资金链路一致性收口（ADR-0014 模式推广）——实现前置调研报告

> 调研基线：spec.md（同目录）+ docs/adr/0014 + 全仓代码扫描。所有路径均为绝对路径，行号以当前工作区为准。
> 结论先行：跑腿/商品与钱包同库同进程，收口 = 组合事务端口（照 marketing 的 `PointsDeductPort` 形状）；抽奖孤儿预扣是唯一需要状态表式补偿 job 的缝。

---

## 0. 工程结构与硬性约定（实现者必读）

- 规范真源：`Z:/better_school/backend/CLAUDE.md`
  - L3-23 分层：`app → trigger → case → domain → types`；`xxx-case` 用例编排层「跨域组合顺序调用各域服务（**无跨域事务**）」；`xxx-infrastructure` 事务落位仓储。
  - L32 **事务落位在仓储内**：域服务不注入 `TransactionTemplate`；仓储方法用编程式事务（`TransactionTemplate`，传播恒 `REQUIRED`）收口。**端口/基础设施层适配器可以持有 TransactionTemplate**（PointsDeductPort 是先例）。
  - L33 **跨域一致性 = 幂等 + 重试，禁止 case 层包多域事务**；资金/信用分操作一律流水先行插入（INSERT IGNORE）。注意：本规格就是要推翻这一条里「case 层顺序调用」的部分，改为组合事务端口——但「域不写域 B 的表」不变。
  - L29-30 port 约定：消费方域 `domain/<X>/adapter/port/` 定义接口，实现落 `infrastructure/adapter/port/<域>/`。
  - L172 **SQL 层零 JOIN**：mapper 全部单表语句。
- 工程布局：better-school 单体 `Z:/better_school/backend/better-school/`（模块 bs-api / bs-app / bs-case / bs-domain / bs-infrastructure / bs-trigger / bs-types）；营销工程 `Z:/better_school/backend/marketing/`（marketing-* 同构）。
- mapper XML 唯一存放处：`Z:/better_school/backend/better-school/bs-app/src/main/resources/mybatis/mapper/` 与 `Z:/better_school/backend/marketing/marketing-app/src/main/resources/mybatis/mapper/`。

---

## 1. 钱包域（资金动作真源）

### 1.1 服务入口（domain 层）

接口 `Z:/better_school/backend/better-school/bs-domain/src/main/java/com/betterschool/domain/wallet/service/IWalletAccountService.java`：
- L23 `void freeze(String userId, BigDecimal amount, String orderId)` —— 冻结悬赏（跑腿，流水类型 FROZEN）
- L26 `freeze(..., WalletDetailTypeEnum type)` —— 带流水类型（跑腿 FROZEN / 商品 PRODUCT_FROZEN）
- L29 `freeze(..., WalletDetailTypeEnum type, int bizRound)` —— 带业务轮次（同一订单同类型多轮，uk 按 biz_round 区分）
- L32/L35/L38 `unfreeze` 三重载 —— 解冻退回（默认 UNFROZEN）
- L41/L44 `income` —— 接单方/接收方入账（默认 INCOME）
- L47 `getBalance`（钱包不存在懒建自愈）

实现 `.../domain/wallet/service/WalletAccountServiceImpl.java`：纯委托仓储（freeze L44-46、unfreeze L59-61、income L69-71）。

### 1.2 流水类型枚举

`.../domain/wallet/model/valobj/WalletDetailTypeEnum.java`（L8-19）：
`RECHARGE / FROZEN(托管冻结) / UNFROZEN(解冻退回) / INCOME(接单收入) / PRODUCT_FROZEN(商品托管冻结) / PRODUCT_UNFROZEN(商品托管解冻) / PRODUCT_INCOME(商品售卖收入)`

### 1.3 仓储实现（事务真源）

`Z:/better_school/backend/better-school/bs-infrastructure/src/main/java/com/betterschool/infrastructure/adapter/repository/wallet/WalletRepository.java`：
- L37-38 持有 `TransactionTemplate`（事务 Bean 在 `bs-app/.../config/TransactionConfig.java`，每工程一个）。
- **freeze（L79-93）**——新组合事务端口的参照形状：
  ```java
  transactionTemplate.execute(status -> {
      if (insertDetail(userId, type, amount, orderId, bizRound) == 0) { return null; }  // 流水先行：uk 冲突 → 幂等跳过
      if (walletDao.updateFreeze(userId, amount) == 0) {                                  // 余额条件 UPDATE
          throw new BusinessException(ResponseCodeEnum.PARAM_ERROR, "余额不足，无法完成操作"); // 整事务回滚（含流水）
      }
      return null;
  });
  ```
- unfreeze L105-115（流水先行 + `updateUnfreeze`）；income L122-132（流水先行 + `updateIncreaseBalance`）。
- L144-153 `insertDetail`：INSERT IGNORE 返回影响行数，0 = 幂等跳过。

### 1.4 幂等唯一键与条件 UPDATE SQL

- 表 DDL：`Z:/better_school/backend/better-school/sql/schema.sql`
  - `virtual_wallet` L850-861（`balance` / `frozen_amount` 两列，uk_user）。
  - `wallet_detail` L863-876：**`UNIQUE KEY uk_order_type (order_id, type, biz_round, user_id)`**（L874）；`order_id` = 关联订单业务 ID（UUID），`biz_round` 预留恒 0。
- `Z:/better_school/backend/better-school/bs-app/src/main/resources/mybatis/mapper/WalletDetailMapper.xml`
  - L5-7 注释：uk_order_type 冲突 → INSERT IGNORE 影响 0 行 = 幂等跳过；wallet_id/user_id 从 virtual_wallet 子查询取。
  - L8-13 `insertIgnoreDetail`（INSERT IGNORE ... SELECT FROM virtual_wallet WHERE user_id=...）。
- `Z:/better_school/backend/better-school/bs-app/src/main/resources/mybatis/mapper/WalletMapper.xml`
  - L17-23 `updateFreeze`：`SET frozen_amount = frozen_amount + ... WHERE user_id=... AND balance - frozen_amount >= #{amount}`（**可用余额条件扣减，0 行 = 不足**）。
  - L25-30 `updateUnfreeze`：`frozen_amount = GREATEST(0, frozen_amount - ...)`。
  - L32-37 `updateIncreaseBalance`：`balance = balance + ...`。

### 1.5 营销侧积分扣减如何包跨域动作（本规格组合事务端口的样板）

见 §2 的 `PointsDeductPort`：基础设施层 port 适配器持有 TransactionTemplate（REQUIRED），先写消费方域自己的单、再委托目标域服务；目标域仓储同为 REQUIRED 自动并入同一物理事务；业务失败 `setRollbackOnly`。

---

## 2. ADR-0014 购买次数链路（模式源头）

ADR 文档：`Z:/better_school/docs/adr/0014-draw-count-purchase-eventual-consistency.md`（决策要点：三态状态机无 FAILED/PROCESSING；组合事务在 raffle 侧 port；不让 PointsRepository 写 raffle 的表）。

### 2.1 状态表与状态机

- 枚举 `Z:/better_school/backend/marketing/marketing-types/src/main/java/com/betterschool/marketing/types/raffle/DrawCountPurchaseStateEnum.java`：`DEDUCTED(0) / DONE(1) / DEAD(2)`。
- 表 `draw_count_purchase_order`：`Z:/better_school/backend/marketing/sql/schema.sql` L77-95 附近（`purchase_order_id` = `hash(userId, token)`；`state/retry_count/next_retry_at/finish_time`；uk `uk_purchase_order_id`）。
- 幂等键工具 `.../marketing-types/src/main/java/com/betterschool/marketing/types/common/IdempotencyKeys.java`（`IdempotencyKeys.of(userId, token)`）。

### 2.2 组合事务端口（要照抄的形状）

`Z:/better_school/backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/raffle/adapter/port/points/PointsDeductPort.java`：
- 域接口：`.../marketing-domain/.../raffle/adapter/port/points/IPointsDeductPort.java`
- L40-65 `deductForOrder(DrawCountPurchaseOrderEntity order)`：`transactionTemplate.execute` 内① `raffleRepository.insertPurchaseOrder(...)`（L44，REQUIRED 并入）→ ② `pointsAccountService.deduct(userId, pointsCost, PointsReasonTypeEnum.REDEEM_DRAW_COUNT, purchaseOrderId)`（L46-48，幂等键 = 购买单 id）→ 成功 `ok` / 不足 `setRollbackOnly` + `insufficient` / 失败 `failed`（L49-62）。
- 类头注释 L18-29 说明为什么组合事务在基础设施 port 而非 case 层或积分侧。

### 2.3 领域服务（Stripe 幂等入口 + 补偿扫描逻辑）

`Z:/better_school/backend/marketing/marketing-domain/src/main/java/com/betterschool/marketing/domain/raffle/service/drawcount/RaffleDrawCountPurchaseServiceImpl.java`：
- L37 `COMPENSATE_MAX_RETRY = 5`；L39-41 `COMPENSATE_BACKOFF = 1/2/4/8/16 分钟`。
- L48-86 `purchaseDrawCount(userId, token, quantity)`：查单快路径 → 不存在则组合事务（L65 `pointsDeductPort.deductForOrder`）→ `DuplicateKeyException` 并发同 token 转幂等推进（L66-75）→ `advanceOrder`。
- L89-113 `compensateDueOrders(max)`：`listDuePurchaseOrders` → **抢占**（L94 `claimPurchaseOrder(order.getId(), nextBackoffAt(...))` 失败跳过）→ `completeOrder`（入账 + 置 DONE）→ 异常计数达上限 `markPurchaseOrderDead`（L105-109，只 log ERROR）。
- L116-128 `advanceOrder`（DONE 直接返回；DEDUCTED/DEAD 顺势补齐）+ `completeOrder`（`drawCountAccountService.creditForPurchase` + `finishPurchaseOrder`）。
- L131-134 `nextBackoffAt`（第 n 次尝试退避档，封顶 16 分钟）。
- 次数域：`.../drawcount/DrawCountAccountServiceImpl.java`——L32 `creditForPurchase(userId, purchaseOrderId, amount)`（桶 ref_id = 购买单 id，uk_user_reason_ref 幂等）、L58 `deductForDraw(userId, recordId)`。

### 2.4 仓储 + SQL（条件更新抢占 / 退避 / 死信 / 置 DONE）

`Z:/better_school/backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/raffle/adapter/repository/raffle/RaffleRepository.java`：
- L478 `insertPurchaseOrder`（REQUIRED 并入 port 事务）、L490 `listDuePurchaseOrders`、L495 `claimPurchaseOrder`、L500 `finishPurchaseOrder`、L505 `markPurchaseOrderDead`。

`Z:/better_school/backend/marketing/marketing-app/src/main/resources/mybatis/mapper/raffle/DrawCountPurchaseOrderMapper.xml`：
- L6-11 `insertOrder`（撞 uk 抛 DuplicateKeyException）。
- L22-29 `listDue`：`WHERE state = 0 AND (next_retry_at IS NULL OR next_retry_at <= NOW()) ORDER BY id ASC LIMIT`。
- L32-36 `claim`：`UPDATE ... SET retry_count = retry_count + 1, next_retry_at = ... WHERE id = ... AND state = 0 AND (next_retry_at IS NULL OR next_retry_at <= NOW())`（**抢占式条件更新**）。
- L39-43 `finish`：`SET state = 1 ... WHERE purchase_order_id = ... AND state <> 1`。
- L46-50 `markDead`：`SET state = 2 ... WHERE id = ... AND state = 0`。

### 2.5 job 触发层与任务 seed（新对账 job 注册要照这里）

- `Z:/better_school/backend/marketing/marketing-trigger/src/main/java/com/betterschool/marketing/trigger/raffle/job/DrawCountPurchaseScanJob.java`：L21 `SCAN_LIMIT = 100`；L25 `@XxlJob("drawCountPurchaseScan")`；只转调域服务 `compensateDueOrders`（**第二缝 = 触发层只测一条断言**的先例）。
- 任务 seed：`Z:/better_school/backend/marketing/sql/xxl-job-tasks.sql` L42-55——执行器 `marketing-executor`，cron `'0/30 * * * * ?'`、`DO_NOTHING`、`FIRST`、`SERIAL_EXECUTION`、幂等 INSERT（WHERE NOT EXISTS executor_handler）。新 job 注册进此文件（executor_group 的 @group_id 取法见 L25）。
- better-school 侧同形 seed：`Z:/better_school/backend/better-school/sql/xxl-job-tasks.sql`（outboxScan L30、searchIndexRebuildJob L43、feedIndexRebuildJob L58、commentFeedIndexRebuildJob L74、productFeedIndexRebuildJob L90、errandFeedIndexRebuildJob L103）。

---

## 3. 跑腿（Errand）订单域

### 3.1 状态机

`Z:/better_school/backend/better-school/bs-domain/src/main/java/com/betterschool/domain/errand/model/valobj/ErrandStatusVO.java` L17-20：
`DRAFT, PENDING_PAYMENT, PUBLISHED, LOCKED, CONFIRMED, PICKED_UP, DELIVERED, COMPLETED, CANCELLED, DISPUTED, DELETED`（枚举为字符串 name 落库 `errand_order.state`；流转图注释 L7-16）。

### 3.2 域服务（状态机 + 结果对象）

`Z:/better_school/backend/better-school/bs-domain/src/main/java/com/betterschool/domain/errand/service/ErrandOrderServiceImpl.java`（类头 L37-41 明确：跨域动作通过结果对象返回，由 case 层顺序编排）：
- **支付** L150-168 `payOrder`：PUBLISHED 重放幂等返回（L156-158）；`errandRepository.payWithPaidMessage`（L165，状态翻转 + outbox + feed 索引一个事务）；返回 `ErrandPayResult(totalReward)`——**冻结不在本方法内**。
- **确认收货** L384-397 `confirmOrder`：COMPLETED 幂等回放完整结算结果（L388-390）；`confirmWithSearchSync`；返回 `ErrandConfirmResult(publisherId, reward, incomes)`（接单方得悬赏，L636-644 `completionResult`/`completionIncomes`）。
- **取消** L400-430 `cancelOrder`：requestId 请求级幂等（L405-408）→ 角色路由 → `cancelByPublisher` L432-445（`needRefund = status != PENDING_PAYMENT`，L440）/ `cancelByTaker` L447-458（回 PUBLISHED，返回 `takerCanceled=true` 供 case 扣信用分）；终态/事件链幂等判定 L418-428（`EVENT_TAKER_CANCELLED` L53）。
- **仲裁** L477-509 `arbitrateOrder`：终态短路回放结算金额（L480-487）；`arbitrateWithSearchSync`；判接单方 = `completionIncomes`。
- **超时取消** L583-613 `handleOrderTimeout`：`committed = CONFIRMED || PICKED_UP`（L597，开工信用分扣减判据）；早到守卫抛失败重投（L601-603）；`markTimeoutWithSearchSync` 未命中也按失败重投（L606-608）；返回 `ErrandTimeoutResult(publisherId, takerId, totalReward, committed)`。
- **自动完成** L616-630 `handleAutoComplete`：DELIVERED 守卫 → `autoCompleteWithSearchSync` → 返回 `ErrandAutoCompleteResult`（含 `completionIncomes`）。
- **支付超时** L546-555 `handlePayTimeout`（PENDING_PAYMENT 守卫 + `cancelPendingPayment`）；**锁单超时** L558-575 `handleLockTimeout`（lock_seq 轮次守卫）。

### 3.3 case 编排层（本次「主缝」，也是当前的资金缺口所在）

`Z:/better_school/backend/better-school/bs-case/src/main/java/com/betterschool/usecase/errand/ErrandOrderCase.java`（类头 L34-37：本层不做事务）：
- L61-64 `payOrder`：**先** `errandOrderService.payOrder`（状态已翻转提交）**后** `walletAccountService.freeze(userId, amount, orderId)`（默认 FROZEN 类型）——两步之间无事务：冻结失败 = 订单已在大厅但悬赏未冻结；宕机 = 永久悬置。**这是收口点。**
- L157-166 `cancelOrder`：`needRefund` → `walletAccountService.unfreeze`（L161）；`takerCanceled` → `creditAccountService.applyChange(takerId, CreditReasonTypeEnum.ERRAND_TAKER_CANCEL, orderId)`（L164）。
- L148-155 `confirmOrder`：unfreeze 发布方 + 逐条 `income` 接单方（**已收敛样板：终态短路回放完整结算结果 + 流水 uk 去重**，spec Further Notes 称之为正面样板）。
- L172-179 `arbitrateOrder`：同 confirm 口径。
- L244-253 `handleOrderTimeout`（超时中心回调）：unfreeze 发布方（L249）+ `committed` 时 `creditAccountService.applyChange(takerId, CreditReasonTypeEnum.ERRAND_TIMEOUT, orderId)`（L251）——**重试被终态短路吞掉后第二段不补跑，是收口点**。
- L255-265 `handleAutoComplete`：unfreeze + income——**接单方悬赏收入可能永久不结算，是收口点**。
- L189-191 `handlePayTimeout`（无资金动作）；L199-219 `handleLockTimeout`（通知尽力而为）。

### 3.4 仓储事务边界（现状）

`Z:/better_school/backend/better-school/bs-infrastructure/src/main/java/com/betterschool/infrastructure/adapter/repository/errand/ErrandRepository.java`（接口 `.../bs-domain/.../errand/adapter/repository/IErrandRepository.java`：payWithPaidMessage L164、confirmWithSearchSync L170、cancelByPublisherWithSearchSync L173、arbitrateWithSearchSync L179、markTimeoutWithSearchSync L182、autoCompleteWithSearchSync L185、cancelPendingPayment L104）：
- L606-616 `payWithPaidMessage`：`transactionTemplate.execute` 内 `payById`（0 行抛 BUSINESS_ERROR）→ `saveErrandPaidMessage(orderId)`（outbox bs-errand-paid）→ `errandFeedIndexDao.upsertFeedIndexOnApprove(orderId)`（feed 索引行诞生）。**收口时把 freeze 并入这里或同形状的组合端口。**
- L643-652 `cancelByPublisherWithSearchSync`（cancelByPublisher + saveSearchSyncMessage）；L682-691 `markTimeoutWithSearchSync`（L374 `markTimeout` 条件 UPDATE + 事件 + 搜索消息）；L694-703 `autoCompleteWithSearchSync`（L397 `autoCompleteById` + 事件）；L631-640 `confirmWithSearchSync`；L667-679 `arbitrateWithSearchSync`。
- L317-328 `cancelByTaker`（条件 UPDATE + TAKER_CANCELLED 事件 + feed 索引翻回）。
- L388-394 `cancelPendingPayment`：**无显式事务**（两条语句各自提交）——支付超时关单路径。
- L176-184 `payById` / L186+ `grab`（L525-538 `grabWithLockTimeout`：状态翻转 + 锁单超时注册一个事务）；事件落库 `saveEvent` L857 起（先 `bumpEventSeq` 取号再插事件行）。
- L490-506 outbox 写点：`saveSearchSyncMessage`（L490）、`saveErrandPaidMessage`（L496，`Constants.ERRAND_PAID_TOPIC`）、`saveOrderPushEvent`（L501，**迁移提交后的独立小事务**，非同事务）；`OutboxMessageStore` 在 `.../bs-infrastructure/src/main/java/com/betterschool/infrastructure/outbox/OutboxMessageStore.java`。

### 3.5 条件状态翻转 SQL（errand_order 单表）

`Z:/better_school/backend/better-school/bs-app/src/main/resources/mybatis/mapper/ErrandMapper.xml`：
- L111-118 `payById`：`SET state='PUBLISHED' WHERE errand_order_id=... AND state='PENDING_PAYMENT' AND deadline > NOW()`（**已过期单拒绝支付**）。
- L202-209 `cancelByPublisher`（state IN ('PENDING_PAYMENT','PUBLISHED','LOCKED','CONFIRMED') → CANCELLED）。
- L213-221 `cancelByTaker`（CONFIRMED → PUBLISHED 清 taker_id）。
- L261-268 `markTimeout`（state IN ('PUBLISHED','LOCKED','CONFIRMED','PICKED_UP') AND deadline <= NOW() → CANCELLED）。
- L323-329 `autoCompleteById`（DELIVERED → COMPLETED）；L192-199 `confirmById`（publisher 守卫）；L245-259 `arbitrateCancelById`/`arbitrateCompleteById`（DISPUTED 守卫）。
- L164-172 `releaseLockedByTimeout`（lock_seq 轮次守卫）；L224-228 `bumpEventSeq`（事件序号取号，写一半）。

### 3.6 事件与 feed 索引

- `Z:/better_school/backend/better-school/bs-app/src/main/resources/mybatis/mapper/ErrandOrderEventMapper.xml`：L11-16 `insertOrderEvent`（errand_order_event：order_id/event_seq/event_type/from_state/to_state/actor/reason/request_id）；L25-31 `selectLatestEvent`；L34-40 `countTakerCancelByRequestId`（请求级幂等事实源）。
- `Z:/better_school/backend/better-school/bs-app/src/main/resources/mybatis/mapper/ErrandFeedIndexMapper.xml`：L57-65 `upsertFeedIndexOnApprove`（支付事务内 INSERT...SELECT + ODKU，feed 索引行唯一诞生点）；L69-73 `updateFeedIndexState`。

### 3.7 信用分扣减入口

- 接口 `Z:/better_school/backend/better-school/bs-domain/src/main/java/com/betterschool/domain/credit/service/ICreditAccountService.java` L19：`CreditChangeResult applyChange(String userId, CreditReasonTypeEnum reasonType, String refId)`。
- 实现 `.../credit/service/CreditAccountServiceImpl.java` L42-50（每次变更 = 仓储原子用例：分数 + 流水 + 封禁联动；流水 uk_user_reason_ref 幂等，见 `CreditRepository.applyChangeWithDetail` L86）。
- 枚举 `.../credit/model/valobj/CreditReasonTypeEnum.java`：L14 `ERRAND_TAKER_CANCEL(-20, "跑腿接单后取消")`、L15 `ERRAND_TIMEOUT(-20, "跑腿超时未完成")`。

### 3.8 超时回调分发（超时中心的入口形状）

`Z:/better_school/backend/better-school/bs-case/src/main/java/com/betterschool/usecase/timeout/TimeoutTaskCallbackDispatcher.java` L44-58：按 `taskType` 分发到 case 方法（`ERRAND_PAY_TIMEOUT → handlePayTimeout`、`ERRAND_LOCK_TIMEOUT → handleLockTimeout(orderId, lockSeq)`、`ERRAND_ORDER_TIMEOUT → handleOrderTimeout`、`ERRAND_AUTO_COMPLETE → handleAutoComplete`；未接入的 taskType 抛异常按失败重投进死信）。taskType 常量在 `.../bs-types/src/main/java/com/betterschool/types/common/Constants.java` L54-79。**超时取消/自动完成的资金收口改在 case 层编排后，此分发器无需变动。**

---

## 4. 商品（Goods/Product）订单域

### 4.1 状态机

`Z:/better_school/backend/better-school/bs-domain/src/main/java/com/betterschool/domain/trade/model/valobj/ProductOrderStatusEnum.java` L11-13：`PENDING_PAYMENT, PAID, DELIVERED, COMPLETED, CANCELLED`（注释 L7-10：取消 PENDING_PAYMENT 无退款 / PAID 需解冻退款）。

### 4.2 域服务

`Z:/better_school/backend/better-school/bs-domain/src/main/java/com/betterschool/domain/trade/service/productorder/ProductOrderServiceImpl.java`（类头 L21-26：跨表原子写收进 ITradeRepository 原子用例）：
- L54-70 `payOrder`：PAID/DELIVERED/COMPLETED 重放幂等返回（L59-64）；`tradeRepository.payOrderById`（L66）只翻订单状态；返回 `ProductPayResult(price × quantity)`（L171-174 `totalAmount`）。
- L84-101 `confirmReceived`：COMPLETED 幂等回放（L90-92）；返回 `ProductConfirmResult(buyerId, sellerId, amount)`。
- L104-123 `cancelOrder`：CANCELLED/COMPLETED 幂等（L110-113）；`needRefund = status == PAID`（L118）；`cancelOrderWithSearchSync`。
- L159-168 `handlePayTimeout`：PENDING_PAYMENT 守卫 + `cancelPendingPaymentWithSearchSync`。

### 4.3 case 编排层（资金缺口所在）

`Z:/better_school/backend/better-school/bs-case/src/main/java/com/betterschool/usecase/trade/ProductOrderCase.java`：
- L33-36 `payOrder`：先 `productOrderService.payOrder` 后 `walletAccountService.freeze(buyerId, amount, orderId, WalletDetailTypeEnum.PRODUCT_FROZEN)`——**两步无事务：买家钱未冻结订单已 PAID，是收口点**。
- L42-48 `confirmReceived`：unfreeze(PRODUCT_UNFROZEN) + income(PRODUCT_INCOME)——现有幂等口径已收敛，**不改**（spec 决策）。
- L50-56 `cancelOrder`：needRefund → unfreeze(PRODUCT_UNFROZEN)——**与跑腿取消同型，是收口点**。

### 4.4 仓储与 SQL

- `Z:/better_school/backend/better-school/bs-infrastructure/.../adapter/repository/trade/TradeRepository.java`：L555 `payOrderById`、L564 `markDeliveredOrderById`、L573 `confirmOrderById`、L664 `cancelOrderWithSearchSync`、L679 `cancelPendingPaymentWithSearchSync`。
- `Z:/better_school/backend/better-school/bs-app/src/main/resources/mybatis/mapper/ProductOrderMapper.xml`：L31-38 `payById`（`WHERE product_order_id=... AND buyer_id=... AND state='PENDING_PAYMENT'` → PAID）；L40-47 `markDeliveredById`；L49-56 `confirmById`；L58-65 `cancelById`（state IN ('PENDING_PAYMENT','PAID') → CANCELLED）；L67-73 `cancelPendingPayment`。

---

## 5. 抽奖链路与库存合并器（孤儿预扣缝）

### 5.1 抽奖主链路编排（营销工程）

`Z:/better_school/backend/marketing/marketing-domain/src/main/java/com/betterschool/marketing/domain/raffle/service/RaffleDrawServiceImpl.java`：
- L52-89 `draw(userId, token)`：① 幂等预检（L58 `queryOwnRecord`，recordId = `IdempotencyKeys.of(userId, token)` L309-311）→ ② 活动校验（L64-65）→ ③ 扣次数 L70 `drawCountAccountService.deductForDraw(userId, recordId)`（ref_id = 抽奖记录 id）→ ④ 责任链决策 `decide` L83 → ⑤ 库存预扣 `issueWithFallback` L85 → ⑥ 落库 `persistRecord` L87。
- **缝的位置（L159-196 `issueWithFallback` → L222-267 `persistRecord`）**：库存预扣经 `inventoryDeductPort.preDeductVirtual/preDeductPhysicalWithFallback`（委托合并器，**独立事务提交后返回**），随后 `persistRecord` 才把中奖记录落库（L256 `raffleRepository.insertDrawRecord(aggregate)`，撞 `uk_record_id` 回放 L257-265）。预扣成功而 persistRecord 未执行（宕机/异常）= **孤儿预扣**：明细行在、抽奖记录不在、次数已扣。
- 港口实现 `Z:/better_school/backend/marketing/marketing-infrastructure/.../raffle/adapter/port/inventory/InventoryDeductPort.java` L28-46：bizType 恒 `InventoryBizTypes.LOTTERY_DRAW`，bizId = recordId。

### 5.2 库存预扣明细表（孤儿预扣的扫描对象）

- 表 DDL：`Z:/better_school/backend/marketing/sql/schema.sql` L298-310 `draw_strategy_award_inventory_detail`：
  - `award_id`（= 台账行）、**`flow_type TINYINT`：0 分配转入 / 1 预扣 / 2 兑换确认**（L301）、`biz_type`（LOTTERY_DRAW / LOTTERY_CLAIM / AWARD_ALLOCATE）、`biz_id`（= 抽奖记录 id）。
  - `UNIQUE KEY uk_award_biz (award_id, biz_type, biz_id)`（L308）。
  - **注意：明细表没有独立 status 列**——「等待态」不是明细行的字段：PHYSICAL 预扣的量落在台账 `waiting_qty`、VIRTUAL 预扣直接进 `issued_qty`（见 5.4）。**孤儿预扣判定 = `flow_type=1 AND biz_type='LOTTERY_DRAW'` 的明细行，其 `biz_id` 在 `draw_record` 无对应行**（物理/虚拟都在此口径内；回补动作只对 PHYSICAL 的 waiting_qty 有意义，VIRTUAL 已进 issued_qty——实现者需按此事实裁剪收敛路径，spec 的「等待态 → 剩余量」转移 SQL 只对 PHYSICAL 台账成立）。
- 台账 `draw_strategy_award_inventory`（schema.sql L286 前后）：三列 `surplus_qty / waiting_qty / issued_qty` + `state`（0 open / 1 exhausted）。

### 5.3 合并器（5ms 批量窗口，有意设计、不动）

- `Z:/better_school/backend/marketing/marketing-domain/src/main/java/com/betterschool/marketing/domain/inventory/service/merge/InventoryMerger.java`：L86-103 `submitAndWait(InventoryDeductRequestVO)`（分片路由 → 缓冲 → 等 flush 回填）；L199-214 `flushBatch`（按动作×type×awardId 二次分组，一组一个事务）；窗口参数在 `MergeConfig`（`maxFlushDelayMillis`/`maxBatch`/`waitTimeoutMs`）。
- 落库真身 `Z:/better_school/backend/marketing/marketing-infrastructure/.../inventory/adapter/repository/InventoryQuotaFlushService.java`：L69-142 `flushDeductGroup`（撞键快照先行 → 批量 INSERT IGNORE 明细 → 台账条件扣减 → 扣不动「探一次再切」第二事务）；「流水先行 + 同事务」形状注释 L48-67。
- `Z:/better_school/backend/marketing/marketing-infrastructure/.../inventory/adapter/repository/InventoryRepository.java` L182-186 `flushDeductGroup` 委托（降级路径与合并器共用同一套 SQL）。

### 5.4 台账条件转移 SQL（现有样式）

`Z:/better_school/backend/marketing/marketing-app/src/main/resources/mybatis/mapper/inventory/AwardInventoryMapper.xml`：
- L24-32 `deductPhysical`：`surplus → waiting`，守卫 `AND (surplus_qty < 0 OR surplus_qty >= #{quantity})`（-1 = 不限量不守卫）。
- L35-43 `deductVirtual`：`surplus → issued`，守卫同形。
- L47-53 `transferWaitingToIssued`：`waiting -= q, issued += q WHERE ... AND waiting_qty >= #{quantity}`——**这就是「条件转移」的现有样式**；孤儿预扣回补要新增的 `waiting → surplus` 反向转移照此形状写（目前不存在）。
- L15-19 `selectSurplus`（探一次再切的剩余量来源）。

### 5.5 抽奖记录落库（后半段重放的目标）

- `Z:/better_school/backend/marketing/marketing-infrastructure/.../raffle/adapter/repository/raffle/RaffleRepository.java`：L209 `queryRecordByRecordId`、L337 `insertDrawRecord(DrawRecordAggregate)`（record + detail + 中奖事件 outbox 同事务；draw_record uk_record_id 见 schema.sql L186-205 附近）。
- 重放口径：同 (userId, token) 恒同 recordId（`RaffleDrawServiceImpl` L305-311）；孤儿预扣重放后半段 = 以原 bizId(recordId) 走 `persistRecord` 语义，扣次明细/预扣明细撞 uk 即幂等（`draw_count_detail` uk_bucket_ref：schema.sql L65-75；`draw_count_bucket` uk_user_reason_ref：L48-63）。

---

## 6. 测试缝与既有测试形状

### 6.1 营销侧先例（购买单三态状态机 + Stripe 语义重放 + 补偿扫描）

- `Z:/better_school/backend/marketing/marketing-app/src/test/java/com/betterschool/marketing/integration/raffle/DrawCountPurchaseOrderIntegrationTest.java`：
  - 头注释 L25-28：组合事务、Stripe 幂等重放、宕机态被补偿收敛、死信与人工自愈。
  - 表隔离：@BeforeEach TRUNCATE（L52-57）。
  - 用例：L109-133 正常购买（单与扣分同事务、ref_id 贯穿）；L135-146 积分不足整事务回滚；L150 Stripe 同 token 重放；L168 宕机残留被补偿收敛（直插 `insertPurchaseOrder(state=0)` 模拟宕机，L75-84）；L204-212 死信人工自愈。
  - 断言风格：直接调 `raffleDrawCountPurchaseService.purchaseDrawCount(...)` + JdbcTemplate 查库（**域服务即缝**，不走 HTTP）。
  - 支撑件：`.../integration/support/TestingEnvironment.java`、`RaffleApiTestBase.java`、`OutboxTransactionProbe.java`。
- 同目录其他相关：`DrawCountBucketIntegrationTest`、`RaffleDrawPipelineIntegrationTest`（主链路）、`.../integration/inventory/InventoryQuotaConservationIntegrationTest.java` 与 `InventoryFlushFailureIntegrationTest.java`（合并器失败注入形状）。

### 6.2 better-school 集成测试位置与触发方式

- 位置：`Z:/better_school/backend/better-school/bs-app/src/test/java/com/betterschool/integration/`（errand / trade / timeout / community / chat / marketing / job 等子包；unit 测试在 `.../integration/` 同级的 `unit/` 不存在——unit 在 `bs-app/src/test/java/com/betterschool/unit/`）。
- 触发方式：跑腿/商品测试**走 HTTP**（`CommunityApiTestBase` 的 `postJson/getJson`，如 `ErrandFullChainIntegrationTest` L37-83：支付→通知→可见、取消→解冻退款断言 `countWalletTransactions(orderId, "UNFROZEN")`）；**超时/回调类测试**用 `.../integration/support/TimeoutCallbackFixture.java`、`TimeoutTaskCallbackProbe.java` 直接驱动。
- 相关既有测试：`integration/errand/ErrandFullChainIntegrationTest`、`ErrandLockStateIntegrationTest`（信用分断言 L184/L218/L274）、`ErrandLockTimeoutIntegrationTest`、`ErrandPaidNotifyIntegrationTest`；`integration/trade/TradeFullChainIntegrationTest`、`ProductInventoryIntegrationTest`；`integration/timeout/TimeoutTaskEndToEndIntegrationTest` 等。
- spec 测试决策（spec.md L63-69）：只测外显行为；主缝 = 用例层公开 pay/cancel/timeout/complete 方法；失败注入点在端口委托（钱包动作）边界；job 只测「触发 → 调域扫描入口」一条断言；**交付口径：实现完成后不运行测试套件**。

---

## 7. 文档现状（API 宣称 vs 实现）

- 跑腿 API 文档 `Z:/better_school/backend/better-school/docs/api/errand.md`：
  - **L81：`支付（冻结悬赏），成功事务内转 PUBLISHED（发布即对外可见）+ outbox（bs-errand-paid → ...通知）`**——宣称「成功事务内」，现状冻结在 case 层事务之外，本次实现成真（spec Implementation Decisions 文档对齐项）。
  - L116-124 取消（PUBLISHED 解冻全额退款 / 接单方 CONFIRMED 取消扣 20 信用分）；L105-114 放弃与防占单；L126-128 取货。
- 商品侧 `Z:/better_school/backend/better-school/docs/api/trade.md`：
  - L104 `支付（买家）：钱包冻结 PRODUCT_FROZEN 托管款，订单进入已付款`——同样未声明同事务，收口后核对口径；L110 确认收货（解冻+入账）；L113 取消（已支付的解冻退回）。
- 钱包 `Z:/better_school/backend/better-school/docs/api/wallet.md`（L3/L9：可用余额 = balance − frozenAmount；表结构见 docs/db/user.md）。
- 术语风格真源 `Z:/better_school/GLOSSARY.md`：购买 L24-26（Avoid 兑换）、购买单 L28-30（单存在即积分已扣）、死信 L32-33（重试耗尽需人工介入）、对账 L35-36（定期比对找差异的兜底动作）——新词条（组合事务端口 / 孤儿预扣收敛 / 资金收口）照此「定义一句 + Avoid 行」格式落同一文件。
- `Z:/better_school/CONTEXT.md` 跑腿订单词条 L154-157（状态链与「支付成功（悬赏冻结托管）后即对外可见」）、预扣词条 L266-268（**「预扣成功而主链路落库失败的窗口不补偿——靠人工 SQL 暴露」**：本规格正是要推翻这一句，孤儿预扣对账 job 落地后该词条需同步修订）。
- ADR 引用的 `backend/marketing/docs/architecture/marketing-services.md` 已不存在（现 marketing/docs/ 下只有 api/{config,lottery,points}.md、db/{draw,inventory,points}.md、award-inventory-refactor-plan.md、README.md），ADR References 里的该路径已漂移，实现时无需追改。

---

## 附：收口点速查（实现者 checklist）

| # | 链路 | 缝的现状 | 收口动作 | 关键文件 |
|---|---|---|---|---|
| 1 | 跑腿支付 | `ErrandOrderCase.payOrder` L61-64 两步无事务 | 状态翻转 + 冻结流水 + 余额条件扣减 + outbox + feed 行一个物理事务 | ErrandOrderCase / ErrandRepository.payWithPaidMessage L606 / WalletRepository.freeze L81 / ErrandMapper.payById L111 |
| 2 | 跑腿取消（发布方） | `ErrandOrderCase.cancelOrder` L157-166 | 取消翻转 + 解冻一个事务（PENDING_PAYMENT 无退款分支保留） | ErrandRepository.cancelByPublisherWithSearchSync L643 |
| 3 | 跑腿超时取消 | `ErrandOrderCase.handleOrderTimeout` L244-253 | 翻转 + 解冻 + （committed 时）信用分扣减一个事务 | ErrandRepository.markTimeoutWithSearchSync L682 / ICreditAccountService.applyChange |
| 4 | 跑腿自动完成 | `ErrandOrderCase.handleAutoComplete` L255-265 | 翻转 + 解冻 + 接单方 income 一个事务 | ErrandRepository.autoCompleteWithSearchSync L694 |
| 5 | 商品支付 | `ProductOrderCase.payOrder` L33-36 | PAID 翻转 + PRODUCT_FROZEN 冻结一个事务 | TradeRepository.payOrderById L555 / ProductOrderMapper.payById L31 |
| 6 | 商品取消 | `ProductOrderCase.cancelOrder` L50-56 | 取消翻转 + 解冻一个事务 | TradeRepository.cancelOrderWithSearchSync L664 |
| 7 | 抽奖孤儿预扣 | 预扣（合并器事务）与落库（raffle 事务）之间的缝 | 新增对账 job（0/30 cron、条件更新抢占、1/2/4/8/16 退避、死信），扫 `draw_strategy_award_inventory_detail flow_type=1 AND biz_type='LOTTERY_DRAW'` 且无 draw_record 行且超 10 分钟；可重放 → 以原 recordId 补落库；不可重放 → 库存回补（PHYSICAL waiting→surplus 转移，照 transferWaitingToIssued L47 样式）+ 次数退还（draw_count_bucket 入账幂等） | RaffleDrawServiceImpl L159-267 / InventoryQuotaFlushService / AwardInventoryMapper.xml / xxl-job-tasks.sql L42-55（注册样板） |

不动清单（已收敛，回归保护对象）：跑腿确认收货（ErrandOrderCase.confirmOrder L148-155）、仲裁（L172-179）、商品确认收货（ProductOrderCase.confirmReceived L42-48）、钱包/信用各动作本体、库存合并器、超时注册、outbox 广播。
