# 05: 防占单（冷却计数与判定）

**Spec:** `.scratch/errand-lock-review-hub/spec.md` §4（全章）

**What to build:** 让「抢了不做」有代价，且代价与过错对等——用**限权（冷却）**而不是扣信用分。规则：滑动 24 小时内**主动放弃**（`LOCK_ABANDONED`）达 3 次 → 该用户在 2 小时内禁止抢任何订单（`grab` 前置直接拒，403 + 明确文案）；**超时未确认不计**（掉线/没看到不可控，惩罚不公）。冷却触发时同时**重置该用户的计数窗口**——否则冷却结束时窗口里那 3 次还在，会立刻再触发、循环到 24 小时滑出，实际封禁变成约 22 小时，与「轻惩罚」直接冲突。

数据落点：**不新增表、不新增列**。事实源仍是订单事件流水（查 `actor_id` + `LOCK_ABANDONED` + 近 24 小时），为此给事件表加索引；计数与判定走 Redis——ZSET 按「放弃时刻」做精确滑动 24 小时、String 用 `SET NX EX 7200` 写冷却标记（幂等，不叠加延长）。写点在放弃事务**提交后**，读点只在 `grab` 前置：**Redis 不参与状态迁移互斥**（原子性仍靠条件 UPDATE），计数跨订单、不是某一行的属性，所以不进 UPDATE 条件；Redis 不可用/降级时回落查事件表，ZSET 不存在即该窗口 0 次、放行（宽恕方向安全，不引入对账补偿）。触发冷却打一条 WARN 日志（用户 id + 窗口计数 + 到期时刻），不落事件。

**不做**：提前预警、超时计数、扣信用分、同单 N 次上限、发布方补偿、管理侧榜单（均记开放项）。

**Blocked by:** 03

**Status:** resolved

- [x] 事件表加索引（`actor_id, event_type, create_time`）随 `schema.sql` 重生库生效
- [x] 主动放弃在 Redis 计数（按「放弃时刻」精确滑动 24h）；超时未确认不计数
- [x] 滑动 24h 内主动放弃 ≥3 次 → 置 2h 冷却 + **重置该用户计数窗口** + 打 WARN 日志（含用户 id／窗口计数／到期时刻）
- [x] 冷却期内抢单被拒：`FORBIDDEN` + 文案（含到期时刻），且**不**改动既有信用分门槛那个 500 类错误的口径
- [x] 冷却期内「该单」也不可抢（冷却键是用户维度、天然覆盖）
- [x] Redis 不可用/降级时回落事件表查询完成判定；Redis 写失败＝少计＝宽恕，不引入对账
- [x] Redis 计数/冷却 key 前缀按既有命名先例落常量
- [x] 集成测试覆盖：第 3 次放弃后第 4 次抢单被拒；2h 后恢复可抢；冷却触发后窗口确实重置（不会放大成 ≈22h 封禁）

## Answer

落地要点：

- **数据落点（不新增表/列）**：唯一 DDL 改动＝`errand_order_event` 加 `KEY idx_actor_type_time (actor_id, event_type, create_time)`（`sql/schema.sql`，重建库生效——已重建 `better_school_test` 并 `SHOW INDEX` 核对）。
- **计数与冷却（Redis 判定加速层）**：新增域端口 `IErrandGrabAbusePort`（`domain/errand/adapter/port/`），实现 `ErrandGrabAbusePort`（`infrastructure/adapter/port/errand/`，注入 `RedissonClient` + `IErrandRepository`）：
  - 计数 ZSET `bs:errand:grab-abandon:{userId}`，member=`{orderId}:{lockSeq}`、score=放弃时刻（epoch millis）——写入即 `removeRangeByScore(-inf, windowFloor]` 做**精确滑动窗口**，`size()` 取窗口计数；
  - 冷却 String `bs:errand:grab-cooldown:{userId}`，值=到期时刻，`RBucket.trySet(until, 7200, SECONDS)`＝`SET NX EX`（**幂等不叠加**；已存在返回既有到期，不刷新）。
- **两 key 前缀**落 `bs-types` `Constants`（`ERRAND_GRAB_ABANDON_KEY` / `ERRAND_GRAB_COOLDOWN_KEY`，对齐 `WS_TICKET_KEY` 先例）；**窗口/阈值/冷却三参数**落 `ErrandOrderServiceImpl` 常量（`GRAB_ABUSE_WINDOW_HOURS=24` / `GRAB_ABUSE_THRESHOLD=3` / `GRAB_COOLDOWN_HOURS=2`，与 `PAY_TIMEOUT_MINUTES`/`LOCK_TIMEOUT_MINUTES` 并列）。
- **写点（放弃主效应成功后）**：`ErrandOrderServiceImpl.abandonLockedOrder` 先 `lockSeqOf(orderId)` 取本轮轮次（锁单期 `lock_seq` 不变、读在放弃之前，同单重抢是不同轮次各计一次），条件 UPDATE + 落 `LOCK_ABANDONED` 事件成功后调 `recordGrabAbandon`：计数 → 达阈值则 `startCooldown` + `resetWindow` + `log.warn`（用户 id／窗口计数／到期时刻）。**触发即重置窗口**——否则冷却结束那 3 次还在会立刻再触发、实际封禁 ≈22h。任何 Redis 异常只记日志（少计=宽恕，不引入对账）。
- **读点（`grabOrder` 前置、条件 UPDATE 之前）**：`requireNotGrabCooledDown` 命中冷却即抛 `FORBIDDEN`「因近期多次放弃接单，您已被限制接单，请于 HH:mm 后再试」。冷却键是用户维度，**天然覆盖「该单」**。**未触碰信用分门槛那个 `BUSINESS_ERROR(500)`**（在 case 层 `ensureOperable`，保持原口径）。
- **降级回落**：`cooldownUntil` 读 Redis 报错时回落事件表——`countRecentAbandons`（`actor_id + LOCK_ABANDONED + create_time >= now-24h`，走新索引）达阈值且最近一次放弃 + 冷却仍在未来才判冷却，否则放行；回落本身再失败也放行。回落方向宁可宽恕（事件表数不出「触发后已重置」）。
- **超时未确认不计**：超时释放（`LOCK_TIMEOUT`，actor `SYSTEM`）**不走**放弃写点，故不进窗口、不触发冷却（惩罚不公）。
- **口径正交**：放弃只记冷却、**不扣信用分**（信用分只记「已承诺后违约」，`-20` 一档与「反复小动作」量刑不成比例）。
- **前端**：零改动——403 走 `Response.info`，`api/http.ts` 默认对非 200 统一 toast 该文案（抢单接口未用 silent）。
- **验证**：新增 `ErrandGrabAbuseCooldownIntegrationTest` 3 例（第 3 次放弃触发冷却 + 第 4 次抢单 403 且文案含 HH:mm + 他人不受影响 + 不扣分；冷却触发即重置窗口 → 删冷却键后可抢且新窗口重新起算；三次锁单超时释放不计入、仍可抢）。单类 3/3 绿；`mvn test` **257 例全绿**（票 04 的 254 + 新增 3）；`mvn compile` / `test-compile` 干净。⚠️ 集成测试连常驻共享测试库，两条 Maven 测试进程不可并发。
- **测试隔离**：`CommunityApiTestBase.truncateTables` 补清 `bs:errand:grab-abandon:*` / `bs:errand:grab-cooldown:*`（Redis 不随 TRUNCATE 清空，防跨用例脏读，与既有 `bs:post:detail:*` 同处）。
- **两轴复查结论（code-review）**：Spec 轴无缺失、无 scope creep，八条验收项全部落地。已按反馈修正三处：①滑动窗口下界改含端点（与事件表回落口径 `create_time >= now-24h` 对齐，毫秒级差一）；②回落判定注释改口——事件表数不出「触发后已重置」，回落实为**偏严**（最多多封一个冷却时长），非「宁宽」，如实记录不补偿；③`abandonLockedOrder` 补注「无显式事务 ⇒ 主效应成功后即提交后」的依赖——若未来包事务，计数必须挪进 afterCommit 钩子。
  - **判过但未采纳**：①两 key 前缀放 `types/common/Constants` 而非 `types.errand.*`（CLAUDE.md §7 多域分段规则）——spec §4.6 **明示落点**即 `bs-types` `Constants` 并对齐 `bs:chat:ws-ticket:` 先例，全仓 errand/chat/product 常量同文件同惯性，spec 优先；②`cooldownUntil(userId, windowFloor, threshold, cooldown)` 的策略参数束（Data Clumps 嫌疑）——阈值/窗口/冷却按 spec §4.6 必须落域服务常量，经参传递是分层的结果而非缺陷，为一个三参方法立策略值对象属 Speculative Generality；③`CONTEXT.md` 补「主动放弃／防占单冷却」词条——按本 effort 拆票口径归**票 14**（文档/词条/ADR 收口）统一处理；④Port 内含回落判据（「判据留在消费方域」）——该条针对跨域只读 port，本端口是本域 Redis 能力 port，回落 SQL 是它的实现细节。

