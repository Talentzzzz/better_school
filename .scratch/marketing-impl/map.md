# map：营销域（三个服务 + 市集入口改版）落地（实施）

## Destination

按 [marketing spec](../marketing/spec.md)（唯一需求来源，索引七节全部「已定稿」；设计阶段见 [marketing map](../marketing/map.md)）完成全部编码落地：

1. **营销域** —— 积分 / 抽奖 / 拉新三个独立 Spring Boot 服务（各自 project + schema + Nacos + Dubbo，跨服务走事件编排、兑换走同步 Dubbo）＋ 单体侧的通知读模型与三个事件消费者；
2. **市集入口行** —— 市集页三 tab 之下加「我的订单 / 收藏 / 消息 / 活动」入口行 + 聚合订单页 + 旧订单入口收口；
3. **消息迁移** —— 市集消息页（系统消息 + 对家会话）、底部消息页三段式收口。

## Notes

- **需求问题一律查 spec 对应章节**；本 effort 不再重开设计讨论。发现 spec 缺口回 `marketing` 补票。
- **拆票口径**：后端三个服务各成一票、抽奖「目录单抽 + 奖池库存」合为一票、前端按 spec 节 1:1（用户 2026-09-15 定，共 11 张）。
- **⚠️ 两处「必须冒烟验证」已由[票 01](issues/01-service-skeleton-and-wiring.md) 实跑通过**（SCA `2023.0.3.4` × Boot 3.3.5、Dubbo `3.3.6` × Boot 3.3.5；证据见该票「实现要点」）。后续票不必再验这个组合，但**依赖它们的结论**（如 `serialize-check-status: WARN`）已被证实有效。
- **营销是全新地形**：全仓 `积分` / `邀请` / `抽奖` / `奖品` / `营销` 零命中；`credit` 是信用分（不可消费）不可复用；`wallet` 的余额表形状已被桶化模型取代。
- **原型变体**：用户选定 ① ②③ ⑤ ⑥ 全 A、④ 经四轮修订定为 D。Vue 原型在 `prototype/marketing` 分支，取法 `git show prototype/marketing:front/src/components/prototype/<Variant>.vue`；截图在 `.scratch/marketing/proto-02/`。
- 领域词汇遵循仓库根 `CONTEXT.md`；后端分层与命名遵循 `backend/CLAUDE.md`。
- **前端无测试设施**（无 vitest/e2e），验收靠 `npm run build`（`vue-tsc`）+ 浏览器目视；后端集成测试单体放 `bs-app/src/test`、**营销服务放各自 `<svc>-app/src/test`**（独立 project 挤不进 `bs-app`；连 `scripts/test-env.sh` 起的常驻栈，不用 Testcontainers），见 `backend/docs/testing.md` §「营销服务的测试设施」（票 05 建立，06/07 照抄 `PointsApiTestBase` 的形状）。
- **本仓无先例的三件事**（票 07 最容易踩）：Redis 只有 `RBucket`/`RLock`/`RBloomFilter`，**无 INCR/DECR 先例**（桶的 `DECR`/`INCR` 是新形状）；全仓无 JSON 列类型（快照存 `TEXT`）；无雪花 id（`biz_id` 是自增 id，故 `lottery_deduct_detail` 的幂等键必须带 `biz_type`）。

## 依赖图（Blocked by）

```
01 骨架 ──┬─→ 05 积分（已 resolved）──┬─→ 08 兑换与领奖 ──────┐
          ├─→ 06 拉新（已 resolved）──┤                       ├─→ 11 营销前端
          └─────────────→ 07 抽奖 ───┴┬──────────────────────┘
                                     ├─→ 09 单体营销消费者 ──→ 10 底部消息收口
                                     └─→ 08
02 通知读模型 ──┬─→ 03 市集消息页 ──→ 04 入口行/聚合订单页/旧入口收口（已 resolved）
                └─→ 10 底部消息收口
```

- 01 → 05 / 06（三个服务都要骨架）——**05、06 均已 resolved**
- ~~01 / 06 → 07~~ **07 已 resolved**（`bs-referral-grouped` 有真生产者；抽奖全链路 + 奖池库存落地）
- ~~05 / 07 → 08~~ **08 已 resolved**（兑换要 Dubbo 打积分、实物领取要 07 建的 `lottery_user_prize`，两条都已满足）
- ~~05 / 06 / 07 → 09~~ **09 已 resolved**（2026-09-16：三个消费者 + `MarketingNoticeCase` 落通知落地）
- 02 → 03（市集消息页读 `scope` 过滤的通知）
- ~~02 / 03 → 04（入口行「消息」格指向 03 的页面）；04 的「活动」格指向 `/marketing`，**该路由由 11 建，本票只落链接，验收不含跳转**~~ **04 已 resolved**（后端类票 05~11 与本票无并发：04 只动 `front/`）。
- ~~02 / 09 → 10（三段式要 scope 取数 + 营销三类通知）~~ **10 已 resolved**（2026-09-16，见 Decisions）
- ~~05 / 06 / 07 / 08 → 11~~ **五个依赖票全 resolved，11 也已 resolved**（2026-09-16，见 Decisions）：活动页 7 区块调的三家、以及兑换 / 领奖四个写接口全部接通

## 文件并发提示（非阻塞边，但建议串行）

- ~~**03 / 04 都改 `MarketView.vue`**（入口行插在三 tab 与 `filter-bar` 之间）→ 04 在 03 之后，无并发。~~ 03 最终**没动 `MarketView.vue`**（只新建 `/market/messages` 路由与页面，入口行整条归 04），04 可径直动 `MarketView.vue`。
- ~~**02 / 09 都改 `NoticeService` 的调用点**（02 给现有生产点补 `scope`，09 加营销三类）→ 建议 02 先落，09 再动。~~ 02 已 resolved，09 随时可动（新增营销三类生产点照票 02 的 `NotificationScopeVO.BOTTOM_SYSTEM` 显式标注）。
- ~~**10 / 11 都改前端消息与通知相关件**（`NotificationsView.vue` 被 10 改按 scope 取数）~~ **10 已 resolved**：实际只动了 `MessagesView.vue`（系统消息行 + 「私信」分区头）与 `NotificationsView.vue` 的 `TYPE_TEXT`（scope 取数 02 已落，无需再改）——11 只新增 `/marketing`，不碰它们。
- ~~**06 / 07 都要动共享 SQL 文件**~~ **已解决且 07 已按此落地**：建库语句统一在
  `sql/init-marketing-{dev,test}.sql`（**只建三个库、不切库**），`scripts/test-env.sh` 的
  `apply_marketing_init <建库文件> <库名> <DDL文件>` 负责把 `USE` 插在中间——三份 `schema-<svc>.sql`
  都不含库名，开发库与测试库才共用一份 DDL。**DDL 各自 `schema-<svc>.sql`，不共用一份**。
  07 只往 `sql/schema-lottery.sql` 与 `sql/migrations/023_lottery.sql` 末尾**追加**了其余 10 张表与奖品种子
  （外加 `lottery_activity` 补 `slogan` 列的幂等 ALTER 守卫），**没动 init/脚本**。
  **留给票 08 的提示**：它若还要给 `bs_lottery` 加表/列，同样往这两个文件末尾追加。
- **04 已落地的前端触点（后续票参考）**：04 只动 `front/`，共 10 处（新建 `components/MarketEntryRow.vue`、`views/me/MyOrdersView.vue`；改 `MarketView`/`ProfileView`/`SearchView`/`MarketMessagesView`/`WantPublishView`/`router`/`uiState`；删两个旧订单页），**外加三个共享件**：`styles/common.scss`（`.tabs-equal`）、`utils/chat.ts`（`marketRoomSource`）、`utils/errand.ts`（`ERRAND_STATUS_TEXT`）。**11 会在 `router/index.ts` 加 `/marketing`**——该文件 04 也动过（加 `/me/orders`、删 `/me/errands`+`/me/product-orders`），区域不同不冲突。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## 实现要点 里 -->

- 2026-09-15 拆票定稿：11 张（后端服务各成一票、抽奖目录单抽与奖池库存合并、前端按 spec 节 1:1），落 `.scratch/marketing-impl/issues/`
- 2026-09-15 拆票期补的 spec 缺口（用户拍板）：**拉新直读 `bs_lottery.lottery_activity`** 取活动起止（`start_at` / `end_at` / `enabled`）—— spec 只定了「活动开始后注册」这条判据，没写拉新从哪拿 `start_at`，而活动配置在抽奖 schema。**⚠️ 对「跨 schema 只存 id、不 join」硬约定的有意偏离**（限定：只读这一单行配置、不 join 业务表、不写；`bs_user.created_at` 仍走 Dubbo）。细节在 [票 06](issues/06-referral-service.md)
- 2026-09-15 [票 01](issues/01-service-skeleton-and-wiring.md) resolved：三服务骨架与本地链路贯通（三个独立 project + 单体 Dubbo Provider + Nacos 单 dataId）。**下游 05 / 06 已解锁**。落地中补的三处票面没写的硬要求（`dubbo.scan.base-packages` 不配则 `@DubboService` 不被扫描、`nacos-config` starter 必须上四个 app 的 classpath、Dubbo 拿注册中心当 config-center 导致注册中心连不上会炸启动）+ 两条冒烟验证证据 + code-review 采纳项（生成残留 `IPointsExchangeService`、域类型外溢到 HTTP 契约、硬编码 401/403、死代码 `generateToken`）全部在票面 `## 实现要点` 里
- 2026-09-15 [票 02](issues/02-monolith-notification-read-model.md) resolved：单体通知读模型换「scope + 水位线」（迁移 026 + `notification_seq`/`notification_read` 两表 + `is_read` 退休 + 15 处生产点显式标 scope + `read`/`unread-count` 端点取代 `read-all`；存量回填对「不推导」约定的一次性豁免在票面）。**下游 03 / 10 已解锁**；`CONTEXT.md` 补「通知收件范围 / 通知已读水位线」词条
- 2026-09-15 [票 03](issues/03-market-messages-page.md) resolved：市集消息页落地（迁移 027 给 `room` 加 `source` + `initiator_id`，建会话入口写来源、首来源钉死；会话列表出 `source`/`peerRole`/`refType`，角色按请求者现算；新页 `MarketMessagesView` + 路由 `/market/messages`，三处市集入口带 `source`）。**下游 04 已解锁**：入口行整条归 04（03 未动 `MarketView.vue`，可径直改）。**落地前定的 spec 缺口**：会话来源列取值口径由用户拍板为「域 + 发起人 id」两列（票面只写了域，推不出请求者视角的角色）。**共享口径**：`peerRole` 按「来源 + 本人是不是首建发起人」现算（跑腿恒 `TAKER`、闲置/求购发起人视角 `SELLER`/`BUYER`）——票 04 的「消息」格未读角标不必重算角色；`CONTEXT.md` 补「会话来源 / 对家角色」词条
- 2026-09-15 [票 04](issues/04-market-entry-row-and-order-hub.md) resolved：市集入口行（四格）+ 聚合订单页 `/me/orders` + 旧订单一入口收口。**共享件（后续票复用，别再各写一份）**：① `.tabs-equal`（`styles/common.scss`）= 二级页等分 tab 条（sticky 48/z99），聚合页与市集消息页共用；② `utils/chat.ts: marketRoomSource()` = 「这个会话属哪个市集域」的唯一判据（`source` 优先、跑腿多人单群按 `refType` 归跑腿），入口行角标与市集消息页分栏**必须**共用它；③ `utils/errand.ts: ERRAND_STATUS_TEXT` = 跑腿状态文案唯一一份（此前列表/「我的」/详情三份且已漂移）。**落地裁决**：角标范围＝市集消息页三类未读之和（`MARKET_ERRAND`+`MARKET_WANT`+市集对家会话，实测 9）；「外层三 tab 样式」取票 03 同解（等分+主色，不搬市集 `.nav-tab` 的 17px 下划线）；「并发调三接口」＝`onMounted` 三个 `load()` 并发（无后端聚合接口），次级子栏懒加载。**已知波及**：`活动` 格指 `/marketing`（票 11 建）现在点会落 `/home`——票 11 落地即生效，不算本票缺陷
- 2026-09-15 [票 06](issues/06-referral-service.md) resolved：拉新全链路（懒生成邀请码 + 填码成团六条校验 + 已拉新名单 + 成团事件）。
  **落地时定的三件事**：① 拉新要直读的 `bs_lottery.lottery_activity` 由本票**提前落**在
  `sql/schema-lottery.sql` + `sql/migrations/023_lottery.sql`（只有这一张表 + 种子）；
  **⚠️ 票 07 往这两个文件末尾追加其余表、别重建**。② 跨库读的表名走配置 `referral.activity-config-table`
  （测试 profile 覆盖成 `bs_lottery_test.*`，写死会读到开发库的活动窗口 ⇒ 三个活动分支假绿）。
  ③ 共享设施改成终态：`init-marketing-*.sql` 只建三库、`apply_marketing_init` 三参并在中间插 `USE`
  （**07 不必再动这两处**）。**验收**：`referral-app` 23 个集成测试全绿 + `test-env.sh up/reset-test-db` 实跑过。
  **同步边的 seam** = 测试源码 `@Primary StubUserQueryPort` 覆盖真 Dubbo Consumer（**07 可复用这个形状**：
  抽奖要调单体 Provider 之外的 Dubbo 边时，桩放各自 `-app/src/test` 的 `support` 包）。
  **已知波及**：成团事件真投递 / Dubbo 真跨进程调用都归联调与票 09；前端调用归票 11。
- 2026-09-15 [票 05](issues/05-points-service.md) resolved：积分服务全链路（桶化模型 + 两个只读 HTTP + 兑换 Dubbo 边 + 中奖消费 + 变动事件）。**落地纠正一处上游数据模型**：`points_consume` 的唯一键必须是 `(lot_id, reason_type, ref_id)` 而不是设计票写的 `(user_id, reason_type, ref_id)`——FIFO 跨桶消费会为同一笔兑换落多行，按用户维度的 uk 会把第 2 行顶掉⇒少扣；**⚠️ 票 07 的 `lottery_draw_count_consume` 是同构表，必须照改**（上游票 `.scratch/marketing/issues/05` §2①⑧ 两处已留 ⚠️ 注记）。**共享设施（票 06/07 复用，别再各写一份）**：① `sql/schema-points.sql` + `init-marketing-dev.sql`/`init-marketing-test.sql` 三件套建库口径（票 07 加 lottery/referral 时**追加**，别另起布局）；② `scripts/test-env.sh` 起栈/`reset-test-db` 已带营销库，新服务照 `apply_marketing_init` 加一行；③ 服务测试设施照 `points/points-app/src/test/support/PointsApiTestBase`（Nacos 共享配置 + `System.setProperty` 注地址 + 直接签发 JWT + 消费者/Provider 当普通 Bean 调）；④ outbox 三件套逐字照抄单体、只换包名；⑤ 统一 `-api` 里放 `IXxxService`（HTTP 契约）+ `IXxxExchangeService`（Dubbo 契约），Controller 实现前者（`backend/CLAUDE.md` §5）。**验收之外的可观察口径**：测试栈无 broker ⇒「事件发出」= outbox 落行 + 载荷正确

- 2026-09-15 [票 07](issues/07-lottery-service.md) resolved：抽奖服务全链路（`bs_lottery` 10 张表 + 奖品种子、
  活动 / 奖品两个免登录读接口、单抽主链路含幂等回放、redis 单桶 + 开桶 + 合并提交 + DB 直扣兜底、成团加次数）。
  **动手前定的两件事（用户拍板）**：① `lottery_activity` 补 `slogan` 列（票 06 接口清单要返回它，但那张表没有；
  MySQL 无 `ADD COLUMN IF NOT EXISTS` ⇒ 两个 DDL 文件末尾追加 `information_schema` 判断的幂等 `ALTER`，
  **不动票 06 那一块**，已实跑两遍验证）；② 种子只追加到 `schema-lottery.sql` + `023_lottery.sql`、
  **不建 `025_marketing_seed.sql`**（照 map 的口径）。**验收**：`lottery-app` **30 个集成测试全绿**
  （目录 5 / 单抽 14 / 奖金池与合并 6 / 降级直扣 5）+ referral 23 个回归全绿（它跨库直读那张加了列的表）。
  **code-review 采纳 5 条**（port 实现类改名合规、两份滚档循环合并成 `choosePrize`、发事件收实体不列 5 个参数、
  删测试里没人读的参数、开桶 `PARAM_ERROR` 的 info 改回逐条固定文案）。
  **未采纳并留了理由**：抽奖事务没用 Aggregate（05/06 同形，且会把每日上限规则推进 infrastructure）、
  `lottery_prize.version` 没建（没有代码读它）、时间列统一 `created_at`（设计票自己不一致）。
  **已知波及**：票 08 的表已建好、接口未建；redis 真停服降级、job 的 cron 配置、`bs-referral-grouped` 真投递
  都归联调 / 票 09；前端调用归票 11。

- 2026-09-15 [票 08](issues/08-lottery-exchange-and-claim.md) resolved：兑换两条边（兑次数 / 兑奖品，同步 Dubbo 打积分扣分）
  与「我的奖品三段 tab + 实物领取」。**一列 SQL 没加**——票 07 已把 `lottery_redeem_order` / `lottery_user_prize` /
  `redeem_points` 建好，本票只把它们用起来（测试库不必重建）。**动手时定的两件事**：① **「重试」在 HTTP 侧
  的落点**——票 06 把请求体形状定死了（兑次数体空 / 兑奖品只有 `prizeId`），客户端上送不了单号，所以
  `findPending(userId, kind, prizeId)` 让服务端把「同一意图」认出来接着做完，**不新开一单**（新开 = 给积分侧换个
  refId 再扣一次）；这条同时把域服务的 `resume()` 从死代码变成真入口。② **领取不拿 `oq` 当闸门**——池内扣减的
  `oq` 靠合并提交才加上去，「刚中奖就领」的秒级窗口里是 0，拿它当闸门等于「xxl-job 没配好 ⇒ 实物永远领不了」；
  取舍是放行 + `log.warn`，极窄窗口里 `oq` 多记 1（该列不对前端暴露）。**兑奖品的库存复用抽奖那两条通路**
  （redis 池优先 + DB 直扣兜底），因为「非池只作 redis 故障储备」已由票 07 写进口径。**code-review 采纳 5 条**
  （resume 无入口、预检放过「池空但储备在」会扣了分拿不到奖品、`source` 字段越出契约、`requireOngoing` 两份
  收进 `ActivityConfigVO`、库存判据收进 `PrizeEntity`）。**验收**：`lottery-app` **50 个用例全绿**
  （票 07 的 30 个无回归 + 本票 20 个）。**已知波及**：合并提交 job 的 cron 仍是部署侧的事（领取那条容忍就是
  为它兜的）；**并发双击兑换**会各开一单各扣一次（同一瞬间的两个请求，没有客户端 token 分不开）——顺序重试已覆盖；
  前端（活动页兑换按钮 / 我的奖品三 tab / 领取表单）归票 11。**票 11 现在四个后端依赖全齐（05 / 06 / 07 / 08）**。

- 2026-09-16 [票 09](issues/09-monolith-marketing-consumers.md) resolved：单体作第四个消费者订阅三个营销 topic 落通知
  （case 层 `MarketingNoticeCase` 复用 `sendSystem`，`scope = BOTTOM_SYSTEM` / `ref_type = MARKETING` / 营销三枚举，
  **不做** INotificationPort、不反向调用营销服务）。**dedup_key 两处形状偏离票 04 已回填**：GROUPED 追加收件人
  （uk_dedup 单列全局唯一，双收件人各一键）、POINTS 用 `<reasonType>:<refId>`（事件体无 txId，防跨表撞键）——
  `docs/db/chat.md` 已同步。**「中奖奖品名」是 spec 内在矛盾**（事件体无奖品名 + 不反查生产方）⇒ 文案按
  prizeType 分档。**验收**：6 集成用例 + 全量回归 341/341 绿；跨服务真 broker 端到端归联调。
  **票 10 已解锁**（依赖 02 + 09 全齐）；前端 `TYPE_TEXT` 补营销三值随票 10。

- 2026-09-16 [票 11](issues/11-marketing-frontend.md) resolved：营销前端落地（`/marketing` 七区块活动页 + 单抽三结果弹窗 +
  我的奖品三段 tab 与底部上滑领取表单，全部接三服务真实接口；`/marketing` 不加 requiresAuth，访客看 banner + 目录、
  个人区块「登录后参与」）。**共享件**：`http.ts` 的 `unwrap` 加 `silent` 开关（第三参改 `AxiosRequestConfig & { silent }`
  交叉类型，旧调用兼容）；`utils/format.ts` 加 `formatDateTime`。**三个裁量**：①「中奖记录」＝我的奖品页（三段 tab
  同源 my-prizes，`/api/lottery/records` 无前端消费面，留审计）；②「查看名单」＝卡片内联展开前 20 条；③兑换入口＝点
  可兑档色块 + ConfirmDialog，兑换失败态补成灰底居中弹窗（文案 `EXCHANGE_FAIL_TEXT` 按 code 取）。
  **code-review 采纳 4 条**（最重：填码框 6→**8** 位，后端 `INVITE_CODE_LENGTH = 8`，原型 mock 是错的）。
  **验收**：build（vue-tsc）过 + Playwright 拦 `/api` 双视口目视 16 张截图存 `verify-11/`（访客/三弹窗/兑换失败/
  填码/名单/ENDED/三 tab/领取表单/桌面）。**已知波及**：入口行「活动」格自此生效；抽奖动效/音效未做（见 Not yet
  specified）；真跨服务联调归联调。**至此 11 张票全部 resolved，本 effort 落地完成。**

- 2026-09-16 [票 10](issues/10-bottom-messages-page.md) resolved：底部消息页三段式收口——「活动消息」横幅换**系统消息行**
  （蓝圆铃铛照原型 `BottomMsgA.vue` + 副文案固定 + 最新一条 BOTTOM_SYSTEM 时间 + 未读点，**未读源从误绑的
  `chatRooms.unreadTotal` 改为 `unread-count?scope=BOTTOM_SYSTEM`**）+「私信」分区头 + `TYPE_TEXT` 补
  `AUDIT_PENDING` 与营销三值。**去向着色**：`/notifications?tab=system` 保留为系统消息行跳转目标（票面那句
  「仅 1 处引用」是删除安全论证，system tab 是唯一 BOTTOM_SYSTEM 列表）。**实际改动仅两文件**——scope 取数 /
  推水位 / 市集动态移出均由 02 已落，营销三类由 09 落地。**code-review 采纳**：空 catch 补 WHY 注释；
  **留给后续**：CONTEXT.md「系统消息」词条与 notification 域用词冲突（词条滞后，MarketMessagesView 已有先例，
  归 `/domain-modeling`）、`NotificationType` 联合类型落后实际 13 值（既有债）。**已知波及**：系统行时间/未读点
  仅挂载时取一次（无 keep-alive，返回即重取）。~~**剩余未落票：11（营销前端）**~~ **11 已 resolved（同日）**。

## Not yet specified

- ~~**入口行「消息」格的未读角标统计哪个范围**~~ **已定（票 04）**：市集消息页三类未读之和 = `MARKET_ERRAND` + `MARKET_WANT` + 市集消息页收录的对家会话未读（含跑腿多人单群；判据与页面分栏同源 `marketRoomSource()`）。细节在票面「落地裁决」。
- ~~**三个服务的健康检查/探活端点是否长期保留**：票 01 会落一条仅用于骨架验收的探活接口，是否保留为各服务健康检查留 01 收口时定。~~ **已定（票 01）：保留**——`GET /api/<svc>/ping` 是「Nacos 配置 → JWT 验签 → Dubbo 同步边」整条链路的端到端证人，后续票改契约后拿它自检比 `/actuator/health` 更能说明问题。
- ~~**抽奖按钮动效与音效**~~ **已定（票 11 落地时收口）**：不做——结果为居中弹窗直出，票面只定「点下去弹什么」；动效/音效仍是 Out of scope 方向的加项，需要时另开票。
- 其余见 [marketing map](../marketing/map.md) 的 Not yet specified（运营看板、服务治理与可观测性、营销库物理隔离）。

## Out of scope

（同 [marketing map](../marketing/map.md) Out of scope：优惠券、多活动/活动状态机、抽奖保底与十连抽、后台配置页、设备指纹风控、通用收货地址簿与物流状态机、redis 重建看门狗/关桶/多桶分摊/抽奖取消回补、Spring Cloud Gateway 网关、其它积分获取途径、底部消息页的营销活动入口。）
