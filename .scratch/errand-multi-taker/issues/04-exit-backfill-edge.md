# 04: 执行期退出、补位与异常路径

**What to build:** 执行开始后取货前：接单方可退团（扣信用 -20），群内被移除并落 SYSTEM 留痕，订单回「差 N 人」补位状态，新人从同一条进团路径补位，有人补位即继续执行；执行 deadline 补位仍未补齐则散团（取消+全额退+未完成不结算+封盘）；发布方取货前可取消整单（全额退、接单者不结算、发布方不扣分、封盘）；取货后锁定——退出与取消一律走 DISPUTED 仲裁（沿用现有通道）。

**Blocked by:** 03

**Status:** resolved

- [x] 执行期取货前退团：扣 ERRAND_TAKER_CANCEL -20（幂等键带 order 维度）、removeMember + 成员退出 SYSTEM 消息、人数回退进补位态
- [x] 补位：新人走 02 的进团路径成功进入（addMember + 成员加入 SYSTEM 消息），补位后订单回执行态
- [x] 补位等待无独立时限：执行 deadline 补位未齐 → 散团（全额退、未完成一律不结算、sealGroup 封盘、SYSTEM 说明消息）
- [x] 发布方取货前取消：全额退、接单者不结算、发布方不扣分、封盘（留档接受接单者时间无偿）
- [x] 取货后：退团/取消请求一律被拒，仅 DISPUTED 仲裁可达终态（沿用）
- [x] 前端：执行态成员的退团入口与二次确认（明示扣分）、补位态展示、发布方取消入口按阶段启用/禁用
- [x] 集成测试：退出扣分幂等、补位后继续、补位超时散团退款幂等+封盘、发布方取消路径、取货后拒绝

## Comments

**成员变更走 outbox（对齐票 03 先例）**：退团/补位的群内动作（removeMember/addMember）与成员行变更跨域，新增 `bs-errand-member-changed` topic——leaveTeam/joinTeam 事务内随成员行变更同事务落 outbox（`saveMemberChangedMessage`），`ErrandMemberChangedConsumer` → case `handleErrandMemberChanged` 顺序编排。幂等三重：订单终态守卫 + 群成员状态静默（已移除/已在会）+ room uk_ref 自愈（roomId 未回填时先补走 handleErrandGrouped 建群再变更成员，覆盖建群事件晚于成员变更的秒级竞态）。

**joinTeam/leaveTeam 双态分流**：joinTeam 放宽为 PUBLISHED（组队期，满员成团走 startGroupedIfFull）或 TAKEN 且缺人（补位：不再 startGrouped、执行 deadline 不重算、落 JOINED outbox）；`grabOrder` 的多人单分流提前到 PUBLISHED 状态守卫之前（否则 TAKEN 补位被单人单守卫挡死），owner 判定随之前移——单人单「owner 抢非 PUBLISHED 单」错误码由 NOT_FOUND 变 PARAM_ERROR（更精确，留档）。leaveTeam 按状态分语义：PUBLISHED 不扣分 / TAKEN 扣分+落 LEFT outbox 进补位态 / PICKED_UP·DELIVERED 拒绝（「已取货后不可退出」）/ 其余状态拒绝；已退出幂等静默但 TAKEN 态重试仍返回需扣分（扣分 uk 幂等，重放安全）。

**扣分幂等口径**：spec 原文「每次退出各扣一次，幂等键沿用 uk_user_reason_ref + order 维度」两说并存——uk(user,reason,ref_id=orderId) 决定同人同单只扣一次，票面写「幂等键带 order 维度」，按 uk 口径实现（重进再退不重复扣，测试定格）。扣分在 case 层事务外（domain 事务提交后 applyChange），失败靠客户端重试 + uk 幂等自愈，符合「禁止 case 包多域事务」。

**散团泛化**：`scatterGrouping` SQL 条件从 PUBLISHED 放宽为 IN ('PUBLISHED','TAKEN')，`scatterGroupingIfShort` 锁内按状态区分执行期（executionPhase 标记 → 通知文案「任务已散团：执行期有人退出且补位未齐」）与组队期；满员不散（执行期满员走通用超时取消）。终态封盘 `sealGroupIfBound`：multi && roomId 已回填 → sealGroup（幂等），挂在补位超时散团、满员执行超时取消、发布方 TAKEN 取消三处——spec「终态即封盘」，比票面覆盖面略宽（满员超时取消也封），提前收掉票 03 留档的追溯缺口，留档。roomId 未回填即终态的窄竞态不封，归票 05 终态追溯。

**SYSTEM 说明消息口径**：群内说明 = sealGroup 落的 SEALED 系统消息（「会话已结束」，封盘不可逆的通用终态文案）；散团原因详情走站内通知（notifyScattered，dedup_key 幂等）。未为散团扩展 sealGroup 自定义文案（票 05 完成封盘复用同一文案，chat 域 API 保持最小）。

**成员变更守卫放宽**：code-review 采纳——`handleErrandMemberChanged` 原仅 TAKEN 放行，取货后（群未封盘）到达的过期事件会把退出者留在群里；改为跳过仅终态（CANCELLED/COMPLETED/REJECTED/PENDING_*），TAKEN/PICKED_UP/DELIVERED/DISPUTED 放行。另一采纳：消费方 `ErrandMemberActionVO.valueOf` 解析移入 try，坏 action 载荷丢弃不触发 broker 无限重投。**留档未改**：①PUBLISHED/TAKEN 双态守卫在 joinTeam 入口/锁内/散团/SQL 共 4 处，两态语义不同（组队期 vs 执行期）不收敛；②`notifyScattered` 布尔分叉文案、ErrandTimeoutResult 两布尔叠加，结果对象仍小，接受；③scatterGrouping 命名沿用票 02（CONTEXT.md 散团定义本就覆盖组队超时与补位未齐两路径）；④case 层守卫/自愈判定与 handleErrandGrouped 先例一致，不下沉 domain。

**多人单取货前置说明**：`pickupById` 仍带 taker_id 条件，多人单无法取货——取货/结算/完成封盘属票 05；「取货后拒绝」测试以 SQL 置态 PICKED_UP 模拟（沿用 expireDeadline 的测试造数风格）。多人单成员不在「订单双方」（isOwner/isTaker 均 false），disputeOrder 仅发布方可发起；成员侧仲裁参与归票 05/06 按人语义。

**验收**：全量后端 295 绿（新增 `ErrandMultiTakerExitBackfillIntegrationTest` 7 例：执行期退团扣分/留痕/幂等、重进再退仍只扣一次、补位回执行态 deadline 不重算+加群+满员拒绝、补位超时散团退款幂等+封盘+SEALED 消息+在团者通知、发布方取消全额退+封盘+不结算+不扣分+终态重复取消被拒、取货后退团/取消被拒+仲裁可达、组队期退团不扣分回归），前端 vue-tsc + vite build 绿。双端 UI 冒烟（Playwright 浏览器 + REST，真实 RocketMQ 消费链路）：发布 2 人单成团建群 → A 执行期退团（REST，credit -20 落库 + MEMBER_REMOVED 上群 + left_at 留痕）→ C 详情页见补位态（「已成团 / 1/2 人（还差 1 人补位）/ 有成员退出，任务等待补位」+ 主按钮「加入补位（1/2 人）」）→ 点击确认弹窗「确认加入补位？…」→ 补位成功 2/2 成员卡出现 → B 视角「退出任务」按钮 + 确认弹窗「执行期退出将扣除 20 信用分」明示扣分 → 发布方行动区「进入协作群/取消订单/发起争议」→ UI 取消整单 →「已取消」+ 库内 CANCELLED、UNFROZEN 40 全额退、INCOME 0、发布方不扣分、room SEALED + SEALED 系统消息。冒烟数据（订单/群/用户/流水/通知/outbox）已删还原，smoke-ticket04-prep.py 留档（smoke04.json 含 token 不入库）。验证踩坑：`spring-boot:run -pl bs-app` 必须先 `mvn install` 否则跑本地仓库旧 jar（成员行落了但不成团的现象即源于此）；后端控制台日志经管道会丢，短信验证码落 logs/app-ticket04.log 或直接读 Redis（Redisson 编码不可直读，走日志）。
