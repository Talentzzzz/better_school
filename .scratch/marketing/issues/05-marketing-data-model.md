Status: resolved
Type: grilling
Blocked by:

# 05: 营销域数据模型（三个 schema 的表、幂等键、奖池库存时序）

## Question

把三个服务的表设计与关键时序定死，口径全部来自票 01 §3–§7 与 `实现方案3.md`（含用户 2026-09-14 的两处修订：**去掉 `wq`**、**DB 直扣兜底要做**）。

**① 积分服务（schema，如 `bs_points`）**：余额表 + 流水表——照 `wallet` 指纹（`virtual_wallet` + `wallet_transaction`：UK `uk_order_type`、**流水先行 INSERT IGNORE**、流水是事实源）。要定：流水需要哪些 `reason_type`（抽奖中奖 / 兑换扣减 / 成团送分）；**幂等键怎么组**（`(user_id, reason_type, ref_id)` 还是带轮次）；余额是列还是由流水算（推荐跟 wallet 一样存列 + `version`）；负数余额怎么防。

**② 抽奖服务（schema，如 `bs_lottery`）**：活动配置表（`start_at` / `end_at` / 每日上限 / 开关 / 名称）、奖品表（档位 / 名称 / 图片 / 类型 `PHYSICAL`·`POINTS` / 权重 / **`redeem_points`** / 指向库存）、库存表（**去 `wq`**：`sq` / `lq` / `oq` / `version`）、桶配置表（`bucket_id` / `status` / `remaining`）、扣减明细表（`biz_type` `LOTTERY`·`REDEEM` / `biz_id` / `bucket_id` / `status` 0·1 / `uk(prize_id, biz_id)`）、抽奖记录表（**随机值**与**奖池快照**字段怎么存：JSON 列？）、兑换单表（实物领取态 + 收件人 / 手机号 / 地址 + 幂等键）、抽奖次数账本（**要不要留「次数从哪来」**：成团 / 兑换）、每日抽奖计数（落 redis 还是 DB 表——影响清零点与防刷强度）。

**③ 拉新服务（schema，如 `bs_referral`）**：邀请码（一人一码，全局唯一；要不要独立表还是挂在用户维度）、成团记录（邀请人 / 被邀请人 / 时间）。**重点：唯一键怎么表达两条约束**——「每人一生只能作为被邀请人一次」（`uk(invitee_id)`）与「同一对不能成两个团」（是否冗余为 `uk(inviter_id, invitee_id)`）；成团事件 outbox。

**④ 跨 schema 的纪律**：三个服务互相只存 id，**不 join、不存昵称头像快照**；用户信息一律经 Dubbo 取回后在应用层拼。写进 spec 当硬约定。

**⑤ 时序（逐条写清，含事务边界）**：
1. **填码成团**：查码 → 校验三条约束（注册时间 ≥ `start_at` / 未被邀请过 / 不是自己）→ 落成团记录 → 写 outbox，**全在一个事务里**。
2. **抽奖**：查次数 → 权重过滤出有库存的档 → `DECR` 桶 → 落明细 + 抽奖记录 → 扣次数 → 返回 → 积分档发事件。
3. **合并提交**：覆盖索引扫描 → 事务内 `sq 减 / lq 减 / oq 加` + 桶配置 `remaining` 减 → **按带回的 biz id 列表**翻 `status=1`。
4. **DB 直扣兜底**：**怎么知道 redis 挂了**（`try/catch` + 超时降级？启动时健康探测？每次抽奖先探活？）——这条要给出可实现的判据，不能只说"挂了就兜底"。
5. **兑换**：兑次数 / 兑奖品两条边（扣积分 + 加次数 / 扣库存）的时序与幂等键（兑换单 id），失败重试的安全性。
6. **实物领取**：待领取 → 填收件信息 → 已领取；**`oq` 在哪个时刻减**。
7. **开桶**：`lq += N` + 桶配置行 + redis `SET`（**不是 INCR**）。

**⑥ 迁移与种子**：迁移编号从 **022** 起（现最高 `021_user_profile_and_privacy.sql`），三个 schema 各自一份还是合并一份；种子数据（活动起止 / 3~5 档奖品 / 权重 / 库存 / 兑换定价 / 每日上限）——**活动 `start_at` 要设成过去的日期**（票 01 §3 的验收注意）；`init.sql` 要不要把三个 schema 一起建（现在它是单体一套）。同步 `backend/docs/db/`。

## Answer

**2026-09-14 收口**：三轮 grilling（Q1–Q26）＋ 收口核对时补一轮（Q27–Q28）。开工前做了事实自核（MySQL `9.7` / Redis `8.8.0` / 全仓无 `JSON` 列类型 / 无 `RAtomicLong` / 无雪花 id / `wallet_transaction` 的 `biz_round` 来历）。**用户在本票反转了三处上游基线**（① 余额列 → 桶化求和；② 成团送积分 → 成团只加抽奖次数；③ 次数永不过期 → 次数也过期），下游影响面见「决策依据」第 5–7 条。

### 0. 总览

三个独立 schema（`bs_points` / `bs_lottery` / `bs_referral`），各自一份 `outbox_message`；**积分与抽奖次数都用「桶化（lot）模型」**——每笔入账是一行带自己 `expire_at` 的桶、消费按 FIFO 扣 `remain`、余额 = 未过期桶 `remain` 之和、独立过期；**奖品表与库存表合并**成一张；库存走 redis 单桶（拨 80%）+ DB 直扣兜底；**开桶由手工接口触发**。

### 1. 积分 schema `bs_points`

**① 桶化模型（Q1/Q4/Q14；反转了票面推荐的「余额列」）**

| 表 | 列 | 唯一键 / 索引 |
| --- | --- | --- |
| `points_lot` 积分桶（= 发放流水，同时是事实源） | `id` AI / `user_id` / `amount INT`（恒正） / `remain INT`（可变，消费时减） / `expire_at DATETIME`（= 入账时点 + `points_valid_days`） / `reason_type VARCHAR(30)` / `ref_id BIGINT` / `created_at` | `uk_user_reason_ref(user_id, reason_type, ref_id)` 入账幂等；`idx_user_expire(user_id, expire_at)` |
| `points_consume` 消费明细 | `id` AI / `user_id` / `lot_id` / `amount INT` / `reason_type VARCHAR(30)` / `ref_id BIGINT`（= 兑换单 id） / `created_at` | ~~`uk_user_reason_ref(user_id, reason_type, ref_id)` 出账幂等~~ **⚠️ 2026-09-15 落地修正（[marketing-impl 票 05](../marketing-impl/issues/05-points-service.md)）**：改为 `uk_lot_reason_ref(lot_id, reason_type, ref_id)`；`idx_lot(lot_id)` 由该 uk 的前缀替代，另加 `idx_user_ref(user_id, reason_type, ref_id)` 供幂等预检 |
| `points_config` 单行配置 | `id TINYINT PK(=1)` / `points_valid_days INT NOT NULL`（种子 365） | — |
| `outbox_message` | 照抄单体形状 | `uk_msg_id` |

- **余额** = `SELECT COALESCE(SUM(remain),0) FROM points_lot WHERE user_id=? AND remain>0 AND expire_at > NOW()`
- **消费** = 同条件 `ORDER BY expire_at ASC, id ASC FOR UPDATE` 逐桶扣 `remain`，凑不够 → 整事务回滚 → 余额不足
- ⇒ **没有余额列、也不需要用条件更新防负**：`FOR UPDATE` + 求和校验天然不可能为负（Q1 推荐的条件更新随之作废）
- `amount` 是**恒正 `INT`**、方向由「在哪张表 + 哪个 `reason_type`」表达（Q2 原推荐「有符号 INT」在拆成两表后退化）；不用 `DECIMAL`（积分无小数）

**② `reason_type` 只有 3 个值（Q2 + Q17 反转后）**：`LOTTERY_WIN("抽奖中奖")` / `REDEEM_DRAW_COUNT("兑换抽奖次数")` / `REDEEM_PRIZE("兑换奖品")`。~~`REFERRAL_BONUS`~~ 已随「成团只加抽奖次数」删除。

**③ 过期（Q15/Q16）**

- **懒过期为准**：所有读路径一律带 `expire_at > NOW()`；另有 xxl-job `pointsExpireScan` 只做落账清理：`UPDATE points_lot SET remain = 0 WHERE expire_at <= NOW() AND remain > 0`（**不删行、留审计**）
- 过期**不发** `bs-points-changed`（避免噪声）
- 有效期 = `points_config.points_valid_days`，种子 **365**

### 2. 抽奖 schema `bs_lottery`

**① 奖品与库存合并成一张（Q5；偏离 `实现方案3.md` 的独立 `inventory`）**：`lottery_prize`

`id` AI / `tier INT`（档位） / `name` / `image` / `type VARCHAR(20)`（`PHYSICAL`·`POINTS`·`THANKS`，Q27） / `weight INT` / `points_amount INT NULL`（POINTS 档发的积分数） / `redeem_points INT NULL`（NULL = 不可兑换） / **`total INT`（⚠️ 2026-09-14 票 06 补：投放总量、建品时定、恒不变）** / `sq INT` / `lq INT` / `oq INT` / `version INT`

- **⚠️ 2026-09-14 票 06 补 `total`**：活动页奖品目录要显示「剩 x / y」，分母 y 必须是**恒定**的投放总量。**不能拿 `sq + oq` 顶替**——它只在「开桶 / 合并提交 / DB 直扣」下守恒，而本票 §5⑥ 的实物领取会 `oq -= 1` ⇒ 每领一件 `sq + oq` 掉 1，分母会漂。对外 `remaining = sq`、`total = lottery_prize.total`（见 [票 06 §2](06-marketing-api-contract.md)）。

- 理由：本域奖品与库存**严格 1:1**，`实现方案3.md` 的 `inventory` 是通用 SKU 模型（可被多业务复用），这里拆开只多一次 join；且票面 ② 自己把幂等键写成了 `prize_id`
- **只有实物档进 `sq/lq/oq`**；积分档与谢谢参与档恒 0、**不产 `deduct_detail`**（票 01 §6「积分档不占库存」的落地）。**谢谢参与不产 `lottery_user_prize`；积分档产，且 `status = CREDITED`**（即时入账、无待领取态）——票 02 ⑤ 的三段 tab 里有「积分入账」，靠的就是这一行（见下 ⑥）

**② 活动配置（单行）**：`lottery_activity` —— `id TINYINT PK(=1)` / `name` / **`slogan`（⚠️ 2026-09-15 票 07 补）** / `start_at` / `end_at` / `daily_limit INT` / `enabled TINYINT` / `draw_count_price INT`（一次抽奖次数的积分价） / `draw_count_valid_days INT`（种子 30）

> ⚠️ **票 07 落地时对本节的三处偏离**（详见 [marketing-impl 票 07](../marketing-impl/issues/07-lottery-service.md)）：
> 1. **补了 `slogan VARCHAR(64)` 列**：票 06 的接口清单要求 `GET /api/lottery/activity` 返回它，
>    而上面这行列清单里没有。MySQL 无 `ADD COLUMN IF NOT EXISTS` ⇒ 两个 DDL 文件末尾追加一段
>    `information_schema` 判断的幂等 `ALTER` + 回填（已实跑两遍验证）。
> 2. **`lottery_prize` 没建 `version` 列**（下面 §2① 列了它）：两条库存通路都用「`WHERE` 里带守卫、
>    影响 0 行即失败」，没有任何代码读 `version`——加一列没人读的列比偏离更糟。
> 3. **时间列一律 `created_at`**（本节 §2③④ 写的是 `create_time`）：全仓其余表（含同域的
>    `lottery_prize` / `lottery_draw_record`）都是 `created_at`，本节自己也前后不一致。

**③ 桶配置（单桶）**：`lottery_bucket_config` —— `id` AI / `prize_id` / `bucket_id VARCHAR(64)` / `status TINYINT`（本期只有 1 = 开启） / `remaining INT` / `create_time`；`uk_prize_bucket(prize_id, bucket_id)`

**④ 扣减明细**：`lottery_deduct_detail` —— `id` AI / `prize_id` / `biz_type VARCHAR(20)`（`LOTTERY`·`REDEEM`） / `biz_id BIGINT` / `bucket_id VARCHAR(64)` / `quantity INT`（恒 1） / `status TINYINT`（0 = 待合并 / 1 = 已合并；兜底直扣落库即 1） / `create_time`

- `uk_prize_biz(prize_id, biz_type, biz_id)` ← **Q28 修正**：票 01 §7 与票面 ② 原写 `(prize_id, biz_id)`，但 `biz_id` 有两个 id 空间（`LOTTERY` 用抽奖记录 id、`REDEEM` 用兑换单 id），自增 id 各表局部、不跨表可比 ⇒ 撞键会把合法兑换误判为「已扣过」而**少扣库存**
- `idx_merge(prize_id, bucket_id, status, biz_id)` 覆盖索引

**⑤ 抽奖记录**：`lottery_draw_record` —— `id` AI / `user_id` / `prize_id`（谢谢参与指向 `THANKS` 行的 id） / `prize_type VARCHAR(20)` / `points_amount INT NULL` / `random_value BIGINT`（本次抽奖的随机值） / `pool_snapshot TEXT`（当时的奖池快照：各档权重 + 剩余量，序列化 JSON） / ~~`created_at`~~ **`client_token VARCHAR(64)`（⚠️ 2026-09-14 票 06 补）** / `created_at`

- **每次抽奖都落一行**（含谢谢参与）；「我的中奖记录」按 `prize_type != 'THANKS'` 过滤 ⇒ 票 01 §6「记录每次抽奖的随机值」的审计承诺才完整
- 快照存 `TEXT` 不存 `JSON` 列：全仓 0 处 `JSON` 列类型（`outbox_message.payload` 先例）
- **⚠️ 2026-09-14 票 06 补：加 `client_token` + `uk_user_token(user_id, client_token)`** —— 用户 2026-09-14 回「都按你推荐的」，把抽奖 POST 做成幂等（前端生成 uuid 随请求带上，撞键回放已存结果、不重复抽也不重复扣次数）。理由：`POST /api/lottery/draw` 是**唯一一个失败会让用户真亏一次抽奖**的写接口，兑现本仓「幂等 + 重试」铁律。详见 [票 06 §4](06-marketing-api-contract.md)。

**⑥ 我的奖品**：`lottery_user_prize` —— `id` AI / `user_id` / `prize_id` / `source VARCHAR(20)`（`DRAW`·`REDEEM`） / `source_ref_id BIGINT` / `status VARCHAR(20)`（`PENDING`·`RECEIVED`·`CREDITED`） / `receiver VARCHAR NULL` / `mobile VARCHAR NULL` / `address VARCHAR NULL` / `created_at` / `claimed_at DATETIME NULL`；`uk_source_ref(source, source_ref_id)`

- 中奖的实物与兑换的实物**汇入同一张**，页面读一条路径；票 02 ⑤ 的三段 tab 直接映射 `PENDING` / `RECEIVED` / `CREDITED`（积分档 `CREDITED`、不回填收件信息）

**⑦ 兑换单**：`lottery_redeem_order` —— `id` AI（**= 兑换幂等键**） / `user_id` / `kind VARCHAR(20)`（`DRAW_COUNT`·`PRIZE`） / `prize_id BIGINT NULL` / `points_cost INT` / `status VARCHAR(20)`（`PENDING`·`DONE`） / `created_at` / `updated_at`

- **偏离票面 ② 的一处**：票面把这个表写成「实物领取态 + 收件人 / 手机号 / 地址 + 幂等键」；本票按 Q9 把**领取态与收件信息移到 `lottery_user_prize`**（中奖与兑换的实物汇流到一条读路径），兑换单只管兑换交易与幂等（`kind=DRAW_COUNT` 时甚至不产 `user_prize` 行）

**⑧ 抽奖次数账本（桶化，Q6；与积分同构）**

| 表 | 列 | 唯一键 / 索引 |
| --- | --- | --- |
| `lottery_draw_count_lot` | `id` AI / `user_id` / `amount INT` / `remain INT` / `expire_at` / `reason_type VARCHAR(20)`（`REFERRAL`·`REDEEM`） / `ref_id BIGINT`（成团 id / 兑换单 id） / `created_at` | `uk_user_reason_ref(user_id, reason_type, ref_id)`（票 04 定的消费幂等键落点）；`idx_user_expire(user_id, expire_at)` |
| `lottery_draw_count_consume` | `id` AI / `user_id` / `lot_id` / `amount INT` / `reason_type VARCHAR(20)`（`DRAW`） / `ref_id BIGINT`（= 抽奖记录 id） / `created_at` | ~~`uk_user_reason_ref(user_id, reason_type, ref_id)`~~ **⚠️ 同上修正**：与 `points_consume` 同构，同样按 `(lot_id, reason_type, ref_id)` 建 uk（一笔抽奖跨桶消费会产生多行，按用户维度的 uk 会顶掉第 2 行）——**票 07 落地时照此建，别再建 `uk_user_reason_ref`** |

- 「次数从哪来」必须留：**票 04 已把消费侧幂等键定在这张流水的 uk 上**，没有它 MQ 重投就会重复加次数
- 有效期同积分：`draw_count_valid_days` 种子 **30**（**⚠️ 反转 票 01 §6 的「次数永不过期」**），过期走 xxl-job `lotteryDrawCountExpireScan`

**⑨ 每日抽奖计数（Q7；落 DB 不落 redis）**：`lottery_draw_daily` —— `id` AI / `user_id` / `draw_date DATE` / `cnt INT`；`uk_user_date(user_id, draw_date)`

- 理由：这是**每人低频**、非全局热点；DB 天然持久（redis 重启不清零，防刷更硬）、可审计；本仓计数器一律走 MySQL（chat `user_seq`），redis 侧连 `RAtomicLong` 都没引入过

**⑩ `outbox_message`** 照抄单体形状。

### 3. 拉新 schema `bs_referral`

| 表 | 列 | 唯一键 / 索引 |
| --- | --- | --- |
| `referral_invite_code` | `id` AI / `user_id` / `code VARCHAR(16)` / `created_at` | `uk_user(user_id)`、`uk_code(code)` |
| `referral_group` 成团记录 | `id` AI / `inviter_id` / `invitee_id` / `code VARCHAR(16)`（实际使用的码） / `created_at` | `uk_invitee(invitee_id)`、`idx_inviter(inviter_id)` |
| `outbox_message` | 照抄单体形状 | `uk_msg_id` |

- **邀请码懒生成**（Q11）：用户首次查「我的邀请码」时生成，8 位、字符集 `23456789ABCDEFGHJKMNPQRSTUVWXYZ`（去易混的 `0/O/1/I/L`），撞 `uk_code` 重试。不接受「注册时生成」——注册在单体，跨服务跟不上
- **唯一键只 `uk(invitee_id)`**（Q12）：它已**蕴含**「同一对只一个团」（一对要成两个团，要么同一 `invitee_id` 两次被 uk 挡，要么一次 a→b 一次 b→a，而 b→a 会因「a 注册早于活动」被业务校验挡掉）；不冗余 `uk(inviter_id, invitee_id)`
- 「不能邀请自己」「注册时间 ≥ `start_at`」是**业务校验**（Dubbo 读 `createdAt`），不落 DDL 约束
- **不建 `referral_config`**（Q17 反转：无成团送积分）

### 4. 跨 schema 纪律（票面 ④，进 spec 当硬约定）

三个服务互相**只存 id**；**不 join、不存昵称头像快照**；用户信息一律经 Dubbo（`IUserQueryService`）取回后在应用层拼。

### 5. 行级时序（Q19–Q25）

**① 填码成团**（Q19）
1. 本地查 `referral_invite_code` 由 `code` 得 `inviterId`（码不存在 → 报错）
2. 业务校验「不是自己」：`inviterId != inviteeId`
3. **事务外** Dubbo 取 `createdAt`，校验 ≥ 活动 `start_at`（老用户 → 报错）
4. 进事务：`INSERT referral_group`（撞 `uk_invitee` = 「你已被邀请过」）＋ 同事务写 `outbox_message('bs-referral-grouped')`
5. 提交

「一人一次」的正确性**由唯一键兜、不靠先查后写**（并发重复提交也只成一次）。

**② 抽奖**（Q20）
1. **预检**（便宜，拦下大多数失败）：读 `lottery_activity`（开关 / `start_at`~`end_at` / `daily_limit`）、读每日计数、读次数余额（`SUM(remain)`）
2. **权重滚档**：在「有库存的档」里按权重归一选一档（积分档与谢谢参与档恒有货）
3. **redis `DECR` 桶**（实物档）——成功即占用；不足 → `INCR` 回补并**剔除该档重新归一**（票 01 §6 的跳过语义，天然实现，不需要预读桶余额）
4. **一个 DB 事务 T**：每日计数原子递增并校验上限（`INSERT ... ON DUPLICATE KEY UPDATE cnt=cnt+1` 后判 `cnt <= daily_limit`，超限抛异常）＋ **扣次数（FIFO 桶消费）** ＋ 插 `deduct_detail(status=0)` ＋ 插抽奖记录 ＋ 中奖则写 `outbox_message('bs-lottery-drawn')`
5. **T 成功** → 提交、返回结果、afterCommit 发事件（**所有中奖都发**，积分消费方按 `prizeType==POINTS` 决定加不加分；谢谢参与不发、但**仍落记录行**）
6. **T 失败**（次数不足 / 每日超限 / 其它）→ 事务整体回滚（次数、计数、明细全还原）→ **唯一要补偿的是 redis：`INCR` 回桶** → 返回失败

⇒ **只把 DB 侧收进一个事务，redis 是事务外唯一副作用**，抽奖不需要任何跨系统分布式事务。

**③ 合并提交**（Q21）
- xxl-job **cron 每 1 秒**（`0/1 * * * * ?`）、**扫描不限量**（不加 `LIMIT`，一次把 `status=0` 全扫掉）；路由「第一个」+ 阻塞「丢弃后续调度」保证单实例不并发
- 事务内：`lottery_prize.sq -= 合并量` / `lq -= 合并量` / `oq += 合并量` ＋ `lottery_bucket_config.remaining -= 合并量` ＋ **按本批带回的 biz id 列表翻 `status=1`**
- **绝不写 `where status=0`**：扫描后新插入的明细会被误翻 ⇒ 少扣 ⇒ 超卖（实现方案3 §5 的原话，不限量也必须守）

**④ DB 直扣兜底**（Q10 判据 + Q22 口径）
- **判据**：每次扣减 `try { 桶 DECR } catch (RedisException|RedisTimeoutException)` + Redisson 连接超时设短（约 1000ms）⇒ 切 DB 直扣；服务内维护 `volatile`「降级中」标志 + **10s 冷却**，冷却期内直接走 DB（极简熔断，避免每请求吃满超时）
- **兜底事务 T**：`UPDATE lottery_prize SET sq = sq - 1, oq = oq + 1 WHERE id=? AND sq - lq - 1 > 0`（影响 0 行 = 库存不足）＋ 扣次数 ＋ 每日计数 ＋ 插抽奖记录 ＋ 插 `deduct_detail(bucket_id='__db_direct__', status=1)`（哨兵 bucket、当场已扣无需合并）
- ⚠️ **`oq += 1` 是票 01 §7 原文没有的一步**（原文只写 `sq` 减）：本票决定**加上**，否则 `oq` 少记、「已中奖」台账与 `sq` 展示口径漂移
- **开桶拨 80%、留 20% 非池**（Q10）：否则 `sq - lq = 0`，兜底永远卖不出、形同虚设

**⑤ 兑换两条边**（Q23）
1. 本地**先插 `lottery_redeem_order(status=PENDING)`**、拿自增 id 当**幂等键**（必须先落库才有 id）
2. **Dubbo 同步调积分服务**扣分（积分侧按 `ref_id = redeemOrderId` 消费桶，撞 `uk_user_reason_ref` 即跳过）
3. 本地事务：`kind=DRAW_COUNT` → 插 `lottery_draw_count_lot(amount=1, reason_type=REDEEM, ref_id=redeemOrderId)`；`kind=PRIZE` → **走与抽奖相同的那套桶**（redis `DECR` / 兜底）+ 落 `deduct_detail(biz_type=REDEEM, biz_id=redeemOrderId)` ＋ 插 `lottery_user_prize(source=REDEEM, source_ref_id=redeemOrderId, status=PENDING)`
4. 置 `redeem_order.status = DONE`
5. **失败重试安全**：同 `redeemOrderId` 重试——积分侧幂等跳过、抽奖侧按 `uk_prize_biz` 幂等跳过，只需补齐未完成的那步
6. 极端残留（扣分成功、本地永远失败）→ 停在 `PENDING` 供人工对账；**不做真补偿回滚**（票 01 §2 的「幂等 + 重试」铁律）

兑换实物**不滚权重**（直接指定 `prize_id`），与抽奖共用同一份库存（同一批桶）。

**⑥ 实物领取**（Q24）
- 提交领取表单成功时：事务内 `user_prize.status: PENDING → RECEIVED` ＋ 落收件人 / 手机号 / 地址 ＋ **`lottery_prize.oq -= 1`**
- 理由：**没有物流状态机**（票 01 §6 不做），「用户领取」是唯一存在的可落账时点；`oq` 语义 = 「已中奖 / 已兑换、尚未被领取」

**⑦ 开桶**（Q25；手工接口，运营上架奖品 / 开启活动时调）
1. 参数 `prize_id` + 拨多少（数字 / 百分比，**默认 80%**）
2. 生成随机 `bucket_id`
3. **DB 事务**：`lottery_prize.lq += N` ＋ 插 `lottery_bucket_config(status=1, remaining=N)`
4. **事务提交后** redis `SET bucket_key = N`（**不是 `INCR`**，避免同桶开两次额度叠加）
5. **幂等重开**：若该奖品已有「开启中」的桶行，**不新建桶**，用该行 `remaining` 重新 `SET`（修复合 / 重试）；**单桶**（一个奖品同时只有一个开启桶）
6. `SET` 失败 → 接口返回失败、运营重试（走 5 的修复路径）

先 DB 后 redis：避免「redis 有额度但桶行不存在」导致合并提交找不到桶行而少扣。~~**接口路径与鉴权归票 06**~~ **票 06 已定**：`POST /api/lottery/buckets/open`（ADMIN 鉴权、不进白名单，见 [票 06 §2](06-marketing-api-contract.md)）。

### 6. 迁移与种子（Q13/Q26）

- 迁移留在共享 `backend/sql/migrations/`（map 明写共享设施留 `backend/` 层），续编：**`022_points.sql` / `023_lottery.sql` / `024_referral.sql` / `025_marketing_seed.sql`**（一 schema 一份 + 种子单独一份，便于演示重置）
- **`init.sql` 原地不动**（单体快照）；**新建 `backend/sql/init-marketing.sql`**：`CREATE DATABASE` 三个 schema + 建表 + 种子一把起，`docker-compose.yml` 注释补一行执行命令
- 种子内容：`lottery_activity` 单行（`start_at` = **过去日期** / `end_at` = 未来 / `daily_limit` / `enabled` / `draw_count_price` / `draw_count_valid_days=30`）＋ `points_config(points_valid_days=365)` ＋ **3~5 档奖品**（含谢谢参与行、积分档、实物档，各带 `weight` / 库存 / `redeem_points`）
- 全部**幂等可重跑**（`CREATE DATABASE IF NOT EXISTS` / `CREATE TABLE IF NOT EXISTS` / `INSERT IGNORE`）
- **开桶不在种子里**：SQL 写不进 redis，靠运营调 §5⑦ 的手工接口；这条要写进 `backend/docs/db/` 的营销文档

### 决策依据（事实自核）

1. 全仓**无 `JSON` 列类型**（MySQL `9.7` 支持但不用）⇒ 奖池快照存 `TEXT`，与 `outbox_message.payload` 同形状。
2. 全仓**无 `RAtomicLong`**（只有 `RBucket`）⇒ 桶的 `DECR`/`INCR` 是引入的新形状（`redissonClient.getAtomicLong`），`SET` 仍用 `RBucket`。
3. **无雪花 id**，业务主键一律自增 + `useGeneratedKeys` ⇒ `deduct_detail.biz_id` 存的是抽奖记录 / 兑换单的自增 id；**自增 id 各表局部、不跨表可比**（用户 Q28 的话），所以幂等键必须带 `biz_type`。
4. `wallet_transaction` 的 `biz_round` 是 `019_errand_multi_taker_income.sql` 为「一个跑腿单多个跑手各一条 INCOME」加的；本域无「同一 ref 多条」场景 ⇒ **不带轮次**。
5. **反转 1（Q1）**：「余额列 + 条件更新防负」换成「桶化求和 + FIFO」——票面 ① 的两案都不选，用户提出第三种。
6. **反转 2（Q2）**：「成团只加抽奖次数」⇒ 删 `REFERRAL_BONUS`、删 `referral_config`、`bs-referral-grouped` 事件体去 `bonusPoints`、**积分服务不再订阅该 topic**。已同步改 [票 01 §3·§5](01-baseline-decisions.md)、[票 04 §4](04-service-skeleton-and-wiring.md)、`spec.md`，各留 ⚠️ 反转注记。
7. **反转 3（Q6）**：票 01 §6 原写「**次数永不过期、可累积**」，用户改口「**抽奖次数也会过期**」并采用与积分同款的桶化模型。已同步改 [票 01 §6](01-baseline-decisions.md) 留 ⚠️ 反转注记。
8. **修正 1（Q28）**：`deduct_detail` 幂等键 `(prize_id, biz_id)` → `(prize_id, biz_type, biz_id)`。已同步改 [票 01 §7](01-baseline-decisions.md) 留 ⚠️ 修正注记。

### 转下游

- **票 06**（**已 resolved**）：接口清单（含开桶接口的路径与鉴权、免登录白名单）、分页排序、失败态文案 —— 见 [票 06 Answer](06-marketing-api-contract.md)。**它反过来回填了本票两处**：① `lottery_draw_record` 加 `client_token` + `uk_user_token`（本票 §2⑤ 已留 ⚠️ 注记）；② `lottery_prize` 加 `total`（本票 §2① 已留 ⚠️ 注记）。
- ~~**票 07 ③**：营销通知的 `type` / `refType` 取值~~ **2026-09-14 已定**：新增三枚举 + `refType = MARKETING` + `scope = BOTTOM_SYSTEM`。⚠️ **另：票 07 回填本票的领地**——单体 `notification` 表加 `scope` / `seq` 两列 + `notification_seq` 计数器 + `notification_read` 水位表 + `is_read` 退休（迁移 `026_notification_read.sql`）；读模型照抄 chat 的 `user_seq` / `timeline.delivery_seq` / `room_user.last_read_seq`（见 [票 07 §5](07-market-entry-hub-contract.md)）。
- **落地票**（`.scratch/marketing-impl/`）：`backend/docs/db/` 三个 schema 文档、`backend/docs/architecture/marketing-services.md`、`docker-compose.yml` 的 `init-marketing.sql` 注释；另票 06 转来**新建三个索引 README**（当前不存在）。
