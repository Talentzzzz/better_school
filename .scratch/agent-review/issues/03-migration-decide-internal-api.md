# 03: 迁移 014 + Java 裁决执行扩展 + internal API

**What to build:** agent 裁决的执行侧地基。迁移 014：`agent_review_log` 表（留痕与成本核算唯一依据）、系统 agent 账号 seed、`content_review` 加 `misjudge_mark` 列（NULL / RULE_LAG / MODEL_ERROR）。`ReviewCase.decide` 扩 `source` 参数（现为写死 MANUAL）。新增 internal API（service token 鉴权 + 内网不对外）：agent 提交裁决值 + telemetry，Java 统一写 `agent_review_log` 并执行既有裁决路径。防越权校验（不信任 agent 侧）：跑腿/求购收到 REJECT 一律不执行、落人工队列、资金零动作；reason_code 枚举外同此处理。幂等靠 content_review 既有 uk INSERT IGNORE——agent 与人工竞态先到先得。

**Blocked by:** 02

**Status:** resolved

- [x] 无 token / 错 token 调 internal API 被拒绝
- [x] agent PASS / REJECT → 状态流转、通知、缓存失效走既有用例路径，content_review 记 source=AGENT、operator=系统账号
- [x] 跑腿/求购 REJECT → 不执行、内容落人工队列、资金零动作
- [x] reason_code 枚举外 → 不执行、落人工队列
- [x] 同 uk 重放裁决不双记（先到先得）
- [x] 每次 agent 裁决写一条 agent_review_log（task_id uk、tokens、cached_tokens、latency_ms、raw_output 全字段）
- [x] 迁移测试 + 集成测试全绿（沿用 180 个绿测基线，现 193）

## Answer

- **迁移 014**（`sql/migrations/014_agent_review_log.sql`，init.sql 同步）：① `agent_review_log`（task_id uk 幂等 + idx_target/idx_created，verdict 落原始裁决 PASS/REJECT/UNCERTAIN，reason_code 枚举外原样落库供误判分析）② `content_review.misjudge_mark`（NULL/RULE_LAG/MODEL_ERROR，可空存量不回填）③ 系统 agent 账号 seed（id 固定 999999999、phone 99999999999、INSERT IGNORE，yml 引用）。`AgentReviewLogMigrationTest` 旧库内联重建 bs_user+content_review 执行真实迁移文件验证全字段可写 + uk 幂等 + seed。
- **ReviewCase.decide 扩 source**：新增 7 参 `decide(..., operatorId, source)`，原 6 参重载默认 MANUAL（人工入口零改动）；approve/reject 内部 10 处 recordReview 全部透传 source 落 `content_review.source`。缓存失效不在 ReviewCase（域仓储写路径内既有），通知/状态流转/资金动作对来源无差别。
- **internal API**：`POST /api/internal/moderation/decide`（`InternalModerationController`，实现 `IInternalModerationService`）。鉴权 = `X-Service-Token` 头常量时间比较（yml `agent.review.service-token`），SecurityConfig 放行 `/api/internal/**` 不走 JWT；"内网不对外"为部署层约束（文档注明）。分层：bs-case 不依赖 bs-api，case 层定义 `AgentDecideCommand`（含嵌套 Telemetry record），trigger 层 DTO→Command 映射。
- **AgentReviewCase**（case 层新用例，internal API 唯一入口）：① verdict/targetType 合法性 + REJECT 缺 reasonText 400（输出协议 reason_text 永远必填）② `reviewDetail` 存在性校验 + 轮次业务表锚定（不信任 agent 上报的 reviewRound）③ **log-first 幂等闸门**：`agent_review_log` task_id uk INSERT IGNORE 先落（返回 false = 同 task 重放，静默跳过执行）④ 按裁决权策略执行：PASS→decide(APPROVE, AGENT)；REJECT+帖子/评论/商品+reasonCode 合法→decide(REJECT, AGENT)；跑腿/求购 REJECT（FUND_INVOLVED_TYPES 代码内写死）、reasonCode 枚举外、UNCERTAIN 三出口不执行转人工——内容保持 PENDING_REVIEW（审核队列=业务表状态直查，天然可见），资金零动作、不落 content_review。telemetry 可选 tokens 缺省按 0 计（成本口径宁小勿大）。执行失败 log 已落的取舍：重放被闸门跳过，由投递层（票 04）重试耗尽转人工兜底。
- **配置**：`agent.review.service-token` / `agent.review.operator-id: 999999999` 进 application.yml。
- **测试**：`AgentDecideInternalApiIntegrationTest` 12 例（无/错 token 拒绝 / PASS 帖子全链路留痕+通知 / REJECT 理由格式+通知 / 跑腿 PASS 放行 / 跑腿 REJECT 拦截资金零动作 / 求购 REJECT 拦截 / 枚举外 code 转人工原样落 log / UNCERTAIN novel_risk 留痕 / 同 task 重放不双记 / 不同 task 同 uk 先到先得 / verdict 枚举外 400 / REJECT 缺 reasonText 400），全量 193 集成测试绿（基线 180 + 新增 13）。
