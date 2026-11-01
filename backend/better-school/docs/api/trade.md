# 二手交易模块接口

## POST /api/trade/products

发布商品（先审后售链路入口，提交即 PENDING_REVIEW）

- Auth: 需要登录；信用分 <60 被 case 层拒
- Body: title(≤30)/description(≤500)/images/price(≥0.01)/tradeLocation/category
- 同步初筛: 标题或描述命中敏感词 → code 500 "内容包含违规词汇，请修改后重试"，**商品不落库**
- 成功: `{ productId }`，状态 PENDING_REVIEW（仅卖家可见，审核通过才转 ON_SALE 对外可见）
- 流程: ProductController → TradePublishCase（信用分 → 同步初筛）→ ProductServiceImpl → product 入库

## POST /api/trade/products/draft · PUT /api/trade/products/{productId}/draft

保存 / 编辑商品草稿（草稿箱停靠态，不做初筛、不做信用分门槛）

- Auth: 需要登录
- Body: 同发布但**全部字段可缺省**（未填完就存），仍校验长度/下限（title≤30、images≤9、price≥0.01 等）
- 编辑仅 DRAFT 可用：PENDING_REVIEW → code 500 "仅草稿可编辑，待审中的商品可先撤回再修改"；他人草稿 → 403
- 成功: 保存返回 `{ productId }`（status=DRAFT），编辑返回空 data
- 流程: ProductController → TradePublishCase（直通）→ ProductServiceImpl → product 入库/条件 UPDATE（WHERE status='DRAFT'）

## POST /api/trade/products/{productId}/submit

提交草稿（与直接发布同门槛，回到先审后售流程）

- Auth: 需要登录（本人）
- 流程: 取本人 DRAFT 草稿 → 信用分门槛 → 完整性校验（title/price/tradeLocation/category 必填，缺 → code 500 "草稿内容不完整…"）→ 同步初筛（命中 → code 500，**草稿原样保留**）→ 条件 UPDATE DRAFT→PENDING_REVIEW
- 成功: 空 data，状态 PENDING_REVIEW

## POST /api/trade/products/{productId}/withdraw

撤回待审商品进草稿箱（PENDING_REVIEW → DRAFT）

- Auth: 需要登录（本人）；非待审态 → code 500 "仅待审中的商品可撤回"
- 成功: 空 data，状态 DRAFT，可编辑后重新提交

## POST /api/trade/products/{productId}/takedown

上架后主动下架商品进草稿箱（ON_SALE → DRAFT）

- Auth: 需要登录（本人）；非 ON_SALE → code 500 "仅在售商品可下架"
- 流程: trade 域事务内条件 UPDATE + 同事务落搜索同步消息（消费方删 ES 文档，退出对外可见）
- 成功: 空 data，状态 DRAFT，可编辑后重新提交回待审（review_round +1，expire_at 刷新重新获得 30 天窗口）

## PUT /api/trade/products/{productId}

上架后编辑商品（编辑语义：仅改价立即生效，改内容回待审）

- Auth: 需要登录（本人）；非 ON_SALE → code 500（PENDING_REVIEW "待审中不可编辑，可先撤回转草稿后修改" / REJECTED "商品已被拒绝，可转入草稿箱修改后重新提交"）
- Body（全字段可选，null = 不改）: title(≤30)/description(≤500)/images(≤9)/price(≥0.01)
- 编辑语义: 仅 price 非空 → 立即生效（状态不变、不进审核、无通知）；title/description/images 任一非空 → 新文字过同步初筛（命中 code 500 原样保留）→ 条件 UPDATE ON_SALE→PENDING_REVIEW（review_round +1，期间他人不可见、卖家本人可见，同事务落搜索同步），重审走统一裁决；混合提交时价格随行更新（价格非审核对象，重审被拒不回滚新价）；两者都空 → code 400 "无变更内容"
- 成功: 空 data

## POST /api/trade/products/{productId}/to-draft

被拒商品转入草稿箱（REJECTED → DRAFT，自救路径）

- Auth: 需要登录（本人）；非 REJECTED → code 500 "仅被拒绝的商品可转入草稿箱"
- 成功: 空 data，状态 DRAFT；编辑重提后产生第二次审核（review_round +1，content_review 两轮记录并存，拒绝理由取最新）

## GET /api/trade/products

对外商品列表（仅 ON_SALE，推荐页）

- Auth: 免登录
- Query: category/minPrice/maxPrice/sort(time|time_asc|hot|price_asc|price_desc)/cursor/page/pageSize(≤50)
- 读路径（market-feed-uv 票 04）：**无筛选**走 `product_feed_index` 索引序扫描 + keyset 游标分页——
  `cursor` 非空则忽略 `page`，响应带 `cursor` 才续页、`total = -1`（未知，不再精确 COUNT）；
  排序键 = 热度（浏览 UV 数）/ 进入可见轮时刻（时间升/降序同索引正反向扫描）/ 价格；
  **带 category 或价格区间** → 旧直查（索引表无筛选列），精确 total、`cursor` 恒 null、按 page 续页；
  索引查询异常 warn 降级旧直查（同筛选路径口径）。游标是域内编解码的不透明串，换排序复用 / 篡改 → code 400；
  索引行与计数窄表的漂移（浏览旁路命中 0 行的 warn）由 `productFeedIndexRebuildJob` 手动重建兜底——
  清空重灌索引 + 计数校准到明细行数，一个事务（market-feed-uv 票 06）
- 成功: 分页 `ProductResponseDTO`（status 恒 ON_SALE；`cursor` 见上）
- 已读灰显（market-feed-uv 票 05）：登录用户每项带 `viewed`——页内 product_id 批量回查
  `product_view_history`（主键前缀 `(user_id, product_id)`，一页一次 IN，不做 N+1），命中即已读；
  **未登录恒 false**（市集匿名可浏览，灰显只对登录用户有意义）。`viewed` 只在本接口填充，
  详情与其他列表（我的发布 / 他人主页 / 收藏）为 `null`。索引路径与筛选 / 降级路径同一口径

## GET /api/trade/products/{productId}

商品详情

- Auth: 免登录可看（DRAFT/PENDING_REVIEW/REJECTED 仅卖家本人可见，他人 404——domain `productDetail` 判定；ON_SALE/LOCKED/SOLD 对外可见）
- 成功: `ProductResponseDTO`（登录时带 favorited/favoriteCount；REJECTED 且本人带 `rejectReason`）；`viewCount` 是浏览 UV（market-feed-uv 票 02：按用户去重，一人一商品只计一次，重复进详情只刷 `viewed_at` 不加数，**匿名访问不计入**——语义变更，旧口径匿名也累加 PV；落库走异步合并，允许丢，读 `product_view_count` 加速列）；`viewed` 恒 null（仅推荐列表填充）

## GET /api/trade/products/mine

我的发布（卖家视角）

- Auth: 需要登录
- 成功: 分页 `ProductResponseDTO`，含自己的待审/草稿/被拒商品（排除软删）；REJECTED 条目带 `rejectReason`

## GET /api/trade/users/{userId}/products

作者主页「闲置」tab：某用户**在售**商品（卖家视角，访客口径；UI 改版票 06）

- Auth: 免登录
- Query: page/pageSize(≤50)，按 create_time 倒序
- 过滤: `state='ON_SALE'`（草稿/待审/被拒/锁定/已售一律不外露）
- 成功: 分页 `ProductResponseDTO`
- 无隐私开关：在售商品本就在 `/api/trade/products` 公开可搜，主页不另设隐藏

## DELETE /api/trade/products/{productId} · POST .../sold · POST .../relist · POST .../favorite · DELETE .../favorite

删除（软删，放行 ON_SALE 与 DRAFT；在售删除同步搜索文档、草稿删除不同步）/ 标记已出 / 重新上架（仅 SOLD，先审后售：转回 PENDING_REVIEW）/ 收藏 / 取消收藏；后四者要求商品处于 ON_SALE（具体状态机见 `ProductServiceImpl`）

## GET /api/trade/favorites

我的收藏分页（排除软删商品）

## 商品订单系列（`ProductOrderCase`，商品下单要求 ON_SALE；LOCKED 托管状态机见代码）

### POST /api/trade/orders?productId
下单（需登录，信用可操作账号）：商品锁定进订单，返回 `data = orderId`。

### POST /api/trade/orders/{orderId}/pay
支付（买家）：钱包冻结 `PRODUCT_FROZEN` 托管款，订单进入已付款。

### POST /api/trade/orders/{orderId}/mark-delivered
卖家标记已发货。

### POST /api/trade/orders/{orderId}/confirm
买家确认收货：托管款解冻（`PRODUCT_UNFROZEN`）并入卖家余额（`PRODUCT_INCOME`），订单完成。

### POST /api/trade/orders/{orderId}/cancel
买家取消订单：已支付的解冻托管款退回（未支付的不动钱包），订单关闭。

### GET /api/trade/orders/{orderId}
订单详情（参与者可见），返回 `ProductOrderResponseDTO`。

### GET /api/trade/orders/mine?role=buy
我的订单分页：`role=buy` 买到的 / `role=sell` 卖出的，返回 `ProductOrderResponseDTO` 分页。
