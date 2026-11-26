# 08: 文档与收尾 — outbox/DB 文档、旧语义清理、集成自检

**What to build:** 文档与全仓一致性收尾：更新 outbox 文档的 msg_id 与 topic 清单，补 `action_rebate_claim` 表文档；清理「先同步发返利、失败再走 MQ」等过时注释；做一遍跨票静态一致性检查。

**Blocked by:** 06, 07

**Status:** ready-for-agent

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

- [ ] `PostRebateClaimTaskMessage` 的花括号数量与字段：`postId/userId/periodKey`
- [ ] `PostRebateCreditMessage`：`claimId/userId/postId/pointsValue/drawCountValue`
- [ ] `Constants` 中只有新 claim/credit topic，不再有 `POST_REBATE_TOPIC`
- [ ] `IRebateService.claimActionRebate` 与 case/consumer 调用参数顺序一致 `(actionType, userId, refId, periodKey)`
- [ ] `RebateClaimResultVO` 字段名在 case/controller 中一致（`apiState/claimId/refId/pointsValue/drawCountValue`）
- [ ] 仓储实现方法名与接口一致；mapper XML `namespace` 与 DAO 全限定名一致
- [ ] `schema.sql` 中 `action_rebate_claim` 表存在且列名与 PO/DAO 对齐
- [ ] 所有新增 Java 文件 import 无悬空（尤其 `RebateClaimState` / `RebateClaimApiState` / `RebatePeriodKeys`）
