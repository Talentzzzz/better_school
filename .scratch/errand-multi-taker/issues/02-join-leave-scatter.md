# 02: 进团、退团与组队散团

**What to build:** 接单者在多人单详情页点「立即抢单」即进团：人数未满即加入成功（先到先得、不可挑选、不能抢自己的单、信用门槛沿用）；进团前只见「当前/所需人数」，进团后可见成员列表（只读昵称头像）；组队期随时退团，不扣信用分、不动资金；组队 deadline 到人数不足自动散团——订单取消、悬赏全额退发布方、进团者收通知、无人扣分。单人单 grab 行为不变。

**Blocked by:** 01

**Status:** resolved

- [x] 进团写订单成员表（多人单不复用 taker_id）；并发进团同一刻仅补足到所需人数，其余失败提示（条件 UPDATE 口径）
- [x] 同一用户不可重复进团；不能进自己的单；ensureOperable 沿用
- [x] 进团前详情仅返回人数，进团后返回成员列表（昵称/头像，只读）
- [x] 组队期退团：成员行标已退出、人数回退、不扣分、无资金流水、可重新进团
- [x] 组队 deadline 人数不足：自动散团 CANCELLED + 全额退款（uk_order_type 幂等）+ 进团者通知 + 无人扣分；重复触发幂等
- [x] 超时扫描 job 兼容多人单组队期（沿用 idx_status_deadline 路径）
- [x] 前端：详情页按可见性切换（人数/成员列表）、进团与退团入口、确认弹窗文案区分进团语义
- [x] 集成测试：并发进团、退团幂等、散团退款幂等；冒烟双人进团退团

## Comments

**并发口径**：票面写「条件 UPDATE 口径」，实现改用**锁订单行 FOR UPDATE + 锁内 COUNT**（`selectByIdForUpdate` → `countInTeam`），对齐 chat 域 `ChatGroupServiceImpl` 锁 room 行后 COUNT 的既有先例（「满员 COUNT 在锁内读才可靠」）。原因：`INSERT ... SELECT ... WHERE (COUNT 子查询) < N` 在 RR 下两个并发事务会各自读到旧 COUNT 双双插行（gap 锁对共享锁相容），无法封顶；锁订单行让同一订单的进团/退团/散团全部串行，结果等价且可证（5 并发抢 3 名额恰 3 成功）。散团本身仍是条件 UPDATE（`scatterGrouping`：status + deadline 双校验）。

**成员状态两态**：spec 数据模型写「在团/已退出/已补位」，但「已补位」语义属票 04（执行期退出后补位），本票只落 `IN_TEAM`/`LEFT` 两态；票 04 需要时增量加值不改列。退团只标 LEFT 保留留痕行（uk_order_user 单行），重新进团清 `left_at` 回 IN_TEAM 复用该行。

**满员不散团**：多人单满员后仍是 PUBLISHED（成团转开始态属票 03），超时扫描对「满员且过 deadline」的单每轮返回 null 空转（幂等无害，无资金动作），票 03 落地后该状态不再出现。

**散团不清成员行**：散团保留 IN_TEAM 留痕（谁在团一目了然）；CANCELLED 详情页无任何操作按钮，不出现退团入口。

**发布者可看成员列表**：spec 的可见性条款是就接单者而言（「进团后成员列表互相可见」）；发布方作为组织方同样可见（`canSeeMembers` 放行 isOwner），未进团的旁观者只见当前人数、响应缺 `members` 字段（DTO `@JsonInclude(NON_NULL)`）。

**「我的接单」未接成员表**：`listMyTaken` 仍走 `taker_id`，多人单参与者暂不出现在「我的接单」（票面未列；成员表已留 `idx_user_status` 供后续接入）。

**member_count 子查询**：`errand_order` 各查询统一带在团人数相关子查询（列表/详情「当前人数」口径一致）；锁路径刻意不带（避免悲观锁语句连带锁成员行），锁内人数一律走 `countInTeam` 直读。

**code-review 采纳**：①`joinTeam` 重新进团补条件 UPDATE 落空校验（原来 `rejoinMember` 影响 0 行也返回成功 = 静默无操作）；②前端满员不再渲染「可点击但必失败」的加入按钮，改「组队已满员，等待成团」提示；③CANCELLED/REJECTED 不再显示「完成时每人各得」文案；④票 01「多人单拒绝直接抢单」用例改写为「抢单入口转进团落成员行不复用接单方」。**留档未改**：`onGrab`/`onJoin` 两个 handler 形状相同（沿用仓库「一动作一 handler + 内联文案」惯例不合并）；`grabOrder`/`joinTeam` 双处 multi-taker 判定（`joinTeam` 是 domain 公开 API，需自我守卫）。

**验收**：全量后端 275 绿（新增 `ErrandMultiTakerJoinIntegrationTest` 10 例 + `ErrandOrderMemberMigrationTest` 1 例；票 01 用例改写），前端构建绿。双端 UI 冒烟（Publisher 浏览器 + TakerA 浏览器 + TakerB REST）：发布页「3 人」chips →「每人 ¥20 × 3 人，支付总额 ¥60」→ 详情「每人报酬 ¥20 / 0/3 人 / 立即支付 ¥60」，支付后库内 `frozen_amount=60.00` + FROZEN 60.00；TakerA 进团前仅「0/3 人（还差 3 人成团）」且**无成员卡** → 点「加入组队（0/3 人）」（确认弹窗「确认加入组队？人满自动成团，组队期可随时退出」）→ 人数 1/3、成员卡出现 TakerA「我」、按钮转「退出组队」；TakerB REST 进团 → TakerA 刷新见 2/3 且两名成员都在列表；TakerA 退团（确认弹窗「确认退出组队？组队期退出不扣信用分，可随时重新加入」）→ 人数回 1/3、成员卡消失、按钮回「加入组队（1/3 人）」→ 重新进团复用留痕行（`errand_order_member` 该用户恒单行、`left_at` 清空、`joined_at` 刷新）。库内实证：全程 `taker_id` 恒 NULL、两人信用分恒 100、`user_credit_log` 0 条、`wallet_transaction` 仅 FROZEN 60（退团无任何资金流水）。**未做**：成团自动开始与系统群建立（票 03）、执行期退出/补位/发布方取消（票 04）。
