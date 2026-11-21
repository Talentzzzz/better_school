# 03: 文档对齐（行为描述零改动，归属描述随新结构修订）

**What to build:** 文档面与新分层口径对齐：`docs/api/chat.md` WS 节的行为描述（帧协议、互踢、判死、补拉兜底）零改动，仅把「经 WsPushRouter」等 trigger 实现名词改写为投递适配器经 `IWsPushPort` 归属 infra、推送决策归属 case 的新口径；`docs/api/notify-channels.md` 修正「复用 ChatConnectionRegistry」失真表述（实际经推送路由投递）与「HTTP 转发」残留字样；`CONTEXT.md` 连接票据/心跳判死/互踢/增量补拉相关词条按新分层归属修订。

**Blocked by:** 02 (fanout-decision-to-case)

**Status:** resolved

- [x] chat.md WS 节：行为描述零改动，实现归属叙述按新结构修订（连接层留 trigger / 投递层 infra / 决策 case）
- [x] notify-channels.md：失真表述与旧方案残留字样修正，关键文件表随搬迁更新
- [x] CONTEXT.md 相关词条：分层归属口径与代码一致，无两套说法

**落地注记**（票 03，worktree t03，提交 `ed66149`）：

- `docs/api/chat.md` L76 多实例推送路由条目：仅改归属叙述——「推送消费方经 `WsPushRouter`」→「推送决策在 case 推送服务（按会话成员扇出、组帧），投递统一经 domain 端口 `IWsPushPort` 收口到 infra WS 推送适配器（Redis 路由表与 pub/sub 频道均为其内部细节）」；Redis 路由表键、TTL、CAS 注销、self-endpoint 单机形态等行为措辞一字未动。
- `docs/api/notify-channels.md`：BANNER 通道删「复用 `ChatConnectionRegistry`」失真表述，改为消费者（反序列化 + 调 case）→ `NotifyPushCase` → `IWsPushPort` → infra 适配器；ORDER 通道补 `ErrandOrderCase.pushStatus` 双方投递决策；关键文件表消费方行加「反序列化 + 调 case」注记，新增推送决策 / WS 投递端口两行，WS 帧行补 `ChatWsFrameType` 实际路径。「HTTP 转发」残留与 `ErrandOrderCase.loadOrder` 引用 grep 零命中（本就不存在）。
- `CONTEXT.md` 连接票据 / 互踢词条加轻量归属注记（握手拦截器经 `IWsTicketPort`、注册表走 `IWsConnectionRegistryPort` 由 infra 落地）；心跳 / 增量补拉词条为纯行为描述且无归属名词、与代码无冲突，保持不动。
- 全 docs 树 grep（WsPushRouter / ChatWsPushService / WsPushSubscriber / IWsRoutePort / IWsPushBroadcast* / ChatConnectionRegistry）：修订前 2 处命中（chat.md L76、notify-channels.md L50），修订后 docs + CONTEXT.md 零残留；`.scratch/` 下命中均为历史留档，不在范围。
