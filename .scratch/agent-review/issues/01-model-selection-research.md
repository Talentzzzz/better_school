# 01-模型选型 research：OpenAI 兼容多模态模型对比

Type: research
Status: resolved
Blocked by:

## Question

发布审核 agent（Python + LangGraph，OpenAI 兼容接口）该选哪家国内可直连供应商的哪个多模态模型？需要外部事实支撑：

1. 2026-09 当前各家（智谱/通义/豆包/Kimi/DeepSeek 等）OpenAI 兼容端点与推荐模型名（图文理解主力款 + 高性价比款）
2. 输入 token 缓存（context caching）原生支持情况与计费折扣——审核场景判规 system prompt 每条内容重复，缓存折扣直接决定成本
3. 结构化输出（response_format json_schema / tool calling）支持程度
4. 价格对比（输入/输出每百万 token、图片计费方式）
5. 新账号速率限制量级
6. 用通用 chat 模型做审核任务的已知坑（供应商安全策略拦截等）

产出：`research/model-selection.md`（对比表 + top2 推荐）。结论回填 spec 的「模型选型」节与成本估算。

## Answer

结论已回填 spec §11，调研全文见 [research/model-selection.md](../research/model-selection.md)（2026-09-08 价格时点）。

- **主选：阿里百炼 Qwen3-VL**——初审 `qwen3-vl-flash`（¥0.15/¥1.5 每百万 token），复审升级 `qwen3-vl-plus`（¥2/¥8，一期可不开）。隐式缓存自动开启、命中价 20%（¥0.03/M），判规 system prompt >1024 token 稳定命中；成本测算约 ¥6.6/万帖，候选中最低。
- 备选：智谱 `glm-4.6v`（¥1/¥3，缓存 20%），`glm-4.6v-flash` 免费档供开发联调；第三候选豆包 1.6-vision（唯一 strict json_schema，输出 ¥8/M 贵 5 倍）。落选：Kimi（新账号并发 1）、DeepSeek（视觉实验版 + 涨价 + json_schema 400）。
- 两个落地坑：①各家网关在输入侧拦截真违规内容（阿里 `data_inspection_failed`、智谱 1301、豆包 SensitiveContentDetected），须捕获错误码转人工——已补进 spec §10/§11；②`qwen3-vl` 无 strict json_schema，用 `json_object` + Pydantic 校验兜底，不合法走既定降级。
