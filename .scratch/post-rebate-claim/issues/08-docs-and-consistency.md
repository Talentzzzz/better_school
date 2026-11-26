# 08: 文档与收尾 — outbox/DB 文档、旧语义清理、集成自检

**What to build:** 文档与全仓一致性收尾：更新 outbox 文档的 msg_id 与 topic 清单，补 `action_rebate_claim` 表文档；清理「先同步发返利、失败再走 MQ」等过时注释；做一遍跨票静态一致性检查。

**Blocked by:** 06, 07

**Status:** resolved

## 交付物

1. `backend/better-school/docs/db/outbox.md`
   - `msg_id` 说明：发帖领取任务 `post-rebate-claim:{postId}`、入账任务 `post-rebate-credit:{postId}`，均为确定性键。
   - `destination` 取值清单：去掉 `bs-post-rebate`，加入 `bs-post-rebate-claim`、`bs-post-rebate-credit`。
   - 「谁在写/谁在读」补一句：领取任务 outbox 由 post 仓储事务内插入、由 case 领取成功后标记 SENT；入账 outbox 由返利仓储领取事务内插入、提交后同步发送。
2. `backend/better-school/docs/db/README.md`
   - 表清单增加 `action_rebate_claim`，链接到新建的 `docs/db/rebate.md`。
3. 新建 `backend/better-school/docs/db/rebate.md`：`action_rebate_config` 与 `action_rebate_claim` 两表字段/索引/状态语义（含 `period_key` 日限口径 `Asia/Shanghai`、`points_value`/`draw_count_value` 快照、`uk_user_action_period` 与 `uk_user_action_ref` 的幂等分工）。
4. 清理过时注释（全仓 grep 确认）：
   - `sync 成功就不发 MQ` / `先同步调返利` / `PostRebateConsumer` / `bs-post-rebate` 旧语义；
   - `PostRebateTaskMessage` 类注释随票 06 删除，若仍有引用一并清。
5. `.scratch/post-rebate-claim/spec.md` 顶部 `Status:` 改为 `resolved`（仅当 01–07 全部 resolved）。

## 静态一致性检查清单（本环境无 JDK21/Maven，人工 grep）

- [x] `PostRebateClaimTaskMessage` 的花括号数量与字段：`postId/userId/periodKey`
- [x] `PostRebateCreditMessage`：`claimId/userId/postId/pointsValue/drawCountValue`
- [x] `Constants` 中只有新 claim/credit topic，不再有 `POST_REBATE_TOPIC`
- [x] `IRebateService.claimActionRebate` 与 case/consumer 调用参数顺序一致 `(actionType, userId, refId, periodKey)`
- [x] `RebateClaimResultVO` 字段名在 case/controller 中一致（`apiState/claimId/refId/pointsValue/drawCountValue`）
- [x] 仓储实现方法名与接口一致；mapper XML `namespace` 与 DAO 全限定名一致
- [x] `schema.sql` 中 `action_rebate_claim` 表存在且列名与 PO/DAO 对齐
- [x] 所有新增 Java 文件 import 无悬空（尤其 `RebateClaimState` / `RebateClaimApiState` / `RebatePeriodKeys`）

## Comments

### 2026-11-26

**Files**

- 修改 `backend/better-school/docs/db/outbox.md`：`msg_id` 说明补两段式确定性键 `post-rebate-claim:{postId}` / `post-rebate-credit:{postId}`（其余仍随机 UUID）；`destination` 取值清单加 `bs-post-rebate-claim` / `bs-post-rebate-credit`（原清单本就没有旧 `bs-post-rebate`）；「注意」段补两条：领取任务由 post 仓储在发布事务内插入（PENDING，不发送）、领取成功后标记 SENT，入账 outbox 由返利仓储在领取事务内插入、提交后同步发送。
- 修改 `backend/better-school/docs/db/README.md`：表清单新增 `|action_rebate_claim|[rebate](rebate.md)|行为返利领取记录（日限 + 快照）|`；头部营销表数 22 → 23、物理表总数 79 → 80（同句括注里的 marketing 22 一并改 23）。
- 新增 `backend/better-school/docs/db/rebate.md`：`action_rebate_config` / `action_rebate_claim` 两表字段、索引、状态语义、`period_key` 日限口径（`yyyyMMdd`，`Asia/Shanghai`，发布事务定死、重放沿用、旧载荷当天兜底）、金额快照与 0 语义、`uk_user_action_ref`（同 ref 幂等）与 `uk_user_action_period`（同周期日限 DENIED）分工、加锁读原因、谁在写/谁在读。
- 修改 `bs-domain/.../post/adapter/repository/IPostRepository.java`（仅 Javadoc）：`savePublished` / `submitDraftAsPublished` 明确事务内落领取任务 outbox（PENDING，不发送）+ 搜索同步，返回 postId + 数据库发布时间。
- 修改 `bs-domain/.../post/service/post/IPostService.java`（仅 Javadoc）：`publishPost` / `submitPostDraft` 同上口径。
- 修改 `bs-domain/.../rebate/service/IRebateService.java`（仅 Javadoc）：接口不再只是「只读规则」，补领取记录由本域 `claimActionRebate` 经仓储本地事务写入。
- 修改 `.scratch/post-rebate-claim/issues/01-types-schema-and-api-contract.md`：msgId 长度注记改为实际字符数（领取 18+36=54、入账 19+36=55）。
- 修改 `.scratch/post-rebate-claim/spec.md`：`Status:` → `resolved`（01–07 均已 resolved）。

**静态一致性检查（无 JDK21/Maven，未编译）**

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| `PostRebateClaimTaskMessage` 字段 | 通过 | `postId/userId/periodKey`，`@AllArgsConstructor` 顺序与 `PostRepository` 构造调用一致 |
| `PostRebateCreditMessage` 字段 | 通过 | `claimId/userId/postId/pointsValue/drawCountValue`，与 `RebateRepository` 构造调用一致 |
| `Constants` topic | 通过 | 只有 `POST_REBATE_CLAIM_TOPIC` / `POST_REBATE_CREDIT_TOPIC`；全仓 grep `POST_REBATE_TOPIC`、`bs-post-rebate`（旧裸 topic）、`PostRebateConsumer`、`PostRebateTaskMessage`、`grantPostRebate`、`PostRebateTaskKeys.of` 均零命中 |
| `claimActionRebate` 参数顺序 | 通过 | 接口与 `RebateServiceImpl` 均 `(actionType, userId, refId, periodKey)`；`PostRebateCase.claimForPost` / `replayClaim` 传 `(POST, userId, postId, periodKey)`；credit 消费只调 `PostRebateCase.credit` |
| `RebateClaimResultVO` 字段使用 | 通过 | `PostController.toPostPublishResponse` 用 `getRefId/getApiState/getPointsValue/getDrawCountValue`；`PostPublishCase` 用 `pending/postId`；VO 无字段改名 |
| 仓储方法名 / mapper namespace | 通过 | `IRebateRepository` 与 `RebateRepository` 方法一一对应；两个 rebate mapper namespace 分别为 `com.betterschool.infrastructure.dao.rebate.IActionRebateClaimDao` / `...IActionRebateConfigDao` |
| schema 列 vs PO/DAO | 通过 | `action_rebate_claim` 11 列与 `ActionRebateClaim` 11 字段、DAO insert 10 列 + `NOW()` 对齐；`action_rebate_config` 10 列与 `ActionRebateConfigPO` 对齐 |
| 悬空 import | 通过 | `RebateClaimState/RebateClaimApiState/RebatePeriodKeys/PostRebateClaimTaskMessage/PostRebateCreditMessage/PostRebateTaskKeys` 的 17 处 import 全部指向现存类 |
| 测试 publish 响应解析 | 通过 | `PostApiTestBase`、`AnonymousRemovalIntegrationTest`、`PostCoverFeedSummaryIntegrationTest`（发布侧两处）、`ViewHistoryIntegrationTest` 均 `path("data").path("postId")` |
| 前端类型 | 通过 | `PostCreateBody` / `createPost` / `submitPostDraft` 均返回 `PostPublishResult { postId, rebateState, pointsRebate, drawCountRebate }`，与 `PostPublishResponseDTO` 一致 |

**未修复（仅记录，未改行为/未扩范围）**

- `docs/db/README.md` 表数分项与 `schema.sql` 实际不一致：脚本统计 `CREATE TABLE` 共 77 条（36 张单体业务表 + 16 张 `timeout_task` + 25 张营销尾段），而非 41 + 16 + 23 = 80。本票按要求只把 22 → 23、79 → 80，未重算分项。
- `backend/docs/marketing/README.md` 的「行为返利（rebate）」仍链到 `db/points.md`，`backend/docs/marketing/db/points.md` 也只记 `action_rebate_config`、无 `action_rebate_claim`；新文档落在 `backend/better-school/docs/db/rebate.md`，两处未交叉链接（票面只要求 better-school 侧 README）。
- `backend/better-school/docs/db/post.md` 发布注意仍只写「同事务落搜索同步消息」，未提领取任务 outbox（本票未列该文件）。
- `backend/docs/marketing/api/points.md` 小节「行为返利入账（域内能力，暂无 MQ/HTTP 入口）」已过时：发帖返利现有 `bs-post-rebate-credit` MQ 入场。
- `front/src/api/community.ts` 的 `savePostDraft` 仍返回 `number`（后端返回 String postId）；spec 已把 postId number → string 全局清理列为 out of scope。

**未编译**：本机无 JDK21/Maven，验收以静态 grep/read 为准。
