# 02: 单体通知读模型（scope / seq / 水位线）

**Spec:** `.scratch/marketing/spec.md` §「营销域」→「通知与已读模型」

**What to build:** 单体既有的 `notification` 表从「逐行 `is_read`」换成 chat 那套「取号 + 水位线」读模型，并新增 `scope` 列让每个生产点**显式标注**这条通知属于哪个收件范围。做完之后市集消息页、底部消息页都能按 `scope` 取数与按范围推水位，且**现有全部生产点都已打上 scope**——这是票 03 / 04 / 10 的共同前置。

改的是**单体既有表**，不是三个营销 schema。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 迁移 `026_notification_read.sql`：`notification` 加 `scope` / `seq` 两列；新增 `notification_seq(user_id, scope, seq)` 计数器表与 `notification_read(user_id, scope, last_read_seq)` 水位表；`is_read` 退休
- [x] `seq` 由计数器在**写入事务内取号**（行锁到提交 ⇒ 取号序 = 提交序）；**禁止拿 `notification.id` 当水位**（全局物理自增，分配序 ≠ 提交序，并发下跳读）
- [x] 标已读 = 推水位（O(1)）；未读数 = 计数器 − 水位（O(1)）；**每个 scope 各推各的水位、互不误清**
- [x] `GET /api/chat/notifications?scope=` 新增过滤；响应里的 `read` 字段由「`seq` ≤ 水位」算出
- [x] `GET /api/chat/notifications/unread-count?scope=`（新增）
- [x] `POST /api/chat/notifications/read`（body `{scope}`，推水位）取代原 `POST /api/chat/notifications/read-all`
- [x] `NoticeService.sendSystem` 及其**全部调用点**补 `scope` 与取号；`dedup_key` 幂等语义不变
- [x] 归属映射逐条落：跑腿支付入审 / 跑腿散团 → `MARKET_ERRAND`；求购支付入审 → `MARKET_WANT`；审核超时提醒（PRODUCT / WANT_TO_BUY / ERRAND / POST）→ `BOTTOM_SYSTEM`；公告 / 审核结果 / 举报结果 / 信用分变动 → `BOTTOM_SYSTEM`；赞 / 评论四类 → `INTERACTION_LIKE` / `INTERACTION_COMMENT`
- [x] **同一个 `(type, refType)` 的两种收件人分别落不同 scope**（如 `ORDER_EVENT × ERRAND` 发给用户的交易动态 vs 发给审核员的审核提醒）
- [x] `scope` 取值收敛为常量/枚举（`MARKET_ERRAND` / `MARKET_WANT` / `MARKET_PRODUCT`（预留，当前无生产方）/ `BOTTOM_SYSTEM` / `INTERACTION_LIKE` / `INTERACTION_COMMENT`），**不从 `(type, refType)` 推导**
- [x] 现有通知相关集成测试全部改绿（`is_read` 退休后不再有该列断言）

## 实现要点（票 02）

**落地清单**：

- 迁移 `026_notification_read.sql`（建 `notification_seq` / `notification_read` 两表 + 加列 + 存量回填 + `is_read` 退休），`schema.sql` 原地同步为终态；迁移测试 `NotificationReadMigrationTest`（旧库带存量数据直跑 026，断言归属回填逐分支、seq 组内重排、计数器按组播种、水位表不播种）。**存量回填豁免**：一次性按 `type` + 提醒文案（`%待审超过%`）区分 `ORDER_EVENT` 两种收件人——与「不从 (type, refType) 推导」的冲突只在这一次性存量回填，写入路径仍是显式标注（文案是存量唯一区分线索，注释已标「尽力而为」）。
- 取号事务边界：`NoticeServiceImpl.sendXxx` 统一走 `transactionTemplate`（`PROPAGATION_REQUIRED`，调用方已有事务则并入）；`NotificationRepository.insertOne` = dedup 先查（不白耗号段，票外加码，语义兼容）→ `notification_seq` upsert 取号 → 落库，取号行锁到提交 ⇒ 取号序 = 提交序。极端并发下 dedup 撞键会留 seq 空号——推水位取**计数器值**一并越过，未读数公式不受空洞影响。
- 读路径：`selectPage` LEFT JOIN `notification_read`，`read` = `seq ≤ COALESCE(水位, 0)` 算出；未读数 = `计数器 − 水位`（O(1)）；`POST read` 推水位到当前计数器值（GREATEST 只前进）。水位表不播种存量 ⇒ 旧通知全部按未读呈现，读一次即推齐。
- 归属映射 15 处生产点全落（`ErrandOrderCase` / `TradePublishCase` / `PostPublishCase` / `AgentReviewDispatchCase` / `CommentReviewCase` / `ReviewCase` / `ReviewOverrideCase` / `ReportCase` / `CreditCase` / `CreditDailyCase` / `PostLikeCase` / `CommentLikeCase` / `insertBroadcast`）；`ORDER_EVENT×ERRAND` 支付入审→`MARKET_ERRAND`、审核提醒→`BOTTOM_SYSTEM` 分落验证在集成测试里。
- 前端最小适配（`read-all` 被删所迫）：`NotificationsView.vue` 三 tab 改按 `TAB_SCOPE`（`INTERACTION_LIKE` / `INTERACTION_COMMENT` / `BOTTOM_SYSTEM`）服务端过滤 + 按范围推水位，删客户端类型过滤。市集侧通知（`MARKET_ERRAND` / `MARKET_WANT`）在票 03 市集消息页落地前暂无前端消费面，属预期。
- 文档同步：`docs/db/chat.md` 补 notification 三表、`docs/api/chat.md` 补四端点、`CONTEXT.md` 补「通知收件范围 / 通知已读水位线」词条；`CommunityApiTestBase` TRUNCATE 清单补两新表。

**验证实测**：全量 329/329 绿（12:03 min，含新增 `NotificationReadModelIntegrationTest` 4 用例 + 迁移测试 2 用例）；`npm run build`（vue-tsc）绿。

**code-review 采纳项**：

1. **unread-count / read 的空白 scope 会 NPE→500**（双轴共同揪出）：`parseScope` 把 blank 转 null 是列表端点的口径，误用于必填端点。已加 `requireScope`（空白 → `PARAM_ERROR`「通知范围不能为空」），domain 接口 javadoc 补「scope 必填（非 null）」，测试补空白 scope 用例。
2. **测试弱断言**：`badScope.code ≠ "200"` 近恒真 → 改断言 `PARAM_ERROR` 具体码 `"400"` + 空白 scope 用例。
3. **计数器注释「0 基」与实现不符**（首号即 1）：schema.sql / 迁移 / docs/db 三处注释改为「首次取号建行 seq=1，从 1 起递增」。
4. **CONTEXT.md 词条缺失**：补「通知收件范围」「通知已读水位线」两条。

**已知波及 / 不在本票范围**：

- `insertBroadcast` 从逐行 auto-commit 改为单事务逐用户取号落库（msgId 幂等检查与落库一致）；全用户量级下广播事务持有一批 `notification_seq` 行锁，校园量级可接受，评审已确认非阻塞。
- Standards 轴提的「五处审核提醒块形状相同」属既有代码形状，本票只加参不重构；`MARKET_PRODUCT` 预留是票面明确要求非加码。
- 营销三类通知（`REFERRAL_GROUPED` / `LOTTERY_WON` / `POINTS_CHANGED` → `BOTTOM_SYSTEM`）由票 09 接入，本票不动三个营销服务。
- 联调开发库要跟上终态需手工跑 `sql/migrations/026_notification_read.sql`（存量按未读呈现属预期）。
