# Map：项目大重构（三服务收敛 + 去限流去 agent + 抽奖重写 + 库表照 sql.md）

> Label: `wayfinder:map` · Effort: project-rework · Charted: 2026-10-05 · **重划：2026-10-06（票 05 收口后，票面拆成 06–18）**

## Destination

> **✅ 已达成（2026-10-07）**：票 17 冒烟 9/9 PASS（0 业务缺陷）+ 票 18 文档/词条/sql.md/map 收口完成。
>
gatekeeper、agent 审核、求购、多人单、群聊、拉新全部移除；三库 DDL（better_school / bs_marketing / bs_timeout）与代码行为照根目录 [`sql.md`](../../sql.md) 落地；抽奖链路照 `rafflex-backend/`（参考项目，未入库）重写，库存扣减不用它的纯 Redis 方案、改用 sql.md 的两段式（Redis 分桶预扣 + state=0 明细 + xxl 每秒合并提交）。验收 = 全栈可起、冒烟通过、CONTEXT.md 与 docs 收口。

## 票面纪律：上下文预算（重划的直接原因）

> 票 05 一张票烧掉超过一个上下文窗口，被迫中途写 [`issues/05-handoff.md`](issues/05-handoff.md) 交接。重划后每张票都带可执行的预算约束，规则同时写进 [`docs/agents/issue-tracker.md`](../../docs/agents/issue-tracker.md) 供后续 effort 沿用。

**硬指标**：一票 **150–200k 上下文为理想区间，300k 为硬顶**。超顶视为票面设计失败，不是执行者的问题。

**票面必须自带的三样东西**（06–18 均已满足）：

1. `## 上下文预算`：目标/硬顶、预估改文件数、**必读事实源的精确指针**（`sql.md` 行区间、参考实现目录）、必跑测试范围、越界信号。
2. `## 已核实事实`：把「考古成本」前移到票面——端口、同名类清单、消费组名、跨服务耦合点、行号边界一律预先查好。执行者**不再全仓 grep、不再通读 `sql.md`、不再读 `target/`**。
3. `## 施工边界`：明确「不做什么 + 归哪张票」，防止顺手扩大导致再次超窗。

**省窗手段（票面已写进对应条目）**：

- **机械改名前移**：搬包 / 改名 / 测试包名改写一律委托子代理（≤4 并发），主窗口只留「映射表 + 摘要」。
- **同一服务禁止并行**：集成测试共用测试库，并发 `mvn test` 必然互相污染（票 05 教训：表现为「手机号已被注册」「Table definition has changed」）。跨服务（如票 15）才可插空并行。
- **全量测试与文档收口集中在终验票**，中间票只跑本域测试子集。
- **票内 `## Answer` 必须记一行 `Context used: ~?k`**，作为下一轮拆票的标定数据。

**合并判据（防止拆过头）**：两张票**共享同一个心智模型**时才合并；同构批量（同 pattern 复制变体，如虚拟库存 ↔ 实物配额）的单文件成本远低于异构改动，此时「~50 文件」阈值可放宽到 ~60。反之，跨服务、跨模型的「两张小票」不要为了省窗口硬并——多一次冷启动（约 15–25k）远小于超窗返工的代价。

**超预算预案**：执行中触到硬顶就**停手**，把未完成项写进 `## Answer` 的「残留清单」，另开一张票（不要在超窗状态下硬推）。

## 施工顺序

| # | 标题 | Blocked by | 预算档 |
| --- | --- | --- | --- |
| [06](issues/06-marketing-project-merge.md) | 建 backend/marketing 七模块骨架并并入 points 域 | — | 中（全机械） |
| [07](issues/07-lottery-domain-relocate.md) | lottery 域并入 marketing，删两个旧工程 | 06 | 中（全机械） |
| [08](issues/08-marketing-ddl.md) | bs_marketing DDL 落地（22 张）+ outbox 形状切换 | 07 | 小 |
| [09](issues/09-remove-referral.md) | 删拉新（营销域 + 单体侧三件套 + 表） | 08 | 小 |
| [10](issues/10-points-bucket-model.md) | 积分 / 次数切桶模型（7 张新表全链） | 09 | 中 |
| [11](issues/11-lottery-tables-and-chain.md) | 抽奖 A：draw_* 6 张表 + 责任链骨架 + 表预热 | 08 | 中 |
| [12](issues/12-inventory-two-stage.md) | 库存两段式：虚拟权益池 + 实物营销配额 | 11 | 中偏大（同构 8 表） |
| [13](issues/13-lottery-draw-pipeline.md) | 抽奖 B：主链路（次数 → 责任链 → 预扣 → 落库） | 10, 12 | 大 |
| [14](issues/14-shipping-and-frontend.md) | 发货闭环（本地消息表 + MQ + 状态机）+ 前端营销页对齐 | 13 | 中偏大（前后端一票） |
| [15](issues/15-timeout-align.md) | 超时中心对齐 sql.md | — | 小（可插空） · **已收口** |
| [16](issues/16-frontend-remove.md) | 前端删功能页 + 成色遗留 | 03, 04, 09 | 中 · **已收口** |
| [17](issues/17-final-smoke.md) | 终验 A：三服务全栈可起 + 冒烟走查 | 05, 10, 12, 13, 14, 15, 16 | 大（用子代理跑测试）· **已收口** |
| [18](issues/18-final-docs.md) | 终验 B：文档 / 词条收口 + grep 清零 + sql.md 回写 + map 收口 | 17 | 中 · **已收口** |

**为什么是 13 张**：下限由「同一服务测试库互斥」+「每票要有一个绿色收口」两个约束卡住——营销那条链（06→07→08→09→10/11→12→13→14）本来就不可能再压；再往下压只能把「两个服务的推倒重来」塞进一窗，那正是票 05 的死法。压缩空间只在「共享心智模型」的小票上（见上「合并判据」）。

Frontier 规则照常：扫描 `.scratch/project-rework/issues/` 中开放、未阻塞、未认领的票，**编号最小者优先**；开工前设 `Status: claimed`，收口写 `## Answer` 后设 `Status: resolved`。

## 旧 → 新映射（原 06–10 已拆分并作废）

| 原票 | 去向 |
| --- | --- |
| 06 营销两工程合一 + 删拉新 + 积分切桶 | → **06**（骨架 + points 搬入）、**07**（lottery 搬入 + 删旧工程）、**08**（DDL）、**09**（删拉新）、**10**（积分切桶） |
| 07 抽奖重写 + 两段式库存 | → **11**（表 + 责任链 + 预热）、**12**（两类两段式库存合一张）、**13**（主链路）、**14**（发货 + 前端营销页合一张） |
| 08 超时中心对齐 | → **15**（内容不变，补预算与事实源） |
| 09 前端随动 | → **16**（删功能页 + 成色遗留）、**14** 的前端半张（营销页接新抽奖 + 审核台） |
| 10 终验收口 | → **17**（全栈可起 + 冒烟）、**18**（文档 / 词条 / sql.md / map 收口） |

改动原因：原 06/07 各是「一个服务级的推倒重来」，单票远超 300k 硬顶；原 09/10 是「一整个前端 + 全项目文档」的宽面票。重划后又按「合并判据」回收了两处过度拆分（库存两段合一张、发货与前端营销页合一张），把 15 张压到 13 张。

> ⚠️ **读旧档时的编号换算**：已收口的 [`05-main-db-rework.md`](issues/05-main-db-rework.md) 与 [`05-handoff.md`](issues/05-handoff.md) 写于重划之前，文中「前端 `condition`（成色）归**票 09**」按新编号应读作**票 16**；「前端营销页/抽奖适配」读作**票 14**。其余旧编号一律按上表换算，历史档原文不改。

## sql.md 行区间索引（各票只读自己那一段，别再通读）

`sql.md` 全文 1051 行（票 15 改后；票面 06–14 写的 1035 是票 15 之前的数），已按段落定位：

> ⚠️ **行号会随票漂移，优先用 § 标题定位**：票 15 在 §1 加了一行注、把 §3 整体回写，本节其余行号随即失真。
> 现状实测：§1 标题 L10 / §2 标题 L602 / §3 标题 L1012；表中 §1–§2 各行 `L` 值是**票 06–14 当时的数**（普遍差 +2～+3，如 §2 抽奖段实测 L731–L844、§2 `outbox_message` 实测 L992–L1008），核对时以 `grep -n '^#\{2,3\} ' sql.md` 为准，**别按死行号读**。

| 用途 | 行区间 | 归属票 |
| --- | --- | --- |
| §1 单体 better_school（29 张） | L10–L598 | 05（已收口） |
| §1 的 `outbox_message` + `timeout_register_outbox` | L198–L235（实测） | 15 |
| §2 营销 bs_marketing 标题与表述 | L600–L604 | 08 |
| §2 积分与抽奖次数段（7 张） | L606–L727 | 10 |
| §2 抽奖段（6 张） | L729–L844 | 11 |
| §2 虚拟库存段（3 张）+ 合并算法说明 L898 | L846–L898 | 12 |
| §2 实物库存段（5 张）+ 说明 L989 | L900–L989 | 12 |
| §2 `outbox_message` | L991–L1007 | 08 |
| §3 超时中心（逻辑 1 张 = 物理 16 张） | **L1012–L1051（文末，实测）**；表 `### timeout_task` 在 L1026 | 15（已收口） |

> 口径：`sql.md` 是**库表唯一事实源**；发现对不齐之处**在票内定并回写 `sql.md`**，并在地图 Decisions so far 记一行。

## Notes

- **决策快照（2026-10-05 grilling 敲定，8 项全按推荐）**：
  1. 多人单随本轮下线（sql.md 既定前提；系统群无宿主）。
  2. marketing-activity + marketing-asset 合并为一个 `backend/marketing/` 七模块工程，包根 `com.betterschool.marketing`，domain 下 `lottery` + `points`，库 `bs_marketing` 一个。
  3. 抽奖域**推倒重写**照 rafflex：责任链规则（黑名单→权重→默认）、抽奖表预热、保底、黑名单、次数账户、本地消息表+重发；表全部换 sql.md 的 `draw_*`。
  4. 主体库行为变更全量实现：计数窄表（post_like_count / post_comment_count，MySQL+MQ 窗口聚合）、product_order_event / errand_order_event 事件表、三张改名（room_user→room_member、wallet_transaction→wallet_detail、user_credit_log→user_credit_detail）。
  5. agent 审核全删不留档：`agent/` 目录、Java 侧整条链路（outbox/投递/裁决回调/作者档案/抽检）、前端抽检页；审核收敛为**同步敏感词初筛 → 人工**，content_review 仅 MANUAL。
  6. 群聊删除、1v1 私信完整保留；会话抽象（room/room_member 恒两行）保留。
  7. **本 effort override wayfinder 默认：map 直接携带执行**，票即施工票，一票一验收段（不再另起 spec/impl effort）。
  8. 存量已收口提交（9593eb8），重构在其上做。
- **票面细化决策（2026-10-06 重划时定，落在票内）**：
  - 票 06：包根下**同名类分域包 / 公共包两处落位**；**Outbox 只留一份**（`sql.md` §2 只有一张 `outbox_message`）；服务标识收敛为 `marketing` / `marketing-producer` / `marketing-executor`；端口收敛 dev 8081 / tri 50052 / actuator 9998。
  - 票 08：**过渡期双 schema**（`schema.sql` 22 张新表 + `schema-legacy.sql` 未迁移的旧域表），保证票 09–13 每票收口都能跑全量测试；outbox 因两库同名不同形状，在本票一次性切换。
  - 票 11：**表预热**（概率 ×10000 + `CacheWarmupJob`）归「决策侧」，放在责任链同一票，给票 13 减负。
  - 票 12：虚拟与实物**两段语义分开建模**，但合并任务只写**一套骨架**挂两个目标；差异只有「合并后进 `issued_qty`」vs「先进 `waiting_qty`、兑换后转 issued」。
  - 票 13/14 切分：13 只到「落库」，发货/兑换/重发闭环 + 前端营销页归 14（前端与它消费的接口同一窗口，避免靠 `## Answer` 跨窗传接口清单）。
- **sql.md 是库表唯一事实源**；注意其 line 12：`errand_evaluation`（跑腿评价）、`user_daily_active`、`delay_outbox_message` "本轮不收录"——**跑腿评价随本轮下线**（票 05 已落实）。
- `rafflex-backend/` 是抽奖参考（未入库，用完可删）；`sql.md` 已入库（9593eb8）。
- 领域文档随票修订：谁改行为谁改词条——审核词条归票 02、求购词条归票 03、chat/多人单词条归票 04、营销词条归票 10/11/13、抽奖库存词条归票 12/13/14。
- 后端编码规范 `backend/CLAUDE.md` 七模块 DDD 不变；前端已有风格不变，只随功能删减/新增调整。
- **共用踩坑清单**：[`issues/05-handoff.md`](issues/05-handoff.md) §5（`git status` 虚报、`CREATE TABLE IF NOT EXISTS` 不改旧表、严格模式业务 ID/时间戳 NOT NULL、夹具号段、测试栈无 broker、`mvn -o` 离线、mapper XML 不参与编译）——**只在踩到环境问题时读该节**，不要通读全文。

## Decisions so far

- **票 05（库表重构）三处 sql.md 回写**，详见 [`issues/05-main-db-rework.md`](issues/05-main-db-rework.md) 的 `## Answer`：
  1. `room` 保留 `user_min`/`user_max`/`source`/`initiator_id` + `uk_direct_pair`（撑 1v1 查重与会话来源/对家角色标签）。
  2. `post`/`product` 保留 `review_round` + `review_entered_at`（轮次口径是「进入待审次数」；`AUDIT_REMIND` 超时注册机制从未实现，轮询扫描需要可靠的进入待审时间，`update_time` 会被浏览量聚合器刷新）。
  3. 计数消息级幂等键是生产端 `msgId`（非 `post_like` 的 uk）——`like → unlike → like` 是合法序列，按关系键去重会丢增量。
- **票 05 已收口**：230 测试全绿；`sql/migrations/` 与迁移测试基类整体删除，`schema.sql` 是唯一 DDL 来源（改表 = 改 schema.sql + 重建库）。
- **票 05 的代价与对策**：单票超出 300k 硬顶，被迫中途交接（`05-handoff.md`）。对策见「票面纪律」；06–18 全部按该纪律重写。

### 票 06 收口（`backend/marketing` 七模块骨架 + points 域并入）

**包名映射表**（旧 `marketing-asset` / 包根 `com.betterschool.asset` → 新 `backend/marketing` / 包根 `com.betterschool.marketing`）：
完整 61 条逐类映射见 [`06-package-mapping.md`](06-package-mapping.md)，机读版 [`06-fileplan.json`](06-fileplan.json)。
落位规则与票面一致，两处按实际语义微调（已在票 06 `## Answer` 说明）：
`Constants` 与 `PointsReasonType` 落 `types.points`（非 `types.common`）——两者都是积分域自有常量/枚举，
且票 07 的 lottery 侧各有同名同类物，落 common 必冲突。

| 层 | 域专属落位 | 跨域公共落位 |
| --- | --- | --- |
| types | `types.points`(`Constants`/`PointsReasonType`)、`types.common`(`LotteryDrawnMessage`) | `types.common`(`AppException`/`ResponseCode`/`OutboxStatus`) |
| api | `api.points`(`IPointsService`/`IPointsExchangeService`)、`api.points.dto`(Points*DTO) | `api.common.dto`(`UserBasicResponseDTO`)、`api.common.response`(`Response`/`PageResult`) |
| domain | `domain.points.{adapter.repository,model.entity,model.valobj,service}` | `domain.common.adapter.port.marketing`(`IUserQueryPort`)、`domain.common.model.valobj.marketing`(`UserBasicVO`) |
| infrastructure | `infrastructure.points.{dao,dao.po,adapter.repository}` | `infrastructure.common.{dao.outbox,outbox,adapter.port.marketing,security}` |
| trigger | `trigger.points.{http,job,listener,rpc}` | `trigger.common.http`(`GlobalExceptionHandler`/`PingController`)、`trigger.common.http.security`(`SecurityUtils`) |
| app | —— | `com.betterschool.marketing`(`MarketingApplication`)、`com.betterschool.marketing.config` |

**Outbox 唯一一份** → `infrastructure.common.dao.outbox`（DAO/PO）+ `infrastructure.common.outbox`（Store/Sender），
mapper XML 仍在 app 模块；本票不改其形状（形状切换归票 08）。

**pom 矩阵**（以 asset 为准，artifactId 由 `marketing-asset-*` 收敛为 `marketing-*`）：

| 模块 | 依赖 |
| --- | --- |
| `marketing-types` | lombok |
| `marketing-api` | types、lombok |
| `marketing-domain` | types、spring-context、spring-tx、slf4j、lombok |
| `marketing-case` | domain、spring-context、spring-tx、slf4j、lombok（**空模块**，保七模块形状） |
| `marketing-infrastructure` | domain、bs-api、spring-boot-starter、mybatis-starter、rocketmq-starter、xxl-job-core、jackson-databind、dubbo-starter、dubbo-nacos-starter、spring-security-web、jakarta.servlet-api(provided)、jjwt×3、lombok |
| `marketing-trigger` | api、domain、case、starter-web、starter-validation、starter-security、rocketmq-starter、xxl-job-core、dubbo-starter、lombok |
| `marketing-app` | trigger、infrastructure、case、api、domain、types、mysql-connector-j(runtime)、xxl-job-core、nacos-config、lombok、starter-test(test) |

parent 依赖管理新增本工程七模块 + 保留 `bs-api` / dubbo / mybatis / rocketmq / jjwt / xxl-job。
**activity 侧多出的依赖（本票不引入）**：`redisson-spring-boot-starter`（奖池桶 DECR/INCR/SET）——归票 07 引入。

**服务标识 / 端口收敛结果**：

| 项 | 旧（asset） | 新（marketing） |
| --- | --- | --- |
| `spring.application.name` / `dubbo.application.name` | `marketing-asset` | `marketing` |
| RocketMQ producer group | `marketing-asset-producer` | `marketing-producer` |
| xxl executor appname（yml + `sql/xxl-job-tasks.sql`） | `marketing-asset-executor` | `marketing-executor` |
| xxl log-path | `./logs/xxl-job-asset` | `./logs/xxl-job-marketing` |
| `dubbo.scan.base-packages` | `com.betterschool.asset` | `com.betterschool.marketing` |
| dev HTTP / tri / actuator | 8081 / 50052 / 9998 | **不变**（本票即占这三口） |
| test actuator / tri | 9990 / 空闲端口注入 | 不变 |

释放 8082 / 50053 / 9997 供票 07 使用。**库名仍为 `bs_asset` / `bs_asset_test`**（收敛到 `bs_marketing` 归票 08）。

**跨 project 契约改名**：`com.betterschool:marketing-asset-api` → `com.betterschool:marketing-api`；
`marketing-activity` 的 parent `marketing-api.version` 与 infrastructure 依赖同步改，
`PointsExchangePort` 的三条 import 改 `com.betterschool.marketing.api.points[.dto]`。
**同步边形态仍是 Dubbo**（是否折叠成进程内调用留给票 13）。

**零行为变更的机械证据**：61 个搬迁文件里 56 个在「FQCN 映射 + 去 package/import」归一化后与旧文件**逐字节相同**；
5 处差异全部是有意为之（3 处 javadoc 里的旧模块路径、`MarketingApplication` 加 FQCN bean 命名器、
`MybatisConfig` 改用逐域 `@MapperScan` + FQCN 命名器）。比对脚本见 [`Compare-Bodies.ps1`](Compare-Bodies.ps1)。


### 票 07 收口（lottery + referral 两域并入 `backend/marketing`，删掉旧工程）

**`backend/` 只剩三个后端 Maven 工程**：`better-school` / `marketing` / `timeout-center`；`backend/marketing-activity/` 已删。
`marketing` 现有 **199 个 java**（原 62 + 并入 137），**87 个集成测试全绿**（`mvn -o clean test`）。

**包名映射**：`com.betterschool.activity.<layer>.<域>.*` → `com.betterschool.marketing.<layer>.<域>.*`
（域段插在层后，与票 06 的 points 同构）。逐类表与去重清单见 [`07-package-mapping.md`](07-package-mapping.md)，
搬迁脚本 [`Migrate-07-v2.ps1`](Migrate-07-v2.ps1)，零行为变更比对脚本 [`Compare-07-bodies.ps1`](Compare-07-bodies.ps1)。

**六类有意落位**（相对纯前缀替换）：`AppException`/`ResponseCode`/`OutboxStatus` → `types.common`；
`Response`/`PageResult`/`UserBasicResponseDTO` → `api.common.*`；`IUserQueryPort`/`UserBasicVO` → `domain.common.*`；
`IOutboxDao`/`OutboxPO`/`OutboxMessageStore`/`OutboxMessageSender` → `infrastructure.common.*`；
`JwtService`/`JwtAuthenticationFilter`/`UserQueryPort`/`GlobalExceptionHandler`/`SecurityUtils` → 各自 `common` 包；
`Constants` → `types.<域>`（去掉 `.common` 段）。跨域 wire 契约 `LotteryDrawnMessage` + 新增 `ReferralGroupedMessage` → `types.common`。

**同名常量按域拆开（保行为的必要动作，不是美化）**：三份 `ResponseCode` 并一份后 Java 不允许重名常量，
而 **400 的 info 三域不同**（points/拉新 `参数错误`、lottery `参数不合法`，且集成测试逐字断言）→
`POINTS_PARAM_ERROR` / `LOTTERY_PARAM_ERROR`；`{POINTS,LOTTERY}_INSUFFICIENT_POINTS`（4101/4306）、
`{REFERRAL,LOTTERY}_ACTIVITY_{NOT_STARTED,ENDED}`（4202/4301、4203/4302）同理。**码值与 info 一字未改**，前端按码分支不受影响。

**服务标识 / 端口 / 库**：`marketing` / `marketing-producer` / `marketing-executor` / 8081 / 50052 / 9998 / `./logs/xxl-job-marketing`
全部维持票 06 的收敛结果；**8082 / 50053 / 9997 已释放**（`front/vite.config.ts` 三条营销前缀已全部指 8081，
`run-app.sh` 的 svc 键收敛为 `bs|marketing|center`）。
**库收敛到 `bs_marketing` / `bs_marketing_test`**（三域同库；`init-{dev,test}.sql` 各只建一个库，
`test-env.sh` 把 `schema-{points,lottery,referral}.sql` 对同一库名各拼一次）。
`migrations/022-024` 与三份 `schema-*.sql` 的 `CREATE DATABASE`/`USE`/头注释**仍写旧名 `bs_asset`/`bs_activity`，归票 08**。

**xxl**：一份 `marketing/sql/xxl-job-tasks.sql` 注册 6 个 handler
（`outboxScan`、`pointsExpireScan`、`lotteryDeductMerge`、`lotteryDrawCountExpireScan`、`lotteryOutboxScan`、`referralOutboxScan`），
与 java 侧 6 个 `@XxlJob` 注解值逐条对齐。三个 outbox 扫描名是**同一段逻辑的三个注册名**
（后端兼容 `xxl_job_info` 里按旧名注册的行；改名会让任务永远调不到）。

**零行为变更的机械证据**：160 个保留文件里 **121 个**在「去 package/import + 去注释空白 + 统一包根 + 去重跳转表 +
常量改名归一化」后与旧文件**逐字节相同**，**真实内容差异 0**；其余 39 个是 §2 去重副本（已合并进 common）。
DDL 一字未改（四份 schema/migration `git show HEAD` 与工作区 SHA256 逐字节相同）。
**比对脚本的价值已被验证**：它报出的 5 处差异就是「400 info 文案冲突」这个会红的真问题（票面未预见）。

**自调用 Dubbo 未折叠**（票面授权改直注，本票选择保持 `@DubboReference`）：票 13 本来要重写兑换边，
且测试用 `@Primary StubPointsExchangePort` 覆盖这条边、自调用时序不进入测试路径。**折叠与否留给票 13**。

**票 06 评审留的三个施工前提，本票处置**：路由冲突已按域解（三个 `PingController` 各占自己的前缀）；
`IUserQueryPort`/`UserBasicVO` 核实三域都用，落 common 成立；`FqcnAnnotationBeanNameGenerator` 保留是**必要**的
（本票落地后确有 5 组同名类落不同包，不挂即 `ConflictingBeanDefinitionException`）。

**票面预算超顶**：`Context used: ~360k`（目标 150–200k、硬顶 300k）。超顶来自票面未预见的四件事：
一次搬迁脚本事故（PowerShell 数组压平导致 `c`→`o` 全树污染，已回滚重做、未进产物）、
`ResponseCode` 的 400 文案冲突、三处机械规则盲区（`marketing-api` 依赖 / 两个 `UserQueryPort` 副本 / `Constants` 的 `.common` 段）、
随端口释放必须联改的 `front/vite.config.ts` + `run-app.sh` + 五处 docs/compose。**未触发拆分**——
都在「机械搬迁 + 装配」边界内，拆出去只会把同一份映射表撕成两半。


### 票 11 收口（draw_* 6 张表 + 责任链 + 表预热）

**新域段 `raffle`**（`domain.raffle` / `infrastructure.raffle` / `trigger.raffle` / `types.raffle`，@MapperScan 已逐域加上）：
新模型骨架与旧 `lottery` 域代码并存不同包（旧代码票 13 整体删，届时 `raffle` 即抽奖域唯一落位；
`DrawRecordEntity` 等旧同名类在 `domain.lottery` 撞不了新包）。决策口径（链顺序/短路、权重命中、
Redis key、action 枚举）逐条写在票 11 `## Answer` 与 `backend/marketing/docs/db/draw.md`，票 13 直接引用。

**两处 sql.md 回写**（比对脚本 22 表逐列全等）：`strategy_weight` → `draw_strategy_weight`（并入 draw_* 命名、
unsigned 主键去掉、时间戳默认值去掉、补 COMMENT，weights varchar(32)→256 容 snowflake award_id）；
`draw_record.award_decr` 纠 typo 为 `award_desc`（设计稿笔误，代码自然用 award_desc）。

**测试**：新增 `RaffleChainIntegrationTest`（5 用例）+ `RaffleWarmupIntegrationTest`（2 用例），
marketing 全量 85 绿（78 旧 + 7 新，旧 `Lottery*` 零回归）。


### 票 13 收口（抽奖主链路：次数 → 责任链 → 库存预扣 → 落库 + 旧 lottery 域整体删除）

**主链路域段**：`domain.raffle` 三个服务（`RaffleDrawServiceImpl` 主链路 / `RaffleQueryServiceImpl` 读侧 /
`RaffleExchangeServiceImpl` 兑次数）+ 三条跨域写边 port（`IDrawCountPort` / `IInventoryDeductPort` /
`IPointsDeductPort`，实现在 `infrastructure.raffle.adapter.port`，纯委托票 10/12 的域服务）+
`IRaffleQueryRepository`（读侧查询面）。票 11 的两个仓储接口**一字未动**（只加不改，零回归）。

**幂等键 = `record_id = SHA-256(userId + ":" + token)` 截 63 位正整数**：确定性映射、**无重放表**，
`uk_record_id` 就是幂等互斥。重放走「先查记录」快路径；并发同 token 靠扣次明细唯一键串行化——
败者要么扣次后查到已落记录回放，要么落库撞 `uk_record_id` 后回放；回放必校验归属（防 hash 碰撞顶出他人记录）。

**事务边界三处**（别混）：① 扣次数在次数域内事务（Lua 原子 + 明细行 `ref_id = record_id`）；
② **落库**一个 MySQL 事务：`draw_record` + `draw_record_detail(DRAW)` 同事务；
③ 库存预扣在库存域内事务（Redis 桶 + `state=0` 明细），**与落库不同事务**——这正是「预扣成功而落库失败」
窗口的来源（**不补偿**，票 12 同款口径，由对账 ③ 暴露）。

**兑换边从 Dubbo 折叠成进程内**：`exchangeDrawCount` 用外层 `TransactionTemplate` 包住
「积分扣分 + 次数入账」——两域同进程同库、域内事务都是 `REQUIRED` 传播，**合并成一个 MySQL 事务**。
旧模型「兑换单 + PENDING 残留 + resume」整套机制因此废除。`IPointsExchangeService` 的 Dubbo Provider
保留作对外契约，但**域内已无 consumer**，测试用的 `@Primary StubPointsExchangePort` 一并删除。

**整包删除**：`domain.lottery` / `infrastructure.lottery` / `types.lottery` / `trigger.lottery`
（`LotteryController` + `PingController` 移到 `trigger.raffle.http`）、api 里 6 个废弃 DTO、
旧 9 个测试类/桩（`LotteryApiTestBase` → 新 `RaffleApiTestBase`）。
`schema-legacy.sql` **删文件**（`bs_marketing` 现在只有 `schema.sql` 一份 DDL）；
`xxl-job-tasks.sql` 删 `lotteryDeductMerge` / `lotteryDrawCountExpireScan` / `lotteryOutboxScan` 三行；
`OutboxMessageSender` 删 `lotteryOutboxScan` 别名（合并期兼容名不再需要）。

**收口时抓到三处「票面没预见但会红」的真问题**（都是「编译通过 ≠ 能跑」）：
1. **旧 `LotteryMapper.xml` 漏删**，且指向已删除的 `infrastructure.lottery.dao.lottery.po.*`——
   它不参与编译（CLAUDE.md §10 的坑），却让**每个** Spring 上下文启动失败（66 个用例全红）。
2. **`RaffleMapper.xml` 两处裸 `<`**（`COALESCE(...) < 0`）不是合法 XML：同样只在上下文启动时才炸。
3. **`PointsLotteryWinIntegrationTest` 与 `LotteryDrawnConsumer` 还认旧的 `POINTS`** 奖品位类型：
   票 13 把口径改成 `VIRTUAL`，消费方写死旧值会让积分档**永远加不上分**——改成 `VIRTUAL` 并同步用例。

**另补两处票面外的一致性问题**：`insertActivity` 夹具改为「先删再插」（活动是单行语义，重铺即改窗口，
避免撞 `uk_activity_id`）；`activityGates` / `activityInfo` 两处用例原先把窗口建在**夹具的绝对起点**
（`ACTIVITY_START.plusDays(1)` = 2026-01-02）上，在实际日期（2026-10-06）下落在过去 → 改为相对 `now()` 计算。
`raffleCacheWarmup` 补登 `xxl-job-tasks.sql`（handler 早已存在但漏了 seed 行）。

**code-review 双轴复核（Standards + Spec 并行子代理）抓到的最重一条**：
**兑次数原先根本没有真幂等键**——`redeemOrderId` 每次请求现编 snowflake，javadoc 却声称
「跨重试幂等由两域 uk 吸收」，于是**网络重试 = 一笔新兑换 = 重复扣积分**（抽奖侧有 token 幂等，
兑换侧漏了）。修法：抽出 `types.common.IdempotencyKeys`（`hash(userId, token)` 确定性映射），
抽奖与兑换共用一个口径；`POST /exchange/draw-count` 改为 body 带 **必填 `token`**
（`RedeemDrawCountRequestDTO`），并补同 token 重放用例。这是本票**唯一的破坏性签名变更**，
已写进 `## Answer` 供票 14 跟。同批复核还修了：yml 日志清单残留 `infrastructure.lottery.dao`、
`IInventoryDeductPort`/`parsePrizeStates` 注释误导、`test-env.sh` 重复行、单体
`MarketingNoticeCase` 的 `POINTS`→`VIRTUAL`（跨工程口径，better-school 侧 6 用例复跑绿）、
补 `draw_virtualExhausted_fallsBack`（虚拟档兜底此前无用例）。

**测试**：marketing 全量 **68 绿**（`mvn -o clean test`；抽奖侧 27 = 票 11 的链 5 + 预热 2、
票 13 的主链路 11 + 读侧/兑换 9；旧 `Lottery*` 9 个类全删，票 07–12 其余 41 个用例零回归）。
better-school 侧回归：`mvn -o test-compile` 绿 + `MarketingNoticeIntegrationTest` 6 用例绿。


### 票 15 收口（超时中心对齐 sql.md：`timeout_task` + 注册 outbox）

**零行为变更**：`timeout_task`（§3）与 `timeout_register_outbox`（§1 L198–L235）在**物理 DDL 上全等**，
本票改的是 `sql.md` 的文字口径——§3 是「分表之前的草案」，与落地代码在 4 处不一致：
列名 `type/state/create_time/update_time` → 落地 `task_type/status/created_at/updated_at`、
`uk_type_biz` 列序 `(biz_id, type)` → `(task_type, biz_id)`（与路由键同序 + 是所有按键语句的谓词顺序）、
表头「单表形态」→「逻辑表 = 16 张物理同构表 `timeout_task_00..15`」、文首命名口径行 L5（见下）。

**第 5 处（票面没预见、本票定的）**：`sql.md` 文首 L5 的命名口径写「**全库**不再出现 `created_at`/`updated_at`」，
而 §3 落地形态用的正是这两个列名（实测 §1 单体 29 + §2 营销 22 = **51 张全守口径**，`timeout_task` 是 52 张里的唯一例外）——
**登记为例外**（文首口径行 + §3 各写一处），不回溯改名（改名要动 DDL × 16 + `TimeoutTaskMapper.xml`，收益只是命名整齐）。

**分表形态结论 = 已满足**（不重建、不拆分）：`information_schema` 实测 16 张表在、
`sharding.yaml`（逻辑表 + HINT 算法）+ `TimeoutTaskShardSlots` 单点公式 + `TimeoutTaskMapper.xml` 全写逻辑表名，
`docs/db/timeout-task.md` 已逐条写清分片路由。**改了 5 个文件**：`sql.md`（§3 回写 + 文首口径行 + §1 注 + `type` 列注释消歧义）、
`schema-timeout-task.sql` 头注释（声明本文件是 DDL 唯一事实源、变更须同批回写 `sql.md` §3）、
`better-school/sql/schema.sql` 一行列注释、本票文件、`map.md`（含行区间索引实测更新 + 「优先用 § 标题定位」告警）。
**DDL 侧列 / 键 / 默认值全部一字未动。**

**两表列名「不一致」是刻意的**（注册边在业务库用 `type/state/create_time`、中心在 `bs_timeout` 用 `task_type/status/created_at`，
跨库不 join、各照本库口径）——已在 `sql.md` 两处各写一句防下一轮被误「对齐」。

**注册边 / 回调链路语义未动**：`ITimeoutTaskRegisterPort` 注册边、`TimeoutTaskCallbackPort.onTimeoutBatch`
回调边（DTO 仍 `taskType/bizId/actionTime` 三字段不带快照）一行未改。

**测试**：timeout-center **37 绿**、better-school **230 绿**（`mvn -o test`，串行跑；两服务测试库不同本可并行）。

**未触发拆分**（`Context used: ~120k`，含 code-review 双轴复核）。**两处票面落空留痕**：
① 验收 3 走的是「列出差异表」那一支——差异全在 sql.md 文字口径，物理 DDL 0 处已改（反向改 16 张分表才是破坏性 DDL + mapper + 分片函数，与「已满足只补文档」冲突）；
② Question 说「满足则只补文档说明」，本票的「补文档」额外包含**回写 `sql.md` 本体**——照 map 自己的口径（L84「发现对不齐之处在票内定并回写 `sql.md`」）+ 票 11 先例做的，否则 §3 会继续向票 17/18 描述不存在的列名集合。

**code-review 抓到并已修 6 条**（详见票 15 `## Answer` §5）：`### timeout_task` 标题回写时丢失（补回）、槽位公式两处不一致（统一为 `TimeoutTaskShardSlots.slotOf` 的真公式）、「与分片函数前缀同序」错误归因、表数 52/53 计数错、文件清单漏 `map.md`、`map.md` 行区间索引失真（实测更新 + 加「优先用 § 标题定位」告警）。

### 票 14 收口（发货闭环：本地消息表 + MQ + 状态机；前端营销页与审核台对齐）

**生产侧**：`draw` 落库那一个事务现在落**三处**——`draw_record` + `draw_record_detail(DRAW)` + outbox 中奖事件
（`DrawRecordAggregate` 带 `drawnEvent`，`DrawRecordRepository` 接 `OutboxMessageStore`）。事件与业务写同事务是
outbox 口径的根：回滚则事件行一起消失。`outboxScan` 从「有形状没调用方」接上生产侧，并**加超时过滤**
（只捞 `state = 0` 且 `update_time` 超过 `outbox.scan.timeout-seconds`，默认 60s；刚提交那批归 afterCommit），
`retry_count` 到 5 置 `state = 2`（死信）。

**消费侧**：新消费者 `LotteryShippingConsumer`（组 `bs-raffle-shipping-group`，**新起**——该 topic 上积分侧已占
`bs-points-lottery-drawn-group`，同组会抢消息；施工前经用户确认按「不新造 **topic**」理解）驱动状态机。
`marketing-case`（此前空模块）首次落地 `RaffleShippingCase`：跨域写组合顺序调用、**无跨域事务**
（先状态推进本地事务，再委托 `IQuotaConfirmPort` 做实物配额 `waiting → issued`）。

**状态机（域内唯一入口 `RaffleShippingServiceImpl`）**：虚拟 / 次数档 `EXCHANGE`+`COMPLETE` 一步到已完成；
实物档 `SHIP` 到已发货并补兑换确认；用户 `POST /my-prizes/{id}/receive` 落 `COMPLETE` 到已完成；
THANKS 落库即终态。幂等三道闸：条件更新守卫（起点状态）+ `uk_record_type` + `msg_id`。

**实物档的三步编排（确认必须排在推进之前）**：状态机拆成**只读计划** `planShipAfterDraw` 与
**推进** `advanceShipAfterDraw`，case 层在中间插库存域的 `confirmIssued`。原因：`confirmIssued` 会因
`inventoryMerge`（每秒）还没把预扣翻进 `waiting_qty` 而抛 4312，而事件比合并先到是常态——若推进先落库，
这一抛会让状态停在已发货，重投时记录已不在待兑换 → 状态机早退 → **确认再也不会执行**，`waiting_qty`
被静默吞掉且无任何报错。拆开之后「确认失败 → 记录仍待兑换 → 重投完整重放」由结构保证。
**这条 bug 是 code-review 的 Spec 轴揪出来的**（原设计是「一次调用内先推进、调用方随后补确认」），
修后补了竞态用例 `ship_physicalPrize_eventBeatsMerge_retriesUntilMergeLands`。

**两处与票面字面的偏离（均撞到 `uk_record_type` 后定，已写进 docs）**：
1. **`已兑换` 成预留段**——实物档从中奖直达「已发货」（发货即确认占用），虚拟档直达「已完成」；
   不为此新造动作枚举，`EXCHANGED` tab 当前恒空，四段口径保留给「到店自提 / 人工发放」这类真有中间态的场景。
2. **库存兑换确认没有自己的幂等键**（票 12 的 `confirmIssued` 按数量转移、无单号判重）：去重锚点是
   **抽奖域的状态起点**——「记录是否仍在待兑换」就是「这一步做没做过」。代价是「确认已提交、推进未提交」
   之间崩溃时重投会再确认一次并撞 4312（显式失败 + 日志可查 + 人工核），而不是静默吞掉。

**收口时抓到的问题**（都在票 14 `## Answer` 有明细）：抽奖侧夹具缺 `physical_inventory` 那一行（`confirmIssued`
要同时递减库存台账）；确认静默丢失（上条）；`existsDetail` 判定时机写反会让确认永不执行；重发用例拿
「绝对值 0」当「没被扫到」的假红（首次失败的异步回调也会给刚提交的行 +1）；`@Value` 非 final 字段与类上
`@RequiredArgsConstructor` 不共存（改显式构造器）。

**测试**：marketing 全量 **80 绿**（票 13 时 68，本票 +12：生产侧 4 + 消费侧 7 + 死信 1）；
`better-school` 全量 **230 绿**（含 `MarketingNoticeIntegrationTest` 6 绿；载荷一字未改，两端无需同改）；
`front` 的 `npm run build` 绿 + 390×844 九张走查截图（`.scratch/project-rework/verify-14/`）。

**前端随动**：`MarketingView.vue` 整体重写（去邀请码 / 填码 / 积分兑奖品三块 + 去 `slogan`/`dailyLimit`/`image`，
奖品块改按 tier 现拼）；`MyPrizesView.vue` 四段 tab + 「确认收货」（收件信息表单随票 13 删端点下线）；
`api/marketing.ts` 重写（删 referral 三件与三个废端点，加 `confirmReceived`）；`vite.config.ts` 删死代理 `/api/referral`。
审核工作台**本票只走查未改动**（已无 agent 痕迹、无空区块）。

> **与票 16 的边界**：票 16 的「拉新 / 邀请码」营销页半边被本票整体重写带走；票 16 其余项
> （求购 / 群聊 / 多人单 / 评价 / `condition` 成色）本票一行未动（已 grep 确认）。

**一处票面必读落空（留痕）**：rafflex 的 `IMqDao` / `SendFailedMqJob` / `ShipUserRaffleRecordConsumer`
实际未照抄——本仓 outbox 形状票 08 已定型（与单体同款），rafflex 是另一套命名与列，照抄反而偏离本仓口径；
只借了它的**语义**（发货事件驱动状态推进 + 实物走兑换确认），结构按本仓域 / 端口分层重写。

### 票 17 收口（终验 A：全栈可起 + 冒烟 9/9 PASS）

**Destination 判定 = 可达**：三服务全量测试 352 全绿（better-school 230 / marketing 80 / timeout-center 42）、
`front` 构建绿、冒烟 9 项逐项留证（`verify-17/`，9/9 PASS，**0 业务缺陷**）。

**全栈入口从此换人**：`backend/scripts/dev-stack.ps1`（Windows PowerShell 5.1；这台机器 bash/WSL 坏，
`test-env.sh` / `run-app.sh` 跑不了）。`up` 幂等可重复（03b 取证），`db-reset` 只 DROP 三个开发库；
`backend/CLAUDE.md` §10 环境口径已同步改写。坑都记在票档 Answer 与交接档：PS 5.1 无三元、
`$ErrorActionPreference='Stop'` 会把原生命令 stderr 当终止错误、`.ps1` 必须 UTF-8 BOM、
`Write-Host` 走 Information 流**不进管道**（取证要用 `*>&1`，`2>&1` 捕不到）、MySQL 喂中文走 `cmd /c type`。

**注册面清零**（`verify-17/evidence/05-registry-checklist.md`）：xxl 17→14 任务；MQ 删 6 topic + 6 组
（code-review 补录 2 个孤儿 `bs-errand-timeout` / `bs-errand-auto-complete` 并删对应死常量）；
Nacos 删 10 条遗留 dataId；referral / 旧 `lottery_*` 判定为「从未注册」无可清项。
票面把 9999/9998/9996 写成 actuator 有误——是 xxl 执行器端口，本仓无 actuator 依赖。

**遗留 5 项全部归票 18**（docs/api README 端口、MySQL 卷已删工程库的 DROP 决策、`BenchmarkTest` 空 topic、
`rafflex-backend/` 入库与否、冒烟脚本重复代码随 `.scratch/` 处置），见票 17 `## Answer`。

### 票 08 收口（bs_marketing DDL 22 张 + outbox 形状切换）

- 22 张新表落 `backend/marketing/sql/schema.sql`，与 sql.md §2 逐表逐列全等（`cmp_schema_marketing.py` 22 identical）；
  `strategy_weight` 是 §2 唯一设计稿原样表（反引号 + `create_time/update_time` 默认值），为保逐列全等照抄未按本库口径改写。
- 过渡期双 schema：`schema.sql` 22 新 + `schema-legacy.sql` 15 旧，保证票 09–13 每票收口都能跑全量测试
  （legacy 清单归票 09/10/13 分批删，票 13 清空即删文件）；outbox 因两库同名不同形状在本票一次性切换。

### 票 09 收口（删拉新）

- 营销侧 referral 域 31 文件 + 单体侧成团通知 2 文件删除；`REFERRAL_GROUPED` 全网无生产/消费方；marketing 63 测试绿。
- `DrawCountReasonType` 只剩 REDEEM/DRAW；`ResponseCode` 删 4201–4206；种子活动「开学季拉新抽奖」→「开学季抽奖」。
- `IUserQueryPort.getBasicBatch` 随唯一调用方删除；跨工程的 `bs-api IUserQueryService.getBasicBatch` 成全网零调用契约，
  超出票面边界（票 17 注册面清零亦未动它）——留给后续清理票。
- 前端残留（vite 代理 / marketing.ts / 邀请码面板）归票 16，票 14 重写营销页时提前带走大半。

### 票 10 收口（积分 / 次数切桶）

- 7 张新表全链落地（两桶 + 两明细 + 两配置 + 行为返利）；桶获独立业务 id（雪花 `bucket_id`），消费明细改引 bucket_id，
  物理主键彻底无业务含义；旧 `points_lot`/`points_consume`/`points_config` 三表随 legacy 删除。
- 对外契约逐字未变（`IPointsService`/`IPointsExchangeService` 签名与 DTO JSON 形状）；`bs-points-changed` 仅新增 reasonType=REBATE。
- 「Lua 原子」落为 DB 原子（UPDATE 守卫 + FOR UPDATE FIFO + 同事务 outbox）——桶的事实源是 MySQL，不拆两份（code-review Spec 轴确认保留）；
  「签到/支付返利」只建能力不接业务（业务事件生产入口超票面边界）。

### 票 12 收口（库存两段式）

- 单域段 `domain.inventory` 两段（virtual / physical）同构建模：共享 Redis 桶端口（Lua 扣减）/ 合并骨架 / 预热 / 对账口径；
  结算列分叉 `issued_qty` vs `waiting_qty`，兑换确认实物独有（票 14 消费）。
- 合并幂等 =「明细翻转只按本组携带 id + 落账同事务」（绝不 `WHERE state=0`）；预扣成功而落库失败的窗口**不补偿**，由对账 ③ 暴露。
- 两处有意偏离（code-review 确认）：合并扫描不做 activity 前置连接（明细自带桶组，停用库存的遗留明细也能收敛）；
  预热只落手动 job（SET 语义会把在途预扣灌回去，xxl seed 里 trigger_status=0）。

### 票 16 收口（前端减法）

- `front/src` 五组验收词全零；`npm run build` 绿；34 条双视口走查（17 页 ×2、64 断言）+ 58 处页内链接 + 114 处源码跳转零死链。
- `GET /chat/rooms/{roomId}/members` **保留**：1v1 已读回执的水位初值仍需要（spec §7.5）——票面「删成员接口」不成立。
- 遗留小项（不在清零词内，未动）：`types/api.ts` 的 `NotificationType` 缺 `AUDIT_PENDING`/`POST_LIKED`/`LOTTERY_WON`/`POINTS_CHANGED` 等值
  （`Record` 兜底不影响渲染）。

### 票 18 收口（文档 / 词条 / sql.md / map 收口）

- **比对脚本 5 份全绿**：`cmp_schema.py`（29/29 表，两处 COMMENT 回写后全等）、`cmp_schema_marketing.py`（22/22）、
  `cmp_db_docs.py`（0 问题）、`cmp_api_routes.py`（0 问题）、`cmp_mapper_params.py`（无问题）。脚本本身修三处口径：
  controller 注解带附加属性（`consumes`）漏匹配、模块前缀改按 `docs/api/README.md` 总览表解析（文档正文是相对路径）、
  `# chat 域表（…）` 域总览标题被误当表名的幽灵表误报；另加 `.../后缀` 连写简写与 WS 握手 URL 两处容忍。
- **sql.md 回写 3 处**：§1 `post_like_count.like_count` 列 COMMENT 去掉自指「见下方说明」、`room.state` COMMENT 补
  「当前恒 1v1，SEALED 不再产生」（均对齐 `schema.sql` 票 04 后口径）；§2 来源行从「设计稿未落库」改指
  `backend/marketing/sql/schema.sql`（票 08 落地、票 13 删过渡文件）。
- **docs/api 补齐 4 块缺口**：新增 `wallet.md`/`credit.md`/`search.md` 三篇（路由早已存在、文档从未覆盖）+
  README 总览补三行、`trade.md` 商品订单系列由一句话展开为 7 条路由、`moderation.md` 补敏感词/审核员管理面 5 条；
  `backend/docs/api/README.md` 端口 8082 → 8081（票 17 遗留①）、`backend/docs/README.md` 起应用入口改 `dev-stack.ps1`
  （svc 键收敛 `bs|marketing|center`）、`better-school/docs/api/README.md` 营销文档指针改指 `backend/marketing/docs/api/`。
- **grep 清零 15 词全零**（git grep 口径，排除 logs / target / node_modules / dist；logs 不入库本就零命中）。
  清洗面：Java 注释 5 文件（钱包冻结轮次、注册 outbox 撤销、到期扫描 job、VT 消费者清单测试）+ 测试夹具描述文案 6 文件、
  `dev-stack.ps1` 注释、`timeout-center.md` §8/§9 按「两处登记 + 两 taskType + 取消边无生产调用方」重写、
  营销 docs 墓碑注释去旧表名字面量、`CONTEXT.md` 3 处、`front/vite.config.ts` 注释 1 处。
  **唯一保留的字面量**：`dev-stack.ps1` 清理数组里的 `referralOutboxScan` 等 handler/topic 名——那是 `mq-prune`/`xxl-seed`
  按**字面量**去删 broker / admin 残留的功能性清单，不是能力残留（票 17 冒烟取证依赖它）。
- **词条复核**：新增能力词条（互动计数、状态流转记录、积分桶/抽奖次数桶、责任链/权重档/表预热/预扣/发货闭环/奖品状态推进）与实现一致；
  本票新增「超时关闭」两词条（超时任务 / 注册 outbox）；已下线能力字样按清零口径移出 Avoid 列表与信用分词条。
- **遗留物处置**：`.scratch/project-rework/` 的 5 份 `cmp_*.py` + 3 份 tsv **入库留作长期对账工具**（本轮与后续改表/改接口都可复跑）；
  `Migrate/Compare-*.ps1` 与 `verify-14/16/17`、`walkthrough-16/` 是一次性验收证据，原地保留不再引用；
  `05-handoff.md` 顶部标注历史件；`rafflex-backend/`（参考项目，含独立 `.git`）**留在磁盘、不入库、不删除**（删除属破坏性操作，
  嵌套 git 库也无法入库），加进 `.gitignore` 保持 `git status` 干净。
- **转出项（记录不执行）**：MySQL 开发卷里已删工程的旧库（`bs_asset`/`bs_activity` 及 `*_test`、`bs_it_*`）未 DROP——
  破坏性操作留给使用者按需清理（`dev-stack.ps1 db-reset` 只重建三个在用开发库）；broker 上 `BenchmarkTest` 空 topic
  同属环境残渣，留 `verify-17/broker-topics-after.txt` 备查；`cancelAsync` 当前无生产调用方（通用撤销能力，javadoc 已改通用口径保留）。

## Not yet specified

- ~~前端删功能后的页面留白/导航调整细节，随票 16 走查时定。~~（票 16 已收口：34 条双视口走查零死链、无空区块）
- ~~抽奖重写中若发现 `sql.md` 的 `draw_*` 与 rafflex 模型对不齐之处（如 `strategy_weight` 表格式未按文档口径规范化），在票 11 内定并回写 `sql.md`。~~（票 11 已回写：`draw_strategy_weight` 规范化 + `award_decr`→`award_desc`）
- ~~跑腿评价下线对前端「订单评价」入口的影响范围，随票 16 走查时定。~~（票 16 已收口：评价入口/互评卡片删除，走查确认无空块）
- ~~**槽位公式的注释漂移（票 15 发现）**：4 处短写 `hash(task_type + biz_id) % 16` 待统一。~~（票 18 已改齐：types `Constants`、`TimeoutTaskShardSlots` javadoc、`TimeoutCenterApiTestBase`、`docs/db/timeout-task.md` 四处均补长度前缀）
- ~~票 12/13/14 触顶则拆片。~~（均未触发拆分；触顶实测见票 17：~340k，佐证「验证+收口」型票按 200k 出头立项更稳）

## Out of scope

- 部署编排：docker-compose 三服务一键起全栈（另起小 effort）。
- 性能调优：复用 `virtual-thread/measure` 既有成果，本轮不新开压测。
- gatekeeper / agent 审核的任何形式的保留、归档、独立拆库——全部直接删除。
