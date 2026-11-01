# map：营销域（拼团拉新 / 抽奖 / 积分）与市集入口改版

Label: wayfinder:map

## Destination

`.scratch/marketing/spec.md` 定稿，三节：① **营销域**——拼团拉新（邀请码成团）、抽奖、积分、奖池库存（redis 单桶 + DB 直扣兜底），落成**三个独立的 Spring Boot 服务**（积分 / 抽奖 / 拉新，各自独立 project + 独立 schema + Dubbo + Nacos，跨服务走**事件编排**）；② **市集新增入口行**——跑腿/闲置/求购三 tab 之下加一行「我的订单 / 收藏 / 消息 / 活动」（参考仓库根目录 `市集.png`）；③ **消息迁移**——把市集相关消息从底部「消息」迁入市集内的「消息」。三节的表结构、接口口径、事件契约、视觉选型全部落定，拆票即可开发。

## Notes

- 领域：better_school 校园平台（校园墙 + 二手 + 跑腿）。本 effort = **新增营销域（三个新服务）+ 市集入口改版**；**只产出决策与 spec，不落地代码**（落地票另起 `.scratch/marketing-impl/`，沿用 `chat-impl` / `ui-revamp-impl` 先例）
- 用到的 skill：视觉票走 `prototype`；口径票走 `grilling` + `domain-modeling`；技术栈票走 `research`
- 参考图（仓库根目录）：`市集.png`（入口行效果）；库存模型蓝本 `实现方案3.md`（redis 单桶库存扣减）
- **营销是全新地形**：全仓 `积分` / `邀请` / `抽奖` / `奖品` / `营销` 均**零命中**。`credit` 是信用分（规则引擎分值、**不可消费**），`wallet` 才是可消费余额——积分服务**不复用 credit 域**；~~照 wallet 的指纹（余额表 + 流水表）建~~ **⚠️ 票 05 反转**：改为**桶化（lot）模型**（入账一行带自己的 `expire_at`、FIFO 消费、余额 = 未过期桶之和，无余额列）
- **云原生是零底座**：无任何 Spring Cloud / Dubbo / Nacos 依赖，`backend/docker-compose.yml` 无 Nacos 容器；但 `bs-api` 每个接口的 javadoc 都写着「后续微服务化时由 Dubbo Provider 实现」——缝是预留的 → **栈选型已由票 03 收敛（[research-cloud-stack.md](research-cloud-stack.md)）：SCA 2023.0.3.4 + Dubbo 3.3.6 + Nacos server 2.x on Boot 3.3.5，其中两条组合仍待冒烟验证**
- 目录结构（用户 2026-09-14 拍板「案 A + 独立 project」）：单体**原地不动**（`backend/pom.xml` 就是它的聚合器），三个营销服务是**平级的独立 project**，根目录都在 `backend/` 下（`backend/points/`、`backend/lottery/`、`backend/referral/`），各自一个聚合器 pom + 各自 7 个模块（`<svc>-types/api/domain/case/infrastructure/trigger/app`）。共享设施（`sql/`、`docs/`、`docker-compose.yml`）留在 `backend/` 这一层
- 事实底座：
  - **市集现状**：`front/src/views/MarketView.vue` = 三 tab（跑腿/闲置/求购，绑 `stores/uiState.ts` 的 `marketTab`）+ 搜索**图标**（跳 `/search?scope=market`）+ `filter-bar` + 瀑布流；**中间没有任何入口行**（草图的「我的订单/收藏/消息/活动」行不存在）
  - **底部消息现状**：`front/src/views/MessagesView.vue` = `.entry-row`（赞 / 评论两入口）+ `.notice-row`（「活动消息」横幅，跳 `/notifications?tab=system`，副标题写死）+ 私信会话列表；系统 tab 由 `front/src/views/msg/NotificationsView.vue` 的 `TYPE_TEXT` / `INTERACTION_TYPES` 推导
  - **通知枚举**：`NotificationTypeVO` 10 值（ANNOUNCEMENT / AUDIT_RESULT / AUDIT_PENDING / REPORT_RESULT / ORDER_EVENT / CREDIT_CHANGE / POST_COMMENTED / COMMENT_REPLIED / POST_LIKED / COMMENT_LIKED），**无营销类**；`notification` 表有 `ref_type` / `ref_id` / `dedup_key`
  - **`ORDER_EVENT` 的全部生产者**（票 07 核实）：跑腿已支付入审（**发发布者**）、跑腿散团（**发在团接单者**）、求购已支付入审（**发作者**）、待审超 24h 提醒（**发 ADMIN/AUDITOR**，覆盖 PRODUCT / WANT_TO_BUY / ERRAND / POST 四类）；**商品订单生命周期（支付/交付/确认）当前不发任何通知** ⇒ **`PRODUCT` 侧没有任何发给买/卖家的交易动态**；且同一 `refType=ERRAND` 既有发**给用户**的动态、也有发**给审核员**的提醒 ⇒ 归属**不能按 `refType` 推导**，必须由生产点显式标 `scope`（票 07 §0.5/§4）
  - `bs_user` 有 `created_at` / `last_login_at` 可用；**全库无任何 address / 收货相关表**（商品订单没有地址环节）
  - Redis 走 Redisson 自动装配（无 `RedisTemplate`），既有 key 前缀 `bs:verify-code:` / `bs:chat:ws-ticket:` / `bs:post:detail:*`，**无 INCR 先例**（只有 RBucket / RLock / RBloomFilter）
  - xxl-job 现有 9 个 handler；批量扫描 + 合并落账的既有形状是 `OutboxMessageSender`（批 100 + 专用标记线程）
  - **跨服务事件的现成形状**：业务事务内写 outbox（`outbox_message`，`msg_id` 唯一）→ afterCommit 异步发 MQ → `bs-trigger/listener/` 的消费者（`bs-<topic>-group` 命名）。先例 `bs-errand-paid`（生产方 `ErrandOrderServiceImpl` → 消费方 `ErrandPaidConsumer` → case 层发通知）
- 用户偏好：票据**粗粒度**；视觉票出 **3 个变体**由用户自己翻页选；选型结论回填 spec
- 本会话（2026-09-14）grilling 敲定的全部口径 → [票 01](issues/01-baseline-decisions.md) 的 Answer 是唯一全文

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## Answer 里 -->

- [票 01 · 口径基线（charting 阶段 grill 定）](issues/01-baseline-decisions.md): **架构**＝三个独立微服务（积分/抽奖/拉新，各自 project + schema + Nacos + Dubbo，**跨服务走事件编排**、兑换走同步 Dubbo）＋ 单体原地不动（案 A）＋ 每个服务自带 types/api ＋ Dubbo 引用藏在域内 port 之后 ＋ 前端**直连**各服务（不做网关，Vite 代理三个前缀）；**拉新**＝团恒 2 人、每人专属码可被多人用、被邀请人须**活动开始后注册** + **一生只能被邀请一次**、填码提交即成团；**三域**零注入、集成靠事件而非注入；**抽奖**＝单活动单抽、权重法、该档库存扣光则跳过重新归一、实物「待领取」填收件信息、积分档不占库存；**库存**＝去 `wq` 只留 `sq/lq/oq`、单桶、中奖即占用、**DB 直扣兜底要做**、关桶/多桶/重建不做；**市集**＝三 tab 下加四入口行（订单聚合页 / 收藏跳现有 / 市集消息页 / `/marketing`）、搜索保留图标；**消息**＝市集页收 `scope∈{MARKET_ERRAND,MARKET_WANT}`（**⚠️ 票 07 修正**原 `ORDER_EVENT∩refType∈{PRODUCT,ERRAND,WANT_TO_BUY}`——排除 `PRODUCT`）、底部页移除市集动态并删「活动消息」横幅；**运营**＝不做后台配置页（SQL 种子）、风控三条硬约束
- [票 02 · 原型（市集入口行与营销活动页，三变体）](issues/02-prototype-market-entry-hub.md): 六页全部定稿，**① ②③ ⑤ ⑥ 全选 A，④ 选 A 后经四轮修订定为 D**。①入口行＝白卡四等分图标格 + 三层吸顶（nav 48 / 入口 48↓ / filter-bar 121↓）；②③活动页＝叙事流、抽奖主卡居中、结果**居中弹窗**；④市集消息页＝外层三 tab 同市集页序 + **系统消息做成一条会话行**（点进去是聊天框，未读数在行右）+ 对家会话（跑腿→跑手 / 求购→卖家 / 闲置→**我买的·我卖的**可切换分类）；⑤我的奖品＝三段 tab + 底部上拉领取表单；⑥底部消息＝**三段**（互动 + 系统消息 + 私信）。口径逐节写进 `spec.md`。**票面留两条转下游**：市集消息页的数据口径归票 07（`room.ref_type` 对 1v1 为 NULL、`uk_direct_pair` 一对话一会话、系统消息要不要以「会话+消息流」形态落），且**与票 01 §9「市集消息页不含私信」直接冲突，需两处同步改**
- [票 03 · 微服务栈选型调研](issues/03-cloud-stack-research.md)：Boot 3.3.5 固定下的可行栈收敛为 Spring Cloud `2023.0.3` ＋ SCA `2023.0.3.4`（nacos-client `2.4.3`）＋ Dubbo `3.3.6` ＋ Nacos server 2.x；SCA 2025.x 要 Boot 3.5/4.0、SCA 无 2024.0.x 线、Dubbo ≤3.2 不可用；配置中心走 `spring.config.import: nacos:`（bootstrap 与 shared-configs 均废弃），`jwt.secret` 收进 Nacos 共享 dataId、**单体是否随之迁配置中心留票 04**；Dubbo 版本自管（SCA BOM 不含 dubbo）、协议配 `tri`、`serialize-check-status` 默认 STRICT 是首次调用的炸点；消费方引 `bs-api` 成立。**03 新发现**：既有 `bs-<topic>-group` 消费组命名在多服务订阅同一 topic 时会抢消息，改 `bs-<service>-<topic>-group`。**两处组合未获一手背书、铺开前必须冒烟验证**（SCA / Dubbo × Boot 3.3.5）。全文 [research-cloud-stack.md](research-cloud-stack.md)。**票 04 已解锁**（其 `Blocked by` 保留为历史记录）
- [票 04 · 三个服务的工程骨架与跨服务链路](issues/04-service-skeleton-and-wiring.md)：两轮 grill + code-review 补一轮，全采纳。**骨架**＝`backend/points|lottery|referral/` 三个平级独立 project（parent 直接 Boot 3.3.5、与单体无 reactor、各 7 模块、各自自带 `-types` 与 `-api/response`）；**Nacos**＝只 1 个共享 dataId（`jwt.secret` 一处供给四个 app、**单体也接**；仍采纳 SCA，记其低维护风险）；**Dubbo**＝`IUserQueryService`（`bs-api`，含 `createdAt`；不复用 `getProfile`）+ 兑换边 `IPointsExchangeService`（`points-api`，**反转票 01 §4：「零编译期依赖」取消**，lottery 直接依赖 points-api），Provider 统一落 `-trigger/rpc/XxxDubboService` + `@DubboReference(check=false)`，不要 `INotificationPort`；**事件边**＝三个 topic 只带「id + 决策必需值」、消费组 `bs-<service>-<topic>-group`、各服务自带 outbox、幂等键落各自业务表唯一键（列组合以票 05 为准）；**通知走事件订阅**（单体当第四个消费者，type/refType 留票 07 ③）；**端口** 8081/8082/8083（dashboard 8082→8089）+ Vite 三前缀排在 `/api` 之前、前端单实例。结论进 spec「营销域」节（票 03 的两处冒烟验证原样带过来）。细节在该票 `## Answer`。
- [票 05 · 营销域数据模型](issues/05-marketing-data-model.md)：三 schema `bs_points`/`bs_lottery`/`bs_referral`；**积分与抽奖次数共用桶化（lot）模型**（入账一行带自己的 `expire_at`、FIFO 消费、余额 = 未过期桶之和——**反转**了票面的「余额列 + 条件更新」）；奖品表与库存表**合并**成 `lottery_prize`、「谢谢参与」落 `type=THANKS` 行、每次抽奖都落记录；开桶走**手工接口**、拨 80% 留 20% 非池喂 DB 兜底；合并 job 每秒 + 扫描不限量。**三处上游反转/修正**：① 用户「**成团只加抽奖次数**」⇒ 删积分订阅 `bs-referral-grouped`；② 次数「永不过期」→ 30 天过期；③ `deduct_detail` 幂等键补 `biz_type`（票 01 §7 原写法两个 id 空间会撞 ⇒ 少扣库存）。迁移 `022–025` + `init-marketing.sql`（开桶不在种子里）。**票 06 已解锁**。细节在该票 `## Answer`。
- [票 06 · 营销域接口口径](issues/06-marketing-api-contract.md)：一轮 grill（Q1–Q10）全采纳。**活动页聚合读 = 前端并发调三家**（7 区块逐块归属干净，不做 BFF）；**失败语义 = 各服务自带 `4xxx` 业务码 + `info` 中文文案、前端按 code 分支**；**抽奖 POST 加客户端 token 幂等**；**兑换两个写接口与定价挂抽奖 ⇒ 积分服务对外零写接口**；白名单只 `GET /api/lottery/activity` + `/prizes`、`/marketing` 不要求登录；库存对外只暴露 `remaining=sq` / `total=lottery_prize.total`（**不拿 `sq+oq` 当分母**——领取会 `oq-=1`；不暴露 `lq`）；**活动 `end_at` 后抽奖/填码/兑换拒、领奖不受限**；开桶 `POST /api/lottery/buckets/open`（ADMIN）。**四处修正票面前提**：① docs 三个索引 README 当前不存在（是「新建」不是「更新」）；② 兑换不在积分服务下；③ 票面列的「奖品已抽完」在「扣光即跳过重新归一」规则下不可达；④ `unwrap` 只有一支错误分支，票 02 两处专用失败交互需要业务码。**回填票 05 两处**：`lottery_draw_record` 加 `client_token` + `uk_user_token`（抽奖幂等）、`lottery_prize` 加 `total`（目录「剩 x/y」的恒定分母）。细节在该票 `## Answer`。
- [票 07 · 市集入口行落地口径（聚合订单页 + 市集消息页 + 底部消息收口）](issues/07-market-entry-hub-contract.md)：一轮 grill（Q1–Q7 全采纳）+ 补问 Q8（已读选 **B 真水位**）。**聚合订单页**＝前端并发调三个既有接口、三 tab 跑腿/闲置/求购、**不另开原型票**；**市集消息页**＝复用 notifications 加 `scope` 过滤、系统消息收「发给本人」的交易动态（**排除 `PRODUCT`**）、对家会话按 `room` 新增**会话来源列**筛；**底部消息**＝删横幅 + 三段式 + system 取数改按 `scope = BOTTOM_SYSTEM`（`TYPE_TEXT` 补 `AUDIT_PENDING`）；**营销通知**＝新增三枚举 + `refType = MARKETING` + `scope = BOTTOM_SYSTEM`（进底部、不进市集）；**已读＝水位线**（`notification` 加 `scope`/`seq` + 计数器 + 水位表，迁移 `026`）；**旧入口**＝删 `/me/product-orders`+`/me/errands`、**保留 `/me/favorites`**、`ProfileView` 两 subtab 收敛。**反转票 01 §9 两处**（排除 `PRODUCT`、「不含私信」→含对家会话）+ 修正 §8 顺序。细节在该票 `## Answer`。

**2026-09-14：7 票全部 resolved、frontier 清空**——本 effort 的设计阶段收口，`spec.md` 各节已定稿（含票 07 追加的「通知与已读模型」）；落地另起 `.scratch/marketing-impl/`。

## Not yet specified

<!-- 见「Fog of war」：还在雾里、尚不能成票的 in-scope 问题；frontier 推进后毕业 -->

- ~~**积分是否会过期**~~（**票 05 已定**：会过期，桶化独立过期，`points_valid_days` 种子 365、次数 30 天——与票 01 原写「次数永不过期」相反）
- ~~**活动收尾口径**~~（**票 06 已定**：`end_at` 后**抽奖 / 填码 / 兑换一律拒**（`ACTIVITY_ENDED`）；**领奖（claim）不受活动起止约束**——已中奖是既得权益，只由 `user_prize.status` 拦；活动页据 `status` 切成「已结束」但奖品目录与我的奖品仍可查。次数与积分的有效期已由票 05 定为「按各自 `expire_at` 独立过期、与 `end_at` 无关」）
- **抽奖按钮动效与音效**：中奖结果的**呈现方式已由票 02 定为居中弹窗**；按钮动效、要不要音效仍未定（不影响接口与表结构）
- **营销数据是否要运营看板**：拉新人数 / 中奖分布 / 兑换量统计页；本期未定，可能落成 spec 的「不做」，也可能另立 effort
- ~~**积分与「我的」页的关系**~~（**票 07 收口时定**：**不新增**「我的」页的积分 / 我的奖品入口 —— 只挂活动页 `/marketing`（票 02 的区块 5 积分卡 + 区块 7「我的奖品 / 中奖记录 ›」），与票 07 收敛「我的」页 subtab、票 01 §8「避免同一种数据多个入口」方向一致。⚠️ 此条票 07 的 `## Question` 未直接问、由收口方向推定，若不同意可推翻）
- **服务治理与可观测性是否纳入**：链路追踪（SkyWalking / Micrometer Tracing）、Sentinel 限流熔断降级——三个服务拆出来之后这两个是微服务展示面的自然companion，但会显著加重本期工程量；本期未定
- **三个服务共用一个 MySQL 实例的隔离边界**：现在方案是「同实例独立 schema」，隔离的是应用层不是物理资源层；要不要给营销独立 DB 实例（或独立连接池上限）留待后续

## Out of scope

<!-- 见「Out of scope」：被明确划到本 effort 终点之外的工作；已关闭，永不毕业 -->

- **优惠券**：用户 2026-09-14 明示「本期先不做优惠券，只做积分」，奖品类型只留 实物 / 积分 两种
- **多活动 / 活动列表 / 活动状态机**：本期单活动，活动页直接就是它（票 01 定）
- **抽奖保底、十连抽**：本期不做（票 01 定）
- **后台配置页**：奖品 / 权重 / 库存 / 兑换定价 / 每日上限 / 活动起止一律用 SQL 种子数据（票 01 定）
- **设备指纹 / 多账号识别风控**：本仓无设备维度数据（票 01 定），只做「不能邀请自己 / 每人只能被邀请一次 / 每日抽奖上限」三条硬约束
- **通用收货地址簿 / 物流状态机**：实物奖品只在领取单上填收件人 + 手机号 + 地址（票 01 定）
- **redis 重建看门狗 / 关桶 / 多桶分摊 / 抽奖取消回补**：本期不做（票 01 定）—— 但 **DB 直扣兜底要做**
- **Spring Cloud Gateway 网关**：只有一个前端，网关照不到收益（票 01 定）；将来上小程序时再加，且是「加一层、不动三个服务」的改动
- **其它积分获取途径（签到 / 发帖送积分等）**：本期积分的 earn 只有抽奖中奖（「成团送积分」已由票 05 反转取消——**成团只加抽奖次数**），其余是后续 effort
- **底部「消息」页的营销活动入口**：用户明示营销入口放市集，底部那条「活动消息」横幅**删除**（票 01 定）
