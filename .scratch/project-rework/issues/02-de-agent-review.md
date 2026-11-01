# 票 02 · 去 agent 审核，收敛为关键词过滤 + 人工

Type: task
Status: resolved
Blocked by: （无）

## Question

审核链路从「同步初筛 → agent 裁决 → 人工复核」收敛为「**同步敏感词初筛 → 人工审核**」，agent 相关全部删除：

- 删仓库根 `agent/` 目录（Python sidecar、guidelines、evals）。
- 删 Java 侧整条 agent 链路：bs-case `usecase/moderation` 的 agent 编排（AgentReviewCase / AgentReviewDispatchCase 等）、bs-infrastructure 的 outbox writer（bs-agent-review topic）、AgentReviewPort（HTTP 投递）、AgentReviewLogPO / AgentDecisionPO 及 mapper、相关 yml 开关（agent.review.enabled）。
- 保留：同步初筛（sensitive_word）、人工审核工作台链路、content_review（source 仅 MANUAL）、举报链路。
- 前端删 `/admin/spot-check` 抽检页；`/admin/review` 工作台保留。
- CONTEXT.md 词条修订：删除/改写「先审后发、agent 裁决、投递、作者档案、sidecar、查作者近期内容、评测集、转人工、抽检、红线、新风险上报」——先审后发保留但描述改为关键词+人工。
- 验收：五类内容（帖子/评论/商品/跑腿——求购与多人单由票 03/04 删）发布后走敏感词初筛进人工队列，审核工作台可裁决；相关测试改绿。

## Answer

已删除，审核链路收敛为「同步敏感词初筛 → 人工」，content_review 仅 MANUAL：

- 仓库根 `agent/` 整目录删除（Python sidecar、guidelines、evals）；`docs/adr/0005-agent-review-sidecar.md`、`docs/moderation-feedback-loop.md`（反哺闭环全流程文档）一并删除。
- Java 侧整条 agent 链路删除：bs-case 的 AgentReviewCase / AgentReviewDispatchCase / AgentDecideCommand / ReviewOverrideCase；bs-domain 的 IAgentReviewPort / AgentReviewProperties / AgentDecisionEntity / AgentReviewLogEntity / AgentReviewTaskEntity / AuthorProfileEntity / AgentReviewTaskMessage / AgentVerdictVO；bs-infrastructure 的 AgentReviewPort（+payload DTO）/ AgentReviewOutboxWriter / AgentDecisionPO / AgentReviewLogPO / AuthorProfilePO；bs-api 的 IInternalModerationService 与 4 个 Agent*DTO；bs-trigger 的 InternalModerationController / AgentReviewDispatchConsumer；bs-types 的 MisjudgeMark。
- 随抽检删除的连带链路：五仓储 override*（覆盖改判）状态流转全链（service → repository → DAO → mapper XML）、SecurityConfig 的 `/api/internal/**` permitAll、Constants.AGENT_REVIEW_TOPIC、虚拟线程注册器 bs-agent-review-group 容量孤例（DOWNSTREAM_CAPACITY 现为空 Map，全部消费者按连接池取值）。
- 人工链路收窄：ReviewCase.decide 去掉 source 参数（恒 MANUAL）；ReviewSourceVO 去掉 AGENT 值；IReviewService / IModerationRepository / IModerationDao / ModerationMapper.xml 删去 agent 留痕、作者档案、抽检查询；评论 saveReviewMessage 恒走 bs-comment-review「发布即提醒」旧链路。
- 五类发布侧（post/comment/product/errand/want-to-buy 仓储）删去 writeIfEnabled outbox 调用，发布即进人工队列行为不变。
- SQL：schema.sql 删 agent_review_log 表、系统 agent 账号 seed（999999999）、content_review.misjudge_mark 列（对齐 sql.md：source 注释只留 MANUAL/KEYWORD/RANDOM）；迁移 014 删除，015 头注释去 agent 表述。
- 前端：SpotCheckView.vue、路由 `/admin/spot-check`、个人页「agent 抽检」入口、moderation.ts 三个 agent API、AgentDecision/MisjudgeMark 类型删除；`/admin/review` 工作台保留。
- CONTEXT.md：删「agent 裁决、投递、作者档案、sidecar、查作者近期内容、评测集、转人工、抽检、红线、新风险上报」十条；「先审后发」改写为敏感词初筛 + 人工；「评论未通过」「信用分」去 agent 表述。
- 验证：五模块主代码 + 测试编译通过；`scripts/test-env.sh reset-test-db` 重建测试库（去 agent 表/列）后 `mvn test` 全绿（331 tests，0 failures 0 errors）；前端 `vue-tsc -b` 通过；grep 全仓（活文档/源码）agent 审核语义零残留（ADR 0002/0003/0004 历史记录按票面留档不动）。
