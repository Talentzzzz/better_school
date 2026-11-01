# 05: 一刀切完成与按人结算

**What to build:** 多人单送达后发布方一次确认，全员同时 COMPLETED（24h 自动完成同样一刀切）：发布方 unfreeze 总额、每个接单者各落一条 income 流水（各得每人报酬，幂等），系统群 sealGroup 封盘、历史只读；钱包与我的接单页可见各自到账。多人单不复用单人单的 taker_id 结算路径。

**Blocked by:** 03

**Status:** resolved

- [x] 发布方确认 → 全员同时转 COMPLETED（一刀切，无部分完成态）
- [x] 结算：unfreeze 总额 + N 条 per-taker income（每人报酬），uk_order_type 幂等键按人扩展，重复确认/重复消费不重复结算
- [x] 24h 自动完成延迟消息对多人单同样一刀切生效
- [x] 系统群封盘（终态 COMPLETED）：禁言、成员变更关闭、历史只读
- [x] 前端：发布方确认按钮、各接单者视角完成态与到账展示、群封盘后输入置灰
- [x] 集成测试：一刀切状态断言、按人结算金额与幂等、自动完成、封盘后禁言禁变更

## Comments

**一刀切 = 单条条件 UPDATE，无部分完成态**：confirm 与 autoComplete 都复用既有 `confirmById`/`autoCompleteById`（DELIVERED→COMPLETED 单行条件 UPDATE）——「全员同时」是状态的天然结果，未新增逐人状态机。

**按人结算与流水唯一键扩容（迁移 019）**：多人单一刀切要在同一订单落 N 条 INCOME，而 `wallet_transaction.uk_order_type(order_id,type,biz_round)` 会把第 2..N 条当重复吞掉（只有首个接单者到账）。019 把 uk 扩为 `(order_id, type, biz_round, user_id)`——spec「uk 按人扩展（biz 维度带 taker）」落地为给键加「人」维度（比把 taker 塞进语义为「业务轮次」的 `biz_round` 更诚实，且该列 INT 装不下 BIGINT userId）。求购/商品路径同一 (order,type,round) 恒属同一用户，扩容后唯一性不变；RECHARGE 的 order_id 为 NULL 本就不受 uk 约束；存量行是旧键的子集，无需回填。迁移测试实证「存量保留 + 同键不同人各落一条 + 同人重复仍拒」。

**结算结果对象改为携带按人明细**：`ErrandConfirmResult`/`ErrandAutoCompleteResult` 由 (takerId, reward) 改为 (publisherId, reward=解冻总额, `incomes=[ErrandIncomeVO(userId, amount)]`)，case 层循环入账。单人单口径不变（单条、金额=悬赏），多人单为全部在团接单者 × 每人报酬。

**多人单取货/送达（票 04 留档缺口在本票收口）**：原 `pickupById`/`deliverById` 带 taker_id 条件，多人单 taker_id 恒 NULL 无法取货。新增 `pickupMultiById`/`deliverMultiById` 条件 UPDATE（子查询判在团成员；取货额外要求团队满员）。**取货要求满员**是必要口径：短员取货会锁死补位入口（补位需 TAKEN），且完成时按人结算凑不齐冻结总额（发布方 unfreeze 总额 > 各人收入之和，余款蒸发）。「任一在团成员可触发取货/送达」是票面未写但流程必需的语义（团队一把抓、不复用 taker_id；取货后团队锁定、成员不再变动）。

**仲裁结案一并收口**（spec API 契约写「现有 confirm / arbitrate / … 语义按一刀切完成扩展」）：原 `arbitrateOrder` 判完成只 `income(takerId, 总额)`——多人单 taker_id NULL 会**静默不结算**（`INSERT IGNORE … WHERE user_id = NULL` 0 行），且仲裁终态从不封盘（spec「散团/完成/取消 → sealGroup 封盘」）。改为判完成复用同一按人明细、判取消不结算，两条终态都 `sealGroupIfBound`。**这是 code-review 揪出的可达缺陷**（发布方可在多人单 PICKED_UP/DELIVERED 发起争议 → ADMIN 判完成/取消），非票面显式项，按「终态即封盘 + 未完成不结算/完成后按人结算」原则一并收掉。

**「我的接单」纳入成员表**（票面「我的接单页可见各自到账」）：`selectMyTaken`/`countMyTaken` 由 `taker_id = ?` 改为 `taker_id = ? OR EXISTS(成员行)`（EXISTS 并集无重复行）；进过团即出现（退出留痕行也算，与订单历史口径一致）；单人单无成员行不受影响。

**封盘**：confirm / autoComplete / arbitrate 三处终态都调 `sealGroupIfBound`（幂等；roomId 未回填不封，沿票 03/04 口径）。前端「群封盘后输入置灰」沿用 chat 既有 SEALED 渲染，未改代码，冒烟实证。

**code-review 采纳**：①仲裁按人结算 + 终态封盘（上述可达缺陷）；②`docs/db/trade.md` 求购条目仍写三列 uk 与新章节自相矛盾——同步为四列；③前端成员卡提示的链式三元抽为 `memberHint` computed（对齐相邻 `completedNote`）。**留档未改**：①「团队满员」在 Java 前置判定（友好文案）与 SQL 子查询（原子闸门）各一份——是「先给友好错误、再靠条件 UPDATE 兜并发」的有意分工，与 joinTeam「服务层判定 + 锁内 COUNT」同构；②`completionResult` 与 `handleAutoComplete` 各 new 一次结果对象（两类型不同，共享的 `completionIncomes` 已抽出，剩余 1 行重复接受）；③`sealGroupIfBound` 每次重新 loadOrder（既有助手，四条终态路径共用，免调用点传对象）；④评价按人（票 06）未做——前端评价入口暂以 `!isMulti` 收起，避免点进必错的单人 to_user 口径（spec API 契约缺口，已由票 06 承接）。

**验收**：全量后端 **298 绿**（新增 `ErrandMultiTakerCompleteSettleIntegrationTest` 8 例：发布方确认一刀切按人结算封盘+幂等、24h 自动完成同口径、缺人不可取货补位满员后取货并按在场成员结算、非在团成员不可取货送达、我的接单可见成员与到账、仲裁判完成按人结算、仲裁判取消全额退不结算、单人单回归；新增 `ErrandMultiTakerIncomeMigrationTest` 1 例），前端 `vue-tsc` + `vite build` 绿。双端 UI 冒烟（浏览器 takerA + REST 发布方，真实 RocketMQ 链路）：发布 2 人单（每人 ¥20）→ 甲乙进团成团建房（room 8）→ 甲详情页「已成团 / 2/2 人 / 完成时每人各得 ¥20」+ 行动区「进入协作群 / 退出任务 / 确认取货」→ 点「确认取货」→「已取货 / 团队已取货，任务锁定」→ 点「确认送达」（二次确认弹窗）→「已送达 / 等待发布者确认完成，确认后每人报酬自动到账」→ 发布方 REST confirm → 库内 COMPLETED、UNFROZEN 40、INCOME 20×2（甲/乙）、余额 1000/1020/1020、room SEALED → 甲刷新「已完成 / 每人报酬 ¥20 已到账」「2/2 人（每人已得 ¥20）」「协作群已封盘，历史消息只读」→ `/chat/8` 输入置灰占位「订单已结束」+ 发送禁用 + SEALED「会话已结束」→ 钱包「跑腿收入 +20.00 / 余额 1020」→ 我的跑腿-我接的列表出现该单（已完成 / 每人 ¥20）。冒烟数据（订单/群/用户/流水/通知/outbox）已删还原，`smoke-ticket05-prep.py` 留档（smoke05.json 含 token 已删不入库）。迁移 **019 已应用联调库**。
