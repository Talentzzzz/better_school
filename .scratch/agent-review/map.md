# map：agent 裁决（发布审核）

Label: wayfinder:map

## Destination

`.scratch/agent-review/spec.md` 定稿：发布内容（帖子/评论/商品/求购/跑腿）接入 agent 裁决机制的完整 spec——架构、裁决权边界、判规分级、输出协议、留痕、降级、成本口径全部可落地，拆票即可开发。

## Notes

- 领域：better_school 校园平台，全站 UGC 已有"同步初筛（关键词）+ 人工审核"管线；本 effort 在其中插入 agent 裁决层
- 后端 Spring Boot 3.3.5 / JDK 21 多模块 DDD（见 `backend/CLAUDE.md`）；agent 侧 Python + LangGraph，OpenAI 兼容接口
- 本会话已用 grilling + domain-modeling 敲完全部设计决策（见 Decisions so far，细节存 spec.md 对应章节）
- 工作流偏好：决策会话用 grilling 逼问收敛；`content_review.source` 已预留 AGENT，`ReviewCase.decide` javadoc 已预留系统账号
- 事实底座：`backend/sql/init.sql:322`（content_review）、`ReviewCase.java`、`CommentReviewConsumer.java`（仅评论有 outbox 链路）、迁移下一个 014
- **地图已抵达终点**：spec 已评审确认（Status: accepted），实施票 02~09 已发布（2026-09-08），票 02~08 已实现（2026-09-08~09），票 09 E2E 冒烟验收七条标准全绿（2026-09-09，全量 213 测试）——**feature 收官，frontier = 终点**；留痕写入权修正——agent_review_log 由 Java 写，telemetry 随回调携带

## Decisions so far

- [终点与范围](../spec.md)：终点 = spec.md；本次只做发布审核（举报裁决、申诉机制后续 effort）→ spec §范围
- [裁决权分类型](../spec.md)：帖子/评论/商品双向自动（PASS/REJECT），跑腿/求购单向（只自动通过，拒绝一律人工），UNCERTAIN 一律转人工；代码内策略枚举写死不做配置化 → spec §裁决权
- [管线位置](../spec.md)：关键词同步初筛保留为第一道 → agent 异步裁决 → 人工（不确定/涉资金拒绝/覆盖/抽检）；发布体验零阻塞 → spec §管线
- [架构分工](../spec.md)：Java 消费 outbox→MQ 并投递任务（HTTP），Python 无状态 LangGraph 服务编排裁决；所有状态流转/资金/通知留在 Java 既有用例（`ReviewCase.decide` 扩 source=AGENT）；单一写入口 = Java internal API + service token → spec §架构；ADR 已记 [ADR-0005](../../docs/adr/0005-agent-review-sidecar.md)
- [agent 形态](../spec.md)：任务输入自带作者档案（信用分/账号状态/账号年龄/违规历史/发布频率/被拒率/举报记录），工具仅 `查作者近期内容`(只读)×1，裁决 = 结构化输出（非工具调用），提交由编排层强制执行 → spec §agent
- [输出协议](../spec.md)：三分类 PASS/REJECT/UNCERTAIN + reason_code 枚举 + reason_text（永远要，指出违规片段与可整改方向；禁止暴露阈值与规避方式）→ spec §输出协议
- [判规分级](../spec.md)：红线（枚举硬规则，模型必须服从不得覆盖）与一般规则（模型裁量）两级；红线外新风险 → UNCERTAIN + novel_risk 上报，人工标注"规则滞后/模型误判"，反哺判规清单（版本化）、敏感词库、评测集 → spec §判规
- [留痕与成本](../spec.md)：`content_review(source=AGENT, operator_id=系统账号)` + `agent_review_log`（模型、prompt 摘要、input/cached/output tokens、耗时、verdict、cache_hit）；成本按 token 输入缓存口径核算 → spec §留痕
- [理由口径全站统一](../spec.md)：人工审核 UI 一并改枚举选型（+补充说明），人工与 agent 拒绝理由同口径 → spec §理由
- [兜底与开关](../spec.md)：人工可覆盖、按需抽检、LLM 超时/不可用/输出不合法转人工（重试在 Java 投递层）、yml 总开关 + Python 探活 → spec §兜底
- [多模态](../spec.md)：图文一并裁决（vision），不搞"有图转人工" → spec §管线
- [术语](../../CONTEXT.md)：新增 agent 裁决、转人工、抽检、红线、新风险上报、信用分；相关旧词条（先审后发/待审/先审后售/审核轮次）措辞已同步
- [模型选型](issues/01-model-selection-research.md)：主选阿里百炼 Qwen3-VL（初审 flash ¥0.15/¥1.5 + 隐式缓存命中 20%，约 ¥6.6/万帖），备选智谱 GLM-4.6V；供应商输入侧拦截坑已补进降级矩阵 → spec §模型选型
- [理由口径枚举化（票 02 已实现）](issues/02-reason-enum-unification.md)：`RejectReasonCode` 落 bs-types（8 code+中文文案）；`ReviewCase.decide` 签名扩 `(reasonCode, supplement)`，拒绝必填合法枚举、落库"枚举文案：补充说明"≤200；两人工 API DTO 的 `reason` 替换为 `reasonCode`+`supplement`；审核工作台改 chip 点选+可选补充；agent 票复用同一枚举 → spec §理由规范
- [Python sidecar 最小 tracer（票 05 已实现）](issues/05-python-tracer-text-only.md)：仓库顶层 `agent/` FastAPI+LangGraph 服务（GET /health、POST /review-task 同步裁决）；guidelines.md v1（front-matter version 注入 prompt，判规置 system 最前吃隐式缓存）；文字审节点 json_object+Pydantic 校验（schema 不合法重试 1 次→503 不提交）；编排层唯一提交者回调 internal API，task_id 由 (target,round) 确定性派生保 uk 幂等；供应商拦截四错误码→UNCERTAIN 记 raw_output；contract/ 固化两服务入出参 → spec §4/§6/§10/§11
- [agent 完整形态：图审 + 下钻工具（票 06 已实现）](issues/06-agent-full-multimodal-tools.md)：裁决图两节点——文字审 → 条件路由 → 图审 vision（仅文字 PASS 且带图时触发，图文一并终裁；REJECT/UNCERTAIN 短路省一次调用，无图不触发 vision）；`查作者近期内容` StructuredTool 绑定两节点（pymysql 只读直连，五类合并取 20 条+content_review 裁决挂载，只放开一轮）；工具失败→整图 UNCERTAIN（raw_output 记 tool_error）；telemetry 跨节点累加合并；guidelines.md 升 v2（配图审+工具使用+图内文字注入防御）→ spec §6/§10
- [评测集与判规回归（票 07 已实现）](issues/07-eval-set-guidelines-regression.md)：`agent/evals/` 38 case 回归门（红线/一般规则/边界/红线冲突/图审/历史判例脱敏，图审资产为脚本生成的 PNG）；红线冲突 case 固化"认为红线有误→UNCERTAIN 不覆盖"语义；`python -m evals.run` 一键跑出 case 级明细 + 理由泄漏检查，基线按 {model}-v{判规版本} 留存 `evals/baselines/` 供变更前后 diff；pytest 侧 `pytest -m evals` 逐 case 断言（默认离线套件排除）→ spec §12；真实跑（Ark glm-5.3-flash）完成判规迭代闭环实测：v2 首跑 35/38 暴露判规表述缺陷 → v3/v4 修订后 38/38（基线 glm-5.3-flash-v4.json，AGENT_MODEL_REASONING_EFFORT=low 提速 6 倍），详见票 07 Answer

## Not yet specified

- **口径一致性度量**：agent 与人工裁决的一致率怎么评（评测集怎么长出来、上线后抽检数据怎么回流成回归集）——依赖 spec 落地与真实数据，起步版在 spec §判规给最小方案，完整方案留 fog

## Out of scope

- **举报裁决**（第二张图：举报是否成立、举报人档案、成立自动下架+扣分）：用户明示"这次先做审核的，举报下次做"
- **申诉机制**：同上，用户明示后续再做
- **agent 裁决联动信用分**：拒绝≠举报核实，两套口径不混
- **封禁/资金动作工具化**：单一写入口原则，agent 只提交裁决值
- **判例指纹查询工具、内容指纹缓存**：grilling 中论证砍掉，成本走 token 输入缓存
