# 09: 全链路冒烟与验收

**What to build:** spec §13 七条验收标准逐条脚本化验证，feature 收官票：①发帖/评 agent 自动通过全链路留痕；②违规内容自动拒绝且理由规整无阈值泄漏；③模糊内容转人工有提醒；④跑腿 agent REJECT 被 Java 拦截、资金零动作；⑤人工覆盖产生新一轮 MANUAL 记录 + misjudge_mark；⑥杀掉 Python 服务内容自动落人工、发布无感；⑦总开关关闭行为与现状一致。

**Blocked by:** 07, 08

**Status:** resolved

- [x] 验收 1：agent 自动通过可见，content_review source=AGENT + agent_review_log 全字段
- [x] 验收 2：自动拒绝理由为"枚举文案 + 整改方向"，无阈值/规避路径泄漏
- [x] 验收 3：UNCERTAIN → 人工队列可见 + 提醒
- [x] 验收 4：跑腿 agent REJECT 拦截转人工，冻结资金零变化
- [x] 验收 5：人工覆盖 → 新一轮 MANUAL 记录 + misjudge_mark 可填
- [x] 验收 6：Python 进程被杀 → 内容落人工，发布接口无感
- [x] 验收 7：总开关关闭 → 与现状行为一致

## Comments

2026-09-09 实现（票 09）：

- **落点**：新增 `E2eSmokeAcceptanceIntegrationTest`（bs-app 集成测试），spec §13 七条标准一条一个 @Test（accept1~accept7），每条走完整链路——HTTP 发布 → outbox → 投递消费者驱动 → agent internal API `/api/internal/moderation/decide` 提交裁决（模拟 Python sidecar 回调）→ 外部可观察状态断言（HTTP 接口 + MySQL）。全量 213 测试绿（206 基线 + 7 新增）。
- **Python 侧模拟口径**：测试环境无真实 Python 进程（spec §12 既定），存亡以 `FakeAgentReviewPort` 探活位模拟（验收 6 杀进程 = healthy=false），裁决走真实 internal API 提交（非 fake 注入），MQ hop 沿用票 04 以来"手动驱动消费者"口径（Testcontainers 仅起 namesrv，broker 缺席 outbox 发送失败已捕获）。
- **验收 2 泄漏检查口径**：与 `agent/evals/runner.py` 的 §8 理由泄漏正则同源（阈值/权重/出现 N 次/红线编号/拼音），断言落库 reason 与作者详情页 rejectReason 均无泄漏。
- **验收 5 轮次语义**：按票 08 口径断言 MANUAL 行与 agent 裁决同轮次（review_round=1，改判链路按轮锚定），agent 原记录并存可追溯。
- **验收 7**：开关关闭断言三件事——不发 agent topic、不留 agent 日志、评论维持 `bs-comment-review` 旧链路；帖子人工路径照常（队列可见 + `/api/moderation/review` 通过）。
- **code-review 修复**：抽检断言补 `review_round`/`reason_code`（"全字段"补全）；队列断言抽 `queueTargetIds` 助手；`agentDecide` 对齐兄弟测试类 import 风格。
