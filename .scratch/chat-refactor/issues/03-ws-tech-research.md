---
Labels: wayfinder:research
Type: research
Status: resolved
Blocked by:
---

# 票 03：长连接技术选型调研

## Question

Spring Boot 3.x 单体、仅手机端 Web 客户端、单设备在线的前提下，IM 长连接实现的事实调研：

- Spring 原生 WebSocket（裸 WebSocket vs STOMP）vs Netty vs SSE 的适用边界与取舍；裸 WebSocket 对自管连接注册表/互踢是否反而更直接
- 移动端 Web 的心跳与断线重连惯例：心跳间隔、退避策略、重连后的对齐动作
- 同账号单设备互踢的常见实现（服务端连接注册表、踢下线消息、客户端被踢后的处理）
- 握手阶段的认证方式（JWT 放 query/header 的惯例与坑）

➡️ 产出：以官方文档为主的 findings，写入 `.scratch/chat-refactor/research/03-ws-选型调研.md`，并在本票 `## Answer` 记结论要点，供票 04 定稿连接层方案。

## Answer

调研 findings（以官方文档/RFC 为准，附来源）：[research/03-ws-选型调研.md](../research/03-ws-选型调研.md)

- **选型：Spring 原生裸 WebSocket**（`spring-boot-starter-websocket` + `TextWebSocketHandler` + 自定义 JSON 信封协议），不用 STOMP/Netty/SSE。IM 的低延迟+双向+服务端主导推送正落 WebSocket 最佳情形；IM 协议本就自定义，STOMP 的 pub-sub 是错配（服务器不能未经订阅主动发消息，Spring 官方文档明文）；Netty 解决超大并发自定义传输，单体无此规模且脱离 Servlet 栈；SSE 单向做不了主通道。单机部署使内存注册表成立。
- 互踢：`ConcurrentHashMap<userId, session>` + 新连接先占位再发应用层 KICK、`close(4000~4999 私有码)`（先例 MQTT 0x8E "Session taken over"）；close 回调按 session 身份防误删；客户端见踢码不自动重连、提示后人工重登（腾讯 IM/融云官方惯例），Web 多 tab 亦算多连接。
- 心跳：客户端应用层 PING 25~30s（< nginx 默认 60s 代理读超时，Socket.IO 默认 25s），服务器 2 周期判死；重连 1s 起指数退避 ×2、上限 30~60s、加抖动防惊群（Socket.IO 默认口径）；重连后按 lastSeq 增量拉缺口、超窗口走全量 REST 对齐。
- 握手认证：浏览器 WS 不能带自定义 header（构造器仅 url+protocols，MDN）；query 传长效 JWT 有泄漏面（RFC 6819 §4.6.7：日志/Referer）→ 建议鉴权 REST 换发 60s 一次性 ticket，`HandshakeInterceptor` 校验消费后绑定会话。
