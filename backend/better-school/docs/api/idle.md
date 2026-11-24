# 二手交易模块接口

## POST /api/idle/products

发布商品（发布即对外可见）

- Auth: 需要登录；信用分 <60 被 case 层拒
- Body: title(≤30)/description(≤500)/images/price(≥0.01)/tradeLocation/category
- 成功: `{ productId }`，状态 ON_SALE（发布即对外可见并同步搜索）
- 流程: ProductController → IdlePublishCase（信用分门槛）→ ProductServiceImpl → product 入库

## POST /api/idle/products/draft · PUT /api/idle/products/{productId}/draft

保存 / 编辑商品草稿（草稿箱停靠态，不做信用分门槛）

- Auth: 需要登录
- Body: 同发布但**全部字段可缺省**（未填完就存），仍校验长度/下限（title≤30、images≤9、price≥0.01 等）
- 编辑仅 DRAFT 可用；他人草稿 → 403
- 成功: 保存返回 `{ productId }`（status=DRAFT），编辑返回空 data
- 流程: ProductController → IdlePublishCase（直通）→ ProductServiceImpl → product 入库/条件 UPDATE（WHERE status='DRAFT'）

## POST /api/idle/products/{productId}/submit

提交草稿（与直接发布同门槛，发布即对外可见）

- Auth: 需要登录（本人）
- 流程: 取本人 DRAFT 草稿 → 信用分门槛 → 完整性校验（title/price/tradeLocation/category 必填，缺 → code 500 "草稿内容不完整…"）→ 条件 UPDATE DRAFT→ON_SALE
- 成功: 空 data，状态 ON_SALE

## POST /api/idle/products/{productId}/takedown

上架后主动下架商品进草稿箱（ON_SALE → DRAFT）

- Auth: 需要登录（本人）；非 ON_SALE → code 500 "仅在售商品可下架"
- 流程: idle 域事务内条件 UPDATE + 同事务落搜索同步消息（消费方删 ES 文档，退出对外可见）
- 成功: 空 data，状态 DRAFT，可编辑后重新提交（expire_at 刷新重新获得 30 天窗口）

## PUT /api/idle/products/{productId}

上架后编辑商品（编辑语义：提交即时生效）

- Auth: 需要登录（本人）；非 ON_SALE → code 500
- Body（全字段可选，null = 不改）: title(≤30)/description(≤500)/images(≤9)/price(≥0.01)
- 编辑语义: 全字段提交即时生效（状态不变、无通知，同事务落搜索同步）；两者都空 → code 400 "无变更内容"
- 成功: 空 data

## GET /api/idle/products

对外商品列表（仅 ON_SALE，推荐页）

- Auth: 免登录
- Query: category/minPrice/maxPrice/sort(time|time_asc|hot|price_asc|price_desc)/cursor/page/pageSize(≤50)
- 读路径（market-feed-uv 票 04）：**无筛选**走 `product_feed_index` 索引序扫描 + keyset 游标分页——
  `cursor` 非空则忽略 `page`，响应带 `cursor` 才续页、`total = -1`（未知，不再精确 COUNT）；
  排序键 = 热度（浏览 UV 数降序，同值按 `create_time` 降序、再按业务 id 降序三段收敛——uuid-biz-id 票 05 起）/ 进入可见轮时刻（时间升/降序同索引正反向扫描，同值按业务 id 收敛）/ 价格；
  **带 category 或价格区间** → 旧直查（索引表无筛选列），精确 total、`cursor` 恒 null、按 page 续页；
  索引查询异常 warn 降级旧直查（同筛选路径口径）。游标是域内编解码的不透明串，换排序复用 / 篡改 → code 400；
  索引行与计数窄表的漂移（浏览旁路命中 0 行的 warn）由 `productFeedIndexRebuildJob` 手动重建兜底——
  清空重灌索引 + 计数校准到明细行数，一个事务（market-feed-uv 票 06）
- 成功: 分页 `ProductResponseDTO`（status 恒 ON_SALE；`cursor` 见上）
- 已读灰显（market-feed-uv 票 05）：登录用户每项带 `viewed`——页内 product_id 批量回查
  `product_view_history`（主键前缀 `(user_id, product_id)`，一页一次 IN，不做 N+1），命中即已读；
  **未登录恒 false**（市集匿名可浏览，灰显只对登录用户有意义）。`viewed` 只在本接口填充，
  详情与其他列表（我的发布 / 他人主页 / 收藏）为 `null`。索引路径与筛选 / 降级路径同一口径

## GET /api/idle/products/{productId}

商品详情

- Auth: 免登录可看（DRAFT 仅卖家本人可见，他人 404——domain `productDetail` 判定；ON_SALE/LOCKED/SOLD 对外可见）
- 成功: `ProductResponseDTO`（登录时带 favorited/favoriteCount）；`viewCount` 是浏览 UV（market-feed-uv 票 02：按用户去重，一人一商品只计一次，重复进详情只刷 `viewed_at` 不加数，**匿名访问不计入**——语义变更，旧口径匿名也累加 PV；落库走异步合并，允许丢，读 `product_view_count` 加速列）；`viewed` 恒 null（仅推荐列表填充）

## GET /api/idle/products/mine

我的发布（卖家视角）

- Auth: 需要登录
- 成功: 分页 `ProductResponseDTO`，含自己的草稿商品（排除软删）

## GET /api/idle/users/{userId}/products

作者主页「闲置」tab：某用户**在售**商品（卖家视角，访客口径；UI 改版票 06）

- Auth: 免登录
- Query: page/pageSize(≤50)，按 create_time 倒序
- 过滤: `state='ON_SALE'`（草稿/锁定/已售一律不外露）
- 成功: 分页 `ProductResponseDTO`
- 无隐私开关：在售商品本就在 `/api/idle/products` 公开可搜，主页不另设隐藏

## DELETE /api/idle/products/{productId} · POST .../sold · POST .../relist · POST .../favorite · DELETE .../favorite

删除（软删，放行 ON_SALE 与 DRAFT；在售删除同步搜索文档、草稿删除不同步）/ 标记已出 / 重新上架（仅 SOLD，转回 ON_SALE）/ 收藏 / 取消收藏；后四者要求商品处于 ON_SALE（具体状态机见 `ProductServiceImpl`）

## GET /api/idle/favorites

我的收藏分页（排除软删商品）

## 商品订单系列（`ProductOrderCase`，商品下单要求 ON_SALE；LOCKED 托管状态机见代码）

### POST /api/idle/orders?productId
下单（需登录，信用可操作账号）：商品锁定进订单，返回 `data = orderId`。

### POST /api/idle/orders/{orderId}/pay
支付（买家）：钱包冻结 `PRODUCT_FROZEN` 托管款，订单进入已付款——**两段式资金链路（ADR-0016）**：资金段冻结（流水先行，`uk_order_type` 幂等 + 可用余额条件扣减）由 case 层先行，余额不足直接失败、订单留待支付；翻转段「待支付 → 已支付」条件翻转为单仓储事务。翻转失败时订单留待支付、货款悬置冻结（长款中间态），由 `productFundOrphanScan` 孤儿扫描解冻收敛（fund-consistency 票 06，组合事务端口已废止）。

### POST /api/idle/orders/{orderId}/mark-delivered
卖家标记已发货。

### POST /api/idle/orders/{orderId}/confirm
买家确认收货：托管款解冻（`PRODUCT_UNFROZEN`）并入卖家余额（`PRODUCT_INCOME`），订单完成。

### POST /api/idle/orders/{orderId}/cancel
买家取消订单：已支付的解冻托管款退回（未支付的不动钱包），订单关闭——**已支付态为两段式（ADR-0016）**：取消翻转（含台账回补 + 搜索同步，单仓储事务）先行，翻转未命中抛业务异常、资金段尚未执行零残留；解冻退款（`uk_order_type` 幂等）由 case 层随后执行；翻转与解冻之间的宕机孤儿（订单已取消但货款悬置冻结）由 `productFundOrphanScan` 收敛（fund-consistency 票 07）；待支付取消无冻结、保持原单事务翻转。

### GET /api/idle/orders/{orderId}
订单详情（参与者可见），返回 `ProductOrderResponseDTO`。

### GET /api/idle/orders/mine?role=buy
我的订单分页：`role=buy` 买到的 / `role=sell` 卖出的，返回 `ProductOrderResponseDTO` 分页。
