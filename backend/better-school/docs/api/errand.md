# 跑腿模块接口

## GET /api/errand/orders

公开跑腿列表（浏览口径，大厅推荐页）

- Auth: 免登录
- Query: type 可选、status（默认 PUBLISHED）、sort（time|time_asc|hot|price_asc|price_desc，默认 time）、cursor、page/pageSize(≤50)
- 口径：**草稿（DRAFT）与软删（DELETED）单一律不出现**（显式传 `status=DRAFT/DELETED` 也不放行）
- 读路径（market-feed-uv 票 04）：**无 type 筛选且 status=PUBLISHED** 走 `errand_feed_index` 索引序扫描 +
  keyset 游标分页——`cursor` 非空则忽略 `page`，响应带 `cursor` 才续页、`total = -1`（未知，不再精确 COUNT）；
  排序键 = 热度（浏览 UV 数降序，同值按 `create_time` 降序、再按业务 id 降序三段收敛——uuid-biz-id 票 05 起）/ 进入大厅时刻（时间升/降序同索引正反向扫描，同值按业务 id 收敛）/
  悬赏（价格升降）；**带 type 或非 PUBLISHED 口径** → 旧直查，精确 total、`cursor` 恒 null、按 page 续页；
  索引查询异常 warn 降级旧直查。游标是域内编解码的不透明串，换排序复用 / 篡改 → code 400；
  索引行与计数窄表的漂移（浏览旁路命中 0 行的 warn）由 `errandFeedIndexRebuildJob` 手动重建兜底——
  清空重灌索引 + 计数校准到明细行数，一个事务（market-feed-uv 票 06）
- 已读灰显（market-feed-uv 票 05）：登录用户每项带 `viewed`——页内 order_id 批量回查
  `errand_view_history`（主键前缀 `(user_id, errand_order_id)`，一页一次 IN，不做 N+1），命中即已读；
  **未登录恒 false**（大厅匿名可浏览，灰显只对登录用户有意义）。`viewed` 只在本接口填充，
  详情与其他列表（我的发布 / 我的接单 / 作者主页）为 `null`。索引路径与筛选 / 降级路径同一口径
- 列表 `viewCount`（卡片浏览量展示的数据源）：索引路径取索引热度快照、筛选 / 降级直查读
  `errand_view_count` 计数窄表——两路数值同源，筛选不会把浏览量掉成 0

## GET /api/errand/orders/{orderId}

订单详情

- Auth: 免登录可看；**草稿（DRAFT）仅作者可见（其他人 404），软删（DELETED）对所有人 404**——domain `orderDetail` 判定
- 成功: `ErrandOrderResponseDTO`（订单全量字段 + images 列表 + `viewCount` 浏览 UV——market-feed-uv 票 02：按用户去重，一人一单只计一次，匿名不计入；异步合并写入允许丢；详情走计数窄表精确值；`viewed` 恒 null——仅大厅列表填充）
- **人数字段**：`reward` 为悬赏；`currentTakers` 当前接单人数（由 taker_id 判定，0/1）；草稿未填项为 null（前端自渲染占位）
- 流程: ErrandController → ErrandOrderCase.orderDetail（可见性）→ 组 DTO

## POST /api/errand/orders

创建订单（创建即 PENDING_PAYMENT）

- Auth: 需要登录；信用分 <60 被 case 层拒
- Body: type/description/pickupLocation/deliveryLocation/reward(≥10)/timeLimit（HOURS_2/HOURS_4/HOURS_12/HOURS_24/DAYS_7/DAYS_30/DAYS_365，存 time_limit_hours 小时数）/images(≤6 张 URL，来自 /api/files/upload)
- 成功: `{ orderId }`，状态 PENDING_PAYMENT
- 与「草稿入口」并存：本入口是直接发布（可选路径），草稿不是必经

## POST /api/errand/orders/draft

保存草稿（草稿入口：服务端落 DRAFT 私有态，与直接发布并存）

- Auth: 需要登录；**不做信用分门槛**（提交时才校验）
- Body: type/description/pickupLocation/deliveryLocation/reward(≥10，可缺省)/timeLimit/images(≤6) **全部可缺省**（未填完就存）
- 可缺省字段留空
- 落 DRAFT：不付款、不对外、**不注册支付超时**（支付钟只在提交后起算）
- 成功: `{ orderId }`，状态 DRAFT

## PUT /api/errand/orders/{orderId}/draft

编辑草稿（全量覆盖内容字段）

- Auth: 需要登录；仅作者、仅 DRAFT（他人 403，非草稿 500）
- Body: 同保存草稿（全量覆盖：缺省 = 该项留空）
- `publisherId` 与状态位不可改；**编辑不产生状态迁移**
- 成功: 无 data

## POST /api/errand/orders/{orderId}/submit

提交草稿：DRAFT → PENDING_PAYMENT（一次状态迁移）

- Auth: 需要登录；仅作者
- 校验: **必填完整性**（type/description/pickupLocation/deliveryLocation/reward≥10/timeLimit）+ 信用分门槛
- 缺项 → code 500 并**停留草稿**（可修改后重提）
- 通过 → 转 PENDING_PAYMENT，且 **15 分钟支付超时任务与状态迁移同事务注册**（草稿态不起支付钟）
- 幂等：非 DRAFT（已提交等）→ 500

## DELETE /api/errand/orders/{orderId}

删除订单（本期软删仅草稿可达）

- Auth: 需要登录；仅作者、仅 DRAFT
- 软删 DRAFT → DELETED（全站统一软删口径）；DELETED 从公开列表 / 详情 / 我的列表一律排除
- 非 DRAFT → 500（`本期 DELETED 仅从 DRAFT 可达`）

## POST /api/errand/orders/{orderId}/pay

支付（冻结悬赏），成功事务内转 PUBLISHED（发布即对外可见）+ outbox（bs-errand-paid → 作者「您的跑腿订单已支付成功，现已对外可见」通知）

## POST /api/errand/orders/{orderId}/grab

抢单（**抢 ≠ 开工**：抢到即锁单，不等于可以开工）

- Auth: 需要登录；信用分 <60 被 case 层拒（ensureOperable）
- **前置：防占单冷却**（票 05）——近 24h（滑动）内主动放弃 ≥3 次则 2h 内禁抢全量订单：`cooldownUntil` 命中直接 403「因近期多次放弃接单，您已被限制接单，请于 HH:mm 后再试」（判定在条件 UPDATE 之前；冷却键是用户维度，天然含「该单」）
- 条件 UPDATE 先到先得（`state='PUBLISHED' AND taker_id IS NULL AND deadline>NOW()` 三重校验）→ **LOCKED**，同 UPDATE `lock_seq=lock_seq+1`，同事务落 `LOCKED` 事件（`PUBLISHED→LOCKED`，actor TAKER）
- 同事务注册 **5 分钟锁单超时**（直插 `timeout_task` 分表，`task_type=ERRAND_LOCK_TIMEOUT`，`biz_id={orderId}:{lockSeq}` 复合键、`action_time=锁单时刻+5min`；内嵌超时中心票 03）：接单方 5 分钟内不点「确认接单」由超时中心到点回调自动释放回 `PUBLISHED`（清 `taker_id`，落 `LOCK_TIMEOUT`/SYSTEM 事件，给被释放的接单方发一条订单动态通知），他人可再抢。计时起点是**锁单时刻**（确认窗口从抢到手起算），与支付超时的「从创建时刻起算」不同
- 「我接的单」与作者主页即刻可见（显示「锁单中」）；公开列表因默认 `status=PUBLISHED` 天然不含
- 锁单期间**不落搜索同步**（已发布单的搜索文档维持旧 PUBLISHED 态、仍可见，`LOCKED` 从不进索引）
- 失败分流：冷却中 → **403**（含到期时刻）；抢自己的单 → 400「不能抢自己发布的订单」；订单不在 PUBLISHED（已被抢 / 已锁单 / 已取消等）→ 404「订单不存在或不可抢」；通过了状态前置但条件 UPDATE 仍命中 0 行（并发被抢 / deadline 已过）→ 500「手慢了，订单已被抢或已超时」
- 流程: ErrandController → ErrandOrderCase.grabOrder → domain grabOrder（前置 Redis 冷却判定 → 条件 UPDATE）

## POST /api/errand/orders/{orderId}/accept

确认接单（锁单 → 已确认）：接单方核对信息后自己点

- Auth: 需要登录；仅持单人（他人 403「只有持单人本人可以确认接单」）
- 条件 UPDATE `state='LOCKED' AND taker_id=?` → `CONFIRMED`，**不改 `lock_seq`**（离开 LOCKED 后陈旧超时回调天然打不中）
- 同事务落 `CONFIRMED` 事件（`LOCKED→CONFIRMED`，actor TAKER）
- 非 LOCKED → 500「订单不在锁单中，无法确认接单」

## POST /api/errand/orders/{orderId}/abandon

放弃（锁单 → 已发布）：设计内的正常动作

- Auth: 需要登录；仅持单人（他人 403）
- 条件 UPDATE `state='LOCKED' AND taker_id=?` → `PUBLISHED` 并清 `taker_id`；**不扣信用分、不落 `TAKER_CANCELLED` 事件、不改 `lock_seq`**
- 同事务落 `LOCK_ABANDONED` 事件（`LOCKED→PUBLISHED`，actor TAKER，`reason_type=TAKER_ABANDON`）
- 放弃后他人可再抢（下次抢单 `lock_seq` 继续 +1）
- **防占单计数**（票 05）：主效应成功后按「放弃时刻」写入滑动窗口（Redis ZSET `bs:errand:grab-abandon:{userId}`，member=`{orderId}:{lockSeq}`）；近 24h 达 3 次 → 置 2h 冷却（`bs:errand:grab-cooldown:{userId}`，`SET NX EX 7200`）+ **重置计数窗口** + WARN 日志。Redis 写失败 = 少计 = 宽恕（不引入对账）
- 与「已确认后接单方取消」（`POST /cancel`：扣 20 信用分 + 落 `TAKER_CANCELLED` 事件）是**分事件的两条路**，不可混

## POST /api/errand/orders/{orderId}/cancel

取消（**取货前**）：PENDING_PAYMENT（无冻结）/ PUBLISHED（解冻全额退款）/ **LOCKED** / CONFIRMED 可取消

- 发布方取消：LOCKED 期**可取消**且解冻退款，**不扣接单方信用分**（锁单期还没开工，不是他的错）
- 接单方取消：仅 CONFIRMED 可退（回 PUBLISHED，扣 20 信用分 + 落 `TAKER_CANCELLED` 事件）；锁单中的接单方走 `POST /abandon`
- 请求级幂等（可选 `requestId` 参数）：客户端**每次点击生成新 UUID、重试沿用同一编号**——同编号的取消重试直接回放成功、业务零执行（含「取消后重抢并再确认」窗口期的迟到重试，不会误伤新一轮）；不带该参数时退化为事件链判定
- 重试幂等（事件链判定）：请求人非订单双方时，**最新事件是本人 `TAKER_CANCELLED` 且订单回 PUBLISHED** → 幂等成功返回（零副作用；重试触发的扣分入账由 `user_credit_detail` 流水唯一键去重）；订单已被他人再抢则 403
- PICKED_UP / DELIVERED 不可取消，只能发起仲裁

## POST /api/errand/orders/{orderId}/pickup

取货：CONFIRMED → PICKED_UP

- 仅接单方本人（`pickupById` 带 taker_id 条件）
- 取货后订单锁定：取消一律被拒，仅 DISPUTED 仲裁可达终态

## POST /api/errand/orders/{orderId}/deliver

送达：PICKED_UP → DELIVERED，同事务落 24h 自动完成延迟消息

- 仅接单方本人

## POST /api/errand/orders/{orderId}/confirm

发布方一次确认完成：DELIVERED → COMPLETED（无部分完成态）

- 结算（`ErrandConfirmResult`）：发布方 unfreeze 悬赏 + income 接单方（得全额）
- 幂等：已完成重复确认按同一明细重放，`wallet_detail.uk_order_type` 幂等（不重复结算/不重复入账）
- 24h 未确认由 bs-errand-auto-complete 消费方走同一结算口径自动完成（`handleAutoComplete`）

## GET /api/errand/orders/mine

我的跑腿（role=publish/take，可选 status 过滤）

- **role=take 口径**：按 `taker_id`
- **排除软删**：DELETED 单不出现在我的列表（草稿 DRAFT 正常可见，供作者继续处理）
- 流程: ErrandController → ErrandOrderCase.listMyOrders → domain listMyOrders

## POST /api/errand/orders/{orderId}/dispute

发起争议（取货后取消一律被拒，争议是唯一的例外路径）

- Auth: 需要登录；仅订单双方（发布方或接单方），局外人 403
- 无 Body；操作方由调用者身份推导（发布方 → `PUBLISHER`，接单方 → `TAKER`）
- 仅 CONFIRMED / PICKED_UP / DELIVERED 可发起，其余状态 500「当前状态不可发起仲裁」；**LOCKED 不在前态集合内**（锁单期还没开工，无可争议事实：接单方不满意→放弃，发布方不满意→取消）
- 事务内：条件 UPDATE 转 DISPUTED + 落搜索同步（争议中退出公开列表）+ 同事务落 `errand_order_event` 的 `DISPUTED` 事件；成功后订单只能由管理员仲裁结案

## POST /api/errand/orders/{orderId}/arbitrate?result=

管理员仲裁结案（DISPUTED 的唯一出口）

- Auth: 仅 ADMIN（非管理员 403）
- Query: `result` = `cancel`（判取消，退款发布方）/ `complete`（判完成，同确认口径结算给接单方）；其他值 400
- 条件 UPDATE 从 DISPUTED 翻转（并发未命中 → 500「订单状态已变化」）；同事务落 `ARBITRATED` 事件（`actor_role=ADMIN`，CANCEL 分支附带解冻退款）
- 幂等：已 CANCELLED/COMPLETED 的终态按对应结果返回成功（重试场景），不重复结算
- 事件操作人取当前登录管理员（`SecurityUtils.currentUserId()`），供纠纷溯源

## GET /api/errand/users/{userId}/orders

作者主页「跑腿」tab：某用户**发布的**进行中跑腿单（发布者视角，访客口径；UI 改版票 06）

- Auth: 免登录
- Query: page/pageSize(≤50)，按 create_time 倒序
- 过滤: `publisher_id={userId}` 且 `state IN ('PUBLISHED','LOCKED','CONFIRMED')`（待支付/终态/争议一律不外露）
- 口径: **只列该用户发布的单**（不含其接单）——作者主页是「这个人发布的跑腿」
- 成功: 分页 `ErrandOrderResponseDTO`
- 无隐私开关：进行中跑腿本就在 `/api/errand/orders` 公开可搜，主页不另设隐藏
