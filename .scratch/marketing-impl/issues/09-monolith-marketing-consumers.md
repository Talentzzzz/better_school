# 09: 单体·营销消费者与营销通知

**Spec:** `.scratch/marketing/spec.md` §「营销域」→「事件边契约」「通知投递」+ §「底部「消息」页收口」

**What to build:** 单体作为**第四个消费者**订阅三个营销 topic，把营销事件落成通知行。做完之后拉新成团、抽奖中奖、积分变动三类通知会出现在底部消息页的 system 范围里。

**营销侧不同步调单体**；单体停 → 营销照跑。

**Blocked by:** 05（`bs-points-changed`）、06（`bs-referral-grouped`）、07（`bs-lottery-drawn`）——三个消费者要真事件才能真正验收

**Status:** resolved

- [x] `bs-trigger/listener/` 新增三个消费者：`ReferralGroupedConsumer` / `LotteryDrawnConsumer` / `PointsChangedConsumer`
- [x] 消费组按修订后的命名：`bs-monolith-referral-grouped-group` / `bs-monolith-lottery-drawn-group` / `bs-monolith-points-changed-group`（**不是**既有的 `bs-<topic>-group`）
- [x] case 层复用 `NoticeService.sendSystem(...)` 落 `notification` 表；**不做** `INotificationPort`、不复用 `INotificationService` 的通知口
- [x] 新增 `REFERRAL_GROUPED` / `LOTTERY_WON` / `POINTS_CHANGED` 三个枚举值，**不复用 `ORDER_EVENT`**
- [x] `ref_type = MARKETING`；`scope = BOTTOM_SYSTEM`（落**底部消息页 system tab**，**不进市集消息页**）
- [x] `ref_id` 取事件体里的业务 id；通知标题/正文按事件呈现（成团、中奖奖品名、积分变动数）
- [x] 幂等键 = `notification.dedup_key`（形如 `MARKETING:GROUPED:<groupId>`），沿用既有唯一键保证重放不重复落行
- [x] 消费侧解析失败 = log + 丢弃；业务异常 = 交 broker 重试
- [x] **单体侧不新增任何反向调用营销服务的代码**；**不改**现有接口语义
- [x] 验收：三个服务的真实事件各触发一次 → 底部消息页 system 范围出现对应通知；同一条消息重投不产生第二行

## 实现要点（票 09）

**落地形状**：`bs-types Constants` 加三个 topic（注释写明消费组必须 `bs-monolith-<topic>-group`）+ `NotificationTypeVO` 三枚举；case 层新建 `usecase/marketing/`（三个事件 VO 形状逐字段对齐三个生产方 Message 类 + `MarketingNoticeCase` 复用 `sendSystem`，三定值 `scope = BOTTOM_SYSTEM` / `ref_type = MARKETING` / 营销三枚举）；trigger 层三个消费者照 `ErrandPaidConsumer` 同构（readValue → 缺业务字段 log+丢弃 → case；解析成功后异常上抛交 broker 重试）。

**两处 dedup_key 形状偏离票 04（均成立，`docs/db/chat.md` 已同步）**：
1. GROUPED 追加收件人 → `MARKETING:GROUPED:<groupId>:<userId>`：`uk_dedup` 是 `dedup_key` **单列全局唯一**，邀请人+被邀请人各落一条必须各占一键（`ErrandOrderCase` 散团通知 `:joinerId` 同构先例）。
2. POINTS 用 `<reasonType>:<refId>` 替代 `<txId>`：票 05 定稿的事件体根本没有 txId 字段；该组合唯一标识一笔变动（与 `points_lot` 的 uk `(user_id, reason_type, ref_id)` 同口径），且防「抽奖记录 id 与兑换单 id 分属两表、裸 refId 跨表撞键」。

**票面「中奖奖品名」的 spec 内在矛盾**：事件体无奖品名（spec 硬要求「事件体只带 id + 必需值」「消费方不反查生产方」）⇒ 文案按 `prizeType` 分档（POINTS 带金额、PHYSICAL 指「我的奖品」领取），不反查。`THANKS` 防御性跳过保留（生产方 `prize.won()` 闸门下不可达，纯防御）。

**code-review 采纳**：`docs/db/chat.md` notification 枚举清单 / 归属映射 / 营销 dedup_key 形状同步；case 层 prizeType 魔法值收 `PRIZE_TYPE_*` 常量。**未采纳**：三个消费者 `onMessage` 同形抽公共 helper——与仓库先例（`ErrandPaidConsumer` 等整段同形复制）一致，校验字段各异，收了反而藏。

**验收口径**：单体的测试设施（常驻栈）跑不了三服务进程，「真实事件各触发一次」落地为——事件体按三个服务 outbox 的真实形状构造、走消费者真实解析与处理路径（`consumer.onMessage`），6 用例绿（成团双方各一条+重投幂等 / 积分档+实物档文案 / THANKS 不落 / 积分正负变动文案 / 残缺载荷丢弃 / scope·ref_type·dedup_key 三定值断言）；**全量回归 341/341 绿**。跨服务真 broker 端到端（营销服务真发 → 单体真收）归联调。前端 `TYPE_TEXT` 补营销三值归票 10。
