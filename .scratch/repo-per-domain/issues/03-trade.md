# trade 域：两仓储合一 + 事务下沉

Type: task
Status: resolved
Blocked by:

## Question

- `IProductRepository`(35) + `IProductOrderRepository`(13) → `ITradeRepository` + `TradeRepository`（两个 DAO 字段：`IProductDao` + `IProductOrderDao`，另加既有的 `ObjectMapper`/`OutboxMessageStore`/`TimeoutRegisterOutboxStore`；`ProductRepository:37/73/108` 已有的事务保持 REQUIRED）。
- `ProductOrderServiceImpl:35` 现在同时注入两个仓储、共用 `:36` 的 `TransactionTemplate`，在 `:46` 下单（save + 锁商品 + 搜索同步 + 超时注册）、`:121` 确认（转单 + 锁售出）、`:155`/`:210` 取消（关单 + 释放商品）里跨两仓储写 —— 合并后每组收成一个 `ITradeRepository` 方法。
- 其余按「原样搬」：`ProductServiceImpl:124/181/227/276/299/315`、`ProductOrderServiceImpl:80`。

判据：bs 侧编译过；`TradeFullChainIntegrationTest`、`TimeoutRegisterOutboxIntegrationTest` 绿；`domain/trade` 只注入 `ITradeRepository`，不出现 `TransactionTemplate`。

## Answer

**1. 两仓储合一**

- `IProductRepository`(107 行 / 35 方法) + `IProductOrderRepository`(49 行 / 17 方法) → `ITradeRepository`（206 行）；实现 `TradeRepository` 持 `IProductDao` + `IProductOrderDao` + `ObjectMapper` + `OutboxMessageStore` + `TimeoutRegisterOutboxStore` + `TransactionTemplate`，两个被合并实现类的方法体原样搬入。**PO / mapper XML / DAO 一律未动**（`dao/product`、`dao/productorder`、`ProductMapper.xml`、`ProductOrderMapper.xml` 零 diff）。
- 落位 `infrastructure/adapter/repository/trade/`，与票 02 的 community 先例一致（不再是 `product`/`productorder` 两个聚合名分包）。
- 同名方法显式消歧（不消歧无法编译），订单侧带 `Order`：`save`→`saveOrder`、`findById`→`findOrderById`、`payById`→`payOrderById`、`markDeliveredById`→`markDeliveredOrderById`、`confirmById`→`confirmOrderById`、`cancelById`→`cancelOrderById`、`cancelPendingPayment`→`cancelPendingPaymentOrder`。商品侧（`save`/`findById`/`lock`/…）保留原名，跨域只读 port 与触发器调用点不必改语义。
- 商品侧转换器改名 `toEntity` → `toProductEntity`（与订单侧 `toOrderEntity` 成对，也是编译必需的消歧）。

**2. 事务下沉（11 处，全部原样搬）**

跨两表的原子用例：

| 原调用点 | 新 `ITradeRepository` 方法 |
| --- | --- |
| `ProductOrderServiceImpl:46` 下单四写 | `createOrderWithLock(orderEntity, productId, payTimeoutAt)` |
| `:80` 支付转单 + 搜索同步 | `payOrderWithSearchSync(orderId, buyerId, productId)` |
| `:121` 确认收货 + 锁售出 + 搜索同步 | `confirmOrderWithSearchSync(orderId, buyerId, productId)` |
| `:155` 买家取消 + 释放商品 + 搜索同步 | `cancelOrderWithSearchSync(orderId, buyerId, productId)` |
| `:210` 待支付超时 + 释放商品 + 搜索同步 | `cancelPendingPaymentWithSearchSync(orderId, productId)` |

商品状态变更 + 搜索同步：

| 原调用点 | 新 `ITradeRepository` 方法 |
| --- | --- |
| `ProductServiceImpl:124` 下架/到期 | `takeDownWithSearchSync(productId)` |
| `:181` 在售改内容回待审 | `editOnSaleContentWithSearchSync(productEntity)` |
| `:227` 审核通过转在售 | `reviewApproveWithSearchSync(productId)` |
| `:276` 浏览计数消息 | `saveViewCountMessage(productId)` |
| `:299` 软删 +（在售才）搜索同步 | `deleteWithSearchSync(productId, publiclyVisible)` |
| `:315` 标记已出 | `markSoldWithSearchSync(productId)` |

- 仓储内一律 `TransactionTemplate`（同一个 `config/TransactionConfig` bean，默认 REQUIRED）：既有跨表写从 HTTP 路径进入时开事务、从别处进入时与既有事务合流，与改造前语义等价 —— `OutboxMessageStore`/`TimeoutRegisterOutboxStore` 的 `registerSynchronization(afterCommit)` 因此仍在同一提交点触发（`TimeoutRegisterOutboxIntegrationTest` 的「事务回滚不落注册行也不发注册」「action_time 原样透传」两条覆盖）。
- 事务 lambda 里的异常（下单锁不到商品的 `BUSINESS_ERROR`、改内容/审核 0 行的 `BUSINESS_ERROR`）原样留在仓储 lambda 内，回滚语义不变；取消订单 release 0 行的 `log.warn("[ProductOrder] …")` 随方法体搬到仓储。
- `ProductOrderServiceImpl` 删掉 `payAndSync`/`confirmAndSync`/`cancelAndSync` 三个私有方法（内容进仓储），`holdPayTimeout` 的「读订单判 PENDING_PAYMENT」守卫留在域内、写操作下沉。
- `ProductRepository:37/73/108` 三处既有事务（`save` 双写回填业务 id、`submitDraft`、`editOnSaleContent`）随类搬入，REQUIRED 不变。

**3. 适配调用方**

`ProductCountConsumer` → `ITradeRepository.updateCountDelta`；`SearchQueryPort`（search 跨域只读）→ `ITradeRepository.findById`/`listByIds`/`listIdsAfter`；`CardRefQueryPort`（chat 跨域只读）→ `ITradeRepository.findById`；`UnboundedQueryCursorIntegrationTest` → `ITradeRepository.listIdsAfter`。

**判据结果**

- `mvn -o compile -pl bs-domain,bs-infrastructure,bs-trigger,bs-app -am`：BUILD SUCCESS。
- `TradeFullChainIntegrationTest`(9) + `TimeoutRegisterOutboxIntegrationTest`(3) 绿；三个 trade 测试类合计 38 项绿。
- 全量 `mvn -o test -pl bs-app -am`：**230 项 0 失败 0 错误**。
- `domain/trade` 只 import `ITradeRepository`；`TransactionTemplate` 在 `domain/trade` 0 命中；全仓（含 front）`IProductRepository`/`IProductOrderRepository` 0 命中。

**code-review 两轴复查（Standards + Spec）发现并已修掉的 3 处行为变更**

首版把「原样搬」搬成了近似——两轴复查各抓到同一批点，逐条回改成与 `144403f` 逐字等价：

1. **订单侧图片快照序列化了两次口径**：合并时两个 `toImagesJson` 撞名，只留下了商品侧那个（空列表 → `"[]"`、失败抛 `IllegalArgumentException`），把订单侧原有的「空列表 → SQL NULL、失败抛 `FrameworkException("商品图片快照序列化失败")`」吃掉了 —— 而 `docs/db/trade.md:81` 与 `outbox/trade` 既有行为都按 NULL 口径。已按原实现拆回 `toOrderImagesJson` / `toOrderImagesList`（商品侧仍是 `toImagesJson`/`toImagesList`），两口径各自保留（方法上写明为什么刻意不合并）。
2. **`action_time` 被算到了事务外**：首版把 `LocalDateTime.now().plusMinutes(15)` 当入参传进 `createOrderWithLock`，比改造前（在 lambda 内算）早了一次调用耗时。已改成传 `payTimeoutMinutes` 常量、到期时刻仍在事务 lambda 内计算。
3. **改内容/审核的 `BUSINESS_ERROR` 抛点移出了事务**：首版让 `editOnSaleContentWithSearchSync`/`reviewApproveWithSearchSync` 返回 `false`、由域内在事务提交后抛 —— 0 行虽无数据差，但由「回滚」变成「提交后抛」。已改回仓储方法内 `void` + 0 行即在 lambda 内抛 `BUSINESS_ERROR`，异常点与改造前一致。

另按复查意见去掉 3 个死参数：`payOrderWithSearchSync`/`confirmOrderWithSearchSync`/`cancelOrderWithSearchSync` 不再收 `productId`（实现内按 `orderId` 反查，与改造前 `payAndSync`/`confirmAndSync`/`cancelAndSync` 同口径），`cancelPendingPaymentWithSearchSync` 同样只收 `orderId`；`docs/db/outbox.md:51` 的「谁在写」同步指向 `TradeRepository.createOrderWithLock`。

**执行中的偏差（有意，非票面原文）**

- 合并后必须消歧 7 个订单侧方法名（同名同参无法在一个接口里共存）——票面「方法体原样搬进一个类」在实现层照做，仅公开方法名加域限定（票 02 同款做法）；复查提过「只有 `save`/`findById` 真撞名，其余 5 个限定属过度命名」，仍按「订单侧整段带 `Order`」保留一致性（`payOrderById` 与 `saveOrder`/`findOrderById` 同风格）。
- 事务方法集中放在 `ITradeRepository` 的「原子用例」两段（跨表用例 + 商品状态变更 + 搜索同步），而非散落在各商品/订单方法之间——票面只要求「每组收成一个方法」，未规定排列。

**遗留（不在本票）**

- `ProductCountConsumer` 仍直连仓储（map 的「触发层/case 层直连仓储是否收口」清单内，本次不动）。
- `TransactionConfig` bean 仍被 credit / errand / wallet / user 四个域的域服务注入，票 04 处理；`domain/trade` 已不注入。
- `save()`/`saveOrder()` 的单写事务边界：`save()` 保持仓储内事务（票面要求 `:37` 保持 REQUIRED），`saveOrder()` 不自己开事务（只在 `createOrderWithLock` 的跨表事务内被调用），与改造前一致。


