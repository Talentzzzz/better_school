# spec：发布审核接入 agent 裁决

Status: accepted (2026-09-08 评审确认)
Date: 2026-09-08
Map: [map.md](map.md)

## 1. 背景与目标

全站 UGC（帖子、评论、商品、求购、跑腿）已有统一审核口径：同步敏感词初筛 → 人工裁决（ADR-0004）。人工是唯一裁决者，评论等碎片化内容量大，人力是瓶颈。

本 spec 在管线中插入 **agent 裁决层**：多模态模型对通过初筛的待审内容异步裁决，大比例内容自动通过/拒绝，人工聚焦不确定样本、涉资金拒绝、抽检与覆盖。设计红线：**审核执行的信任边界一条不移动**——状态机、幂等、资金、通知全部留在既有 Java 用例，agent 只产生"裁决值 + 理由"。

## 2. 范围

**做**：五类内容（帖子/评论/商品/求购/跑腿）的发布审核接入 agent 裁决；理由口径全站枚举化（人工 UI 一并改）；留痕与成本核算；降级与开关。

**不做（后续 effort）**：
- 举报裁决（第二张图：举报是否成立、举报人档案、成立自动下架+扣分）
- 申诉机制
- agent 裁决联动信用分（拒绝 ≠ 举报核实，两套口径不混）
- 判例指纹查询工具、内容指纹缓存（成本走 token 输入缓存）
- 封禁/资金动作工具化（单一写入口原则）

## 3. 总体管线

```
发布请求
  → 同步敏感词初筛（保留现状，命中即报错不落库）
  → 落库为待审 + 同事务写 outbox（新增 topic bs-agent-review，五类内容统一）
  → Java 消费者：组装作者档案 → POST Python /review-task（同步等待，带超时）
      ├─ Python 正常：LangGraph 图裁决 → 编排层提交裁决
      │    POST Java /api/internal/moderation/decide
      │      PASS     → ReviewCase.decide(APPROVE, source=AGENT)   # 既有路径
      │      REJECT   → ReviewCase.decide(REJECT,  source=AGENT)   # 涉资金类型被 Java 侧拒绝，转人工
      │      UNCERTAIN→ 转人工队列 + 提醒审核员
      ├─ Python 不健康 / 超时 / 输出不合法：MQ 重试 N 次 → 转人工 + 提醒
```

- 发布接口零新增阻塞（异步裁决），待审语义不变：他人不可见、作者可见、通过才计数/通知。
- 评论旧 outbox 链路（`bs-comment-review`，现为"发布即提醒审核员"）调整：提醒时机移至**转人工**时刻，发布时不再提醒（agent 已接管第一裁决）。

## 4. 架构分工

### Java 侧（既有单体，唯一执行者）

- **outbox 生产**：五类发布用例同事务写 `outbox_message`（topic `bs-agent-review`，消息体：target_type/target_id/review_round）。
- **投递消费者**：读消息 → 组装作者档案（见 §6.2）→ HTTP POST Python 服务；失败按 MQ 重试，超过次数转人工。
- **internal API**：`POST /api/internal/moderation/decide`，service token（shared secret，yml 配置）认证 + 内网不对外暴露；入参 `{task_id, target_type, target_id, review_round, verdict, reason_code, reason_text, novel_risk, telemetry{model, prompt_version, input_tokens, cached_tokens, output_tokens, cache_hit, latency_ms, raw_output}}`——**`agent_review_log` 由 Java 在裁决执行时统一写入**（数据随回调携带，Python 无 DB 写权限，单一写入口不破）。
- **裁决执行**：复用 `ReviewCase.decide`，扩签名传 `source=AGENT`（现为写死 MANUAL，需改）；`operator_id` = 系统账号（迁移 014 内置 seed，yml 引用其 id）。幂等靠 `content_review` 既有 uk `(target,target_id,action,review_round)` INSERT IGNORE——agent 与人工竞态先到先得。
- **防越权校验（不信任 Python）**：跑腿/求购收到 `REJECT` 一律不执行，落人工队列；`reason_code` 不在枚举内同此处理。

### Python 侧（新服务，无状态编排者）

- FastAPI + LangGraph，无 DB 写权限，唯一下钻工具走只读 DB 账号。
- 端点：`POST /review-task`（同步裁决，超时约 120s）、`GET /health`。
- 编排层是**唯一提交者**：模型输出经 schema 校验后由代码 POST 回 Java——模型对"是否提交"无自由度。

### 部署

代码落仓库顶层 `agent/`（与 backend、front 平级的第三应用：app/ FastAPI 入口、graph/ LangGraph 图、guidelines.md 判规、evals/ 评测集、tests/）。与既有约定一致——compose 只编排中间件，应用本地运行，仅备 Dockerfile。env 注入模型 base_url/api-key、service token、只读 DB 账号。

## 5. 裁决权策略（代码内写死）

| 内容类型 | PASS | REJECT | UNCERTAIN |
|---|---|---|---|
| 帖子/评论/商品 | 自动通过 | 自动拒绝 | 转人工 |
| 跑腿/求购（涉资金） | 自动通过（含解冻/可见） | **不允许**（Java 侧拦截，转人工） | 转人工 |

策略为类型安全枚举映射（targetType → allowedVerdicts），不做 application.yml 运营配置。

## 6. agent 设计

### 6.1 图形态（发布裁决图）

```
任务输入 → 文字审 → 图审（vision，无图跳过）
        → 按需调工具[查作者近期内容]（怀疑导流/复读模式时下钻取证）
        → 结构化输出裁决（with_structured_output）
编排层：schema 校验 → POST Java internal API
```

- 裁决是图的**返回值**，不是工具调用；工具留给模型有选择权的动作。
- 工具失败（DB 超时等）→ 整图 UNCERTAIN 转人工，agent 不自行重试；重试在 Java 投递层。

### 6.2 任务输入（Java 组装）

内容：类型、id、审核轮次、文字字段、图片（文件内容直传或内网 URL）。

作者档案（每次必带，防"模型忘记查"）：
- 信用分、账号状态（正常/封禁）、账号年龄
- 审核历史：被拒次数、reason_code 分布、最近一次违规时间
- 发布频率：近 1h / 近 24h 发布数
- 近 30 天被拒率、各内容类型累计发布数
- 被举报次数（既有 `content_report` 计数，仅作输入）

工具 `查作者近期内容(user_id)`：该作者最近 20 条内容 + 各自裁决结果（只读 DB 账号直连）。

### 6.3 输出协议

```json
{ "verdict": "PASS | REJECT | UNCERTAIN",
  "reason_code": "POLITICAL | PORNOGRAPHY | AD_PROMOTION | PROHIBITED_ITEM
                 | FRAUD | HARASSMENT | INFRINGEMENT | OTHER",
  "reason_text": "≤120 字，指出违规片段与可整改方向",
  "novel_risk": true }        // 仅 UNCERTAIN 且发现判规外疑似违规时
```

- 三分类，无数值置信度（LLM 自报置信度不可靠，"不确定"即显式第三类）。
- `reason_text` 永远必填，不只"其他"才有：枚举保证口径可统计，text 保证作者知道哪违规、怎么改。

## 7. 判规分级与反哺

判规清单两级（初版见 §附录，实现时落 `agent/guidelines.md`——判规是 Python prompt 的直接输入、随 agent 服务部署，git 版本化，prompt 注入版本号）：

- **红线**：枚举硬性禁止类目（涉政有害、色情低俗、违禁品交易、诈骗导流、人身攻击、隐私侵犯）。模型必须服从，不得覆盖；认为红线有误 → UNCERTAIN + 说明，由人工确认。
- **一般规则**：模型按判规裁量（校园无关内容、低质重复、场景错位等）。

**新风险上报**：模型发现判规未覆盖的疑似违规 → 不自行扩权，`UNCERTAIN + novel_risk=true` 转人工；人工裁决时标注 **规则滞后 / 模型误判**（审核记录新增标注字段），反哺三出口：
1. 判规清单（`agent/guidelines.md` 新增/修订条文，版本 +1）
2. 同步初筛词库（admin 已有增删功能）
3. 评测集（该 case 入集，Python 侧回归）

原则：模型不绕规则，规则不永生。

## 8. 理由规范（给用户的解释）

- **要**：违规事实（哪个词/哪张图/哪段描述）+ 可整改方向（"删除联系方式后可重新发布"）。
- **禁止**：检测阈值与触发机制细节（"出现 3 次才拦"、"权重超 0.8"）、系统性规避路径（"换成拼音即可"）、内部规则编号。
- 落库口径：`枚举文案 + "：" + reason_text`，仍受 `content_review.reason` varchar(200) 约束。
- **理由口径全站统一**：人工审核 UI 同步改为枚举点选（+ 可选补充说明），人工与 agent 拒绝理由同构，作者看到的拒绝理由永远规整。

## 9. 数据模型（迁移 014）

1. **`agent_review_log`**（新表）：id、task_id（uk）、target_type、target_id、review_round、model、prompt_version、verdict、reason_code、novel_risk、input_tokens、**cached_tokens**、output_tokens、cache_hit、latency_ms、raw_output（text）、created_at。误判分析与成本核算的唯一依据。**写入方为 Java**（telemetry 随回调携带，见 §4）。
2. **系统账号 seed**：内置 agent 系统用户，`content_review.operator_id` 关联。
3. **审核记录标注字段**：`content_review` 加 `misjudge_mark`（NULL / RULE_LAG / MODEL_ERROR），仅人工覆盖 agent 判决时填写，反哺闭环的落点。
4. 不加 `content_hash`（判例工具已砍）。

## 10. 降级与开关

| 场景 | 行为 |
|---|---|
| yml 总开关 `agent.review.enabled=false` | 不发 outbox 审核 topic，全量人工（现状） |
| 总开关运行期关闭后的残留消息 | 消费者按转人工 + 提醒处理（票 04 实施补充） |
| Python `/health` 不健康 | 消费者直接转人工 + 提醒，不空转重试（探活短超时 3s） |
| 裁决超时（120s） | MQ 重试 N（=3）次 → 转人工 |
| 输出 schema 不合法 / reason_code 枚举外 | 该次作废，重试后仍不合法 → 转人工 |
| 工具（查作者近期内容）失败 | 整图 UNCERTAIN 转人工 |
| agent 与人工竞态（uk 冲突） | 先到先得，后到方静默跳过（INSERT IGNORE 语义） |

## 11. 模型选型

> 调研全文：[research/model-selection.md](research/model-selection.md)（2026-09-08 时点价格）

**主选：阿里百炼 Qwen3-VL（OpenAI 兼容端点）**

| 角色 | 模型 | 输入/输出（¥/百万 token） | 用途 |
|---|---|---|---|
| 初审 | `qwen3-vl-flash` | 0.15 / 1.5 | 全量首发裁决 |
| 复审升级 | `qwen3-vl-plus` | 2 / 8 | 高风险样本二次裁决（可选，一期可不开） |

- **隐式输入缓存自动开启，命中价 20%**（¥0.03/M）：判规 system prompt 注定 >1024 token、每条内容重复，稳定命中——这正是我们选"判规进 prompt"的成本红利。
- 成本测算约 **¥6.6 / 万帖**（含图文），全场候选最低。
- 备选：智谱 `glm-4.6v`（¥1/¥3，缓存同 20%）；其 `glm-4.6v-flash` 免费档（1 并发）供开发联调。第三候选豆包 1.6-vision（唯一官方 strict json_schema，但输出 ¥8/M 贵 5 倍）。
- `qwen3-vl` 不支持 strict json_schema → 用 `json_object` + Pydantic 校验兜底；不合法输出走 §10 既定降级转人工，无需额外设计。

**关键坑（纳入降级矩阵）**：各家网关都会在**输入侧**拦截真违规内容（阿里 `data_inspection_failed`、智谱 1301、豆包 SensitiveContentDetected）——真红线内容可能根本到不了模型。处理：捕获该错误码 → 直接 UNCERTAIN 转人工（供应商都拦的内容基本坐实红线，人工快速确认即可，不算浪费）。

| 场景（补 §10） | 行为 |
|---|---|
| 供应商输入侧拦截（data_inspection_failed 等） | UNCERTAIN 转人工，raw_output 记拦截错误码 |

## 12. 测试策略

- **Java 集成测试**（Testcontainers，沿用 174 个绿测基线）：internal API 鉴权（无 token/错 token 拒绝）、幂等（重放不双记）、agent×人工竞态、涉资金 REJECT 拦截转人工、开关关闭全人工、Python 不健康降级链路（fake adapter）。
- **Python**：pytest——图单元测试（fake LLM，断言节点流转与工具触发条件）、schema 校验、红线冲突输出 UNCERTAIN；与 Java 的契约测试（internal API 入参示例固化）。
- **评测集**：起步 30+ case（红线/一般规则/边界/历史真实判例脱敏），判规或模型变更必跑。

## 13. 验收标准

1. 发布帖/评 → agent 自动通过可见，全链路留痕（content_review source=AGENT + agent_review_log 全字段）。
2. 违规内容被自动拒绝，作者看到"枚举文案 + 整改方向"理由，无阈值泄漏。
3. 模糊内容 UNCERTAIN → 人工队列可见并有提醒。
4. 跑腿 agent REJECT 被 Java 拦截转人工，资金零动作。
5. 人工覆盖 agent 判决 → 新一轮 MANUAL 记录 + misjudge_mark 可填。
6. 杀掉 Python 服务 → 内容自动落人工，发布体验无感。
7. 总开关关闭 → 行为与现状完全一致。

## 附录：判规初版（实现时落 guidelines.md，你评审修改）

**红线（自动 REJECT，跑腿/求购除外——其 REJECT 转人工）**
1. 涉政有害、暴恐、煽动
2. 色情低俗（含擦边图片）
3. 违禁品交易：药品、管制器具、账号代充、代写代考、代检
4. 诈骗与校外导流：兼职刷单、钓鱼链接、校外资金盘、微信/QQ 拉人交易
5. 人身攻击、辱骂、歧视、网暴
6. 侵犯隐私：泄露他人实名、联系方式、住址、成绩等

**一般规则（模型裁量，边界转人工）**
1. 与校园生活无关的商业广告（校内社团/二手/失物招领除外）
2. 重复刷屏、无实质内容的水贴
3. 跑腿/商品描述与实际明显不符的夸大
4. 抄袭冒充他人作品

**必须转人工（禁止自动裁决）**
1. 红线边缘难以确认的（如讽刺/玩梗疑似影射）
2. 涉及具体个人纠纷、双方各执一词
3. 判规未覆盖但疑似有害（novel_risk）
