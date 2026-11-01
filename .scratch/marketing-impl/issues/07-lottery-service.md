# 07: 抽奖服务（数据模型 + 目录与单抽 + 奖池库存）

**Spec:** `.scratch/marketing/spec.md` §「营销域」→「数据模型与行级时序」第 2 / 3 / 4 / 7 条「接口口径与失败语义」+ `.scratch/marketing/issues/05-marketing-data-model.md`、`06-marketing-api-contract.md`（含两处回填：`lottery_draw_record.client_token`、`lottery_prize.total`）

**What to build:** 抽奖服务从零到「能抽一次」的完整链路：`bs_lottery` schema 全建 + 种子、活动与奖品两个免登录读接口、单抽主链路（预检 → 权重滚档 → 扣次数 → 落记录 → 发事件）、奖池 redis 单桶 + 开桶接口 + 合并提交 job + DB 直扣兜底。

这是本 effort 最大的一票，含三块相对独立的机制，可分批做但一次收口。

**Blocked by:** 01（骨架）、06（要真发一条 `bs-referral-grouped` 才能验收「成团加次数」）

**Status:** resolved

### 一 · 数据模型与种子

> **⚠️ 票 06 留的交接（动手前必读）**
> 1. **`lottery_activity` 已经建好了**：票 06（拉新）要直读它判活动起止，所以提前落在
>    `sql/schema-lottery.sql` 与 `sql/migrations/023_lottery.sql` 两个文件里（**列已按下面这条写全**，含种子：
>    `start_at` 过去 / `end_at` 未来 / `enabled=1`）。
>    **往这两个文件末尾追加**其余表与奖品种子，**不要重建这两个文件**——重建会把活动配置连同种子顶掉，
>    拉新立刻读不到活动窗口。
> 2. **建库文件与脚本不要再动**：`sql/init-marketing-{dev,test}.sql` 已建全三个库，
>    `scripts/test-env.sh` 的 `apply_marketing_init <建库文件> <库名> <DDL文件>` 已是终态（三个服务各一行）。
> 3. **`bs_lottery_test` 也已建好**，改过 schema 后用 `scripts/test-env.sh reset-test-db` 重建即可。

- [x] schema `bs_lottery`：~~`lottery_activity`（**单行** `id TINYINT PK(=1)`，含 `name` / `start_at` / `end_at` / `daily_limit` / `enabled` / `draw_count_price` / `draw_count_valid_days`（种子 30））~~ **已在票 06 落好**、`lottery_prize`、`lottery_draw_record`、`lottery_draw_count_lot` / `lottery_draw_count_consume`、`lottery_deduct_detail`、`lottery_user_prize`、`lottery_redeem_order`、`lottery_bucket_config`，外加本服务自己的 `outbox_message`；**追加**到 `schema-lottery.sql` 与迁移 `023_lottery.sql`（两文件已存在）
- [x] **奖品表与库存表合并**成 `lottery_prize`（本域奖品与库存严格 1:1，拆开只多一次 join）；`type = PHYSICAL | POINTS | THANKS`；**只有实物档进 `sq` / `lq` / `oq`**
- [x] `lottery_prize.total` = 投放总量（建品时定、**恒不变**，目录「剩 x / y」的分母）；**不能拿 `sq + oq` 顶替**（实物领取会 `oq -= 1`，分母会漂）
- [x] **「谢谢参与」落一行 `type = THANKS`**（有 `weight`、无库存、恒有货）
- [x] **每次抽奖都落一行 `lottery_draw_record`**（含谢谢参与），带 `random_value BIGINT` + `pool_snapshot TEXT`（无 JSON 列类型，快照存 `TEXT`）
- [x] `lottery_deduct_detail` 幂等键 = **`uk(prize_id, biz_type, biz_id)`**（`biz_id` 有两个 id 空间：`LOTTERY` 用抽奖记录 id、`REDEEM` 用兑换单 id；自增 id 不跨表可比，**漏 `biz_type` 会少扣库存**）+ `idx_merge` 覆盖索引
- [x] `lottery_draw_record.client_token` + `uk_user_token(user_id, client_token)`（抽奖幂等）
- [x] `lottery_user_prize`（中奖与兑换**汇入同一张**，`source` / `source_ref_id`，三态 `PENDING` / `RECEIVED` / `CREDITED`，`uk(source, source_ref_id)`）+ `lottery_redeem_order`（`id` 即兑换幂等键）
- [x] 抽奖次数用**与积分同构的桶模型**：`lottery_draw_count_lot` + `lottery_draw_count_consume`；`reason_type` = `REFERRAL`（成团）/ `REDEEM`（兑换）/ `DRAW`（消费）；**「次数从哪来」必须留**
- [x] ~~种子并入 `025_marketing_seed.sql`~~ **改为追加到 `schema-lottery.sql` + `023_lottery.sql`**（用户 2026-09-15 拍板，照 map 明写的口径）：活动（**`start_at` 设成过去日期**，否则演示时自己注册的测试号填码会被拒——那是预期行为不是 bug）、3~5 档奖品 + 权重 + 兑换定价 + 每日上限；**开桶不在种子里**（SQL 写不进 redis）。**不建 025**：`apply_marketing_init` 只跑 `init-marketing-*.sql` + `schema-<svc>.sql`，025 没人执行、还会与 schema 里那份种子互相漂移
- [x] ~~新建 `backend/sql/init-marketing.sql`（建三 schema + 表 + 种子一把起）~~ **票 06 已把建库口径落成终态**：`init-marketing-{dev,test}.sql`（只建三个库、不切库）+ `scripts/test-env.sh` 的 `apply_marketing_init`（把 `USE` 插在建库文件与 DDL 主体之间）。map 明写「07 不要再改 init/脚本」，`init.sql` **原地不动**

### 二 · 目录与单抽

- [x] `GET /api/lottery/activity`（**免登录**）→ `{name,slogan,startAt,endAt,status,dailyLimit,drawCountPrice,drawCountValidDays}`，`status ∈ NOT_STARTED · ONGOING · ENDED`
- [x] `GET /api/lottery/prizes`（**免登录**）→ `[{prizeId,tier,name,image,type,pointsAmount,redeemPoints,remaining,total,unlimited}]`；库存**只暴露 `remaining = sq`** 与 `total`，**不暴露 `lq` / `oq`**；POINTS / THANKS 档给 `unlimited: true`、两字段 null
- [x] `GET /api/lottery/draw-chances` → `{ available, todayDrawn }`（`available` = 未过期桶 `SUM(remain)`）
- [x] `POST /api/lottery/draw` body `{token}`（`client_token`），**单抽**，时序照 spec 第 2 条：预检（活动 / 每日上限 / 次数余额）→ **权重滚档**（该档库存扣光则跳过、重新归一）→ redis 桶 `DECR`（不足则回补 + 剔除该档重新归一）→ **一个 DB 事务**（每日计数 + 扣次数 + 扣减明细 + 抽奖记录 + 中奖落 `lottery_user_prize` + outbox）→ 成功发事件
- [x] **DB 事务失败只需补偿 redis（`INCR` 回桶）**——DB 侧只有一个事务，无跨系统分布式事务
- [x] **谢谢参与也走成功**（`prizeType = THANKS`）；**谢谢参与不产 `lottery_user_prize` 行**；积分档中奖产行并直接落 `CREDITED`（积分由事件加到积分服务）；实物档落 `PENDING`
- [x] 撞 `uk_user_token` **回放**已存结果（不重复抽、不重复扣次数）——这是本仓「幂等 + 重试」铁律在唯一一个「失败会让用户真亏一次抽奖」的写接口上的落地
- [x] `GET /api/lottery/records` 我的中奖记录，**过滤 `prize_type = THANKS`**，`created_at DESC`；活动信息与奖品目录**不分页**
- [x] 消费 `bs-referral-grouped` 加抽奖次数（消费组 `bs-lottery-referral-grouped-group`），幂等键落 `lottery_draw_count_lot` 的 `uk_user_reason_ref`（`reason_type = REFERRAL`、`ref_id = groupId`）
- [x] 发 `bs-lottery-drawn` `{recordId, userId, prizeId, prizeType, pointsAmount}`（走本服务 outbox，`MAX_RETRY_COUNT = 5`，死信 `status = 2`）
- [x] 失败码 + 固定中文文案：`ACTIVITY_NOT_STARTED` / `ACTIVITY_ENDED` / `NO_CHANCE` / `DAILY_LIMIT_REACHED`；活动 `end_at` 后抽奖一律拒（`enabled = 0` 同理）

### 三 · 奖池库存

- [x] **redis 单桶**：本仓无 `RAtomicLong` 先例，`DECR` / `INCR` 是新形状；key 前缀进本服务 `Constants`
- [x] `POST /api/lottery/buckets/open`（**ADMIN**）body `{prizeId,percent?,amount?}`：DB 事务（`lq += N` + 插桶行，**默认拨 80%**）→ **提交后** redis `SET`（不是 `INCR`）；**已有开启桶行则用该行 `remaining` 重 `SET`，不新建桶**
- [x] **开桶留 20% 非池**：否则 `sq - lq = 0`，DB 直扣兜底永远卖不出、形同虚设
- [x] **合并提交**：xxl-job **每秒**、**扫描不限量**（不加 `LIMIT`）；事务内 `sq` / `lq` / `oq` + 桶 `remaining` 同步 + **按本批带回的 biz id 列表翻 `status = 1`**（**绝不写 `where status = 0`**，否则超卖）
- [x] **DB 直扣兜底**（spec 第 4 条）：判据 = `try/catch` redis + 短超时 + **10s 冷却**降级；`WHERE sq - lq - 1 > 0` **只卖非池**；明细 `bucket_id` 用哨兵 `__db_direct__`、`status` 落库即 `1`；**`oq += 1`**（票 01 §7 原文只写 `sq` 减，此处补上）
- [x] 开桶接口的失败码：`PRIZE_NOT_FOUND` / `PARAM_ERROR`

### 验收

- [x] 抽到实物 / 积分 / 谢谢参与各一次，记录与 `lottery_user_prize` 落行正确；同 `client_token` 重放不重复抽
- [x] 某档库存扣光后该档被跳过、权重重新归一（不再抽到）
- [x] 开桶 → redis 为权威 → 连续抽若干次 → 合并 job 把 `sq` / `lq` / `oq` 与桶 `remaining` 同步正确、明细 `status` 只翻本批
- [x] 停掉 redis → 降级走 DB 直扣仍能卖出（且 10s 冷却内不反复重试）；恢复后回到 redis 路径

## 实现要点（票 07）

### 落了什么

`backend/lottery/` 从「只有骨架」到「目录 / 单抽 / 奖池库存一条链路跑通」，本 effort 最大的一票。

| 面 | 落点 |
| --- | --- |
| SQL | `sql/schema-lottery.sql` + `sql/migrations/023_lottery.sql` **末尾追加** 10 张表 + 奖品种子 + `lottery_activity` 补 `slogan` 列的幂等守卫 |
| types | `Constants`（topic / 消费组 / 桶 key 前缀 / 哨兵 / 降级冷却 key）、`ResponseCode` 五个 4xxx、七个枚举、`BucketUnavailableException` |
| api | `ILotteryService` + 8 个 DTO + `PageResult`；api pom 补 `jakarta.validation-api` |
| domain | 7 个仓储接口 + 7 个实体 + 7 个 VO + `IPrizeBucketPort`；五个服务（目录 / 抽奖 / 次数账本 / 开桶 / 合并提交）+ `PrizeWeightRoller` + `BucketDegradeGuard` |
| infrastructure | `ILotteryDao` + 10 个 PO + `LotteryMapper.xml`、七个仓储实现、`PrizeBucketPort`（redisson）、outbox 三件套（逐字照抄单体、只换包名） |
| trigger | `LotteryController`、`ReferralGroupedConsumer`、`DeductMergeJob`、`DrawCountExpireScanJob` |
| app | datasource（`bs_lottery`）、redis（逻辑库 0）、mybatis、rocketmq、`MybatisConfig` / `TransactionConfig`、`SecurityConfig` 白名单两条 |
| 测试 | `lottery-app/src/test`：`LotteryApiTestBase` + `SwitchablePrizeBucketPort` + 4 个测试类共 **30 个用例** |
| 文档 | `docs/api/lottery.md`、`docs/db/lottery.md`，并更新 `docs/{api,db}/README.md`、`docs/architecture/marketing-services.md`、`docs/testing.md` |

### 动手前定的两件事（用户 2026-09-15 拍板）

1. **`lottery_activity` 加 `slogan` 列**：票 06 的接口清单要求 `/api/lottery/activity` 返回它，但票 06
   按设计票 05 的列清单落的表里没有这一列。MySQL 无 `ADD COLUMN IF NOT EXISTS`，所以在两个 DDL 文件
   **末尾追加**一段 `information_schema` 判断的幂等 `ALTER` + 回填 `UPDATE`——**不动票 06 那一块**，
   整份脚本仍可整体重跑（已实跑两遍验证：第二遍走 `SELECT 1` 空操作）。
2. **种子只追加到 `schema-lottery.sql` 与 `023_lottery.sql`，不建 `025_marketing_seed.sql`**（票面原文提了 025，
   但 map 比票面新、且明写「只需往这两个文件末尾追加其余表与奖品种子」）。理由：`apply_marketing_init`
   只跑 `init-marketing-*.sql` + `schema-<svc>.sql`，025 没人执行，还会与 schema 里那份种子互相漂移。
   05/06 也都没建 025。

### 实现细节里值得记的

- **「滚档 + 占库存」两份路径合并成一个 `choosePrize`**：redis 路径与 DB 直扣路径的循环骨架完全一样
  （滚一档 → 实物档试着占 → 占不到就剔除、用剩下的权重重掷），只有「怎么占」不同。把占用方式做成
  入参（`Function<PrizeEntity,String>`，占不到返回 `null`、占到返回明细要记的 bucketId），两条路径各剩一行。
  这是 code-review 采纳项（Duplicated Code）。
- **`PrizeWeightRoller` 里的 `excluded` 集合就是「库存扣光则跳过、重新归一」的落点**：不预读库存，
  而是把判定放在实扣那一刻——预读与实扣之间有窗口，重掷没有。
- **「没开桶」= 该档不可卖**：redis 路径先 `findOpen`，没有桶行就剔除该档。含义是**非池库存只是
  redis 故障时的储备**，正常路径一分不卖（与「开桶留 20% 非池」的设计一致）。
  ⚠️ 副作用：**奖池里若一个可用的非实物档都不剩**（比如把谢谢参与的 weight 也改成 0）而实物档又都没货，
  滚档会抛 `IllegalStateException` → 返回 `999`。种子里谢谢参与恒在，正常运营走不到这里，属配置故障。
- **降级熔断放在域层**（`BucketDegradeGuard`）而不是 infrastructure：它决定抽奖走哪条分支，是领域算法的一部分，
  也因此能被测试直接驱动（冷却时长走 `lottery.bucket.degrade-cooldown-millis`，测试侧压到 300ms）。
- **`release` 失败不掩盖原始异常**：`releaseQuietly` 只记日志——池子少一个额度（偏保守，不会超卖），
  比让运维看不到真正的失败原因强。
- **合并提交按「本批明细 id」翻状态**（`markMerged(ids)`），不是按 `biz_id` 也不是 `WHERE status = 0`：
  id 是这批扫描真正带回的行，是本仓能给出的最强「只翻本批」表达。用例
  `markMergedOnlyFlipsThisBatch` 专门钉住「扫描之后新插入的行必须留在待合并」。
- **`redissonClient.getAtomicLong(key).get()` 会把不存在的 key 建成 0**：测试里断言桶值前先
  `getBucket(key).isExists()`，否则永远看不出「这个桶从没被碰过」。
- **`giveChances` 的 ref_id 走发号器**：撞 `uk_user_reason_ref` 的话第二个桶会被唯一键挡掉，测试会假绿。

### 验证怎么做的

`cd backend/lottery && mvn -o test -Dsurefire.failIfNoSpecifiedTests=false` → **30 个用例全绿**：

| 用例类 | 数量 | 覆盖 |
| --- | --- | --- |
| `LotteryCatalogIntegrationTest` | 5 | 活动信息免登录 + status 三态、奖品目录（只暴露 `remaining`/`total`、无 `lq`/`oq`、不限量档 `unlimited`）、抽奖次数 = 未过期桶求和、三个个人接口 401 |
| `LotteryDrawIntegrationTest` | 14 | 实物 / 积分 / 谢谢参与三种结果各落行正确（明细、我的奖品态、事件、次数扣除）、同 token 重放、不同 token 两次、NO_CHANCE（含过期桶）、DAILY_LIMIT_REACHED（且不多扣次数）、活动三态闸门、缺 token 400、某档池空后被跳过、中奖记录过滤谢谢参与且只看自己、奖池快照是合法 JSON |
| `LotteryBucketMergeIntegrationTest` | 6 | 开桶默认 80%、指定 amount、超量拒绝、重开不叠加只重 SET、五类失败码、合并提交同步三列与桶 remaining、**明细只翻本批** |
| `LotteryDrawDegradeIntegrationTest` | 5 | redis 挂 → DB 直扣（`__db_direct__`、`status=1`、`oq+1`、不碰池子）、冷却内只试一次 redis、冷却后回到 redis 路径、非池库存为 0 被剔除、没开桶的实物档被剔除 |

- 顺序实跑过：`reset-test-db` 重建三库 → lottery 30 全绿 → **referral 23 全绿**（它跨库直读
  `bs_lottery_test.lottery_activity`，本次给它加了列，回归确认没打破）。
- **前端未动**：`/api/lottery/**` 的页面调用归票 11；Vite 代理由票 01 落好。

### code-review 采纳项

两轴并行，采纳 5 条：

1. **Standards 轴**：`RedissonPrizeBucketPort` 违反 `backend/CLAUDE.md` §3「port 实现类名 = 接口去掉 `I`」
   ⇒ 改名 `PrizeBucketPort`（照 `UserQueryPort implements IUserQueryPort`）。
2. **Standards 轴**：`drawThroughPool` / `drawThroughDatabase` 重复了整段滚档循环 ⇒ 抽出 `choosePrize`（见上）。
3. **Standards 轴**：`publishDrawnEvent(userId, recordId, prizeId, prizeType, pointsAmount)` 把
   `LotteryDrawnMessage` 的字段又列了一遍（Data Clumps）⇒ 直接收 `DrawRecordEntity`。
4. **Standards 轴**：测试基类 `openBucket(long prizeId, String jsonBody)` 的首参没人读 ⇒ 删掉。
5. **Spec 轴**：开桶的 `PARAM_ERROR` **info 是动态拼的**，违反票 06 §3「info 逐条固定、不由后端动态生成」
   （且票面给的开桶文案是「参数不合法」）⇒ 改为固定文案，具体原因走 `log.warn`。

未采纳（记录理由）：

- **抽奖事务没用 Aggregate**（§2「多 entity 一次事务存储时封装聚合对象」）：点 05 的 `deduct` 与票 06 的
  `createGroup` 同样没用，是本仓营销服务的既定形状；且这里的五张表是异构的（记录 / 明细 / 次数 / 我的奖品 /
  每日计数），**每日上限的判据要等计数递增后回读**——把这条业务规则塞进聚合的「一次存储」会把它推进
  infrastructure。域服务里那个 `persistDraw` 就是这段事务的边界。
- **`lottery_prize.version` 没建**：设计票 05 §2① 列了它，但本域两条库存通路都用
  「`WHERE` 里带守卫、影响 0 行即失败」而不是乐观锁，没有任何代码读它。加一列没人读的列比这处偏离更糟。
- **`lottery_bucket_config` / `lottery_deduct_detail` 的时间列叫 `created_at` 而不是设计票写的 `create_time`**：
  全仓其余表（含同域的 `lottery_prize` / `lottery_draw_record`）一律 `created_at`，设计票自己也不一致。
- **`bucket_id` 用 `__db_direct__` 哨兵字符串**（Primitive Obsession）：票面明文规定了这个哨兵，照做。

### 已知波及 / 不在本票范围

- **票 08 的写入口已建好表、未建接口**：`lottery_redeem_order`、`lottery_user_prize`（读面与领取）、
  `lottery_prize.redeem_points`、`ResponseCode` 的 `4306` 起号段都留给票 08。
- **redis 真停服的降级未验**：本票用测试桩抛 `BucketUnavailableException` 表达「redis 挂」，没有真停容器；
  真停服 + 10s 冷却（生产值）归联调。
- **合并提交 job / 次数过期 job 未在调度中心配**：本票只落 `@XxlJob` handler 与实跑服务方法，
  cron（`0/1 * * * * ?`）与「路由第一个 + 丢弃后续调度」由部署侧配。
- **单桶限制**：一个奖品只能开一次桶（重开只重 SET）。关桶 / 多桶分摊 / 重建看门狗在本 effort 的
  Out of scope 里。
- **实物领取会 `oq -= 1`**：本票只把 `oq` 加到「已中奖未领取」，减的那一步在票 08 的领取接口。
- **`bs-referral-grouped` 的真投递未验**：测试栈有 namesrv 没 broker，加次数用直调消费者 Bean 验；
  真投递与单体落通知归票 09。
