# Spec：chat WS 推送链路分层归位（连接层留 trigger，投递层下沉 infra，成员决策上收 case）

Status: ready-for-agent

> 本 spec 由 2026-11-20 会话讨论收口而来。讨论中确认的口径：外部契约（HTTP / WS 帧协议 / 互踢 / 判死 / 补拉兜底）零变化；备选的「最小痛点版」（只挪 ChatWsPushService）已被用户否决，本票按整体下沉实施。

## Problem Statement

仓库的分层规则是：trigger 只做入站适配（HTTP Controller、MQ Listener、WS 入站连接），业务决策在 case/domain，出站能力藏在 domain 端口后由 infra 实现。chat WS 推送链路现状有四处违反这条规则：

1. **trigger 越过 case 直接摸仓储**：`ChatWsPushService` 住在 trigger，直接依赖 `IChatRepository`——「查会话成员、排除发送者本人」这些是业务决策，却发生在 trigger 层；
2. **投递机器住在 trigger**：`WsPushRouter`、会话注册表（心跳判死）、连接注册表（互踢）、帧写出器（并发写保护）都是「把一帧送达到某个用户」的出站能力，与发邮件、写库同性质，应藏在端口后面，目前却与入站适配器混在同一层；
3. **domain 感知了 infra 细节**：`IWsRoutePort` / `IWsPushBroadcastPort` 把「Redis 路由表 + pub/sub 定向频道」的语义直接暴露给 domain——Redis 只是 WS 投递适配器的实现手段，domain 不该知道它的存在；
4. **扩展无统一端口**：将来加第二个推送通道（邮件、小程序订阅消息等）时，业务域没有可实现的统一推送端口，得继续绕过 case 直接调 trigger 内部件。

## Solution

把 WS 拆成两半各归其位：

- **连接层（入站）留 trigger**：WS 端点的连接处理器与票据握手拦截器原样保留——它们接收外部连接与帧，和 HTTP Controller、MQ Listener 同性质；
- **投递层下沉 infra**：会话注册表、连接注册表、帧写出器、推送路由器、实例订阅器整体搬入 infra，打包为一个 WS 推送适配器，实现 domain 新端口 `IWsPushPort`；Redis 路由表与 pub/sub 降级为该适配器的内部实现细节，对应旧端口从 domain 端口族中移除；
- **成员决策上收 case**：新增推送 case 服务承接「按会话成员扇出、MSG/READ 排除触发者、RECALL 推全员」的决策逻辑，MQ 消费者只做反序列化 + 调 case；
- **连接交接走端口**：连接处理器在建连/断连时通过 domain 端口把 session 交给 infra 登记/注销，trigger 不再自己持有注册表。

对使用者完全不可见：帧协议信封、端点路径、票据握手、互踢（KICK + close 4001）、心跳判死参数、timeline 补拉兜底全部不变。

## User Stories

1. As a 收消息用户, I want 对方发来的消息继续以 MSG 帧实时推到我正在打开的会话, so that 不刷新页面也能看到新消息
2. As a 发消息用户, I want 我发出的消息不再回推给我自己（发送方以 HTTP 200 为准）, so that 界面不出现自己消息的双份
3. As a 打开会话的用户, I want 我的已读动作以 READ 帧推给对方, so that 对方看到「已读」是即时的
4. As a 撤回消息的用户, I want RECALL 帧推给会话全部在线成员, so that 撤回对双方同时生效
5. As a 在另一台设备登录的用户, I want 旧设备继续收 KICK 帧并被 close(4001) 踢下线, so that 单设备互踢语义不变
6. As a 断网恢复的用户, I want 重连后仍能按 (roomId, seq) 判重并经 timeline 补拉追平漏推, so that 推送通道重构不影响可靠性兜底
7. As a 后端维护者, I want trigger 层不再出现任何对 chat 仓储的依赖, so that 分层规则「trigger 只做入站适配」恢复一致
8. As a 后端维护者, I want 所有「把帧送达到用户」的机器（注册表/写出器/路由器/订阅器）搬进 infra, so that trigger 里只剩连接处理器与握手拦截器两个入站件
9. As a 后端维护者, I want MQ 推送消费者只做反序列化 + 调 case, so that 消费者薄到没有可测的业务分支
10. As a 后端维护者, I want 「按成员扇出、排除触发者」的决策收进 case 服务, so that 推送策略有唯一的业务归属地
11. As a 后端维护者, I want domain 只暴露一个 `IWsPushPort` 给推送链路, so that 业务域对「怎么送」零感知
12. As a 后端维护者, I want Redis 路由表与 pub/sub 频道降级为 infra 适配器内部细节, so that domain 端口族不再出现 Redis 语义
13. As a 未来要加第二个推送通道的维护者, I want 新通道按同一 `IWsPushPort` 写适配器, so that 加通道不改业务代码
14. As a 未来要加第二个 WS 端点的维护者, I want 支撑骨架（票据握手、JSON 写出、注册表、判死）的设计意图保留, so that 复用路径不被这次搬迁破坏
15. As a 集成测试维护者, I want 既有 chat WS 集成测试族零改动语义、只随代码搬迁调整装配, so that 行为等价由既有断言背书
16. As a 单测维护者, I want 注册表/写出器的既有单测随代码搬进 infra 且断言不变, so that 搬迁是纯位移而非重写
17. As a 文档读者, I want chat.md 的 WS 节与 CONTEXT.md 相关词条对齐新分层口径, so that 文档与代码不再有两套说法
18. As a 运维者, I want 多实例推送路由的配置项语义不变（留空即单机形态）, so that 部署形态切换不受重构影响

## Implementation Decisions

- **模块切分（纯位移，不改行为）**：
  - trigger 保留：WS 连接处理器、chat 票据握手拦截器、三个推送消费者（MSG/READ/RECALL）与既有 notify/order 推送消费者、WS 端点注册配置；
  - trigger 移除：推送服务（成员决策部分上收 case）、推送路由器、会话注册表、连接注册表、帧写出器、实例订阅器及其支撑骨架；
  - case 新增：chat 推送 case 服务——按事件类型组装帧（MSG/READ/RECALL 信封与 payload 现口径）、经 chat 仓储解析会话成员、排除触发者（MSG/READ）、逐成员调 `IWsPushPort.deliver`；消费者改为反序列化事件 + 调 case；
  - domain 新增：`IWsPushPort`（按 userId 投递一帧）与连接登记端口（建连注册/续期/断连注销，含代际 token 的 CAS 注销语义随端口下沉）；`IWsRoutePort` / `IWsPushBroadcastPort` 从 domain 端口族**删除**；
  - infra 新增：WS 推送适配器（打包会话注册表、连接注册表、帧写出器、多实例路由器、实例订阅器与 Redis 路由/pub-sub 实现），实现上述端口；支撑骨架四个抽象类随投递层物理下沉。
- **帧协议零变化**：信封 `{type, roomId?, seq?, payload?, ts}`、C→S 仅 PING、KICK + close(4001)、心跳 25s / 判死 60s / 容器兜底 90s、`bs:ws-route:*` / `bs:ws-push:*` key 口径、TTL 与续期规则、配置项 `better-school.ws.route.*`（留空 = 单机形态）全部维持现状。
- **判重与兜底零变化**：推送仍为尽力而为，漏推合法；客户端 (roomId, seq) 判重 + timeline 补拉兜底不因本次重构有任何改动。
- **notify / order 推送同规则归位**：站内通知与订单推送消费者按同一模式改为调 case（各自的扇出决策随 case 归位），不单独开第二套口径。
- **既有单测随代码搬移**：会话注册表、连接注册表的单元测试物理搬进 infra，断言与语义不变。
- **文档面**：chat.md 的「WS 长连接」节、CONTEXT.md 相关词条、相关 api/db 文档注记按新分层口径修订（行为描述不变，只改归属描述）。

## Testing Decisions

- **只测外部行为**：不断言内部实现、不 mock 内部 Bean——判据是帧协议、互踢、判死、路由表可观察状态，而非类搬到了哪。
- **缝（seam）零新增（用户拍板）**：沿用既有最高缝——
  - chat WS 集成测试族：起完整上下文、打真实 WS 连接、走真实票据握手，断言连接建立/互踢/心跳判死/下行帧；
  - 消费者直调缝：构造 payload 直调推送消费者 Bean，断言下行帧与 Redis 路由表/频道的可观察状态（既有先例口径）；
  - 既有 ws 注册表单元测试随代码搬移，不改断言。
- **行为等价判据**：既有集成测试断言集原样通过即为等价，本票不新增行为断言（分层归属不属于外部行为）。
- **实跑豁免（用户拍板，先例：chat-member-table-refactor）**：本票**不起环境跑集成测试**。交付闸门为：better-school 全模块 `mvn compile` + `test-compile` 全绿 + 不依赖 Docker 的纯单测全绿；集成测试族留待下一次全量回归窗口统一实跑。风险随票注记：WS 链路只有集成测试能兜，实跑前该票不得标记「已验证行为等价」，只可标记「编译与单测已验证」。

## Out of Scope

- 不改帧协议、端点路径、票据握手鉴权方式与 Spring Security 白名单口径；
- 不新增任何推送通道（邮件 / 小程序订阅消息 / 短信等）；
- 不改 MQ 主题、outbox 事件结构与载荷；
- 不改 timeline 补拉、判重合流等客户端兜底语义；
- 不做群聊、多设备登录等新功能；
- 不动 `bs-trigger/ws` 支撑骨架的抽象设计（只做物理下沉，不复刻新骨架）。

## Further Notes

- 「最小痛点版」（只把成员决策挪进 case、投递机器留 trigger）在讨论中被否决：那会留下「trigger 持有出站投递机器」的结构性违规，与本次分层目标不一致。
- 支撑骨架（票据握手 / JSON 写出 / 单连接注册表 / 心跳判死四个抽象类）的复用意图保留：它们随投递层下沉 infra 后，仍是「第二个 WS 端点」的地基；连接处理器留在 trigger 侧，将来新端点同样只写薄绑定。
- 讨论 [`docs/api/chat.md`](../../backend/better-school/docs/api/chat.md) 的 WS 节是目前行为口径的唯一完整描述；本票实施后该节的行为描述应零改动，仅归属描述（「经 WsPushRouter」等实现名词）随新结构修订。
