# 用户表（账号 / 钱包 / 信用分）

> 业务：账号登录 + 公开资料 + 隐私开关，以及以用户为主体的虚拟钱包与信用分流水；归属模块：user（`virtual_wallet` / `wallet_detail` 归 wallet 域、`user_credit_detail` 归 credit 域，三表均只以用户为键，随本文档记）

|表名|说明|
|----|----|
|bs_user|用户主表（`user` 是 MySQL 保留字，故加 `bs_` 前缀）|
|virtual_wallet|虚拟钱包（一人一行：余额 + 托管冻结）|
|wallet_detail|钱包流水（资金事实源，uk_order_type 幂等）|
|user_credit_detail|信用分流水（信用分事实源，uk_user_reason_ref 幂等）|

**主键口径（全库统一，uuid-biz-id 起）**：`id` 是纯内部物理自增，**对外与跨表引用一律用业务 ID 列**（本组表为 `user_id` / `wallet_id`）；业务 ID 是应用层发号的 UUID 字符串（`VARCHAR(36)`，`UUID.randomUUID()` 在 insert 前生成、随行一次写入），与物理 `id` 永不同值、物理 id 永不对外。存量数据不迁移（删库重建），历史「插 0 回写」割接脚本已退役。

# bs_user

> 业务：账号登录 + 公开资料 + 隐私开关；归属模块：user

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键，不对外|
|user_id|varchar(36)|UNIQUE(uk_user_id), NOT NULL|业务 ID（UUID 字符串）：登录态、跨表引用、接口出参一律用它|
|phone|varchar(20)|UNIQUE(uk_phone), NOT NULL|手机号，登录凭证；**对外资料接口不返回**（PII）|
|nickname|varchar(50)|NOT NULL|昵称|
|avatar|varchar(255)|NULL|头像 URL（MinIO）|
|signature|varchar(60)|NULL|个性签名：可空、≤40 字（按字符校验并 trim），空即不展示；「设置 → 编辑资料」可改|
|gender|varchar(10)|NULL|性别：`MALE`/`FEMALE`，`NULL`＝不展示；「设置 → 编辑资料」可改|
|show_liked|tinyint(1)|NOT NULL, DEFAULT 1|隐私开关：公开我的「赞过」——1 公开 / 0 仅自己；**仅他视生效**|
|show_comments|tinyint(1)|NOT NULL, DEFAULT 1|隐私开关：公开我的「评论」——1 公开 / 0 仅自己；**仅他视生效**|
|role|varchar(20)|NOT NULL, DEFAULT 'USER'|角色 USER/ADMIN|
|state|varchar(20)|NOT NULL, DEFAULT 'NORMAL'|状态 NORMAL/BANNED（原 `status` 列改名；信用分跌破 30 由 credit 域条件 UPDATE 置 BANNED）|
|credit_score|int|NOT NULL, DEFAULT 100|信用分，区间 0–100（credit 域写：`GREATEST(0, LEAST(100, credit_score + delta))`），勿在 user 域写|
|last_login_at|datetime|NULL|最近登录时间|
|create_time|datetime|NOT NULL|注册时间（SQL `NOW()` 写入，**无 DB 默认值**）|
|update_time|datetime|NOT NULL|更新时间（SQL `NOW()` 写入，无 `ON UPDATE`）|

## 索引
- `uk_user_id (user_id)`：业务键查用户
- `uk_phone (phone)`：登录查用户

## 关联
- 1:1 → virtual_wallet（user_id）
- 1:N → wallet_detail / user_credit_detail（user_id）
- 被 post.user_id / post_comment.user_id / product.user_id / errand_order.publisher_id|taker_id / product_order.buyer_id|seller_id / notification|timeline.user_id 引用（一律存 `user_id`）

## 谁在写 / 注意
- 资料编辑只写 `signature`/`gender`（`UserMapper.updateProfile`），隐私设置只写 `show_liked`/`show_comments`（`updatePrivacy`），登录只写 `nickname`/`avatar`/`last_login_at`（`updateById`）——**三条语句各管一摊，不要合并成通用 updateById**：`role`/`state`/`credit_score` 归其他域写，防止登录写覆盖并发封禁/调分
- **获赞无独立计数列**：公开资料接口的 `receivedLikeCount` 读时两段查聚合——post 单表取该用户 NORMAL 帖 id 集，再按 id 集批量读 `post_like_count` 后 Java 求和（窄表计数由 MQ 窗口聚合异步刷盘，属近似值；不含评论获赞；SQL 层零 JOIN）
- 查用户请走 `phone` / `user_id`（都有 uk），不要扫全表

# virtual_wallet

> 业务：校园币钱包（跑腿悬赏与商品交易共用托管）；归属模块：wallet

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键|
|wallet_id|varchar(36)|UNIQUE(uk_wallet_id), NOT NULL|业务 ID（UUID 字符串，对外唯一标识）|
|user_id|varchar(36)|UNIQUE(uk_user), NOT NULL|钱包所有者；一人一钱包|
|balance|decimal(10,2)|NOT NULL, DEFAULT 0.00|余额|
|frozen_amount|decimal(10,2)|NOT NULL, DEFAULT 0.00|冻结金额（托管中，不参与可用余额）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

## 注意
- 建钱包走 `INSERT IGNORE`（撞 `uk_user` = 已存在，幂等）；`wallet_id` 由应用层 `UUID.randomUUID()` 在 insert 前生成随行写入（uuid-biz-id 票 06 起，不再回写）
- 可用余额 = `balance − frozen_amount`；冻结是条件 UPDATE（`balance − frozen_amount >= amount`，不满足=余额不足），解冻是 `GREATEST(0, frozen_amount − amount)`
- **资金一律「流水先行」**：先 `INSERT IGNORE` wallet_detail（撞 `uk_order_type` = 已处理过，幂等跳过），影响 0 行就直接返回，不再动余额/冻结

# wallet_detail

> 业务：钱包流水（资金事实源，跑腿 + 商品共用一表，靠 `type` 前缀区分）；归属模块：wallet

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键|
|wallet_id|varchar(36)|NOT NULL|钱包业务 ID（引用 `virtual_wallet.wallet_id`）|
|user_id|varchar(36)|NOT NULL, INDEX(idx_user)|流水归属用户（INCOME 为收款方）|
|type|varchar(20)|NOT NULL|RECHARGE/FROZEN/UNFROZEN/INCOME（跑腿）+ PRODUCT_FROZEN/PRODUCT_UNFROZEN/PRODUCT_INCOME（商品）|
|amount|decimal(10,2)|NOT NULL|变动金额（正数，方向由 `type` 表达）|
|order_id|varchar(36)|NULL, INDEX(uk_order_type 首列)|关联订单业务 ID（跑腿=`errand_order_id`、商品=`product_order_id`，UUID 字符串；RECHARGE 为 NULL）|
|biz_round|int|NOT NULL, DEFAULT 0|业务轮次：同一订单同类型多轮资金操作的去重维度（预留，当前恒 0）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

## 索引 / 注意
- `uk_order_type (order_id, type, biz_round, user_id)`：幂等键（历史形态保留：在 (订单, type, 轮次) 之上再加「人」维度，人恒为末列）。**RECHARGE 的 `order_id` 为 NULL，MySQL 唯一键不约束含 NULL 行**，本就是多次充值多行
- 幂等语义：同一 (订单, type, 轮次, 人) 只允许一条流水；并发/重试撞键 → 影响 0 行 → 该次资金动作整体跳过
- 只插不改（无 UPDATE / DELETE 语句）；分页读按 `user_id` + `id DESC`

# user_credit_detail

> 业务：信用分流水（信用分事实源，一变动一行）；归属模块：credit

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键|
|user_id|varchar(36)|NOT NULL, INDEX(idx_user)|变动用户|
|change|int|NOT NULL|信用分变化量（可为负；列名是 SQL 关键字 `change`，SQL 里必须反引号）|
|reason_type|varchar(30)|NOT NULL|原因类型，取 `CreditReasonTypeVO` 枚举名：ERRAND_TAKER_CANCEL(-20) / ERRAND_TIMEOUT(-20) / TRADE_COMPLAINT(-20，预留未接入) / ADMIN_ADJUST / ADMIN_BAN / ADMIN_UNBAN(0)|
|ref_id|varchar(36)|NULL|关联业务 ID（跑腿类=`errand_order_id` UUID；ADMIN 类为 NULL）|
|create_time|datetime|NOT NULL|创建时间（也是「每日上限」统计的时间窗依据）|
|update_time|datetime|NOT NULL|更新时间|

## 索引 / 注意
- `uk_user_reason_ref (user_id, reason_type, ref_id)`：幂等键，`INSERT IGNORE` 撞键即「已处理过」跳过。**ADMIN 类 `ref_id` 为 NULL，唯一键不约束，故可重复调分**（业务上允许）
- 加分上限按 `reason_type` 的 dailyCap 判定（`sumTodayChange` 取当日 `create_time` 窗口内的 `SUM(change)`），当前枚举均未设 dailyCap
- 分数写入 `bs_user.credit_score` 与本表在同一事务：**先插流水，再条件 UPDATE 分数**
