# spec：营销域（拉新 / 抽奖 / 积分）与市集入口改版

本文件是本次 effort 的终点产物。口径来源：票 01（charting 阶段 grill 的全部基线）+ 逐票收口时追加的节。
**未定的节不写**，等对应票 resolved 再追加，避免与后续票打架。

## 索引

| 节 | 状态 | 来源票 |
| --- | --- | --- |
| 营销域（三个服务 / 拉新 / 抽奖 / 积分 / 奖池库存） | 基线已定；**工程骨架与跨服务链路已定稿**（票 04）；**表结构与行级时序已定稿**（票 05）；**接口口径与失败语义已定稿**（票 06）；**通知投递与已读模型已定稿**（票 07） | 01 · 04 · 05 · 06 · 07 |
| 市集入口行 | **已定稿**（视觉与吸顶层级） | 02 |
| 营销活动页与抽奖交互 | **已定稿**（视觉与交互） | 02 |
| 我的奖品 / 中奖记录 | **已定稿**（视觉） | 02 |
| 市集消息页 | **已定稿**（视觉与信息结构） | 02 |
| 底部「消息」页收口 | **已定稿**（视觉与信息结构） | 02 |
| 市集消息页 / 聚合订单页的数据口径与去向 / 通知与已读模型 | **已定稿** | 07 |

---

## 营销域（三个服务 / 拉新 / 抽奖 / 积分 / 奖池库存）

来源：票 01（口径基线，业务口径全文见 [票 01 Answer](issues/01-baseline-decisions.md)）+ 票 04（工程骨架与跨服务链路）+ 票 05（数据模型与行级时序）+ 票 06（接口口径与失败语义）。
**栈选型的完整论证与逐条出处见 [research-cloud-stack.md](research-cloud-stack.md)**（票 03 产出；两处「必须冒烟验证」仍是落地硬前置）。
**表结构与行级时序已由票 05 定稿**（见下「数据模型与行级时序」）；**接口清单、失败码与免登录白名单已由票 06 定稿**（见下「接口口径与失败语义」）。

### 服务拆分与工程骨架

- 三个平级**独立 project**：`backend/points/`（积分）、`backend/lottery/`（抽奖）、`backend/referral/`（拉新）；单体 `backend/pom.xml` 原地不动，与三服务**无 reactor 关系**（靠 `~/.m2` 里的 `bs-api` / `points-api` 通信）。
- 每服务 = 一个聚合器 pom（parent `spring-boot-starter-parent:3.3.5`）+ 7 个模块 `<svc>-types/api/domain/case/infrastructure/trigger/app`，依赖方向照 `backend/CLAUDE.md` §1 严格单向。
- groupId `com.betterschool`；基础包 `com.betterschool.<svc>`；启动类在各自 `-app` 根包。
- **每个服务自带 types**：各自的 `-types` 内自带 `common/Constants`（本服务 topic / redis key 前缀）、`enums/ResponseCodeEnum`、`exception/AppException` —— **不复用 `bs-types`**（改一个常量会三个服务一起重发）。对外 HTTP 的 `Response<T>{code,info,data}` 信封放各自的 **`-api/response/`**（照 `backend/CLAUDE.md` §5 与 `bs-api/.../response/Response.java` 的位置），前端 `unwrap` 不变。
- mapper XML 在各自 `-app/src/main/resources/mybatis/mapper/`。
- 三个**各自独立 schema**（同实例），跨库不 join、互相只存 id；昵称头像一律经 Dubbo 取回后应用层拼装。

### 事件边契约（异步）

| topic | 生产方 | 事件体 | 消费方（消费组） |
| --- | --- | --- | --- |
| `bs-referral-grouped` | 拉新（成团成功） | `{groupId, inviterId, inviteeId}` | 抽奖 `bs-lottery-referral-grouped-group`（双方各 +1 次）、单体 `bs-monolith-referral-grouped-group`（落通知） |
| `bs-lottery-drawn` | 抽奖（中奖） | `{recordId, userId, prizeId, prizeType, pointsAmount}` | 积分 `bs-points-lottery-drawn-group`（积分档加分）、单体 `bs-monolith-lottery-drawn-group`（落通知） |
| `bs-points-changed` | 积分（变动） | `{userId, delta, balanceAfter, reasonType, refId}` | 单体 `bs-monolith-points-changed-group`（落通知） |

- **⚠️ 2026-09-14 票 05 反转**：用户拍板「**成团只加抽奖次数**」⇒ `bs-referral-grouped` 的**积分消费方与 `bonusPoints` 字段取消**（原文「默认 0 的成团送积分」配置一并取消）。详见 [票 05](issues/05-marketing-data-model.md) 决策依据第 6 条；[票 01 §3·§5](issues/01-baseline-decisions.md) 已同步留注记。
- **事件体只带「id + 消费方决策必需的值」**，不带昵称头像等展示快照（展示走 Dubbo 查用户），消费方**不反查生产方** —— 这是「缺一个服务也能跑」的前提。
- **消费组命名 `bs-<service>-<topic>-group`**（对既有 `bs-<topic>-group` 约定的修订：同 topic 多方订阅时同组会抢消息、不同组各收全量）。
- **每个服务各自一份 outbox**（`outbox_message` + `OutboxMessageStore` / `OutboxMessageSender` / `@XxlJob("outboxScan")`）；handler 名可同名（各自独立 xxl 执行器 appname）。`MAX_RETRY_COUNT = 5`；死信 = `status = 2`；消费侧解析失败 = log + 丢弃，业务异常 = 交 broker 重试。
- **消费侧幂等键**（落点与列组合均已由票 05 定稿）：积分 → `points_lot` 的 `uk_user_reason_ref(user_id, reason_type, ref_id)`；抽奖次数 → `lottery_draw_count_lot` 的同名 uk（`reason_type = REFERRAL`、`ref_id = groupId`）；单体 → `notification.dedup_key`（形如 `MARKETING:GROUPED:<groupId>`）。不额外建 consumed_message 表。

### 同步边（Dubbo）

- **新增 `IUserQueryService`（`bs-api`）**：`UserBasicDTO getBasic(Long userId)` + `List<UserBasicDTO> getBasicBatch(Collection<Long> userIds)`，`UserBasicDTO { userId, nickname, avatar, createdAt }`。**不复用 `IUserService.getProfile`**（实测无 `createdAt`，且带 `receivedLikeCount` / 隐私开关等无关字段）。
- Provider 在单体 `bs-trigger/rpc/UserQueryDubboService`（`@DubboService`，只读，新增 `rpc/` 子包）。
- Consumer 在三服务：`<svc>-domain/adapter/port/marketing/IUserQueryPort` + `<svc>-infrastructure/adapter/port/marketing/UserQueryPort`（`@DubboReference(check = false) IUserQueryService`，映射成域类型）。**域层与 case 层不认识 `bs-api` 类型**。`check = false` 保证单体没起也能启动。
- **兑换边**（票 01 §2 定的唯一另一条同步边）：接口 **`IPointsExchangeService` 放 `backend/points/points-api`**（DTO 同模块），积分服务在 `bs-trigger/rpc/PointsExchangeDubboService`（`@DubboService`）实现；**抽奖服务 pom 直接依赖 `com.betterschool:points-api:1.0.0-SNAPSHOT`**，域内 `IPointsExchangePort` + infra `@DubboReference(check = false)` 实现。**幂等键 = 兑换单 id**。
  - ⚠️ **由此取消票 01 §4 的「三个营销服务零编译期依赖」**（用户 2026-09-14 拍板「必要时可以依赖」：兑换把抽奖与积分绑在一起）。「零互相注入」（不跨服务注入 Spring bean）不受影响。**验收口径**：积分服务停掉时，抽奖（抽一次）与成团照跑，**只有兑换不可用**。
- 各 project 的 Dubbo Provider 实现统一放 **`-trigger/rpc/`**、命名 `XxxDubboService`（与 `http/job/listener/ws` 并列；`backend/CLAUDE.md` §4 各补一行）。
- **不做** `INotificationPort` / 不复用 `INotificationService`（通知改事件订阅，一条方法都不用）。
- Dubbo `3.3.6`，显式配 `tri` 协议 + 每服务独立端口；**`serialize-check-status` 默认 STRICT 是首次调用炸点**（本地联调先加 allowlist 或设 `WARN`）。

### 通知投递

**事件订阅**：单体作为第四个消费者订阅三个营销 topic，case 层复用 `NoticeService.sendSystem(...)` 落 `notification` 表。营销侧**不同步调单体**；单体停 → 营销照跑。
营销通知的 `type` / `refType` / `scope` 取值**已由票 07 定**：新增 `REFERRAL_GROUPED` / `LOTTERY_WON` / `POINTS_CHANGED` 三个枚举值（**不复用** `ORDER_EVENT`）、`ref_type = MARKETING`、`scope = BOTTOM_SYSTEM` —— 落**底部消息页 system tab**，**不进市集消息页**（详见票 07 Answer §4 与下「通知与已读模型」）。

### Nacos

- Nacos server `2.5.4`（`docker-compose.yml` 新增 `bs-nacos`，standalone，端口 `8848` + `9848`，补三个 `NACOS_AUTH_*` 引导变量，不设 `NACOS_AUTH_ENABLE`）。
- **只有 1 个共享 dataId `better-school-shared.yml`**：`jwt.secret` / `jwt.expire-days`，四个 app 都经 `spring.config.import: nacos:…` 导入（`bootstrap.yml` / `shared-configs` 均废弃）。**单体也接**（删本地两行）⇒ jwt.secret 单一来源，轮换改一处；「没 Nacos 单体起不来」已由「单体做 Dubbo Provider」付掉。
- 各服务自己的配置（数据源 / schema / 端口 / topic）留在各自本地 `application.yml`，**不拆 per-service dataId**。单体其余静态 yml 原地不动。

### 鉴权

- 每个服务**自带**一份 `JwtAuthenticationFilter` + `JwtService`（读同一个 Nacos 下发的 `jwt.secret`），各自 `SecurityConfig` 照单体白名单写法（`.requestMatchers(HttpMethod.GET, "/api/<svc>/**").permitAll()`）。**白名单只两条**（票 06 定）：`GET /api/lottery/activity`、`GET /api/lottery/prizes`；其余全需登录，开桶接口另有 ADMIN 校验（见下「接口口径与失败语义」）。
- Dubbo 不走 HTTP ⇒ **不需要 `X-Service-Token`**。
- 已知取舍：Dubbo tri 端口本机明文暴露，本地可接受，生产需内网隔离（本期不做）。

### 本地开发与构建

| app | HTTP | xxl 执行器 appname / port / log-path | Dubbo tri |
| --- | --- | --- | --- |
| 单体 | 8080 | `better-school-executor` / 9999 / `./logs/xxl-job` | 50051 |
| points | 8081 | `better-school-points-executor` / 9998 / `./logs/xxl-job-points` | 50052 |
| lottery | 8082 | `better-school-lottery-executor` / 9997 / `./logs/xxl-job-lottery` | 50053 |
| referral | 8083 | `better-school-referral-executor` / 9996 / `./logs/xxl-job-referral` | 50054 |

- `bs-rocketmq-dashboard` 宿主端口 **`8082 → 8089`**（让出 8082 给 lottery）。
- Vite 三条代理**必须排在既有 `/api` 之前**（按 key 顺序匹配）：`/api/points → 8081`、`/api/lottery → 8082`、`/api/referral → 8083`；三个服务 controller 映射在 `/api/<svc>/**`、**不 rewrite**。
- 前端**单实例**：`front/src/api/http.ts`（`baseURL: '/api'`）不变，新增 `api/marketing.ts` 等调 `httpGet('/points/…')`。
- 每 project 独立 `mvn`；**契约变更纪律**：`改 bs-api / bs-types → 先在 backend/ 执行 mvn -o install -pl bs-api -am -DskipTests，再重启三个服务`；改 `points-api` 同理（在 `backend/points/` 装 `points-api` 后重启抽奖服务）。
- 启动顺序：中间件（含 `bs-nacos`）→ 单体（provider）→ 三服务。

### 单体侧改动清单

新增 `bs-trigger/rpc/UserQueryDubboService`（`@DubboService`，只读）+ 三个事件消费者（`bs-trigger/listener/`：`ReferralGroupedConsumer` / `LotteryDrawnConsumer` / `PointsChangedConsumer`）+ Dubbo / SCA 依赖与 `application.yml` 调整（删 `jwt.*`、加 `spring.config.import` 与 `dubbo.*`）。**不做**任何反向调用营销服务；**不改**现有接口语义。**票 07 追加**：`notification` 表结构改动（`scope`/`seq` + 计数器 + 水位表 + `is_read` 退休）+ `NotificationController` 三接口按 `scope` 改造 + `NoticeService.sendSystem` 调用点补 `scope` 与取号 —— 细节见上「通知与已读模型」。

### 数据模型与行级时序

来源：票 05（**全文见 [票 05 Answer](issues/05-marketing-data-model.md)**，此处只写口径骨架）。

**三个独立 schema**：`bs_points` / `bs_lottery` / `bs_referral`，各自一份 `outbox_message`。**跨 schema 硬约定**：三服务互相只存 id、**不 join、不存昵称头像快照**，用户信息一律经 Dubbo 取回后应用层拼。

**桶化（lot）模型 —— 积分与抽奖次数共用**（**⚠️ 反转**：推翻了「余额列 + 条件更新防负」的常见做法）：

- 每笔入账 = 一行**桶**，带自己的 `expire_at`；消费按 FIFO（`ORDER BY expire_at ASC, id ASC FOR UPDATE`）扣各行 `remain`，凑不够则整事务回滚
- **余额 = `SUM(remain)` over 未过期桶**——没有余额列，`FOR UPDATE` + 求和校验天然不可能为负
- **独立过期**：读路径一律带 `expire_at > NOW()`（懒过期为准）；xxl-job 只做 `remain = 0` 的落账清理（不删行、留审计、不发事件）
- 积分：`points_lot` + `points_consume`，`points_config.points_valid_days` 种子 **365**；`reason_type` 仅 `LOTTERY_WIN` / `REDEEM_DRAW_COUNT` / `REDEEM_PRIZE`
- 抽奖次数：`lottery_draw_count_lot` + `lottery_draw_count_consume`，`draw_count_valid_days` 种子 **30**；`reason_type` = `REFERRAL`（成团）/ `REDEEM`（兑换）/ `DRAW`（消费）；**「次数从哪来」必须留**（票 04 的消费幂等键就落在这张流水上）

**抽奖 schema 要点**：

- **奖品表与库存表合并**成 `lottery_prize`（偏离 `实现方案3.md` 的独立 `inventory`：本域奖品与库存严格 1:1，拆开只多一次 join）；`type = PHYSICAL | POINTS | THANKS`，**只有实物档进 `sq/lq/oq`**
- 「谢谢参与」**落一行 `type = THANKS`**（有 `weight`、无库存、恒有货）——概率可由运营改一行 weight 调，而不是藏在权重补数里
- **每次抽奖都落一行 `lottery_draw_record`**（含谢谢参与），带 `random_value BIGINT` + `pool_snapshot TEXT`（当时的奖池快照）；「我的中奖记录」按 `prize_type != 'THANKS'` 过滤
- 扣减明细 `lottery_deduct_detail` 的幂等键是 **`uk(prize_id, biz_type, biz_id)`**（**⚠️ 修正**票 01 §7 的 `(prize_id, biz_id)`：`biz_id` 有两个 id 空间，自增 id 不跨表可比，漏 `biz_type` 会少扣库存）
- 我的奖品 `lottery_user_prize`（中奖与兑换**汇入同一张**，`source` + `source_ref_id`，三态 `PENDING` / `RECEIVED` / `CREDITED`）+ 兑换单 `lottery_redeem_order`（`id` 即兑换幂等键）

**拉新 schema**：`referral_invite_code`（一人一码、懒生成、去易混字符）+ `referral_group`（**只 `uk(invitee_id)`**，它已蕴含「同一对只一个团」）。**不建成团送积分配置**（见上文 ⚠️ 反转）。

**七条行级时序**（事务边界是关键）：

1. **填码成团**：查码 → 校验「不是自己」→ **事务外** Dubbo 取 `createdAt` 校验「活动开始后注册」→ 事务内 `INSERT referral_group`（撞 `uk_invitee` = 已被邀请过）+ 写 outbox → 提交。「一人一次」由唯一键兜，不靠先查后写
2. **抽奖**：预检（活动 / 每日上限 / 次数余额）→ 权重滚档 → **redis `DECR` 桶**（不足则回补 + 剔除该档重新归一）→ **一个 DB 事务**（每日计数 + 扣次数 + 明细 + 抽奖记录 + outbox）→ 成功发事件；**DB 事务失败只需补偿 redis（`INCR` 回桶）**——DB 侧只有一个事务，无跨系统分布式事务
3. **合并提交**：xxl-job **每秒**、**扫描不限量**（不加 `LIMIT`）；事务内 `sq/lq/oq` + 桶 `remaining` 同步 + **按本批带回的 biz id 列表翻 `status=1`**（**绝不写 `where status=0`**，否则超卖）
4. **DB 直扣兜底**：判据 = `try/catch` redis + 短超时 + 10s 冷却降级；`WHERE sq - lq - 1 > 0` 只卖非池；明细 `bucket_id` 用哨兵 `__db_direct__`、`status` 落库即 1；**`oq += 1`**（⚠️ 票 01 §7 原文只写 `sq` 减，本票补上）
5. **兑换两条边**：先插 `lottery_redeem_order(PENDING)` 拿幂等键 → Dubbo 扣分 → 本地事务（加次数 / 扣库存 + 落待领取）→ 置 `DONE`；重试用同一个 `redeemOrderId`，两侧幂等键各自跳过；极端残留停在 `PENDING` 供人工对账，不做真补偿
6. **实物领取**：提交领取表单时 `status: PENDING → RECEIVED` + 落收件信息 + **`oq -= 1`**（没有物流状态机，「领取」是唯一可落账时点）
7. **开桶**（**手工接口**，运营上架奖品 / 开启活动时调）：DB 事务（`lq += N` + 插桶行，**默认拨 80%**）→ **提交后** redis `SET`（不是 `INCR`）；已有开启桶行则用该行 `remaining` 重 `SET`（不新建桶）

**迁移与种子**：`backend/sql/migrations/` 续编 `022_points.sql` / `023_lottery.sql` / `024_referral.sql` / `026_notification_read.sql`（票 07：`notification` 加 `scope` / `seq` 两列 + `notification_seq` 计数器 + `notification_read` 水位表 + `is_read` 退休）；**`init.sql` 原地不动**。种子含活动（**`start_at` 设成过去日期**，票 01 §3 的验收注意）/ `points_config` / 3~5 档奖品（含谢谢参与行）。**开桶不在种子里**（SQL 写不进 redis），靠运营调开桶接口。

> ⚠️ **落地时改掉的两处**（用户 2026-09-15 拍板，见 [marketing-impl 票 07](../marketing-impl/issues/07-lottery-service.md)）：
> 1. **不建 `025_marketing_seed.sql`**：`scripts/test-env.sh` 的 `apply_marketing_init` 只跑
>    `init-marketing-*.sql` + `schema-<svc>.sql`，单独的种子文件没人执行、还会与 schema 里那份互相漂移。
>    种子（含奖品种子）**并入各服务的 `schema-<svc>.sql` 与 `02N_*.sql`**——05/06 已是这个形状，07 跟随。
> 2. **不建 `init-marketing.sql`**：票 06 已把建库口径落成终态——`init-marketing-{dev,test}.sql`（只建三个库、
>    不切库）+ `apply_marketing_init <建库文件> <库名> <DDL文件>`（把 `USE` 插在中间）。「一把起」由脚本承担。

**开桶留 20% 非池**（拨 80%）：否则 `sq - lq = 0`，DB 直扣兜底永远卖不出、形同虚设。

### 通知与已读模型（票 07 定，回填本节「数据模型」领地）

改的是**单体既有的 `notification` 表**（原 DDL 见 `backend/sql/init.sql:261-278`），**不是三个营销 schema**。

- **`scope` 列（新增，写入时由生产点显式标注）**：取值 `MARKET_ERRAND` / `MARKET_WANT` / `MARKET_PRODUCT`（预留、当前无生产方）/ `BOTTOM_SYSTEM` / `INTERACTION_LIKE` / `INTERACTION_COMMENT`。**不能从 `(type, refType)` 推导**——同一个 `ORDER_EVENT × ERRAND` 既可能是「发给用户的交易动态」（`scope = MARKET_ERRAND`），也可能是「发给审核员的审核提醒」（`scope = BOTTOM_SYSTEM`），只能靠收件人与生产意图区分（票 07 §0.5）。
- **读模型照抄 chat 的「序号 + 水位线」**（先例 `user_seq` / `timeline.delivery_seq` / `room_user.last_read_seq`，`init.sql:203 / 240 / 254`）：`notification` 加 `seq` 列，由 `notification_seq(user_id, scope, seq)` 计数器在**写入事务内取号**（行锁到提交 ⇒ 取号序 = 提交序）；水位表 `notification_read(user_id, scope, last_read_seq)`。
  - **标已读** = 推水位（O(1)）；**未读数** = `计数器 − 水位`（O(1)）；**每个页面 / 范围各推各的水位、互不误清**。
  - **禁止拿 `notification.id` 当水位**（全局物理自增，分配序 ≠ 提交序，并发下跳读——`init.sql:240` 在 `timeline` 上专门注明过这个坑）。
- **`is_read` 退休**：读态由「`seq` ≤ 水位」算出，响应里的 `read` 字段据此置位。现有 `POST /api/chat/notifications/read-all`（`UPDATE notification SET is_read = 1 WHERE user_id = ? AND is_read = 0`）**由新的 `POST /api/chat/notifications/read`（body `{scope}`，按 `scope` 推水位）取代**。
- **归属映射（生产点标 `scope`）**：跑腿支付入审 / 跑腿散团 → `MARKET_ERRAND`；求购支付入审 → `MARKET_WANT`；审核超时提醒（PRODUCT / WANT_TO_BUY / ERRAND / POST）→ `BOTTOM_SYSTEM`；公告 / 审核结果 / 举报结果 / 信用分变动 → `BOTTOM_SYSTEM`；赞 / 评论四类 → `INTERACTION_LIKE` / `INTERACTION_COMMENT`；营销三类 → `BOTTOM_SYSTEM`。
- **接口（`NotificationController`）**：`GET /api/chat/notifications?scope=`（新增过滤）、`GET /api/chat/notifications/unread-count?scope=`、`POST /api/chat/notifications/read`（body `{scope}`，推水位）。

### 接口口径与失败语义

来源：票 06（**全文见 [票 06 Answer](issues/06-marketing-api-contract.md)**，此处只写口径骨架）。三服务 controller 映射 `/api/<svc>/**`、**不 rewrite**（票 04）；分页一律 `page`/`pageSize`（默认 `1`/`10`、上限 `50`）+ `PageResult{page,pageSize,total,list}`（照 `/api/wallet/transactions`）。

**① 活动页的聚合读 = 前端并发调三家**。活动页 7 个区块逐块归属干净（banner / 抽奖主卡 / 奖品目录 / 底部 → 抽奖，邀请码卡 / 填码卡 → 拉新，积分卡 → 积分），无区块需跨服务拼装 ⇒ **不做 BFF**，各区块渲染各自的加载与失败态。

**② 接口清单**（鉴权列除标「免登录」外均需登录）：

| 服务 | 路径 | 方法 | 说明 |
| --- | --- | --- | --- |
| 拉新 | `/api/referral/invite-code` | GET | `{ code }`，懒生成（`uk_user` 幂等，可重放） |
| 拉新 | `/api/referral/invitees` | GET | `PageResult<{userId,nickname,avatar,groupedAt}>`；`total` = 已拉新人数、前 8 条 = 头像并排 |
| 拉新 | `/api/referral/groups` | POST | 填码成团，body `{code}` |
| 抽奖 | `/api/lottery/activity` | GET | **免登录**。`{name,slogan,startAt,endAt,status,dailyLimit,drawCountPrice,drawCountValidDays}`，`status ∈ NOT_STARTED·ONGOING·ENDED` |
| 抽奖 | `/api/lottery/prizes` | GET | **免登录**。`[{prizeId,tier,name,image,type,pointsAmount,redeemPoints,remaining,total,unlimited}]` |
| 抽奖 | `/api/lottery/draw-chances` | GET | `{ available, todayDrawn }`（`available` = 未过期桶 `SUM(remain)`） |
| 抽奖 | `/api/lottery/draw` | POST | 单抽，body `{token}`（见 ③）；**谢谢参与也走成功**（`prizeType=THANKS`） |
| 抽奖 | `/api/lottery/records` | GET | 我的中奖记录，**过滤 `prize_type=THANKS`** |
| 抽奖 | `/api/lottery/my-prizes` | GET | `?status=PENDING\|RECEIVED\|CREDITED`，三段 tab 的映射 |
| 抽奖 | `/api/lottery/my-prizes/{id}/claim` | POST | 领取表单 body `{receiver,mobile,address}` |
| 抽奖 | `/api/lottery/exchange/draw-count` | POST | 积分兑次数（Dubbo 调积分扣分） |
| 抽奖 | `/api/lottery/exchange/prize` | POST | 积分兑奖品 body `{prizeId}` |
| 抽奖 | `/api/lottery/buckets/open` | POST | **ADMIN**，开桶 body `{prizeId,percent?,amount?}`（默认 80） |
| 积分 | `/api/points/balance` | GET | `{ balance }` |
| 积分 | `/api/points/transactions` | GET | 流水（`points_lot` ∪ `points_consume` 按时间归并），`direction ∈ IN·OUT` |

- **兑换的两个写接口挂抽奖、不挂积分**（票 01 §2·§5 的既定边）：前端打抽奖 → 抽奖同步 Dubbo 调积分扣分。**兑换定价也归抽奖**（次数价 = `lottery_activity.draw_count_price`、奖品价 = `lottery_prize.redeem_points`，随上面两个 GET 返回，**不单做定价接口**）⇒ **积分服务对外只有两个只读接口、零写接口**。
- **库存对外字段**：只暴露 `remaining = sq`（剩余可中奖）与 `total = lottery_prize.total`（**投放总量、建品时定、恒不变**；⚠️ 票 06 回填票 05 给 `lottery_prize` 补此列——**不能拿 `sq + oq` 顶替**，实物领取会 `oq -= 1`，当分母会随领取漂移）；**不暴露 `lq` / `oq`**（`lq` = 「拨给 redis 多少」，暴露会让运营开一次桶前端「剩余」莫名抖动）。不限量档（POINTS / THANKS）给 `unlimited: true`、两字段 null。
- **排序**：中奖记录 / 我的奖品 / 积分流水 / 拉新名单一律 `created_at DESC`；活动信息与奖品目录不分页。
- **领取表单校验**：收件人 `@NotBlank @Size(max=30)`、手机号 `^1\d{10}$`、地址 `@NotBlank @Size(max=200)`（中文 message，走 `GlobalExceptionHandler` → `400`）。

**③ 失败语义 = 业务 code 分支 + `info` 中文文案**。成功码一律 `"200"`（前端 `unwrap` 已认）；业务失败用**各服务自带的 `-types/enums/ResponseCodeEnum` 里的 `4xxx` 业务码**（无全局注册表），`info` 则放**逐条固定的中文文案**（全文见 [票 06 §3](issues/06-marketing-api-contract.md)）。前端 `api/marketing.ts` **按 code 分支**（`NO_CHANCE` → 票 02 的「次数不足」专用弹窗、填码五码 → 对应文案），未识别 code 落回默认 toast `info`。**失败码**（代表集）：填码 `CODE_NOT_FOUND`/`ACTIVITY_NOT_STARTED`/`ACTIVITY_ENDED`/`OLD_USER`/`ALREADY_INVITED`/`CANNOT_INVITE_SELF`；抽奖 `ACTIVITY_NOT_STARTED`/`ACTIVITY_ENDED`/`NO_CHANCE`/`DAILY_LIMIT_REACHED`；兑换 `ACTIVITY_NOT_STARTED`/`ACTIVITY_ENDED`/`INSUFFICIENT_POINTS`/`PRIZE_NOT_REDEEMABLE`/`OUT_OF_STOCK`；领奖 `PRIZE_NOT_CLAIMABLE`；开桶 `PRIZE_NOT_FOUND`/`PARAM_ERROR`。
⇢ **落地注意**：`front/src/api/http.ts` 的 `unwrap` 现在对任何非 200 都 toast `info`，要加一个「调用方自己处理失败」的开关，否则业务 code 已被专用弹窗处理后默认 toast 还会再弹一次。

**④ 抽奖 POST 幂等**：`lottery_draw_record` 加 `client_token` + `uk_user_token(user_id, client_token)`（⚠️ 此列由票 06 回填票 05），前端生成 uuid 随 `draw` 请求带上，撞键**回放**已存结果（不重复抽、不重复扣次数）。这是本仓「幂等 + 重试」铁律在**唯一一个失败会让用户真亏一次抽奖**的写接口上的落地。

**⑤ 免登录与访客态**：白名单只 `GET /api/lottery/activity` + `GET /api/lottery/prizes`；`/marketing` 路由**不加 `requiresAuth`**（与 `/market` 一致）。访客看 banner + 奖品目录，个人区块显示「登录后参与」，点抽奖 / 填码 / 兑换跳 `/login?redirect=/marketing`。
⇢ **新发现：票 02 原型只画了登录态，访客态与兑换失败态都没有原型** ⇒ 落地票要自己补这两个态。

**⑥ 活动 `end_at` 之后**：抽奖 / 填码 / 兑换**一律拒**（`ACTIVITY_ENDED`，`enabled=0` 同理）；**领奖（claim）不受活动起止约束**——已中奖是既得权益，只由 `user_prize.status` 拦。活动页据 `status` 切成「已结束」，但奖品目录与我的奖品仍可查。

### 待定

- `backend/docs/` 架构说明页与三个 schema 的 db/api 文档 → 落地票（本 effort 不落代码）。**落地面**：`backend/docs/api/{points,lottery,referral}.md` + `backend/docs/db/{points,lottery,referral}.md`，并**新建**三个索引 README（`backend/docs/README.md`、`api/README.md`、`db/README.md` —— `backend/CLAUDE.md` §9 要求但仓库里当前没有）

---

## 市集入口行

来源：票 02 ①，用户 2026-09-14 选定 **变体 A（白卡图标格）**。

**位置**：市集页三 tab（跑腿 / 闲置 / 求购）之下、`filter-bar` 之上。搜索仍是 nav 右上**图标**（跳 `/search?scope=market`），不做真输入框。

**排布**：白底整条，四个等分格「我的订单 / 收藏 / 消息 / 活动」。每格 = 42px 圆角方图标块（各自浅色底 + 主题色图标，圆角 14px）+ 12px 灰字标签居中。**消息**格右上角带未读角标（红底白字，>99 显示 `99+`）。

**吸顶层级（本题的核心口径）**：三层依次压住 ——

| 层 | 高度 | sticky top |
| --- | --- | --- |
| `.market-nav`（三 tab） | 48px | `0` |
| 入口行 | **实测 86.4px** | `48px` |
| `filter-bar` | 42px | **`48px + 入口行实测高`**（原型实测 134px） |

**去向**：我的订单 → **市集聚合订单页**（数据口径**已由票 07 定稿**）；收藏 → 现有 `/me/favorites`（**不新建**，落地后保留该路由）；消息 → 市集消息页；活动 → `/marketing`。原型里「我的订单」暂指 `/me/product-orders`（**落地后改指聚合页**）。

**聚合订单页（票 07 定稿）**：前端**并发调三个既有接口、前端聚合**（不做后端聚合接口），各 tab 各自分页。三 tab = **跑腿 / 闲置 / 求购**（与市集页同序）。跑腿 ← `GET /api/errand/orders/mine`（保留「我发布的 / 我接的」双 sub-tab + 状态筛选）；闲置 ← `GET /api/trade/orders/mine`（保留「我买的 / 我卖的」双 sub-tab，无状态筛选）；求购 ← `GET /api/trade/want-to-buy/mine`（无 sub-tab，状态用行内角标）。**不另开原型票**：外层套本节的入口行三 tab 样式，列表行沿用旧页实现。

**旧入口收口（票 07 定稿）**：删 `/me/product-orders`（`MyProductOrderView.vue`）与 `/me/errands`（`MyErrandView.vue`）两个**订单类**旧页（吸收进聚合页）；`SearchView.vue:253`「查看更多我的跑腿 ›」改指聚合页跑腿 tab。**保留 `/me/favorites`（`MyFavoritesView.vue`）**——它是「收藏」格的去向。`ProfileView.vue` 的闲置 subtab 收敛为「我发布的」单栏、跑腿 subtab 收敛为「我发布的」单栏（我收藏的 / 我求购的 / 我购买的 / 我接的移出），让聚合页成为订单类唯一入口、`/me/favorites` 成为收藏唯一入口。

**落地注意（原型实测踩到的坑）**：入口行高度**不是**「图标 42 + 标签 12」那么简单 —— 标签受全局 `body { line-height: 1.7 }` 影响，12px 字实测行盒 **20.4px**，入口行因此是 **86.4px** 而非 73px。原型最初把 `filter-bar` 的 sticky top 写死 `121px`，比入口行底部（134.4px）少 13.4px；而入口行 z-index（99）高于 filter-bar（97），**入口行把 filter-bar 顶部压住了 13px**（code-review Spec 轴揪出，已修为 `ResizeObserver` 实测入口行 `offsetHeight` 后下发 `48 + 高`）。

⇒ **落地时 `filter-bar` 的 sticky top 必须由入口行高度导出，不能两处各写一个魔数**；改入口行尺寸（图标块大小 / 字号）后要重新核这一对值。

---

## 营销活动页与抽奖交互

来源：票 02 ②③，用户 2026-09-14 选定 **变体 A（叙事流：抽奖主卡居中）**。

**页面骨架**：灰底单列竖排白卡，区块自上而下 ——

1. **活动 banner**：渐变红整卡；活动名（21px 粗）+ 口号 + 底部左右两端「剩 N 天」「每日最多抽 N 次 · 今日已抽 N」。
2. **抽奖主卡（主角）**：居中的「我的抽奖次数」大数字（44px 主色）+ **整行**主按钮「抽一次」；下方一行小字「100 积分 = 1 次」+「用积分兑换次数 ›」。
3. **邀请码卡**：专属邀请码大字（20px、字距 4）+ 复制 / 分享两个次按钮；下方「已成功拉新 N 人」+ 头像并排（24px、间距 4）+「查看名单 ›」。
4. **填码卡**：输入框 + 提交；**只有这里能填码**。下方可展开「失败时会怎么提示」列出五种失败文案（码不存在 / 活动未开始 / 你已被邀请过 / 老用户不能参与 / 不能填自己的码）。
5. **积分卡**：余额大字 +「兑换奖品 ›」。
6. **奖品目录**：2 列网格；每格 = 方形奖品图（色块 + emoji）+ 名称 + 「剩 x/y」或「不限量」；**已兑完**档在图上右下角压黑底白字角标；可兑档显示「N 分可兑」。
7. **底部**：「我的奖品 / 中奖记录 ›」。

**抽奖交互（单抽）**：点「抽一次」→ **居中弹窗**，三种结果各自的呈现：

| 结果 | 弹窗内容 |
| --- | --- |
| 中奖 | 奖品色块（96px 圆角）+ 档位小字 + 奖品名 + 主按钮「开心收下」 |
| 谢谢参与 | 灰底色块 + 「谢谢参与」+ 副文案「再攒几次，好运在路上」+「知道了」 |
| 次数不足 | 灰底色块 + 「抽奖次数不足」+ 引导文案（邀请好友得次数 / 积分兑换）+ 主按钮「去邀请好友」+ 次按钮「用积分兑换次数」 |

**落地注意**：次数为 0 时抽奖主按钮的状态要收口（置灰 / 文案改「用积分兑换次数」），本 ticket 只定「点下去弹什么」；真实中奖结果由抽奖服务返回，原型里用调试控件切三种结果，落地必须删掉该控件。

---

## 我的奖品 / 中奖记录

来源：票 02 ⑤，用户 2026-09-14 选定 **变体 A（三段 tab + 底部上滑领取表单）**。

**结构**：顶部三段等分 tab「待领取 / 已领取 / 积分入账」；列表行 = 奖品图（56px 色块）+ 档位小字（主色）+ 名称 + 「中奖于 …」+ 右侧状态字与「去领取」按钮（**仅待领取**有按钮）。

**领取表单**：底部上滑面板 —— 面板标题 + 奖品摘要行 + 三个字段（收件人 / 手机号 11 位 / 收货地址 textarea）+ 提示「提交后奖品将进入待发放，实物不可退换」+ 提交 / 取消。

**落地注意**：三态与后端状态的映射（实物 `PENDING` / `RECEIVED`，积分 `CREDITED`）以票 05 的表为准；「积分入账」不回填收件信息。

---

## 市集消息页

来源：票 02 ④，用户 2026-09-14 选 A 后经四轮修订定为 **变体 D**，**用户回「就是这个效果」确认定稿**。

**外层**：三个 tab，**顺序与市集页一致**：跑腿 / 闲置 / 求购。

**每个 tab 顶部 —— 系统消息 = 一条会话行**（形态与私信行同构）：蓝圆铃铛（46px 圆）+「系统消息」+ 最新一条正文预览（省略）+ **右侧竖排「时间 / 未读计数」**。
**不是**折叠面板、**不显示「共 N 条」**。点进去是**一个聊天框**（全屏、带返回、日期分隔「近 7 天」），该域的系统消息就是框里的多条气泡（气泡 = 标题 + 正文，左对齐、白底、左上圆角小）。

**系统消息行之下，按域不同**：

| tab | 之下 |
| --- | --- |
| 跑腿 | `跑手消息` 分区，对家会话摊开（对家在跑腿单里恒为跑手） |
| 闲置 | `我买的` / `我卖的` **两个可切换分类**（chips，一次看一栏，各带未读角标）；对家=卖家 → 我买的，对家=买家 → 我卖的 |
| 求购 | `卖家消息` 分区，对家会话摊开 |

**对家会话行**：头像（44px）+ 角色小标签（跑手 / 买家 / 卖家）+ 昵称 + 最新消息 + 右侧时间与未读计数。空态走页面通用空态组件。

**数据口径已由票 07 定稿**（全文见 [票 07 Answer](issues/07-market-entry-hub-contract.md)）：

- **系统消息** = `scope ∈ {MARKET_ERRAND, MARKET_WANT}` 的 `notification`（即**发给本人的**交易动态，`ORDER_EVENT`）。**闲置 tab 无系统消息**（`PRODUCT` 侧没有发给用户的动态）。**不落 chat 的 `room`/`message` 表**——前端把该 `scope` 的 `notification` 行渲染成**伪会话气泡**（气泡 = 标题 + 正文）。
- **对家会话**：`room` 新增**会话来源**列，1v1 首次由「私信联系」入口建会话时写入（票 02 三候选选 **(a)**，与用户规则「从市集里私信走市集、从首页进去走底部」贴合）；`uk_direct_pair` 不变；**接受首来源钉死**（同一对人先聊闲置又聊求购，仍是同一条会话、同一标签）。跑腿多人单群（`ref_type=ERRAND`）天然可筛。
- **点一条** → 按 `refType` 跳既有 `/errand/:id` 或 `/want/:id`。
- **已读** → 水位线模型（见上文「通知与已读模型」）。

**⚠️ 票 01 §9 已同步反转**：票 01 §9 原写「市集消息页……**不含私信**」——本节的「对家会话」正是私信，票 01 §9 已留反转注记（来源标记 = `room` 会话来源列、首来源钉死已接受）。

---

## 底部「消息」页收口

来源：票 02 ⑥，用户 2026-09-14 选定 **变体 A（互动方块 + 系统消息 + 私信列表）**。

**结构**：**三段**（不是票面原写的两段）——

1. **互动**：两个等分方块入口「赞」（粉底）/「评论」（绿底），52px 圆角色块 + 24px 图标 + 14px 标签。
2. **系统消息**：蓝圆铃铛行 —— 「系统消息」+ 副文案「审核通知、平台公告、信用分变动」+ 右侧时间与未读点。**这是用户 2026-09-14 追加的硬要求**（「页面底部的消息里也要有系统消息，比如审核通知等等」）。
3. **私信列表**：头像 + 昵称（群带「群」标签）+ 最新消息 + 时间 + 未读计数；底部一行「共 N 条未读消息」。

**系统消息取数（票 07 定）**：`scope = BOTTOM_SYSTEM` 的全部 = 公告 / 审核结果 / 待审提醒（`AUDIT_PENDING`）/ 举报结果 / 信用分变动 + 营销三类 + 审核超时提醒。**取数从「非交互类型」改为「`scope = BOTTOM_SYSTEM`」**（`NotificationsView.vue:63-64` 现为 `!interaction.includes(n.type)`；不改会把市集交易类也收进来、与市集消息页重复）。`INTERACTION_TYPES` **不变**；`TYPE_TEXT` **补 `AUDIT_PENDING`**（现有 9 值漏了它，落到显示兜底「通知」）与营销三值。

**移除项**：「活动消息」横幅删除（`MessagesView.vue:75`，全仓**仅此 1 处**引用 `/notifications?tab=system`）；市集三类交易动态（闲置 / 跑腿 / 求购）移出，去市集消息页。

**分工（票 07 修正）**：**交易类**系统消息归**市集消息页** = `scope ∈ {MARKET_ERRAND, MARKET_WANT}`，即**实际有生产方的**「支付入审」（跑腿 / 求购）与「跑腿散团」；~~待支付 / 取消 / 审核未通过~~ **当前无生产方**（票 02 原型里那几项是示意，见 [票 07 §0.6](issues/07-market-entry-hub-contract.md)）。**非交易类**系统通知（审核通知 / 平台公告 / 信用分 / 举报结果）留**底部消息页**（`scope = BOTTOM_SYSTEM`）。⚠️ 原文「与票 01 §9 一致」已失效——§9 被票 07 反转。

**顶部 nav 不变**：沿用现状的「消息」标题 + 右上搜索图标（→ `/messages/contacts`）—— 本票只改三段式内容区，不动 nav。
