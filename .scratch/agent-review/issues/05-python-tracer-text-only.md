# 05: Python 服务最小 tracer（文字裁决端到端）

**What to build:** Python + LangGraph 服务首个端到端切片（代码落仓库顶层 `agent/`，与 backend、front 平级）：FastAPI（POST /review-task 同步裁决、GET /health），本地运行、Dockerfile 备用（沿用"compose 只编排中间件"的既有约定）。判规初版落 agent/guidelines.md（spec 附录为底稿，git 版本化），prompt 注入版本号。最小图：任务输入 → 文字审节点 → 结构化输出（with_structured_output + Pydantic 校验，json_object 模式兜底——主选模型无 strict json_schema）。编排层是唯一提交者：校验通过后由代码回调 Java internal API 提交裁决，模型对"是否提交"无自由度。供应商输入侧拦截错误码（data_inspection_failed 等）捕获 → UNCERTAIN 转人工、错误码记 raw_output。契约测试固化两服务入参。

**Blocked by:** 03, 04

**Status:** resolved

- [x] 判规 prompt 携带版本号，可回溯 agent_review_log.prompt_version
- [x] 关键词初筛漏网的变体违规文字 → agent 自动 REJECT，作者看到"枚举文案 + 整改方向"理由、无阈值泄漏
- [x] 正常文字内容 PASS → 端到端可见，双表留痕完整
- [x] 模糊内容 UNCERTAIN → 转人工队列 + 提醒
- [x] 输出 schema 不合法 → 重试后仍不合法 → 转人工
- [x] 供应商输入侧拦截 → UNCERTAIN 转人工，raw_output 记错误码
- [x] /health 探活可用；服务可本地一键启动
- [x] 契约测试（Java↔Python 入参/出参固化）通过

## Answer

- **目录结构**（仓库顶层 `agent/`，与 backend/front 平级）：`app/`（main 路由、settings、schemas、orchestrator、java_client）+ `graph/`（state、prompt、text_review 节点）+ `guidelines.md` + `contract/`（契约固化示例）+ `tests/` + requirements/.env.example/Dockerfile（备用，本地 `uvicorn app.main:app --port 8100` 一键启动，README 记录步骤）。
- **判规**：`agent/guidelines.md` front-matter `version: 1`，正文以 spec 附录为底稿扩写（红线/一般规则/必须转人工 + 理由写作规范禁止阈值泄漏 + 档案使用方式 + prompt 注入防御）。`graph/prompt.py` 加载并注入版本号，判规文本固定在 system prompt 最前（隐式缓存前缀命中，spec §11 成本红利）。
- **最小图**：StateGraph 单节点 text_review（票 06 在此扩展图审/工具）。结构化输出等价 `with_structured_output(method="json_mode")` + Pydantic 校验，显式实现以保留 raw usage_metadata（telemetry 的 tokens/cached_tokens/cache_hit 口径）并容忍代码围栏；schema 不合法带错误样本重试 1 次，仍不合法抛 SchemaInvalidError → HTTP 503 不提交（Java MQ 重试 3 次耗尽转人工）。空文字内容不空裁直接 UNCERTAIN（图审属票 06）。
- **编排层唯一提交者**：Orchestrator 生成 task_id（由 targetType:targetId:reviewRound 确定性派生 sha256 前 32 位——MQ 重试重投同内容同轮次拿同一 task_id，Java 侧 agent_review_log uk 幂等闸门"重放先到先得"才生效）→ 图裁决 → 组装 DecideRequest → JavaModerationClient 回调 `/api/internal/moderation/decide`（X-Service-Token 头，Java 业务错误也是 HTTP 200 故校验 body.code=="0000"）。模型对是否提交无自由度。503/502 全部映射为 Java 侧 MQ 重试语义；单次模型调用默认超时 50s（schema 重试最多 2 次调用 + Java 回调包在 Java 侧 120s 读超时内）。
- **供应商拦截**：捕获 data_inspection_failed / ip_infringement_suspect / SensitiveContentDetected / 1301（单个词边界正则匹配，纯数字 1301 防子串误判）→ UNCERTAIN 提交转人工，raw_output 记错误码。
- **契约**：入参模型镜像 `AgentReviewTaskPayload`（含作者档案全字段，lastRejectAt 以 ISO 字符串透传），出参镜像 `AgentDecideRequestDTO`；`agent/contract/` 两份示例 JSON 为快照（Java 侧为准，README 记同步义务），test_contract 双向断言（Java 示例可被 Pydantic 解析 / Python 序列化与 Java 示例逐字段相等）。
- **测试**：pytest 24 例全绿（ScriptedChatModel 脚本化模型输出全程不联网）：图层 PASS/REJECT/UNCERTAIN telemetry 断言、schema 重试成功与两连败、REJECT 缺 reason_code 判不合法、拦截转人工、通用 LLM 异常、空文字不调模型、超长 reason_text 截断；API 层端到端（回调请求头/路径/telemetry 逐字段）、task_id 重试间确定、Java 业务失败与 HTTP 失败映射 502、schema 不合法 503 不提交、422 入参校验；/health。真实 uvicorn 冒烟：health 200、缺字段 422、无 API key 时 LLM 失败正确落 503 graph_execution_error。
- **验证边界**：本票端到端指 Python 进程内"投递→裁决→回调报文"级验证（Java 侧执行/双表留痕/队列可见由票 03/04 集成测试按同一契约覆盖）；带真实模型与真实 Java 的跨服务联跑属票 09 e2e smoke 范围。
