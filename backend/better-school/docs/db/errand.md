# 跑腿表

> 业务：跑腿订单（恒单人单，组队形态已随票 04 整体下线）；归属模块：errand

|表名|说明|
|----|----|
|errand_order|跑腿订单主表（先付后审状态机）|
|errand_order_event|订单状态流转记录（只插不改，纠纷溯源事实源）|

**主键口径（uuid-biz-id 起）**：`id` 是纯内部物理自增，业务 ID 为 `errand_order_id`；业务 ID 是应用层发号的 UUID 字符串（`VARCHAR(36)`，`UUID.randomUUID()` 在 insert 前生成、随行一次写入），与物理 `id` 永不同值、物理 id 永不对外（对外与 `errand_order_event.order_id`、钱包流水 `order_id` 都用业务 ID）。存量数据不迁移（删库重建），历史「插 0 回写」割接脚本已退役。

# errand_order

> 业务：跑腿订单（先付后审 + 单人单）；归属模块：errand

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键，不对外|
|errand_order_id|varchar(36)|UNIQUE(uk_errand_order_id), NOT NULL|业务 ID（对外唯一标识，UUID 字符串）|
|publisher_id|varchar(36)|NOT NULL, INDEX(idx_publisher)|下单方用户 ID|
|taker_id|varchar(36)|NULL, INDEX(idx_taker)|接单方用户 ID（抢单＝锁单时即时填充；**放弃 / 接单方取消**时清空——发布方取消与超时只置终态、保留 taker_id 作历史留痕；恒单人单）|
|lock_seq|int|NOT NULL, DEFAULT 0|锁单轮次（每次抢单 +1；超时中心复合 bizId={orderId}:{lock_seq} 的轮次与回调守卫比对值；**确认接单 / 放弃都不改它**，故离开 LOCKED 后陈旧回调天然打不中）|
|type|varchar(20)|NULL|EXPRESS_PICKUP/BUY/DELIVER/OTHER（草稿允许为空，提交时必填）|
|description|varchar(200)|NULL|任务描述（同步初筛覆盖；草稿允许为空，提交时必填）|
|pickup_location|varchar(100)|NULL|取件地点（草稿允许为空，提交时必填）|
|delivery_location|varchar(100)|NULL|送达地点（草稿允许为空，提交时必填）|
|reward|decimal(10,2)|NULL|悬赏（校园币，支付时冻结；草稿允许为空，提交时必填）|
|time_limit_hours|int|NULL|时限（2/4/12/24 小时，支付时起算；草稿允许为空，提交时必填）|
|images|varchar(1000)|NULL|配图 URL 列表（JSON 数组，最多 6 张，MinIO；创建/草稿链路校验上限）|
|deadline|datetime|NULL|超时时间（支付确认时刻 + 时限；支付确认 SQL `TIMESTAMPADD` 按数据库时钟起算，Java 只传时限小时数）|
|state|varchar(20)|NOT NULL, DEFAULT 'PENDING_PAYMENT'|DRAFT(草稿)/PENDING_PAYMENT/PENDING_REVIEW(已付款待审)/PUBLISHED/LOCKED(锁单中)/CONFIRMED(已确认，原 TAKEN 改名)/PICKED_UP/DELIVERED/COMPLETED/CANCELLED/DISPUTED/REJECTED/DELETED(软删，仅草稿可达)；先付后审：支付转待审，审核通过发布，拒绝转 REJECTED 退款（原 `status` 列改名；详见 ErrandStatusVO / ADR-0003）|
|taker_canceled|tinyint(1)|NOT NULL, DEFAULT 0|接单方取消幂等位（取消过为 1，重试判定用，防重复扣分）|
|version|bigint|NOT NULL, DEFAULT 0|乐观锁版本（预留）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

## 索引
- `uk_errand_order_id (errand_order_id)`
- `idx_state_deadline (state, deadline)`：超时扫描
- `idx_publisher` / `idx_taker`：我的订单（发布/接单两向）

## 关联
- 1:N → errand_order_event（order_id = errand_order_id）
- 资金：`virtual_wallet` 冻结/退款/入账 + `wallet_detail` 流水（FROZEN/UNFROZEN/INCOME），见 [user](user.md)
- 超时任务注册见 [outbox](outbox.md)

## 注意
- images 与 post/product 的 images 同一存储约定：JSON 数组字符串，空为 NULL；序列化在 ErrandRepository
- 状态流转全部走条件 UPDATE（ErrandMapper.xml），**不要无条件 UPDATE state**
- 冻结/退款/入账一律按悬赏（ErrandOrderEntity.getTotalReward）
- 完成结算：发布方确认 / 送达超 24h 自动完成 → DELIVERED→COMPLETED（单条条件 UPDATE，无部分完成态）；结算 = unfreeze 悬赏 + income 接单方一条流水（`uk_order_type` 保证不重复）
- **草稿链（票 02）**：内容列可空（草稿允许缺省，提交时 case 层校验必填）；`POST /orders/draft` 落 DRAFT（不注册支付超时）、`PUT /orders/{id}/draft` 全量编辑（仅 DRAFT 行生效、作者/状态位不在 SET 列）、`POST /orders/{id}/submit` 条件 UPDATE DRAFT→PENDING_PAYMENT（带 `publisher_id` 守卫）**同事务**写 `SUBMITTED` 事件与 `timeout_task` 直插（`ERRAND_PAY_TIMEOUT`，到期 = 提交时刻 + 15min；内嵌超时中心票 03）、`DELETE /orders/{id}` 软删 DRAFT→DELETED。软删与草稿不出现在公开列表（`selectPage`/`count` 带 `state NOT IN ('DRAFT','DELETED')`），DELETED 亦从我的两向列表排除
- **锁单链（票 03，抢 ≠ 开工）**：`grab` 条件 UPDATE `PUBLISHED→LOCKED`（`state + taker_id IS NULL + deadline>NOW()` 三重校验；同 UPDATE `lock_seq+1`，事件 `LOCKED`）；`acceptLocked` `LOCKED→CONFIRMED`（仅持单人、不改 `lock_seq`，事件 `CONFIRMED`）；`abandonLocked` `LOCKED→PUBLISHED` 清 `taker_id`（仅持单人，**不扣信用分 / 不动 `taker_canceled` / 不改 `lock_seq`**，事件 `LOCK_ABANDONED`+`reason_type=TAKER_ABANDON`）。发布方取消白名单含 `LOCKED`（退款、不扣接单方分）；`DISPUTED` 前态集合不含 `LOCKED`；执行时限扫描（`selectTimeoutOrderIds`）与 `markTimeout` 的状态集都含 `LOCKED`——**锁单期未开工＝未产生承诺，`handleOrderTimeout` 对 LOCKED 不扣信用分**（与放弃同口径）
- **锁单超时释放（票 04）**：`releaseLockedByTimeout` 条件 UPDATE `state='LOCKED' AND lock_seq=?`（**轮次守卫**）→ `PUBLISHED` 并清 `taker_id`，同事务落 `LOCK_TIMEOUT` 事件（`LOCKED→PUBLISHED`，actor `SYSTEM`）。命中 0 行＝幂等成功零副作用（已确认 / 已放弃并重抢到新轮次 / 已取消 / 不存在）；释放**不扣信用分、不动 `taker_canceled`、不改 `lock_seq`**（轮次只由抢单推进），也不落搜索同步（已发布单的搜索文档维持 `PUBLISHED` 态）。被释放的接单方在清空 `taker_id` **之前**读出，供发一条结果通知
- **防占单冷却（票 05）**：`abandonLocked` 主效应成功后按「放弃时刻」写滑动窗口 Redis ZSET `bs:errand:grab-abandon:{userId}`（member=`{orderId}:{lockSeq}`），近 24h 达 3 次 → 置 2h 冷却 `bs:errand:grab-cooldown:{userId}`（`SET NX EX 7200`）+ **重置计数窗口** + WARN 日志；`grab` 条件 UPDATE 之前读冷却 403 拒抢。**Redis 是判定加速层、不是事实源**——事实源仍是 `errand_order_event`（`actor_id + event_type='LOCK_ABANDONED' + create_time >= now-24h`，走 `idx_actor_type_time`），Redis 报错时回落该查询；Redis 写失败 = 少计 = 宽恕，不引入对账。超时未确认（`LOCK_TIMEOUT`）**不计**（不可控）；放弃**不扣信用分**（口径正交：信用分只记违约，冷却只记滥用）
- 超时注册（内嵌超时中心票 03：两处都在业务事务内**直插 `timeout_task` 分表**，超时行随业务事务同生同死；同键三分支幂等 = 新插入 / 在途不改期 / 终态复活）：① 创建订单 / 提交草稿 → `task_type=ERRAND_PAY_TIMEOUT`，`biz_id=orderId`，到期 = 创建/提交时刻 + 15min；② 抢单 → `task_type=ERRAND_LOCK_TIMEOUT`，`biz_id={orderId}:{lockSeq}`（**复合键**，每次锁单一个独立键，陈旧任务只影响旧键），到期 = **锁单时刻** + 5min（与支付超时「从创建时刻起算」口径不同）。旧 bs-errand-pay-timeout 延迟消息链路不再有新行（保留的 errandTimeoutScan / ErrandPayTimeoutConsumer 属待下线旧链路）
- 争议链：DISPUTED（`reason_type=DISPUTE`）→ 管理员仲裁置 CANCELLED 或 COMPLETED（`reason_type=ARBITRATION`），仲裁同样落事件行，全链可从本表加 errand_order_event 回溯
- 信用分扣减（接单后取消/超时）经 `user_credit_detail`（`uk_user_reason_ref` 幂等），ref_id = 订单业务 ID

# errand_order_event

> 业务：跑腿订单状态流转记录（只插入不修改；同订单内 id 递增即流转顺序）；归属模块：errand

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键，同订单内递增即流转顺序|
|order_id|varchar(36)|NOT NULL, INDEX(idx_order)|跑腿订单**业务 ID**（= errand_order.errand_order_id，UUID 字符串）|
|event_type|varchar(30)|NOT NULL|事件类型：CREATED/SUBMITTED(草稿提交)/PAID/**LOCKED(抢单)**/**CONFIRMED(确认接单)**/LOCK_ABANDONED(锁单放弃)/LOCK_TIMEOUT(锁单超时，票 04)/PICKED_UP/DELIVERED/COMPLETED(发布方确认完成)/CANCELLED/TAKER_CANCELLED/DISPUTED/ARBITRATED/TIMEOUT/PAY_TIMEOUT/REVIEW_APPROVED/REVIEW_REJECTED/AUTO_COMPLETED/DELETED(软删)|
|from_state|varchar(20)|NULL|变更前状态；创建事件为 NULL，**取消/超时/纠纷等由服务端直接置终态的事件也写 NULL（`to_state=CANCELLED/DISPUTED`），不要用「NULL = 创建事件」判定**|
|to_state|varchar(20)|NOT NULL|变更后状态|
|actor_id|varchar(36)|NULL|操作人用户 ID（仅 SYSTEM 事件允许 NULL）|
|actor_role|varchar(20)|NOT NULL|操作方 PUBLISHER/TAKER/SYSTEM/ADMIN|
|reason_type|varchar(50)|NULL|原因类型，仅取消/退出/争议/仲裁类事件写入：PUBLISHER_CANCEL / TAKER_CANCEL / **TAKER_ABANDON（锁单放弃）** / DISPUTE / ARBITRATION / REVIEW_REJECT（审核驳回）|
|reason|varchar(200)|NULL|原因补充说明（用户输入，提交时先过同步初筛；SYSTEM 事件恒 NULL；审核驳回存拒绝理由）|
|images|varchar(1000)|NULL|随附凭证图 URL 列表（JSON 数组，MinIO，INSERT 时定型不可改）|
|create_time|datetime|NOT NULL|事件时间|
|update_time|datetime|NOT NULL|固定等于写入时间，无更新业务|

## 注意
- **只插不改**：无 UPDATE / DELETE 语句，append-only；同订单按 `id` 升序即状态变更顺序（禁用 `create_time` 排序——同秒并发会乱序）
- 与订单状态变更同事务写入：条件 UPDATE 命中 1 行才插事件，重放/并发失败不留假事件
- 它是纠纷溯源的事实源：订单行只有当前状态，历史只能从本表回溯；`TAKER_CANCELLED` 与 `taker_canceled` 幂等位配合，防重试重复扣分
- 索引：`idx_order (order_id)` —— 按订单回溯事件流；**`idx_actor_type_time (actor_id, event_type, create_time)`**（票 05）—— 防占单 Redis 降级时按「actor + `LOCK_ABANDONED` + 近 24h」计数，是本表唯一的 actor 维度查询路径
