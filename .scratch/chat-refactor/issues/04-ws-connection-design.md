---
Labels: wayfinder:grilling
Type: grilling
Status: resolved
Blocked by: 03
---

# 票 04：连接层方案定稿

## Question

基于票 03 的调研结论，定稿连接层方案：

- 选型落定（Spring WebSocket / Netty / SSE 中的哪个，为什么）
- 连接注册表与互踢时序：新连接建立 → 旧连接被踢 → 客户端收到什么 → 前端怎么响应
- 心跳与断线重连策略的具体参数与职责划分（服务端探活 vs 客户端心跳）
- 握手认证：JWT 校验时机与失败处理
- 在线推送的触发点与离线补拉的分工边界（什么走推、什么永远靠拉）

➡️ 产出：连接生命周期状态机 + 关键时序（登录建连、互踢、断线重连、收发一条消息的完整链路）。

## Answer

连接层定稿：**Spring 原生裸 WebSocket + 自定义 JSON 协议 + 自管连接注册表**（选型沿用票 03），核心决策 **REST 发送 + WS 纯下行**（C→S 仅剩心跳，不发明 ACK/去重协议；发送复用 moderation/事务/HTTP 错误语义）。完整产出见 [research/04-连接层设计.md](../research/04-连接层设计.md)：连接生命周期状态机（客户端 DISCONNECT/CONNECTING/CONNECTED/KICKED + 服务端登记/判死不变式）、四条关键时序（登录建连/互踢/断线重连/收发一条消息）、协议类型清单（PING/PONG/MSG/READ/RECALL/KICK）、参数汇总、推/拉分工边界（推是加速拉是真相）。

要点：推送走 outbox→消费者→注册表；MSG 全量载荷按 (roomId, seq) 判重、排除发送者；READ/RECALL 轻事件只带定位（READ 载荷经票 08 修订为「定位+readerId」，供群聊已读回执就地更新，1v1 群统一）；重连对齐用 per-user 投递序号 delivery_seq（grilling 时的「timeline 全局游标」方案经 code-review 更正——seq 是会话内编号不可跨会话比较，自增主键当游标有并发乱序漏拉漏洞，详见票 01 Comments）；连接即用、对齐并行不设阻塞态。边缘项归属：WS 集成测试→票 07、旧轮询降级→票 08、群系统消息→票 05。
