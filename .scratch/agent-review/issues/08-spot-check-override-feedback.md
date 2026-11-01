# 08: 抽检、人工覆盖与反哺闭环

**What to build:** 对 agent 裁决的人工监督闭环。审核端可查 agent 判决明细（agent_review_log 查询接口 + 审核前端入口：verdict、理由、token 用量、耗时）；人工覆盖 agent 判决 → 产生新一轮 MANUAL 审核记录（既有审核轮次机制）；`misjudge_mark`（RULE_LAG / MODEL_ERROR）填写入口与校验——仅覆盖 agent 判决时可填；反哺三出口（判规清单修订、敏感词库增删、评测集补 case）的操作流程成文。

**Blocked by:** 03, 05

**Status:** resolved

- [x] 审核端可查 agent 裁决明细（含 tokens/耗时/原始输出）
- [x] 人工覆盖 agent 判决产生新一轮 MANUAL 审核记录，内容状态正确流转
- [x] misjudge_mark 仅在覆盖 agent 判决时可填，值域校验生效
- [x] 反哺三出口操作流程文档化
- [x] 集成测试覆盖覆盖改判与标注校验

## Comments

2026-09-09 实现（票 08）：

- **"新一轮 MANUAL 审核记录"的落地口径**：业务行未重提，`review_round` 不变；覆盖改判在**当前轮次插一条与 agent 记录不同 action 的 MANUAL 行**（content_review uk 天然允许同轮 agent/人工并存，agent 原记录保留，改判链路可追溯）。一轮至多一次人工覆盖（写入前查 `existsManualReview`）；与 agent 原裁决同向 = 确认（幂等静默成功、不新增记录、不得带标注）。
- **覆盖类型边界**：仅 POST/COMMENT/PRODUCT 可改判（NORMAL↔REJECTED / ON_SALE↔REJECTED 条件 UPDATE，帖子/商品恢复可见同事务落搜索同步，评论恢复可见补互动通知、计数 ±1）。ERRAND/WANT_TO_BUY 涉资金不支持——agent REJECT 本就被拦截走人工队列，agent PASS 后内容已上线进入履约/资金语义，无既有"审核拒绝"路径可复用（spec 信任边界红线），强行改判等于新增下架+退款动作，归入后续举报裁决 effort；接口对两类明确报业务错误。
- **新接口**：GET `/api/moderation/agent-decisions`（列表：同目标同轮最新 log JOIN 业务表与执行口径）、GET `.../{logId}`（详情含 rawOutput）、POST `.../{logId}/override`（覆盖改判）。用例 `ReviewOverrideCase`，misjudge_mark 值域 RULE_LAG/MODEL_ERROR 枚举校验，仅真正改判时可填。
- **反哺文档**：`docs/moderation-feedback-loop.md`（标注→判规修订 version+1→词库增删→评测集补 case 的操作流程与检查清单）。
- **测试**：集成测试 7 例（改判双向状态流转/标注落库/通知、值域与场景校验、涉资金拦截、抽检查询与鉴权），全量 206 测试绿；UI 已浏览器实测（列表/详情/改判双向/标注/覆盖后锁定）。
- **踩坑记录**：本地 dev 库此前未应用迁移 014/015，本次补齐（agent_review_log、misjudge_mark、reason_code）。
