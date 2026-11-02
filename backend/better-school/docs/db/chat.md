# chat 域表（room/direct_room_pair/room_member/message/timeline/user_seq + notification 三表）

> 业务：站内私信（恒 1v1，群形态已随票 04 整体下线；时间线模型：写扩散 timeline + 会话级水位线）+ 站内通知（scope + 水位线已读模型）；归属模块：chat
> DDL 唯一事实源：`sql/schema.sql`（票 05 全库重写）。旧 message_thread/message 私信两表、旧 chat REST 均已下线（不迁移），新接口重建中

# room（会话表，恒 1v1）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键：纯内部键，永不对外、永不再与业务键同值|
|room_id|varchar(36)|UNIQUE(`uk_room_id`), NOT NULL|会话业务 ID（对外唯一标识，UUID 字符串）：应用层 insert 前由 `IIdGenerator` 发号、随行一次写入；room_member/message/timeline 引用的都是它|
|source|varchar(20)|NULL|会话来源（首次由市集「私信联系」入口建会话时写入、**首来源钉死不覆盖**；首页/主页入口建的为 NULL）：ERRAND/PRODUCT|
|initiator_id|varchar(36)|NULL|首建发起人 user_id（对家角色标签的相对视角锚点；`source` 为 NULL 时也为 NULL）|
|last_seq|bigint|NOT NULL, DEFAULT 0|消息序号水位（取号器）：发消息事务内 `UPDATE last_seq=last_seq+1` 原子取号，回滚无空洞|
|state|varchar(20)|NOT NULL, DEFAULT 'ACTIVE'|与取号器同行：一把行锁完成取号+终态判定；当前恒 ACTIVE（SEALED 已随票 04 下线不再产生，列随口径保留）|
|create_time|datetime|NOT NULL|创建时间（无默认值，应用写入；全库无 created_at）|
|update_time|datetime|NOT NULL|更新时间|

> `user_min`/`user_max`/`uk_direct_pair` 已迁出至 direct_room_pair（direct-room-pair 收口）——room 回归纯会话实体、不知道「配对」的存在。
> `last_msg_type`/`last_msg_preview`/`last_msg_at` 已迁出至 room_member（列表职责迁用户维度）——room 回归纯取号器 + 会话真相。
> 历史存量割接脚本已随 uuid-biz-id 票 08 退役：存量数据不迁移，删库重建。

## 索引
- `uk_room_id (room_id)`：业务键唯一；对外接口与跨表引用按它定位

## 注意
- `id` 与 `room_id` 不是同一口径：所有读/改按 `room_id`，`id` 是纯内部键（uuid-biz-id 起两者永不同值）
- `last_seq` 是取号器，禁用自增 `id` 充当消息序号；取号必须与消息/时间线写入同事务
- 未读数不落库 = `room_member.last_seq_copy − room_member.last_read_seq` O(1)（副本与水位线都在用户维度成员行上就地可算）
- `source` 只写一次（`direct_room_pair.uk_pair` 命中即原样返回既有 room）：同一对人先聊闲置又聊跑腿仍是同一条会话、同一个标签
- `source` + `initiator_id` 是市集消息页的筛子与角色标签来源；对家角色（跑手/卖家/买家）**按请求者现算不落库**——同一会话两侧各自看到自己的对家

# direct_room_pair（1v1 会话配对映射，查重与反查专用）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键：纯内部键，永不对外|
|user_min|varchar(36)|NOT NULL, `uk_pair` 首列|1v1 双方 user_id 的**规范序在前方**（字符串字典序：UUID 无序，min/max 按字典序归一化，无序对在有序存储里的规范表示）|
|user_max|varchar(36)|NOT NULL|1v1 双方 user_id 的规范序在后方（字符串字典序）|
|room_id|varchar(36)|NOT NULL, UNIQUE(`uk_room_id`)|指向 `room.room_id`（配对→会话反查锚点）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

## 索引
- `uk_pair (user_min, user_max)`：1v1 查重，同一对人恒一行——建会话的幂等裁决者
- `uk_room_id (room_id)`：一会话恒一行配对

## 注意
- 建会话事务的**收尾闸门**：room + 两行 room_member + 一行配对同事务；并发建同一对人时后到者撞 `uk_pair` 整事务回滚（room/成员行不落），服务层捕获 `DuplicateKeyException` 后经本表幂等回读既有 `room_id`
- 先查后插走本表（`findDirectRoom`）：查到配对行即原样返回既有会话（首来源钉死）
- 会话列表的对家 user_id 不由本表出：列表 SQL 自连 room_member（1v1 恒两行）直出；本表只承担建会话查重与反查

# room_member（会话成员状态，每会话恒两行；用户维度会话列表唯一查询面）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键：纯内部键，永不对外|
|room_id|varchar(36)|NOT NULL, `uk_room_user` 首列|会话业务键（引用 `room.room_id`，非物理 `id`）|
|user_id|varchar(36)|NOT NULL|成员 user_id|
|last_seq_copy|bigint|NOT NULL, DEFAULT 0|会话水位副本：未读数 = `此列 − last_read_seq`（用户维度就地可算，与 OpenIM Conversation 行同构）；写链路 GREATEST 只进不退，真源 `room.last_seq` 仍由取号事务维护|
|last_msg_type|varchar(20)|NULL|最后一条消息类型副本（列表预览标签：IMAGE=[图片]、CARD=按 ref_type 出 [商品]/[帖子]/[跑腿]，不查标题）|
|last_msg_preview|varchar(200)|NULL|最后一条消息预览文案副本（TEXT=原文截断至 200，撤回=[已撤回]）|
|last_msg_at|datetime|NULL|最后消息时间副本（会话列表排序键）|
|last_read_seq|bigint|NOT NULL, DEFAULT 0|已读水位线：未读数 = `last_seq_copy − 此列`；打开会话即推到 last_seq_copy；已读回执 = 对方的此列|
|hidden_at|datetime|NULL|会话隐藏时间（用户侧删除会话，软删）；新消息清空弹回|
|create_time|datetime|NOT NULL|创建时间（加入时间）|
|update_time|datetime|NOT NULL|更新时间|

## 索引
- `uk_room_user (room_id, user_id)`：成员唯一
- `idx_user_msg (user_id, last_msg_at)`：会话列表驱动索引（user_id 等值 + last_msg_at 倒序取页）

## 注意
- 成员状态只在这一张表可变（隐藏/已读）；会话列表的排序键与预览三件套也只落这张表
- 水位线只前进不回退（SQL 带 `last_read_seq < 目标` 条件）；水位副本 `last_seq_copy` 由写链路 GREATEST 推进、同样只进不退
- 无业务 ID 列：对外按 (room_id, user_id) 定位

# message（消息内容表，内容中心只写一份）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键：纯内部键，永不对外（timeline 引 `msg_id`）|
|msg_id|varchar(36)|UNIQUE(`uk_msg_id`), NOT NULL|消息业务 ID（对外唯一标识，UUID 字符串）：应用层 insert 前由 `IIdGenerator` 发号、随行一次写入；跨表引用与对外暴露用它|
|room_id|varchar(36)|NOT NULL|会话业务键（引用 `room.room_id`）|
|seq|bigint|NOT NULL|消息序号（`room.last_seq` 取号，会话内从 1 起），`uk_room_seq` 第二列|
|from_user_id|varchar(36)|NOT NULL|发送者（1v1 无系统消息，恒非空）|
|msg_type|varchar(20)|NOT NULL, DEFAULT 'TEXT'|TEXT/IMAGE/CARD 三类定死|
|content|varchar(1024)|NULL|载荷按类型分：TEXT=裸文本（上限 500 由代码常量收敛，列宽留余量）；IMAGE=JSON{url,width,height}；CARD=恒空|
|client_msg_id|varchar(36)|NULL|客户端幂等键（UUID，重发复用同键），`uk_room_client` 第二列|
|ref_type|varchar(20)|NULL|CARD 引用类型 PRODUCT/POST/ERRAND（仅 CARD 非空）|
|ref_id|varchar(36)|NULL|CARD 引用对象业务 ID（POST/PRODUCT/ERRAND 均为 UUID 字符串；渲染实时查，失效显示「内容已失效」）|
|state|varchar(20)|NOT NULL, DEFAULT 'NORMAL'|NORMAL/RECALLED：撤回占 seq 不回退、计入未读，`content` 库内保留取证、读路径脱敏为「已撤回」空壳|
|recalled_at|datetime|NULL|撤回时间|
|recalled_by|varchar(20)|NULL|USER=发送者限时窗口内自撤 / AUDITOR=举报强制撤回|
|create_time|datetime|NOT NULL|发送时间|
|update_time|datetime|NOT NULL|更新时间（撤回置 RECALLED 时刷新）|

## 索引
- `uk_msg_id (msg_id)`：业务键唯一，timeline 与对外接口的引用锚点
- `uk_room_seq (room_id, seq)`：历史分页游标 + WS 推送判重键
- `uk_room_client (room_id, client_msg_id)`：发送幂等（撞键回查收敛同一条）
- `idx_ref(ref_type, ref_id)` 刻意不建：无「按卡片反查消息」读路径，出现再补

## 注意
- 撤回条件 UPDATE 带 `state='NORMAL'` 守卫：用户自撤与强制撤回并发先到先得，已撤回行不被覆盖
- 发消息事务顺序：insert 前生成 `msg_id`（`IIdGenerator`）→ 插 message（业务键随行一次写入）→ timeline 扇出引用该 `msg_id`

# timeline（时间线，不可变瘦收件箱）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理自增，禁止当同步游标（id 分配序 ≠ 提交序）|
|user_id|varchar(36)|NOT NULL, `uk_user_delivery` 首列|收件人|
|room_id|varchar(36)|NOT NULL|会话业务键（冗余：判重键与消息定位，免回查）|
|seq|bigint|NOT NULL|消息序号副本|
|msg_id|varchar(36)|NOT NULL|消息业务 ID（外联 `message.msg_id`，**不是** message 的物理 `id`）|
|delivery_seq|bigint|NOT NULL|per-user 投递序号（与消息序号是两个序号空间不可混用）|
|create_time|datetime|NOT NULL|投递时间|
|update_time|datetime|NOT NULL|更新时间（照全库收尾列；本表只插不改，实际恒等于 create_time）|

## 索引
- `uk_user_delivery (user_id, delivery_seq)`：增量补拉游标（`user_id = ? AND delivery_seq > ?`，全表唯一读法）

## 注意
- 只插不改，一条消息 N 个成员 N 行多值 INSERT 扇出；禁 UPDATE/DELETE
- 补拉 JOIN 按业务键：`timeline t JOIN message m ON m.msg_id = t.msg_id`

# user_seq（投递序号计数器表）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键：仅库内用|
|user_id|varchar(36)|UNIQUE(`uk_user`), NOT NULL|用户 ID|
|seq|bigint|NOT NULL, DEFAULT 0|计数器（0 基）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

## 注意
- 扇出事务内 `INSERT ... ON DUPLICATE KEY UPDATE seq=seq+N` 读回取号（行不存在按 N 建行，`uk_user` 是 ODKU 命中键），行锁到提交保证 per-user 单调且=提交序
- 无独立读路径

## 关联
- direct_room_pair N:1 → room（走 `room_id` 业务键；1v1 查重与反查专用）
- room 1:N → room_member / message / timeline（逻辑外键走 `room_id` 业务键）
- message 1:N → timeline（走 `msg_id` 业务键）
- 被 content_report（target_type=MESSAGE）引用：chat 零审核的举报兜底

## 谁在写
- 发消息事务：room.last_seq 取号 + message 插入（`msg_id` insert 前发号一次写入）+ timeline 扇出 + user_seq 计数 + room_member 列表副本（`last_seq_copy` GREATEST + 预览三件套）
- 撤回事务：message 三件套（state/recalled_at/recalled_by）+ 守卫替换 room_member 双方 `last_msg_preview`
- 读路径：room_member 水位线推高（推到 `last_seq_copy`）

# notification（站内通知，scope + 水位线已读模型，营销票 02）

> 已读照抄 chat 的「序号 + 水位线」；逐行 is_read 已退休（读态 = seq ≤ 该范围水位）。
> 票 05 口径：时间列为 `create_time`/`update_time`（无 `created_at`），`notification` 有业务 ID `notification_id`（≠ 物理 `id`）；两张计数器表由「复合主键」改为物理 `id` 主键 + `uk_user_scope` 唯一键，并各自补齐两个时间列。

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理自增，**禁止当已读水位**（分配序 ≠ 提交序，并发跳读）|
|notification_id|varchar(36)|NOT NULL, UNIQUE `uk_notification_id`|业务 ID（对外唯一标识，UUID 字符串）：应用层 insert 前由 `IIdGenerator` 发号、随行一次写入（uuid-biz-id 票 06 起，不再有 bindBizId 回写）|
|user_id|varchar(36)|NOT NULL|接收用户|
|scope|varchar(20)|NOT NULL|收件范围：MARKET_ERRAND/MARKET_PRODUCT(预留)/BOTTOM_SYSTEM/INTERACTION_LIKE/INTERACTION_COMMENT；**生产点写入时显式标注，禁止从 (type,refType) 推导**（同一 ORDER_EVENT×ERRAND 发用户=MARKET_ERRAND、发审核员=BOTTOM_SYSTEM）|
|seq|bigint|NOT NULL|per-(user,scope) 收件序号（notification_seq 写入事务内取号）|
|sender_id|varchar(36)|NULL|触发者（互动通知：谁赞/评了你；广播类为空）|
|type|varchar(20)|NOT NULL|ANNOUNCEMENT/AUDIT_RESULT/AUDIT_PENDING/REPORT_RESULT/ORDER_EVENT/CREDIT_CHANGE/POST_COMMENTED/COMMENT_REPLIED/POST_LIKED/COMMENT_LIKED/LOTTERY_WON/POINTS_CHANGED|
|title|varchar(100)|NOT NULL|标题|
|content|varchar(500)|NOT NULL|内容|
|ref_type|varchar(20)|NULL|关联类型；营销三类固定 MARKETING（营销票 09）|
|ref_id|varchar(36)|NULL|关联业务 ID（POST/COMMENT/PRODUCT/ERRAND 均为 UUID 字符串，票 03/05；REPORT=report_id UUID，票 06）|
|broadcast_msg_id|varchar(64)|NULL|公告广播幂等键|
|dedup_key|varchar(100)|NULL, UNIQUE `uk_dedup`|互动/系统通知幂等键（同一触发者对同一目标只发一条，取消不撤回）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

## 索引
- `idx_user_scope (user_id, scope, id)`：按范围分页（scope 过滤 + id 降序）
- `uk_dedup (dedup_key)`、`idx_broadcast (broadcast_msg_id)`：两类幂等锚点

## 注意
- 归属映射：跑腿支付入审→MARKET_ERRAND；审核超时/转人工提醒、公告/审核结果/举报结果/信用分变动→BOTTOM_SYSTEM；赞→INTERACTION_LIKE；评论/回复→INTERACTION_COMMENT
- 营销两类（票 09 后存量，生产方=抽奖/积分服务，事件订阅投递，消费组 bs-monolith-<topic>-group）：LOTTERY_WON（bs-lottery-drawn，ref_id=抽奖记录 id，谢谢参与不发事件）/ POINTS_CHANGED（bs-points-changed，ref_id=事件 refId）→ 全部 BOTTOM_SYSTEM、ref_type=MARKETING
- 营销 dedup_key 形状：`MARKETING:DRAWN:<recordId>` / `MARKETING:POINTS:<reasonType>:<refId>`（事件体无 txId，reasonType+refId 唯一标识一笔变动，同 points_bucket uk 口径）
- dedup 幂等先查后插（不白耗 seq 号段），并发窗口由 uk_dedup + INSERT IGNORE 兜底——极端并发重复会留 seq 空号，推水位取计数器值一并越过

# notification_seq（通知收件序号计数器，每 (user, scope) 一行）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键（票 05 起由复合主键改为代理键）|
|user_id|varchar(36)|NOT NULL|接收用户 ID|
|scope|varchar(20)|NOT NULL|收件范围|
|seq|bigint|NOT NULL, DEFAULT 0|计数器（首次取号建行 seq=1）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

- 唯一键 `uk_user_scope (user_id, scope)`：每用户每范围恒一行
- 通知写入事务内 `INSERT ... ON DUPLICATE KEY UPDATE seq=seq+1` 读回取号（首号即 1），行锁到提交 ⇒ 取号序 = 提交序

# notification_read（通知已读水位，每 (user, scope) 一行）

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|物理主键（票 05 起由复合主键改为代理键）|
|user_id|varchar(36)|NOT NULL|接收用户 ID|
|scope|varchar(20)|NOT NULL|收件范围（各范围各推各的水位，互不误清）|
|last_read_seq|bigint|NOT NULL, DEFAULT 0|已读水位线：seq ≤ 此列全已读；标已读 = 推到当前计数器值（GREATEST 只前进）|
|create_time|datetime|NOT NULL|创建时间|
|update_time|datetime|NOT NULL|更新时间|

- 唯一键 `uk_user_scope (user_id, scope)`：每用户每范围恒一行
- 未读数 = `notification_seq.seq − last_read_seq` O(1)；列表 read 字段由 `seq ≤ 水位` JOIN 算出
