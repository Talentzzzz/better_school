---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by: 02, 03
---

# chat 落地票 04：WS 连接层与推送链路

## Scope

依据 spec §3 与[连接层设计文档](../../chat-refactor/research/04-连接层设计.md)实现长连接（Spring 原生裸 WebSocket，`spring-boot-starter-websocket`）。

1. 组件：`WsTicketService`（消费票 03 票据）→ `ChatWsHandshakeInterceptor`（原子取删校验，失败 401）→ `ChatWsHandler`；`ChatConnectionRegistry`（`Map<userId, session>`，新连接先占位后发 KICK+close(4001)，close 回调两参注销防误删，session 用 `ConcurrentWebSocketSessionDecorator`）；`ChatSessionRegistry`（lastInboundAt + 调度清扫 60s 判死）。
2. 协议信封 `{type, roomId?, seq?, payload?, ts}`：C→S 仅 `PING`；S→C `PONG`/`MSG`（roomId+seq+完整消息体含发送者昵称头像）/`READ`（roomId+userId+readSeq）/`RECALL`（roomId+seq）/`KICK`（reason=SESSION_TAKEN_OVER + close(4001)）。任何入站帧刷新 lastInboundAt。
3. `ChatPushConsumer`（outbox 消费者，bs-trigger listener 包）：消费 MSG/READ/RECALL 事件 → 查注册表 → 在线成员推送，**MSG 排除发送者本人**，不在线跳过；单设备互踢在注册表 put 时触发。
4. 服务端无业务路由、无 ACK；推送失败不重试（漏推合法，补拉兜底）。

## 验收要点

- 互踢时序：新连接 put → 旧连接收 KICK + close(4001)，旧连接迟到 close 不误删新连接。
- 判死：60s 无入站被清理；PONG 正常应答。
- 单元测试覆盖注册表互斥规则；WS 全链路行为测试归票 07。

## Comments
