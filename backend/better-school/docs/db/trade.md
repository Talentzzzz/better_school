# 二手交易表

> 业务：闲置商品 + 商品托管订单（卖家发货、买家确认）+ 订单状态流转留痕；归属模块：trade

|表名|说明|
|----|----|
|product|二手商品主表（先审后售状态机）|
|product_favorite|商品收藏（uk_user_product 防重）|
|product_order|商品托管订单（下单时对商品做快照）|
|product_order_event|订单状态流转记录（只插不改，纠纷溯源事实源）|

**主键口径（uuid-biz-id 起）**：`id` 是纯内部物理自增，业务 ID 为 `product_id` / `product_order_id`；业务 ID 是应用层发号的 UUID 字符串（`VARCHAR(36)`，`IIdGenerator` 在 insert 前生成、随行一次写入），与物理 `id` 永不同值、物理 id 永不对外。存量数据不迁移（删库重建），历史「插 0 回写」割接脚本已退役。

资金表（`virtual_wallet` / `wallet_detail`）见 [user](user.md)，商品与跑腿共用同一套钱包与流水。

# product

> 业务：闲置商品发布与先审后售状态机；归属模块：trade

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键，不对外|
|product_id|varchar(36)|UNIQUE(uk_product_id), NOT NULL|业务 ID（UUID 字符串）：product_favorite / product_order 引用它|
|user_id|varchar(36)|NOT NULL, INDEX(idx_user)|卖家用户 ID|
|title|varchar(30)|NULL|标题（草稿允许为空，提交时必填）|
|description|varchar(500)|NULL|描述|
|images|varchar(1000)|NULL|图片 URL 列表（JSON 数组，MinIO）|
|price|decimal(10,2)|NULL|价格（草稿允许为空，提交时必填）|
|trade_location|varchar(100)|NULL|交易地点（校区+场所）（草稿允许为空，提交时必填）|
|category|varchar(20)|NULL|分类 DIGITAL/BOOKS/DAILY/SPORTS/TICKETS/OTHER（草稿允许为空，提交时必填）|
|state|varchar(20)|NOT NULL, DEFAULT 'PENDING_REVIEW'|DRAFT(草稿)/PENDING_REVIEW(待审)/ON_SALE/LOCKED(交易中)/SOLD/REJECTED(审核未通过)/DELETED；先审后售，提交进待审，通过转在售，拒绝转 REJECTED（原 `status` 列改名，原 `OFF_SHELF` 终态已并入 DRAFT）|
|review_round|int|NOT NULL, DEFAULT 0|审核轮次：累计进入待审次数（直接发布=1，草稿提交/重新上架/在售改内容回审再 +1）；`content_review.review_round` 取此列|
|review_entered_at|datetime|NULL|最近一次进入待审时间（发布/草稿提交/改内容/重新上架时刷新；24h 待审超时提醒扫描锚点）|
|expire_at|datetime|NULL|到期下架时间（进入待审时刷新满 30 天，XXL-Job 扫描汇入草稿箱 DRAFT，可重提）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

> 已删除的列：`view_count`（浏览数，旧 PV 口径——market-feed-uv 票 02 换源到计数窄表 `product_view_count`、票 06 删列。真相源 `product_view_history`、加速列 `product_view_count`，存量 PV 废弃不换算 UV）。存量库不迁移（uuid-biz-id：删库重建），历史删列脚本已退役。

## 索引
- `uk_product_id (product_id)`
- `idx_category_created (category, create_time)`：分类列表
- `idx_user (user_id)`：我的发布

## 注意
- **商品状况描述维度已整体下线**：本表及全库都没有对应列，请求体/出参/断言的相关字段一并删除，不要再补
- 对外查询只放行 ON_SALE（列表 SQL 写死）；详情可见性在 domain `productDetail` 判定（DRAFT/PENDING_REVIEW/REJECTED/DELETED 仅本人）
- 状态流转一律条件 UPDATE（`WHERE product_id = ? AND state = ?`）；审核通过才落搜索同步消息（bs-search-sync），软删同样要落
- 审核：统一裁决经 `reviewApproveProduct`（PENDING_REVIEW→ON_SALE，事务内落搜索同步）/ `reviewRejectProduct`（→REJECTED，无资金环节）条件 UPDATE；REJECTED 详情/我的发布对卖家带出 rejectReason（content_review 按轮次取最新）
- 交易链 ON_SALE→LOCKED→SOLD，SOLD 重新上架回 PENDING_REVIEW；在售改内容回 PENDING_REVIEW（改价直接生效不改状态）

# product_favorite

> 业务：商品收藏关系；归属模块：trade

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键|
|user_id|varchar(36)|NOT NULL, INDEX(uk_user_product 首列)|收藏用户 ID|
|product_id|varchar(36)|NOT NULL, INDEX(idx_product)|商品业务 ID|
|create_time|datetime|NOT NULL|收藏时间|
|update_time|datetime|NOT NULL|更新时间|

## 注意
- `uk_user_product (user_id, product_id)`：`INSERT IGNORE` 幂等，重复收藏不报错；取消收藏 DELETE 该行
- `idx_product (product_id)`：商品维度的收藏数/收藏状态反查

# product_order

> 业务：商品托管订单（买家付款进入托管 → 卖家交付 → 买家确认放款）；归属模块：trade

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键，不对外|
|product_order_id|varchar(36)|UNIQUE(uk_product_order_id), NOT NULL|业务 ID（UUID 字符串）：对外暴露 + `product_order_event.order_id` + 钱包流水 `order_id` 都用它|
|buyer_id|varchar(36)|NOT NULL, INDEX(idx_buyer)|买家用户 ID|
|seller_id|varchar(36)|NOT NULL, INDEX(idx_seller)|卖家用户 ID|
|product_id|varchar(36)|NOT NULL, INDEX(idx_product)|商品业务 ID（引用：跳转/反查/对账用）|
|price|decimal(10,2)|NOT NULL|成交价快照|
|product_title|varchar(30)|NULL|商品标题快照（下单时写入，此后不可变）|
|product_description|varchar(500)|NULL|商品描述快照（下单时写入，此后不可变）|
|product_images|varchar(1000)|NULL|商品图片 URL 列表快照（JSON 数组，下单时写入，此后不可变）|
|product_trade_location|varchar(100)|NULL|交易地点快照（下单时写入，此后不可变）|
|product_category|varchar(20)|NULL|商品分类快照（下单时写入，此后不可变）|
|state|varchar(20)|NOT NULL, DEFAULT 'PENDING_PAYMENT'|PENDING_PAYMENT 待支付 / PAID 已支付待交付 / DELIVERED 已交付待确认 / COMPLETED 完成 / CANCELLED 取消（原 `status` 列改名）|
|create_time|datetime|NOT NULL|下单时间|
|update_time|datetime|NOT NULL|更新时间|

## 索引
- `uk_product_order_id (product_order_id)`
- `idx_state_created (state, create_time)`：超时扫描/列表
- `idx_buyer` / `idx_seller`：我的订单（买/卖两向）
- `idx_product (product_id)`

## 注意
- 托管资金走 wallet_detail 的 PRODUCT_FROZEN / PRODUCT_UNFROZEN / PRODUCT_INCOME 三类流水，唯一键 `uk_order_type` 保证「不重复结算/不重复入账」（见 [user](user.md)）
- 状态流转全部条件 UPDATE（ProductOrderMapper.xml），每次成功流转同事务插一行 product_order_event
- 支付超时：下单同事务直插 `timeout_task` 分表（内嵌超时中心票 03，`task_type=PRODUCT_ORDER_PAY_TIMEOUT`，原注册 outbox 已删），超时由系统条件 UPDATE 置 CANCELLED（`reason_type=PAY_TIMEOUT`）
- 快照列下单后不可变：商品改价/下架/删除都不影响已成立的订单

# product_order_event

> 业务：商品订单状态流转记录（只插入不修改；同订单内 id 递增即流转顺序）；归属模块：trade

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键，同订单内递增即流转顺序|
|order_id|varchar(36)|NOT NULL, INDEX(idx_order)|商品订单业务 ID（= product_order.product_order_id，UUID 字符串）|
|event_type|varchar(30)|NOT NULL|事件类型：CREATED/PAID/DELIVERED/COMPLETED/CANCELLED 等|
|from_state|varchar(20)|NULL|变更前状态；创建事件为 NULL，**由服务端直接置终态的取消事件也写 NULL**，不要用「NULL = 创建事件」判定|
|to_state|varchar(20)|NOT NULL|变更后状态|
|actor_id|varchar(36)|NULL|操作人用户 ID（仅 SYSTEM 事件允许 NULL）|
|actor_role|varchar(20)|NOT NULL|操作方 BUYER/SELLER/SYSTEM/ADMIN|
|reason_type|varchar(50)|NULL|原因类型，仅取消类事件写入：BUYER_CANCEL（买家取消）/ PAY_TIMEOUT（系统支付超时）|
|reason|varchar(200)|NULL|原因补充说明（用户输入，提交时先过同步初筛；SYSTEM 事件恒 NULL）|
|images|varchar(1000)|NULL|随附凭证图 URL 列表（JSON 数组，MinIO，INSERT 时定型不可改）|
|create_time|datetime|NOT NULL|事件时间|
|update_time|datetime|NOT NULL|固定等于写入时间，无更新业务|

## 注意
- **只插不改**：无 UPDATE / DELETE 语句，append-only；同订单按 `id` 升序即状态变更顺序（禁用 `create_time` 排序——同秒并发会乱序）
- 与订单状态变更同事务写入：条件 UPDATE 命中 1 行才插事件，故重放/并发失败不会留下假事件
- 它是订单状态历史的事实源：订单行只有当前状态，历史只能从本表回溯（对账 / 客服纠纷溯源都查这里）；商品订单没有独立争议态，取消只有买家自撤与系统支付超时两种
