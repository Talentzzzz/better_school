# 行为返利表（返利规则 + 领取记录）

> 业务：行为返利（签到 / 支付 / 发帖等行为按运营规则发放积分或抽奖次数）；归属模块：rebate（`action_rebate_config` / `action_rebate_claim` 均归返利域独占写）

|表名|说明|
|----|----|
|action_rebate_config|行为返利规则（行为 × 返利类型 → 发多少积分 / 多少次，单行规则）|
|action_rebate_claim|行为返利领取记录（限次名额 + 领取时的金额快照；本期发帖返利接入）|

**主键口径（uuid-biz-id 起）**：`id` 是纯内部物理自增，不对外。`action_rebate_claim` 的业务 ID 为 `claim_id`（`uk_claim_id`），由统一 `IIdGenerator`（默认 `UuidIdGenerator`）在 insert 前发号一次写入；`action_rebate_config` 无自有业务 ID 列，按 `uk_action_rebate (action_type, rebate_type)` 寻址。

# action_rebate_config

> 业务：行为返利规则；归属模块：rebate

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键，不对外|
|action_type|varchar(16)|NOT NULL|行为类型 `sign`(签到) / `pay`(支付) / `post`(发帖)|
|rebate_type|varchar(16)|NOT NULL|返利类型 `DRAW_COUNT`(抽奖次数) / `POINTS`(用户活动积分)|
|rebate_name|varchar(64)|NOT NULL|返利名称|
|rebate_desc|varchar(128)|NULL|返利描述|
|rebate_value|int|NOT NULL, DEFAULT 0|返利值（抽奖次数 / 积分值，非负）|
|config_id|bigint|NOT NULL|有效期配置 id（`POINTS` → `points_validity_config.id`；`DRAW_COUNT` → `draw_count_config.id`）|
|state|varchar(12)|NOT NULL|状态 `open`(开启) / `close`(关闭)|
|create_time|datetime|NOT NULL|创建时间（SQL `NOW()` 写入）|
|update_time|datetime|NOT NULL|更新时间（SQL `NOW()` 写入）|

## 索引
- `uk_action_rebate (action_type, rebate_type)`：同一行为同一返利类型一条规则；后台 upsert 以它为键（`ON DUPLICATE KEY UPDATE` 整行更新）
- `idx_config (config_id)`：按有效期配置反查规则

## 注意
- 有效期配置**不在本域**：积分有效期归 points 域（`points_validity_config`）、次数有效期归 raffle 域（`draw_count_config`，抽奖次数与抽奖同域），`config_id` 只是指向那一份配置的 id
- 写只来自后台管理面（`POST /api/marketing/config/rebates`，ADMIN）；返利域在领取时、桶域在旧行为入账时只读
- `state = close` 与「没配」同语义：领取 / 入账拿到 null 直接跳过，不发放、不占名额
- 同一行为可有两行（`POINTS` 一行、`DRAW_COUNT` 一行），领取时各读一条 open 规则组成快照

# action_rebate_claim

> 业务：行为返利领取记录（日限名额 + 金额快照）；归属模块：rebate

|字段|类型|约束|说明|
|----|----|----|----|
|id|bigint|PK, AUTO_INCREMENT|内部物理主键，不对外|
|claim_id|varchar(36)|UNIQUE(uk_claim_id), NOT NULL|业务 ID（UUID 字符串）：入账消息、排障查询用它|
|user_id|varchar(36)|NOT NULL|领取用户 ID|
|action_type|varchar(16)|NOT NULL|行为类型 `sign` / `pay` / `post`|
|period_key|varchar(16)|NOT NULL|限次周期键：日限 `yyyyMMdd`，时区固定 `Asia/Shanghai`，发帖事务里定死|
|ref_id|varchar(36)|NOT NULL|本次行为 ID（发帖 = `postId`）|
|points_value|int|NOT NULL, DEFAULT 0|领取时快照的积分值|
|draw_count_value|int|NOT NULL, DEFAULT 0|领取时快照的抽奖次数|
|state|varchar(16)|NOT NULL|持久化状态 `CLAIMED` / `GRANTED`|
|create_time|datetime|NOT NULL|领取时间（SQL `NOW()` 写入）|
|update_time|datetime|NOT NULL|更新时间（标记 GRANTED 时刷新）|

## 索引
- `uk_claim_id (claim_id)`：业务键查领取记录（入账消费、客服排障）
- `uk_user_action_period (user_id, action_type, period_key)`：**日限唯一键**——同人同行为同周期只允许一条领取记录，多实例并发由数据库唯一键裁决，不会超发
- `uk_user_action_ref (user_id, action_type, ref_id)`：**同一次行为只领取一次**——重放 / 重复投递按它幂等，不会产生第二条领取记录
- `idx_state_time (state, update_time)`：后续对账扫描用（`CLAIMED` 超时未 `GRANTED`），本期不实现扫描

## 状态语义
- 持久化状态只有两个值：
  - `CLAIMED`：已抢占名额、金额快照已定，等待积分 / 抽奖次数入账
  - `GRANTED`：积分与抽奖次数均已入账完成（credit 消费者按快照入账后条件 UPDATE 置位）
- 不落库的三种结果是 **API 状态**（见 `RebateClaimApiState`），只出现在发帖响应里：
  - `DENIED`：同周期名额已被该用户的其他行为领取（如今天已用另一条帖子领过）
  - `NO_REBATE`：无开放规则或快照金额为 0，不占名额
  - `PENDING`：同步领取异常，领取任务留 `PENDING` 等待 outbox 重放；此时不向用户承诺金额
- 已有领取行重放时 API 状态仍返回 `CLAIMED`（本次行为确实已领取），落库状态照实回 `CLAIMED` / `GRANTED`

## period_key 与日限口径
- 日限 = `Asia/Shanghai` 自然日 0 点，格式 `yyyyMMdd`
- 周期键在发帖 / 提交草稿事务里按**数据库时钟的发布时间**（`post.update_time`）定死，随领取任务消息落库；重放使用任务里的原值，不按重放当天重算，避免跨零点串天
- 口径固定 `Asia/Shanghai`；发布时间取数据库时钟（部署 `TZ=Asia/Shanghai`），`RebatePeriodKeys.of` 显式按 `ZONE` 取日期、不读 JVM 默认时区；`today()` 仅在旧载荷兜底时用应用时钟按 `Asia/Shanghai` 取当天
- 旧载荷缺 `periodKey` 时按消费当天兜底（`RebatePeriodKeys.today()`），避免旧消息被丢弃

## 快照与金额 0 语义
- `points_value` / `draw_count_value` 是**领取时**按 open 规则快照下来的金额；后续运营改规则或关闭规则，不影响已承诺金额（入账消费者直接按快照入账，不再读规则）
- 首次领取时两条 open 规则都为 0 / 都没配：**不插 claim 行、不占名额**，返回 `NO_REBATE`
- 只有一种为 0：照常落行占名额，`0` 的那一种返利不发——credit 消费者按 `> 0` 才调对应桶域入账
- `DENIED` / `NO_REBATE` / `PENDING` 的响应金额恒为 0（这三种状态没有承诺过任何金额）

## 幂等分工（两条唯一键）
- **`uk_user_action_ref`（同一行为）**：同 `(user_id, action_type, ref_id)` 命中已有行 → 幂等返回已有快照，不重复插 outbox、不重复发送；即便已有行已 `GRANTED`，API 仍返回 `CLAIMED`
- **`uk_user_action_period`（同一周期）**：同 `(user_id, action_type, period_key)` 已被**其他 ref** 领取 → 返回 `DENIED`，不落行、不占名额
- 判定顺序固定：先按 ref 加锁查（幂等优先），未命中再按周期加锁查（日限）；都未命中才插行。并发撞唯一键时按同样顺序加锁重查，区分「同 ref 幂等」与「同周期 DENIED」
- 加锁读（`SELECT ... FOR UPDATE`）是必须的：REPEATABLE READ 下普通读看不见并发事务刚提交的新行，只有加锁读才能拿到最新已提交状态；事务外排障读用普通读（`selectByRefNoLock`）

## 谁在写 / 谁在读
- **谁在写**：
  - `action_rebate_claim` 行只由返利仓储的领取事务插入——查重 + 插 claim +（`CLAIMED` 时）插 credit outbox 收在一个 REQUIRED 本地事务里；事务边界不出仓储（ADR-0016）
  - 发帖 / 提交草稿事务**不写** claim 行，只写一条领取任务 outbox（PENDING，msgId `post-rebate-claim:{postId}`）；同步领取成功后由 case 把任务标记 SENT，领取异常才留 PENDING 交 `outboxScan` 重放
  - `state` 的 `CLAIMED → GRANTED` 只由 credit 消费者经 `markGranted` 条件 UPDATE 推进（重复调用 0 行、无副作用）
  - `action_rebate_config` 只由后台管理面 upsert
- **谁在读**：
  - 返利域领取时读两条 open 规则构快照；客服排障按 `ref_id` / `claim_id` 查询
  - 积分 / 抽奖域旧行为入账经各自只读 port（`IPointsRebatePort` / `IDrawCountRebatePort`）读配置；发帖返利改走 credit 消息里的快照
- **不回收**：领取后删帖不回收返利、不释放名额、不删 claim 行（防刷；见 spec「产品口径」）

## 关联
- `ref_id` → `post.post_id`（本期发帖返利）；其他行为接入时仍存该行为的业务 ID
- 入账落点：积分桶 `points_bucket`、抽奖次数桶 `draw_count_bucket`，幂等键 `(user_id, REBATE, postId)`
