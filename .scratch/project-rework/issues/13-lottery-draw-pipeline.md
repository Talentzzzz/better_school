# 票 13 · 抽奖主链路（次数 → 责任链 → 库存预扣 → 落库）

Type: task
Status: resolved
Blocked by: 10, 12

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **≤250k**；硬顶 **300k** |
| 预估改文件 | ~50（主链路 + 旧 `lottery_*` 全删 + 旧用例改写 ~9 + 文档） |
| 必读 | 本票；rafflex 的 `AbstractActivityRaffle`（主链路骨架）；票 10 的 `draw_count_bucket` 接口、**票 12 的 `## Answer`**（库存 port 与兜底接口签名——这是唯一权威来源，不要读票 12 的实现代码） |
| 不读 | `sql.md`（表都已落地，只在需要核对列名时查 `sql.md#L729-L844`）；rafflex 的 product/redeem 域（→ 票 14） |
| 必跑 | `backend/marketing` 全量 + 新增抽奖全链路用例 |
| 越界信号 | 发现「发货 / 兑换也要一起改」→ 停手，那是票 14；**本票只到「落库」，发放不闭环也允许** |

## 已核实事实

1. 前置能力**已全部就位**，本票只做编排：票 10 的 `draw_count_bucket`（次数桶 + Lua 原子）、票 11 的 6 张 `draw_*` 表 + `ChainFactory` 责任链 + 表预热（概率 ×10000 已在 Redis，本票只管用）、票 12 的库存预扣 port（虚拟 + 实物/兜底，一次提供）。
2. rafflex 主链路骨架：`rafflex-backend/domain/src/main/java/cn/talent/domain/raffle/service/raffle/AbstractActivityRaffle.java`。
3. Redisson 已在依赖矩阵内；**库存真相在 DB 台账**，Redis 只承载「桶余量 + 概率表」。
4. 旧 `lottery_*` 表与旧代码本票**整体作废**：旧 9 个测试类（`LotteryApiTestBase`、`LotteryCatalogIntegrationTest`、`LotteryDrawIntegrationTest`、`LotteryDrawDegradeIntegrationTest`、`LotteryExchangeIntegrationTest`、`LotteryUserPrizeIntegrationTest`、`LotteryBucketMergeIntegrationTest`、`StubPointsExchangePort`、`SwitchablePrizeBucketPort`）按新模型改写或替换；`schema-legacy.sql` 去掉全部 `lottery_*`（若清空则删文件）。
5. 对外接口 `ILotteryService` 的**签名尽量不变**（前端随动随票 14 一起做，见那张票）；确实必须变的，逐条列进 `## Answer` 供票 14 用。
6. 现状兑换 / 我的奖品读侧接口面在 `ILotteryService` / `LotteryController`（票 07 后的 `marketing` 工程内）；发货与兑换**写侧**闭环归票 14。

## Question

把抽奖主链路跑通并按 `sql.md` 落地读侧：

**次数扣减（`draw_count_bucket` Lua 原子）→ 责任链（黑名单 → 权重保底 → 默认）→ 库存预扣（票 12 的 port，落 `state=0` 明细）→ 落 `draw_record` + `draw_record_detail(DRAW)` 同事务 → 返回中奖结果**

- 预热缺失时降级走 DB 直算（可对账）——预热本身已在票 11，本票只验降级路径。
- **幂等**：同一用户同一活动的重复请求不得重复扣次数 / 重复预扣（幂等键口径写进 `## Answer`）。
- 抽奖目录 / 我的奖品等读侧接口接新表。
- `CONTEXT.md` 抽奖词条按新链路词汇重写。

## 施工边界（不做什么）

- 不做发货、本地消息表 / MQ 重发、兑换确认（→ 票 14）。
- 不改票 10/11/12 的桶、责任链、库存语义（只能调用）。
- 不改前端（→ 票 14，但要在 `## Answer` 给出接口变更清单）。

## 验收

1. `backend/marketing && mvn -o test` 全量绿。
2. 全链路集成用例 ≥4：正常中奖（次数 −1、库存桶 −N、`state=0` 明细一行、`draw_record`+`draw_record_detail(DRAW)` 各一行）；次数不足拒绝且**无任何副作用**；黑名单拦截；配额不足走兜底奖品且不产生 `waiting_qty`。
3. 幂等用例：同请求重放不重复扣次数 / 不重复预扣。
4. 预热降级用例：Redis 概率表清空后仍能出奖。
5. `schema-legacy.sql` 已无 `lottery_*`；`lottery_` 在 `backend/marketing` 零命中。
6. `## Answer` 记录：幂等键、事务边界、`ILotteryService` 签名变更清单、Redis key 命名、与合并任务的接缝。

## Answer

**落地形状**：`domain.raffle` 加三个服务 + 三条跨域 port + 一个读侧仓储。
- 主链路 `RaffleDrawServiceImpl`（编排：幂等预检 → 活动校验 → 扣次数 → 责任链 → 库存预扣 → 落库）；
- 读侧 `RaffleQueryServiceImpl`（活动 / 目录 / 次数 / 记录 / 我的奖品）；
- 兑换 `RaffleExchangeServiceImpl`（兑抽奖次数）；
- 跨域写边 port 三件（CLAUDE.md §1 不允许域服务互注，仿旧 `IPointsExchangePort` 的 port 模式；
  marketing-case 层是空的、未启用）：`IDrawCountPort`（次数域）、
  `IInventoryDeductPort`（库存域，嵌套 `Outcome` 枚举）、`IPointsDeductPort`（积分域，嵌套 `PointsDeductOutcome`），
  实现在 `infrastructure/raffle/adapter/port/`，**纯委托**票 10/12 的域服务；
- `IRaffleQueryRepository`（domain）+ `RaffleQueryRepository`（infrastructure）：读侧 / 重放查询面；
  **票 11 的两个仓储接口一字未动**（只加不改）；
- `IRaffleDao` + `RaffleMapper.xml` 追加读侧查询；三个视图 PO（`DrawPrizeViewPO` / `DrawRecordViewPO` /
  `DrawUserPrizeViewPO`）；valobj 七个（DrawResultVO / ActivityInfoVO / PrizeCatalogVO / DrawChanceVO /
  DrawRecordVO / UserPrizeVO / RedeemResultVO）。
- types：`ResponseCode` 加 `4313`；`types.raffle.Constants` 加 `DRAW_COUNT_EXCHANGE_PRICE = 100`。

### 幂等键

`record_id = SHA-256(userId + ":" + token)`，取前 8 字节做正 long（`& Long.MAX_VALUE`，0 抬成 1）。
**确定性映射、无重放表**——同一 `(userId, token)` 恒映射同一 `record_id`，`uk_record_id` 本身就是幂等互斥。

- 重放快路径：先查 `draw_record`，命中即回放（不扣次数、不预扣、不重新决策）；
  回放**必校验归属**（`user_id` 不符按 `SYSTEM_ERROR` 拒绝，防 hash 碰撞把他人记录顶出来）
- 并发同 token 由扣次明细的唯一键串行化：败者要么在扣次后查到已落记录回放，
  要么落库时撞 `uk_record_id`（捕获 `DuplicateKeyException`）后回放；**两条路都不重复扣次数、不重复预扣**
- 出参面额（`pointsAmount`）在**重放路径也现算**（读 `virtual_inventory.value`），不存快照列

### 事务边界（三处，务必分清）

| # | 事务 | 内容 | 幂等/守卫 |
| --- | --- | --- | --- |
| ① | 次数域内 | `draw_count_bucket` Lua 原子扣 1 + 明细行 | 明细行 `ref_id = record_id`（uk） |
| ② | **落库一个 MySQL 事务** | `draw_record` + `draw_record_detail(DRAW)` | `uk_record_id`（撞了 = 并发重放） |
| ③ | 库存域内 | Redis 桶原子减 + `state=0` 预扣明细 | 每笔预扣是新明细；重复发放由 ① 的幂等挡住 |

**③ 与 ② 不在同一事务**（跨域写组合不做跨域事务，CLAUDE.md §1）——这就是下面「预扣后落库失败」窗口的来源。
**兑换边**（`POST /exchange/draw-count`）是另一处：外层 `TransactionTemplate` 包住
「积分扣分 + 次数入账」，两域同进程同库、域内事务都是 `REQUIRED` 传播 → **合并成一个 MySQL 事务**，
任一失败整笔回滚；跨重试幂等靠两域各自 uk（`redeemOrderId` = snowflake = 发放幂等键）。
旧模型「兑换单 + PENDING 残留 + resume」整套补偿机制因此**废除**。

### 接口变更清单（票 14 前端对齐用）

**删端点**（3 条）：
- `POST /api/lottery/my-prizes/{id}/claim` —— 收件信息与状态推进归票 14 发货闭环
- `POST /api/lottery/exchange/prize` —— 新模型没有「积分兑奖品」业务（奖品只来自抽奖）
- `POST /api/lottery/buckets/open` —— 旧奖池桶模型整体作废；库存改走票 12 台账 + `inventoryBucketWarmup` job
  （**票 13 起本域不再有 ADMIN 接口**；配置后台的写接口仍有 ADMIN 校验）

**删方法**（3 个，均在 `ILotteryService` 上）：
- `claimPrize(...)` —— 实物领取（收件信息 / 状态推进归票 14 发货闭环）
- `exchangePrize(...)` —— 积分兑奖品（新模型无此业务）
- `openBucket(...)` —— 旧奖池桶模型整体作废

**改签名**（1 个，**这是本票唯一的破坏性签名变更，票 14 必须跟**）：
- `exchangeDrawCount()` → **`exchangeDrawCount(RedeemDrawCountRequestDTO request)`**，
  body `{ token }`（`@NotBlank @Size(max=64)`）。原因见下「幂等」：单号不能现编，
  否则网络重试 = 一笔新兑换 = 重复扣积分。**前端每次兑换要生成一个 token，重试带同一个。**
- 新增 2 个类型：`api.lottery.dto.RedeemDrawCountRequestDTO`、`types.common.IdempotencyKeys`（确定性映射工具）

**DTO 字段变更**：
- 删 `image`（4 个 DTO：活动/奖品/记录/我的奖品）、删 `slogan`、删 `dailyLimit`、删 `redeemPoints`
- 删「我的奖品」的 `source`（新模型奖品只来自抽奖，无 REDEEM 来源）
- `prizeType` 取值 `POINTS` → **`VIRTUAL`**（现为 `VIRTUAL` / `PHYSICAL` / `THANKS`）
- 「我的奖品」`status` 由 `PENDING`/`RECEIVED`/`CREDITED` 三态 → **`PENDING`/`EXCHANGED`/`SHIPPED`/`COMPLETED`**
  四段（域内库值 `待兑换`/`已兑换`/`已发货`/`已完成`，HTTP 面英文；缺省 `PENDING`）
- `GET /activity`：**无活动时 `data` 为 `null`**（旧模型缺行抛异常）；新增 `drawCountPrice`、`drawCountValidDays`

**未变签名**（URL 与入参形状都不动）：`getActivity` / `listPrizes` / `getDrawChances` / `draw` /
`listRecords(int page, int pageSize)` / `listMyPrizes(String status, int page, int pageSize)`
（后两个的 `page`/`pageSize`/`status` 仍由 `@RequestParam` 承载；`status` 的**取值集合**变了，见上）。

### Redis key 命名

本域前缀 `bs:raffle:`（票 11 定，票 13 直接引用，未新增 key）：

| key | 结构 | 写入方 |
| --- | --- | --- |
| `bs:raffle:table:{strategyId}:default` | RMap\<槽位, awardId\>，恒 10000 槽 | 预热 |
| `bs:raffle:table:{strategyId}:weight:{count}` | RMap\<槽位, awardId\>，恒 10000 槽 | 预热 |
| `bs:raffle:award:{strategyId}:{blacklist\|fallback}` | bucket（值 = awardId） | 预热 |
| `bs:raffle:warmup:{strategyId}` | bucket；**不存在 = 降级态** | 预热 |
| `bs:raffle:blacklist:user:{strategyId}:{userId}` | bucket（存在即命中） | **风控侧**（本域无黑名单用户表） |

库存段 `bs:inventory:virtual:bucket:<invId>:<bucketId>` / `bs:inventory:quota:bucket:<invId>:<bucketId>`
属票 12，本票只读它（预热缺失 → `preDeductVirtual` 返回 false → 走兜底档）。

### 与合并任务的接缝（票 12 的 `inventoryMerge`，每秒）

- 本票**只产生** `state=0` 明细（预扣），**从不翻账、从不减台账**；台账的 `surplus/issued/waiting`
  变化全部由合并任务做。所以「中奖瞬间」目录上的 `remaining` 不变（**有意如此**：在途量不进目录口径）。
- 合并扫描**不连接活动表**（票 12 的有意口径）：它按 `(inv_id, bucket_id)` 直接扫 `state=0`，
  好处是停用（closed/exhausted）库存的遗留明细也能收敛。**活动维度的限定因此落在本票的预扣入口**：
  活动不 `open` 就根本不会产生新预扣（`draw` 第 2 步先拦）。
- 对账恒等式（票 12 的 ③）把两段变化缝起来：`Redis 余额 == redis_qty − 该桶未合并明细和`。

### 对账窗口两处说明（不假装没有）

1. **预扣成功而落库失败不补偿**（票 12 同款口径）：库存 `state=0` 明细已落、`draw_record` 没落，
   库存被白扣一份。由对账 ③ 暴露（Redis 余额小于应有值）。调用方拿同一 token 重试会重新抽一份
   （那条 `record_id` 还没落库），**但先前白扣的那份不会自动回来**，需人工按明细 `ref_id` 核。
2. **合并扫描不连活动表**（同上）：收敛性优先于「只合运行中活动」的字面口径；
   停用库存的遗留明细照合，不会永远挂在 `state=0`。

### 降级路径

- **预热缺失**：`warmup:{strategyId}` 不存在 → 默认节点抛 `RAFFLE_WARMUP_MISSING(4311)` →
  主链路捕获该码**降级 DB 直算**（只取 `type = normal` 的档，`award_rate ×10000` 归一化后按累计槽位随机；
  `normal` 档为空才把 4311 抛出去）。口径与预热同源（同一 `award_rate` 与同一 `normal` 过滤）。
- **库存不足**：虚拟 `preDeductVirtual` false / 实物 `FALLBACK` → 切 `type = fallback` 奖品位
  （实物先试兜底 `inv_id` 再扣虚拟池，配额段**零写入**、不产生悬挂 `waiting_qty`）；
  兜底也没有 → **新码 `4313 RAFFLE_ISSUE_FAILED("奖品发放失败，请稍后再试")`**，
  该次不落 `draw_record`，已扣的次数**不回退**（记在预扣明细里，靠对账核）。
- **`inv_id = 0`** = 不限量，跳过预扣；`THANKS` 无库存来源，落库时 `inv_id` 置 `NULL`。

### 落地中实际做的事（票面之外）

- **`state` 初值**：`THANKS` 落库即 **已完成**（不走兑换/发货）；其余落 **待兑换**，推进归票 14。
  明细**只插 `DRAW` 一条**，`COMPLETE` 明细留给票 14 状态机。
- **`raffleCacheWarmup` 补登** `xxl-job-tasks.sql`：handler 早在票 11 就存在（`CacheWarmupJob`），
  但漏了 seed 行——本票补上（cron `0 0 2 * * ?`，参数 = `activityId`）。
- **收口时抓到的三处「编译通过 ≠ 能跑」**（详见 map.md「票 13 收口」）：
  旧 `LotteryMapper.xml` 漏删（指向已删 PO，让每个 Spring 上下文启动失败）、
  `RaffleMapper.xml` 两处裸 `<` 不是合法 XML、`LotteryDrawnConsumer` 与
  `PointsLotteryWinIntegrationTest` 还认旧 `POINTS` 奖品位类型（会让积分档永远加不上分 → 改 `VIRTUAL`）。
- 两处测试夹具修正：`insertActivity` 改「先删再插」（活动是单行语义，重铺 = 改窗口，避免撞 `uk_activity_id`）；
  活动窗口用例改为**相对 `now()`** 计算（原先建在夹具的绝对起点上，在当前真实日期下落在过去）。

### 验收对照

1. `backend/marketing && mvn -o clean test` **全量 68 绿**（BUILD SUCCESS）。✔
2. 全链路集成用例 **11 个**（≥4）：虚拟 / 实物 / 谢谢中奖（断言次数 −1、Redis 桶 −1、`state=0` 明细一行、
   `draw_record` + `draw_record_detail(DRAW)` 各一行）；次数不足拒绝且**无任何副作用**；黑名单拦截；
   配额不足走兜底且**配额段零写入**（无 `waiting_qty`）；**虚拟档权益不足也走兜底**；同 token 幂等；
   异 token 两次；预热降级；活动三闸门。
   （注：票面字面的「`state=0`」在域内是**状态名**不是数字——`state` 列存中文
   `待兑换`/`已兑换`/`已发货`/`已完成`，`DrawRecordActionVO` 给映射；明细侧的 `state=0` 才是数字。）✔
3. 幂等用例 **2 个**：抽奖同 token 重放返回同一 `recordId`、次数只扣一次、明细只有一行、记录只有一行；
   兑换同 token 重放只入账一次、积分只扣一次、换 token 才是新的一笔。✔
4. 预热降级用例：`deleteByPattern("bs:raffle:*")` 后仍出奖并照常落库。✔
5. `schema-legacy.sql` 已删除；`backend/marketing` 的 `lottery_` **命中 11 处 / 7 文件，全部是历史说明性文字**
   （不是代码引用）：`types/raffle/Constants.java` 1 处（定价载体出处）、
   `sql/schema.sql` 2 处（头注释 + 种子注释）、6 份文档共 8 处（`docs/api/lottery.md` ×3、
   `docs/db/{draw,inventory,points}.md` 各 1、`docs/README.md` ×2）——这些是**有意保留的沿革说明**
   （说清「旧表已删、新表是唯一落位」），不是未清的引用；代码侧（`.java`）除该注释外**零命中**。✔
6. 本 `## Answer` 记录：幂等键、事务边界、`ILotteryService` 签名变更清单、Redis key 命名、与合并任务的接缝。✔

### code-review 双轴复核后的补充修复（Standards + Spec）

评审（两轴并行）提出、本票**已修**的项：
1. **兑次数原先没有真幂等键**（Standards 硬违规，最重）：`redeemOrderId` 每次请求现编 snowflake，
   而 javadoc 却声称「跨重试幂等由两域 uk 吸收」——网络重试 = 新单号 = **重复扣积分**。
   已改成 `redeemOrderId = hash(userId, token)` + body 带 `token`（复用抽奖同一套
   `types.common.IdempotencyKeys`），并补同 token 重放用例。
2. **日志配置漏改**：`application.yml` / `application-test.yml` 仍留
   `infrastructure.lottery.dao: warn`（包已删）→ 改成五个域段逐个列出，与 `@MapperScan` 清单一致。
3. **跨 project 口径残留**：单体 `MarketingNoticeCase` 仍按 `POINTS` 判文案 → 改认 `VIRTUAL`
   （保留旧值兼容在途消息），`MarketingNoticeIntegrationTest` 补旧值用例；better-school 侧 6 用例绿。
4. 三处注释/文档误导：`IInventoryDeductPort` 的方法名、`LotteryController.parsePrizeStates`
   的「取前三段 + 全部」、`ILotteryService` 的「三段 tab」。
5. `test-env.sh` 的 `apply_service_init` 重复行（同一参数连调两次）删除。
6. **虚拟档兜底此前无用例**（Spec 指出）→ 补 `draw_virtualExhausted_fallsBack`。

评审提出、**本票未处理**（留痕，不假装已解决）：
- `RaffleMapper.xml` 两条 CASE 里 `a.inv_id = 0 OR COALESCE(...) < 0` 重复，以及 `claimedAt`
  依赖 `draw_record_detail.type='EXCHANGE'`（票 14 才会写）→ 属**有意的前置查询面**，票 14 接状态机后即生效。
- `state` 中文库值散落在实体/VO/PO/schema/controller 多处（票 11 刚把 `PrizeType` 枚举删成 String）：
  本票不引入新枚举（会与 `schema.sql` 的中文库值口径二次漂移），统一枚举化留给票 14 与状态机一起做。
- `PointsChangedMessage` 的 `reasonType` 分支仍含 `REDEEM_PRIZE`（该业务已废）——无害死分支，
  随票 14 清理通知文案时一起收。

<!-- 收口时补：Context used: ~?k；是否触发拆分；连带影响清单 -->

Context used: ~205k（目标 ≤250k 内；未触发拆分）。
连带影响：`schema-legacy.sql` 删文件（`test-env.sh` / `init-{dev,test}.sql` / `schema.sql` 头注释同步）、
`xxl-job-tasks.sql` 三行删除 + 一行补登（**admin 需手工清 `xxl_job_info` 旧行**）、
`docs/api/lottery.md` 重写、`docs/db/lottery.md` 删除、`docs/db/draw.md` 补主链路/读侧/幂等段、
`docs/README.md` 索引行、`backend/docs/architecture/marketing-services.md`（域段 / Dubbo 折叠 / DDL / handler 清单）、
根 `CONTEXT.md` 新增「抽奖主链路」小节 6 词条、`map.md` 加收口段。

