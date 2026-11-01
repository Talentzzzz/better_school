# 审核 Agent 模型选型调研（多模态内容裁决）

> 调研日期：2026-09-08。场景：Spring Boot 校园 UGC 平台（帖子/评论/商品/跑腿）接入 LLM agent 做内容审核裁决（图文多模态），编排层 Python + LangGraph，模型经 OpenAI 兼容接口调用国内可直连供应商。
>
> 信息来源：各供应商官方文档/价格页（经搜索聚合交叉验证，来源链接附文末）。**标 [二手] 的为多来源交叉但未能直接抓取官方页面核实的条目**（WebFetch 在本环境被网络策略拦截）。各家价格调整频繁，落地前以控制台实时价为准。

---

## 1. 候选模型盘点（2026-09 在售）

| 供应商 | OpenAI 兼容端点 | 图文理解主力款 | 高性价比款 | 备注 |
|---|---|---|---|---|
| 阿里百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen3-vl-plus`（≤32K：¥1/M 入、¥10/M 出）；旗舰 `qwen3-vl-235b-a22b-instruct`（¥2/¥8） | `qwen3-vl-flash`（≤32K：¥0.15/M 入、¥1.5/M 出） | VL 系列支持思考/非思考模式；上下文最大 256K+ |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4.6v`（106B-A12B，128K，¥1/¥3 @≤32K） | `glm-4.6v-flashx`（¥0.15/¥1.5）；`glm-4.6v-flash` **免费**（1 并发） | GLM-4.6V 支持图片/视频/文档输入，原生 function call；GLM-4.5V 为上一代（¥2/¥6） |
| 字节火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` | `doubao-seed-1.6-vision`（≤32K：¥0.8/M 入、¥8/M 出，256K ctx，vision-250815）；`doubao-seed-2.0-lite` 全模态（¥0.6–1.8 入、¥3.6–10.8 出）[二手] | `doubao-seed-1.6-vision` 低档已是低价位；2.0-lite 更低 | Seed 1.6+ 支持 `detail: low/high/xhigh`；2.1-pro/turbo 为新一代旗舰（¥6/¥30、¥3/¥15） |
| 月之暗面 | `https://api.moonshot.cn/v1` | `kimi-k2.5`（原生多模态，¥4/M 入、¥21/M 出，缓存命中 ¥0.7，256K ctx） | 旧款 `moonshot-v1-8k-vision-preview`（¥2/¥10，8K ctx 太短） | `kimi-latest` 自带视觉但 ¥12–¥60/M，太贵；K3（¥20/¥100）非审核场景所需 |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-v4-flash-vision-exp`（2026-08-21 上线，**实验版**，计费同 v4-flash：高峰 ¥3/¥9，空闲减半，缓存命中 ¥0.1） | 无真正低价款 | 官方 API 此前长期无视觉；`deepseek-vl` 系列只有开源权重（需自部署或走 SiliconFlow）。单图 ≤32MiB、单请求 ≤600 张，图按尺寸折 token |

**结论**：DeepSeek 视觉线刚起步且是 exp 实验版 + 2026-08 大幅涨价（v4-flash 输出 +350%），Kimi 视觉单价偏高且新账号限流极低——两家从主力候选降级；真正可打的是 **Qwen3-VL、GLM-4.6V、Doubao vision 三家**。

---

## 2. 输入 token 缓存（context caching）

| 供应商 | 支持方式 | 缓存命中价（相对原价） | usage 返回字段 |
|---|---|---|---|
| 阿里百炼 | **隐式缓存自动开启**（前缀 ≥1,024 token 才可命中）+ 显式缓存 API | 命中部分 = 输入价 **20%**（qwen3-vl-flash：¥0.03/M）；显式缓存命中 10% 但创建费 125%（新千问云平台口径）[二手] | `cached_tokens`（`prompt_tokens_details` 内） |
| 智谱 | 价格表直接列"缓存命中"价（前缀自动缓存）[二手] | GLM-4.6V：¥0.2/M @≤32K 档 = **20%**；FlashX ¥0.03/M | usage 含缓存命中计费 |
| 火山方舟 | 前缀缓存自动；另有显式"上下文缓存对话 API" | doubao-seed-1.6-vision 命中 **¥0.16/M = 20%**，缓存存储 ¥0.017/M/小时；2.1 系列命中价同为 20% | usage 含缓存命中 |
| Moonshot | kimi-k2.5 / kimi-latest **自动上下文缓存** | K2.5 命中 ¥0.7/M ≈ **17.5%**；kimi-latest 命中固定 ¥1/M（8k 档仅 8.3%） | `cached_tokens` |
| DeepSeek | 磁盘自动缓存（无开关、无存储费） | v4-flash 命中 ¥0.1/M（高峰）≈ 未命中价的 **3.3%**，全场最低；但峰谷计价 | `prompt_cache_hit_tokens` / `prompt_cache_miss_tokens` |

**对审核场景的影响（量化）**：审核 agent 每条内容都复用同一段判规 system prompt（本场景约 1.5–2K token），且每条内容是独立单轮请求——只要 system prompt 放最前且字节级一致，前缀命中率理论上接近 100%，缓存把"固定部分"的输入成本砍到 20%（DeepSeek 砍到 3%）。

以"判规 prompt 2,000 token + 内容（文字+图折算）1,000 token + 输出 300 token"的单帖为例，缓存对输入固定部分的节省：

| 模型 | 无缓存输入成本/帖 | 有缓存输入成本/帖 | 节省 |
|---|---|---|---|
| qwen3-vl-flash | ¥0.00045 | ¥0.00021 | -53% |
| glm-4.6v | ¥0.003 | ¥0.0014 | -53% |
| doubao-seed-1.6-vision | ¥0.0024 | ¥0.00112 | -53% |
| kimi-k2.5 | ¥0.012 | ¥0.0054 | -55% |

（输出成本另计；Qwen 官方提示视觉场景缓存技巧：对不同图片问同一套判规，**把文本放在图片前面**可提升前缀命中。）

注意三点：① Qwen 隐式缓存要求前缀 ≥1,024 token，判规 prompt 必须写够长才有意义；② 火山方舟"推理限额"功能与前缀缓存互斥；③ 命中不保证，需以响应 usage 实测为准并做监控。

---

## 3. 结构化输出（verdict/reason_code/reason_text 强约束）

| 供应商 | `json_object` | `json_schema`（strict） | 审核 agent 落地建议 |
|---|---|---|---|
| 火山方舟 | ✅ | ✅ 官方**推荐 json_schema + strict:true**（Seed 1.6+ 系列标注"结构化输出推荐 json_schema 模式"）；TPM 保障包不支持 | 三家中结构化约束最强，LangGraph 可直接 `with_structured_output(method="json_schema")` |
| 阿里百炼 | ✅ 支持面最广（VL 模型非思考模式支持） | ⚠️ strict json_schema 目前仅覆盖较新文本系列（qwen3.7/3.8 等），**qwen3-vl 未明确支持**[二手] | VL 走 `json_object` + system prompt 内嵌 schema + LangGraph Pydantic 校验重试 |
| 智谱 | ✅（官方主推） | ⚠️ 官方文档主推 json_object + 提示词给 schema + 客户端 `jsonschema` 校验；json_schema 仅在第三方聚合平台对 glm-5+ 有支持[二手] | 同上，json_object + 校验重试 |
| Moonshot | ✅ JSON Mode | 未确认[二手] | json_object + 校验 |
| DeepSeek | ✅ | ❌ Chat API 传 `json_schema` 直接 400（"This response_format type is unavailable now"，社区 issue 多处证实）；且不建议 thinking 与 json_object 同开 | 只能 json_object + 提示词约束 + 解析兜底 |

三家头部（火山/阿里/智谱）对 tool calling 均已支持（doubao-seed-1.6-vision 支持工具调用的视觉推理；GLM-4.6V 原生 function call；qwen3-vl-235b 标注支持 Function Calling），LangGraph 里用 tool-call 结构化输出作为 json_object 之外的备选通道可行。

---

## 4. 价格对比（元/百万 token，按量付费，2026-09 口径）

| 模型 | 输入 | 输入（缓存命中） | 输出 | 分档 |
|---|---|---|---|---|
| qwen3-vl-flash | 0.15 | 0.03 | 1.5 | 32K–128K：0.3/3；128K+：0.6/6 |
| qwen3-vl-plus | 1 | —（同 20% 折扣口径）[二手] | 10 | 32K–128K：1.5/15；128K+：3/30 |
| qwen3-vl-235b-a22b-instruct | 2 | — | 8 | 北京地域不分档 |
| glm-4.6v | 1 | 0.2 | 3 | 32K–128K：2/6（命中 0.4） |
| glm-4.6v-flashx | 0.15 | 0.03 | 1.5 | 32K–128K：0.3/3 |
| glm-4.6v-flash | 免费 | 免费 | 免费 | 限 1 并发 |
| doubao-seed-1.6-vision | 0.8 | 0.16 | 8 | 32K–128K：1.2/16；128K–256K：2.4/24 |
| doubao-seed-2.0-lite | 0.6–1.8 | （2.1 系列命中价为 20% 口径）[二手] | 3.6–10.8 | 按输入长度分档 |
| kimi-k2.5 | 4 | 0.7 | 21 | 262K ctx 不分档 |
| deepseek-v4-flash（vision-exp 同价） | 高峰 3 / 空闲 1.5 | 0.1 / 0.05 | 高峰 9 / 空闲 4.5 | 2026-08-17 调价后，输出较调价前 +350% |

**图片怎么计费——全部按图折算成 token 并入输入，没有按张计费**：

| 供应商 | 图片→token 规则 |
|---|---|
| 阿里 | `token = h_bar × w_bar / token_pixels + 2`；Qwen3-VL 每 token 对应 32×32 像素（Qwen2.5-VL 为 28×28）；1024×1024 ≈ **326 token**；图片缩放到 [min_pixels, max_pixels] 预算内，`vl_high_resolution_images=True` 时上限 16,384 token |
| 火山 | `token = min(宽×高 ÷ 784, 单图上限)`；`detail:low` 上限 **1,312 token**、`high/xhigh` 上限 **5,120 token**；1280×720 ≈ 1,176 token |
| 智谱 | 官方未公开 GLM-4.6V 换算公式[二手]，以响应 `usage.prompt_tokens` 实测为准 |
| DeepSeek | 按尺寸折算，单图最多计 384 token[二手] |
| Moonshot | 按图折算 token（文档口径），上限见模型文档[二手] |

**单帖成本测算**（判规 2,000 tok 缓存命中 + 内容 1,000 tok 含 1 张 ~800 tok 图 + 输出 300 tok verdict JSON）：

| 模型 | 单帖成本 | 每万帖 |
|---|---|---|
| qwen3-vl-flash | ≈ ¥0.00066 | **≈ ¥6.6** |
| glm-4.6v-flashx | ≈ ¥0.00066 | ≈ ¥6.6 |
| glm-4.6v | ≈ ¥0.0023 | ≈ ¥23 |
| doubao-seed-2.0-lite | ≈ ¥0.0019 | ≈ ¥19 |
| doubao-seed-1.6-vision | ≈ ¥0.0035 | ≈ ¥35（输出价 8 元/M 占大头） |
| deepseek-v4-flash（高峰） | ≈ ¥0.0059 | ≈ ¥59 |
| kimi-k2.5 | ≈ ¥0.0117 | ≈ ¥117 |

（日均 1 万帖规模下，头部三个低价款全年成本都在几百元量级，成本不是决定性因素，质量与工程约束才是。）

---

## 5. 速率限制 / 并发配额（新账号默认档）

| 供应商 | 新账号默认 | 提额方式 |
|---|---|---|
| 阿里百炼 | 按 RPM+TPM：qwen3-vl-plus/flash 与 plus 同档宽松（plus 档参考 3 万 RPM / 500 万 TPM[二手]）；**快照版额度低**（如 60 RPM / 10 万 TPM），生产别锁快照 | 控制台查看，商务提额；Batch API 不限流 |
| 智谱 | 按**并发数**分权益等级 V0–V3：视觉 Flash 系列 V0 约 5 并发、FlashX 约 30；**免费款仅 1 并发** | 提升权益等级/付费 |
| 火山方舟 | 每模型 RPM+TPM（历史默认 60 RPM/6 万 TPM，新模型默认约 500 RPM/80 万 TPM[二手]）；**TPM 预扣机制**（按预估输出提前扣额度）；未充值/低充值账号高并发被限制 | 接入点级自定义；模型级提额走工单 |
| Moonshot | **Tier 0（¥0）：并发 1、RPM 3、TPM 50 万、TPD 150 万**——并发 1 对线上服务基本不可用；充 ¥50 升 Tier 1（并发 15、RPM 100、TPM 200 万） | 累计充值自动升级 |
| DeepSeek | v4-flash 并发限制约 2500（官方口径）；但 2026 年多次服务异常、算力紧张导致峰谷涨价 | — |

**审核场景判断**：审核是"每条 UGC 触发一次"的低频但持续流量，并发 3–10、TPM 几十万即可支撑日均数万帖（Qwen 快照档例外，别用快照版模型名）。Moonshot Tier 0 并发 1 直接出局；火山需先充值才能放开并发。

---

## 6. 内容合规：用通用 chat 模型做审核的已知坑

**核心矛盾：审核 agent 必须把违规内容喂给模型，而各家网关都有输入侧安全过滤，违规内容可能在"进模型之前"就被 400 拦截。** 各家错误码：

| 供应商 | 拦截错误码 / 表现 | 已知坑 |
|---|---|---|
| 阿里百炼 | `data_inspection_failed`（400，"Input or output data may contain inappropriate content"）、`ip_infringement_suspect` | **有真实误判案例**：长文本被误拦，调 prompt 无效，开发者靠把单段降到 ~700 字才跑通（阿里云开发者社区 2024-11）；官方建议捕获该错误优雅降级、勿把原始报错透给用户；可用 `X-DashScope-DataInspection` 头启用/配置 CIP 护栏 |
| 火山方舟 | `SensitiveContentDetected`（含 SevereViolation）、`InputText/ImageRiskDetection`、`OutputTextRiskDetection`；配套火山风控**自定义敏感词库**+拦截日志查询 | 风控偏保守；自定义词库是把双刃剑——平台方可以在控制台放宽自己的词库，但硬违规无法绕过 |
| 智谱 | **1301**（400）：输入或生成内容含不敏感/不安全内容即拦截，流式返回 `finish_reason:"sensitive"`；附 `contentFilter`（role + level 0–3） | 社区公开吐槽智谱"胆小敏感且怕事"（SegmentFail），对边缘内容过敏；官方建议安全测试需求走商务通道报备，避免企业账号被判违规 |
| Moonshot | 无公开的大规模输入拦截文档（相对低调），风控细节不透明 | 不透明本身是风险：无法预判拦截行为 |
| DeepSeek | 涉政类对齐极严是公认事实；审核场景下"模型拒答"与"网关拦截"双重风险叠加；且 2026 年服务稳定性受诟病 | 最不适合审核场景 |

**工程侧共识**（社区实践）：
1. 审核结果必须带"转人工"分支——网关 400 / 模型拒答 / 低置信三类都进人工队列，不能让拦截=放过。
2. 减少模型层误拦靠 system prompt 明确"审核员角色 + 只判给定维度 + 不过度解读"+ 灰色地带少样本示例；供应商侧误拦（网关层）只能靠分段/降长度/重试缓解。
3. **供应商自家的审核专用模型可作补充而非替代**：阿里百炼有 Qwen3Guard 护栏（基础版 ¥0.4/M token、pro ¥3/M token，另有按次计费图片审核 ¥30/万次）——本平台的"机审初筛"可以直接用这类护栏产品，LLM agent 只做需要推理的复杂裁决，还能规避"喂违规内容给 chat 模型"的拦截问题。
4. 合规红线：平台用 AI 做审核不触发《AI 生成合成内容标识办法》的标识义务（那是针对 AI 生成内容），但裁决记录、审计日志需留存备查。

---

## 7. Top 2 推荐

### 主选：Qwen3-VL（阿里云百炼）

- **低成本纵深**：`qwen3-vl-flash`（¥0.15/¥1.5）做日常主力，`qwen3-vl-plus` / `qwen3-vl-235b-a22b-instruct`（¥2/¥8）做疑难升级档，同一端点同一套代码只换模型名，LangGraph 里可做"flash 初审 → 旗舰复审"的级联路由。
- **缓存最省**：隐式缓存自动开启，命中价 20%，判规 prompt（>1,024 token）稳定命中；官方还有视觉场景提升命中率的位置指引。
- **工程友好**：OpenAI 兼容模式成熟（LangGraph/`langchain-openai` 即插即用）、默认 RPM/TPM 三家最宽、新账号每模型 100 万 token 免费 90 天够开发联调。
- **可补审核护栏**：同平台 Qwen3Guard 可承接机审初筛。
- **风险与对策**：① `data_inspection_failed` 输入拦截会拦掉部分真违规内容 → 捕获该错误码直接转人工（被平台网关拦本身就可以视为"高度可疑"信号）；② qwen3-vl 暂不保证 strict json_schema → 用 json_object + system prompt 内嵌 schema + Pydantic 校验失败重试（LangGraph 一条边的事）。

### 备选：GLM-4.6V（智谱）

- 价格第二低（¥1/¥3 @≤32K，缓存命中 20%），`glm-4.6v-flashx`（¥0.15/¥1.5）与 qwen-flash 同价可平替。
- **免费档 `glm-4.6v-flash` 是独有的开发红利**：联调、评测集回归、LangGraph 单测零成本（仅 1 并发，别上生产）。
- 视觉推理能力口碑好（开源 106B-A12B，支持图片/视频/文档），原生 function call。
- **风险与对策**：1301 内容拦截同样要捕获转人工；strict json_schema 非官方主推 → 同主选的 json_object+校验兜底；并发模型与阿里不同（按并发数而非 TPM），容量规划要换算法。

### 落选理由速记

- **Doubao-seed-1.6-vision（第三名，值得保留在候选池）**：三家中唯一官方推荐 `json_schema+strict` 的结构化输出、图片 token 上限明确（low 1,312/high 5,120，成本最好预估）、缓存 20%；但输出 ¥8/M 让单帖成本比 Qwen 贵 5 倍，TPM 预扣机制+未充值限并发对灰度上线不友好。如果后续发现 Qwen 结构化输出不稳，第一个迁就是它。
- **Kimi**：Tier 0 并发 1 直接不可用，K2.5 输出 ¥21/M 太贵。
- **DeepSeek**：视觉 exp 实验版 + 刚涨价 + 稳定性差 + json_schema 400，审核场景全维度不匹配。

---

## 8. 来源

- 智谱：速率限制（docs.bigmodel.cn/cn/api/rate-limit）、错误码与内容安全（docs.bigmodel.cn/cn/faq/api-code、/cn/guide/security/safety）、结构化输出（/cn/guide/capabilities/struct-output）、GLM-4.6V-Flash 免费模型页、bigmodel.cn/pricing（价格经 tokenrate.dev、302ai.cn、CSDN 多来源交叉）
- 阿里百炼：模型价格与模型详情（help.aliyun.com/zh/model-studio/qwen3-vl-plus、qwen3-vl-flash、qwen3-vl-235b-a22b-instruct）、Context Cache（/zh/model-studio/context-cache）、限流（/zh/model-studio/rate-limit）、新人免费额度（/zh/model-studio/new-free-quota）、结构化输出（/zh/model-studio/qwen-structured-output）、内容安全护栏（/zh/model-studio/content-security）、DataInspectionFailed 误判案例（developer.aliyun.com/article/1634549）、Qwen3Guard 计费（help.aliyun.com/zh/document_detail/2872704）、图片 token 计算（/zh/model-studio 图像 token 文档）
- 火山方舟：模型价格（docs.volcengine.com/docs/82379/1544106）、图片理解与 token 用量（/docs/82379/1362931）、结构化输出 beta（/docs/82379/1568221）、错误码（/docs/82379/1299023）、内容安全护栏（/docs/82379/1981525）、开通管理/限流（/docs/82379/1159200、ListModelRateLimit）、安心体验模式（/docs/82379/1465347）
- Moonshot：多模态模型 Kimi K2.5 定价与充值限速（platform.kimi.com/docs/pricing/chat-k25、/docs/pricing/limits）、Moonshot V1 定价（platform.kimi.com/docs/pricing/chat-v1）
- DeepSeek：模型与价格（api-docs.deepseek.com/zh-cn/quick_start/pricing）、Vision 指南（api-docs.deepseek.com/guides/vision）、JSON Output（/guides/json_mode）、2026-08 调价报道（界面新闻 jiemian.com/article/14933595.html、腾讯云 CloudBase 调价通知）、json_schema 400 issue（github.com/llm-exe/llm-exe/issues/662 等）
- 审核场景实践：AIGC 内容安全审核实践（CSDN 2026-03）、鉴冰 AI FENCE 误杀率分析（博客园 2025-10）、智谱过敏吐槽（segmentfault.com/a/1190000045509272）

> 核实建议：落地前在三家控制台各充值 ¥50 实测——真实 usage（图片折 token 数、缓存命中率）、1301/DataInspectionFailed 对真违规样本的拦截率、结构化输出稳定性，这三个数字只能实测得出。
