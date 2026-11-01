# 10: 前端·底部消息页收口

**Spec:** `.scratch/marketing/spec.md` §「底部「消息」页收口」

**What to build:** 底部「消息」页改成三段式，并把系统消息的取数从「非交互类型」改为按 `scope = BOTTOM_SYSTEM`；市集三类交易动态移出（去市集消息页）、「活动消息」横幅删除。

**Blocked by:** 02（按 `scope` 取数与推水位）、09（营销三类通知要真的落到 `BOTTOM_SYSTEM`）

**Status:** resolved

- [x] **三段式**（不是票面原写的两段）：
  1. **互动**：两个等分方块入口「赞」（粉底）/「评论」（绿底），52px 圆角色块 + 24px 图标 + 14px 标签
  2. **系统消息**：蓝圆铃铛行 ——「系统消息」+ 副文案「审核通知、平台公告、信用分变动」+ 右侧时间与未读点
  3. **私信列表**：头像 + 昵称（群带「群」标签）+ 最新消息 + 时间 + 未读计数；底部一行「共 N 条未读消息」
- [x] **系统消息取数从「非交互类型」改为 `scope = BOTTOM_SYSTEM`**（现为 `!interaction.includes(n.type)`；不改会把市集交易类也收进来、与市集消息页重复）
- [x] `INTERACTION_TYPES` **不变**；`TYPE_TEXT` **补 `AUDIT_PENDING`**（现有值漏了它，会落到显示兜底「通知」）与营销三值
- [x] 系统消息范围包含：公告 / 审核结果 / 待审提醒（`AUDIT_PENDING`）/ 举报结果 / 信用分变动 + 营销三类 + 审核超时提醒
- [x] **删除「活动消息」横幅**（全仓**仅此 1 处**引用 `/notifications?tab=system`）
- [x] **市集三类交易动态（闲置 / 跑腿 / 求购）移出**本页，去市集消息页
- [x] `NotificationsView` 改按 `scope` 取数（`GET /api/chat/notifications?scope=`）；已读改成推水位（`POST /api/chat/notifications/read`），不再逐行 `is_read`
- [x] **顶部 nav 不变**：沿用现状的「消息」标题 + 右上搜索图标（→ `/messages/contacts`）——本票只改内容区，不动 nav
- [x] `npm run build`（`vue-tsc`）通过；双视口目视验收（三段齐全、系统消息行与私信行不重复、市集交易类不再出现），截图存 `verify-10/`
- [x] 视觉照 `prototype/marketing` 分支的 `BottomMsgA.vue`（变体 A）

## 实现要点（票 10）

**实际改动只有两文件**——票面多项在票 02/03/09 落地时已顺带满足：`NotificationsView` 按 scope 取数 + 推水位（02）、市集交易类移出（02 的 scope 过滤，后端）、营销三类真落 `BOTTOM_SYSTEM`（09）。本票补的是：

1. **`MessagesView`**：「活动消息」横幅换成**系统消息行**——蓝圆铃铛（SVG 逐字节照 `BottomMsgA.vue`）+ 标题「系统消息」+ 副文案（票 07 定的固定文案，非最新一条预览，与市集消息页的同构行**数据口径不同**：那边显示最新正文预览，这边是静态副文案）+ 右侧真实时间（`listNotifications(1, 1, 'BOTTOM_SYSTEM')` 取最新一条 `createdAt`，无通知不显时间）+ 未读点（`getNotificationsUnreadCount('BOTTOM_SYSTEM')`，>0 才显，**不再误绑** `chatRooms.unreadTotal`）；补「私信」分区头（原型 A 有）。**去向着色**：`/notifications?tab=system` 保留为该行的跳转目标——票面「全仓仅 1 处引用」是删除安全性论证而非「必须杀链接」，`NotificationsView` system tab 就是唯一的 BOTTOM_SYSTEM 列表，删了行就是死的；横幅的「删除」落实为旧标题/静态文案/错误未读源的整体替换。
2. **`NotificationsView`**：`TYPE_TEXT` 补 `AUDIT_PENDING: '待审'` + 营销三值 `'拉新' / '中奖' / '积分'`（沿用现有两字短标签惯例；后端枚举全名是「待审提醒 / 成团成功 / 抽奖中奖 / 积分变动」，图标圆块放不下）。

**验收**：`npm run build`（vue-tsc）绿；Playwright 拦 `/api`（正则 `^https?://…/api/`）伪造数据，375/414 双视口截图存 `verify-10/`——三段齐全、铃铛行时间与未读点来自真数据（mock 2 条未读）、私信群标签/未读计数/「共 7 条未读」、点系统消息行跳 system tab 且发出 `POST /notifications/read`（推水位）、8 类通知（含 AUDIT_PENDING 与营销三值）图标标签全不落兜底。

**code-review 采纳**：空 `catch` 补 WHY 注释（与 `MarketMessagesView` 的失败口径注释对齐）。**未采纳/不属本票**：① CONTEXT.md「系统消息」词条与 notification 域用词冲突（词条标注 Avoid 通知义）——票 03 的 `MarketMessagesView` 已用同标签，属词条滞后，留给 `/domain-modeling` 补词条，本票不扩散改动；② `NotificationType` 联合类型（types/api.ts，5 值）落后于实际 13 值——既有债，本票不半修；③ 单次取数不照抄 Market 的 reactive cache（单 scope 一次性取，抄了才是 Speculative Generality）。

**已知波及**：系统消息行的时间/未读点只在挂载时取一次，页面驻留期间新通知不实时刷新（返回本页会重挂载重取，无 keep-alive）；`/marketing` 活动入口与本票无关（票 11）。
