---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by: 04, 05, 06
---

# chat 落地票 07：WS 集成测试与异常路径收口

## Scope

依据 spec §9 测试策略（票 07 设计定稿），全链路行为测试。

测试形态：真 WS 客户端 `StandardWebSocketClient` 直连 `ws://localhost:{RANDOM_PORT}/ws/chat?ticket=`；推送触发不经过 MQ、直接调用 `ChatPushConsumer` 模拟 outbox 消费。基类事实：namesrv 必须有、broker 刻意不启；不 mock 内部 Bean。

覆盖清单：

1. 握手：无效/过期票据 401；票据一次性（二次使用被拒）。
2. 互踢：同账号新连接 → 旧连接收 KICK + close(4001)。
3. 心跳：PING→PONG；60s 无入站判死清理（可缩短周期参数化测试）。
4. MSG：排除发送者；接收端按 (roomId, seq) 去重语义（载荷含 seq 供客户端判重）；群消息全员在线各收一份。
5. READ：推水位线后其他在线成员收 `{roomId, userId, readSeq}`；重复打开无第二份。
6. 补拉合流：断线期间两条消息 → 重连后 GET timeline 补拉 + 期间实时推送，重复段由 (roomId, seq) 判重吸收、无丢失。
7. 撤回：RECALL 轻推 (roomId, seq) 到在线成员。

## 验收要点

- spec §2 异常路径表全部有对应用例或显式记档（推丢失/断线已读不补推为接受退化，注释记档即可）。

## Comments

（2026-09-10 resolved，全量 257 绿）`ChatWsIntegrationTest` 10 用例，连贯三轮绿（59s/类）：

- **形态**：真 `StandardWebSocketClient` 直连 `RANDOM_PORT`（tomcat-embed-websocket 自带客户端容器）+ 读 `outbox_message` 载荷直调 `ChatPushConsumer`/`ChatReadPushConsumer`/`ChatRecallPushConsumer` 模拟消费，不经 MQ；不 mock 内部 Bean。
- **覆盖清单 1–7** 逐条落地：①握手（无效/过期/缺失票据 + 票据一次性重放拒绝）②互踢（旧连接 KICK+close(4001)，新连接仍收推送证迟到 close 未误删）③心跳（PING→PONG + 活跃会话不被清扫 + 注入未来时钟驱动判死 GOING_AWAY）④MSG（排除发送者、载荷带判重键 (roomId,seq) 与昵称头像槽位、群消息全员在线各一份）⑤READ（推其他在线成员 `{userId, readSeq}`、reader 本人不收、重复打开无第二份）⑥补拉合流（断线两条→重连实时一条，推送段判重键已在补拉结果中，无丢失）⑦RECALL（轻推全员含发送者）。
- **spec §2 异常路径表收口**：第 1/2 行（发送超时重发、重复提交幂等）由票 02 `ChatSendPipelineIntegrationTest` 背书（类 javadoc 交叉引用）；第 3 行（推送失败/掉线）由断线用例证「漏推合法」；第 4 行（补拉与实时推送竞争）由合流用例证判重键重合；第 6 行（断线期间已读）由 `offlineReaderWatermarkRecoveredFromRestNotPush` 证「不补推、重连走 REST 取水位线初值」。
- **接受退化（显式记档，注释在用例内）**：第 5 行 RECALL 轻推丢失——界面暂不更新，重进/重连呈现终态，不重试；断线期间 READ 不缓存不补推——初值一律走 REST 读路径。
- **测试设施踩坑（已写入 `backend/docs/testing.md`）**：①Tomcat 客户端把被拒响应码包进 `DeploymentException` 消息，握手断言必须 `hasMessageContaining("401")`——只断言「抛异常」会把服务未起/超时一并放过；②握手 future 完成早于服务端 `afterConnectionEstablished`（注册表写入），建连后补 PING→PONG 同步一次往返，互踢/推送时序才稳定；③注册表/会话表是共享上下文单例，`@AfterEach` 必须关闭本用例全部连接。
- **顺带改动**：`ChatSessionRegistry.DEAD_THRESHOLD_MS` 由包私有改 public（测试注入时钟对齐该阈值，避免字面量复制漂移）；`docs/testing.md` 补「WS 全链路测试」节与文件表条目。
- **code-review 修正**：握手断言由「仅 ExecutionException」收紧为「cause=DeploymentException 且消息含 401」（原断言对 403/500/连接失败也放行）；合流断言删除自证式 `Set` 算术（`pulledKeys` 已含全部键，再 add 一个键后断言 size 恒真），改为断言「推送段 (roomId,seq) 已在补拉集合中」+ 补拉条目必带 `deliverySeq`；判死用例补「活跃会话不被清扫」逆向分支；MSG 载荷补 `fromAvatar` 槽位断言；`WsClient` 去掉纯委托的 `close()`（Middle Man）。评审留档未改项：`createDirectRoom`/`sendText` 帮助方法与既有 chat 测试类各自私有副本重复——沿用仓库既有惯例（不作基类抽取）。
