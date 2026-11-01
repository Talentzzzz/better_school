---
Labels: wayfinder:map
---

# chat 领域重构（wayfinder map）

## Destination

一份可开工的 chat 领域重构 spec：定稿表结构 DDL、长连接（WebSocket）方案、接口清单、群聊产品规则，并把落地实施拆成票——spec + tickets 收口，符合既有 grilling→to-spec→to-tickets 流程。落地实施本身另开 effort。

## Notes

**现状（2026-09-09 勘察）**：chat 域仅 1v1 纯文本私信，`message_thread`/`message` 两表（`backend/sql/init.sql` L168/L182），HTTP 5s 轮询增量拉取（`ChatController`、`front/src/stores/unread.ts`），无 WebSocket、无群聊、无撤回/删除；通知域挂在 chat 域内（`NoticeServiceImpl`）；发送文本过 moderation 敏感词初筛。

**勘图会话已钉决策（2026-09-09）**：
- 动机：求职作品展示 IM 架构为主（时间线、读写扩散、长连接要有说法、能讲清取舍），实时性为辅
- 群聊全量实现（人数上限 500）。~~与跑腿多人接单解耦（群聊只留通用能力，跑腿绑定是未来用途）~~（2026-09-09 票 05 grilling 改向：群聊为跑腿而生——**系统群**，用户无建群/拉人/解散入口；本次做纯领域层通用能力，跑腿绑定是未来用途；chat 全路径零审核 + 举报兜底）
- 消息类型：文本 + 图片 + 业务卡片（商品/帖子/跑腿）
- 客户端：仅手机端；单设备在线（新连接互踢旧连接），重连必对齐（增量游标 + 超窗全量兜底，见票 04），不做多端同步
- 旧私信数据直接重置不迁移；通知域不动，本次只重写私信部分（room/message/时间线）
- 功能范围含：撤回、会话级隐藏、会话精确未读数、已读回执

**每票默认调用**：grilling + domain-modeling（chat 术语目前全缺，随票落 CONTEXT.md）；research 票用 research skill。

## Decisions so far

<!-- 一行一票：gist + 链接，详情在票内 -->
- [票 01](issues/01-timeline-model.md)：1v1 与群聊统一写扩散——room（seq 取号+列表冗余）/ room_user（水位线 last_read_seq + hidden_at）/ message（内容中心表）/ timeline（不可变瘦收件箱，多值 INSERT 扇出）；未读数 = last_seq − 水位线，已读粒度会话级、撤回占未读；用户构想的「消息记录表」否决（即 timeline 本体，status 被水位线取代）；写放大量化 ~800 万行/天单机可接受，spec 固化估算+演进口。
- [票 02](issues/02-ddl-prototype.md)：DDL 原型定稿（[ddl-prototype.sql](ddl-prototype.sql)，供用户反应后迭代并入 init.sql）——五表：room（last_seq 原子取号+name 群名系统传入不可改+last_msg 冗余+SEALED 封盘软删）/ room_user（水位线 last_read_seq+hidden_at 隐藏+left_at 移除留痕）/ message（msg_type 四类+content 载荷+CARD ref 独立列+撤回三件套 status/recalled_at/recalled_by）/ timeline（瘦收件箱只插不改，uk(user_id,delivery_seq) 即补拉游标）/ user_seq（per-user 投递序号计数器）；消息记录表不建；已读位=会话级水位线无计数器无 COUNT；群无角色字段、500 上限走系统侧 COUNT 校验不入库；1v1 唯一会话=room.user_min/user_max 可空列 + uk_direct_pair（群为 NULL）；三主索引 idx_user/uk_room_seq/uk_user_delivery 对着三条读写路径，其余不设（message.idx_ref 反查刻意不建无读路径，timeline 写放大敏感）；content_report.target_type 仅注释扩 MESSAGE 无列变更。开放点三项已由用户定夺（uk_direct_pair 可空列采纳、preview 维持 200、被移除成员列表隐藏详情可追溯），原型定稿，并入 init.sql 归实施 effort。
- [票 04](issues/04-ws-connection-design.md)：连接层定稿（[设计文档](research/04-连接层设计.md)）——REST 发送 + WS 纯下行（阵营二形态，不发明 ACK/去重协议）；推送走 outbox→消费者→注册表（与 agent-review 异步骨架同构）、MSG 全量载荷按 (roomId,seq) 判重、排除发送者；事件类（READ/RECALL）轻推只带定位；重连对齐用 **per-user 投递序号 delivery_seq**（user_seq 计数器扇出事务内取号；grilling 初版的「timeline 全局游标」经 code-review 否决更正，见票 01 Comments），连接即用对齐并行不设阻塞态；协议 C→S 仅 PING，S→C PONG/MSG/READ/RECALL/KICK；参数包沿用票 03（心跳 25s/判死 60s、票据 60s 一次性、KICK+close(4001) 被踢不重连、退避 1s×2 上限 30s）；推/拉边界收口：推是加速拉是真相。边缘项切走：WS 测试策略→票 07、旧轮询降级→票 08、群系统消息→票 05。
- [票 05](issues/05-group-rules.md)：群聊产品规则定稿——**系统群**：群聊为跑腿多人单而生，用户无建群/拉人/踢人/解散/改群信息入口，无群主/管理员/禁言；落地为纯领域层（room GROUP 类型 + 系统侧 service API，集成测试当调用方，无 HTTP/前端）；群名系统传入不可改，上限 500 可配置；1v1 与群统一 room 模型不可互转；**封盘**=宿主业务终态（COMPLETED/CANCELLED 均算）→ 软删不可逆、禁发言、历史全员只读可见；成员移除留痕不删行、停止扇出；建群/进出/封盘走**会话内系统消息**（SYSTEM 类型占 seq 计未读，投递对象=事件时刻成员集合，notification 域不动）；**chat 全路径零审核**（只拆 MessageSendCase 敏感词调用，帖/评/商品/跑腿不动），兜底=消息举报（content_report 扩 MESSAGE、一人一消息一单、AUDITOR 处理=强制撤回复用撤回机制、不绑扣分）；否决记档：用户建群/邀请链接、硬删解散、系统消息走 notification 域、举报绑扣分。波及：票 06 改题「消息类型与举报」、遗留项群写放大降级（系统群按单建群规模小）。
- [票 06](issues/06-message-types-moderation.md)：消息类型与举报定稿（[设计文档](research/06-消息类型与举报.md)）——类型清单 **TEXT/IMAGE/CARD/SYSTEM** 四类定死（撤回是消息状态不是类型）；消息体**混合结构**：msg_type + content 载荷（TEXT 纯文本 ≤500 单一常量、IMAGE JSON `{url,width,height}`、SYSTEM JSON event 枚举 + 服务端渲染文案）+ CARD 独立 `ref_type`/`ref_id` 列（否决全 JSON/全独立列）；**卡片纯实时渲染**（用户定向否决快照）：失效显示失效态、发送校验=存在且未删/未拒（SOLD/PENDING_REVIEW 可发）、CARD content 存空、last_msg 预览用类型标签；图片复用 `/api/files/upload` 链路不动、单条单图、URL 校验本站前缀；**撤回落地**：发送者 2 分钟窗口、1v1 与群一致、message 加 status+recalled_at+recalled_by（USER/AUDITOR）、content 库内保留读路径脱敏；**举报落地**：已撤回消息可举报（处理时幂等）、复用 uk_reporter_target、举报者须为会话成员、AUDITOR 强制撤回走 chat 系统侧内部撤回 API（无窗口限制、已自行撤回只落结论）、不绑扣分；DDL 归票 02、前端渲染归票 08。
- [票 07](issues/07-reliability-sync.md)：消息可靠性与同步语义收口——票面大半被票 04 提前定掉（双序号空间、(roomId,seq) 判重、REST 发送即确认、推/拉边界），本票定案四项：① **client_msg_id 幂等键**（message 加列 + uk(room_id, client_msg_id)，超时/重复提交撞 uk 返回原消息，重发手动复用同键，DDL 并入 init.sql effort）；② 补拉口径 `afterDeliverySeq + limit 200` 硬顶 500、不足一页即追平、游标 0 = 全量（timeline 永不删除 → 「超窗」当前唯一场景即游标 0，归档归票 09）；③ 已读时序三细节：打开即推水位线不等补拉、仅水位线前进才 UPDATE+推 READ（幂等无噪音）、READ 推其他在线成员群不节流（O(在线) 非O(成员)）；④ WS 集成测试 = 真 WS 客户端（StandardWebSocketClient 直连 RANDOM_PORT）+ 直接调用 ChatPushConsumer 模拟 outbox 消费（测试基类既有事实：namesrv 必须有/broker 刻意不启），不 mock 内部 Bean、单一 seam 的 WS 延伸；异常路径表收口（超时/重复/漏推/竞争/RECALL 丢失/断线已读）。CONTEXT.md 新增术语：发送幂等，增量补拉术语更新（游标 0 即全量）。
- [票 08](issues/08-frontend-scope.md)：前端改造范围定稿——**手机端适配前提过时**（勘察证伪票面：front/ 已是移动式 480px+TabBar，无存量适配）；**定时轮询全拆**（unread 5s/列表 8s/详情 5s 三处定时器不要，REST 拉接口保留，重连补拉兜底——轮询降级与补拉功能重复，「推是加速拉是真相」的前端兑现）；**rooms 共享 Pinia store**（列表数据+未读+lastMsg 单一来源 total 派生，MSG/READ 就地更新置顶，列表页纯渲染）；群聊 UI=消息流+**只读成员列表页**，本次无进群入口（系统群纯领域层，可达性随跑腿绑定 effort，渲染层完整支持 GROUP+封盘只读态，测试后端造数）；详情页六项全做（发送 pending/失败红叹号手动重发复用 client_msg_id、长按撤回 2 分钟窗口、图片复用 upload、卡片「联系 TA」+失效态、SYSTEM 灰字、KICK 提示清 token 跳登录）；**已读回执群聊全量**（用户改向：群内所有消息下显示已读者头像前 3+省略、全读显示「全部已读」、SYSTEM 除外、按当前成员算；1v1 维持自己消息旁已读/未读小字；数据流=详情页打开拉成员接口捎带 last_read_seq 一次 + **READ 推送加 readerId** 就地更新——票 04「READ 只带定位」修订为「定位+readerId」1v1 群统一，否决 MSG 捎带全员水位与纯拉）；会话隐藏入口=长按会话「删除」、新消息推送弹回；delivery_seq 游标内存持有不持久化（刷新 0=全量，localStorage 归演进口）；接口清单波及（threads room 模型/seq 分页历史/成员含水位/发送幂等/撤回/隐藏/WS ticket）归票 09。
- [票 09](issues/09-spec-finalize.md)：spec 定稿与落地票拆分——[spec.md](spec.md) 汇总票 01–08 全部决策为唯一需求来源（DDL 定稿含 client_msg_id 修订/同步语义/长连接/接口清单含 REST 十一端点与 WS 协议/群聊规则/零审核+举报/前端七改造点/容量估算固化）；遗留项「写放大量化」在 spec §8 收口（800 万 timeline 行/天≈0.8GB、均值 92 峰值 900 写/秒单机可接受，演进口三条：timeline 归档/群扇出转异步/前端游标持久化）；CONTEXT.md「消息序号」术语校对一处（增量同步改由投递序号承担）；落地实施拆 10 票至 [.scratch/chat-impl/](../chat-impl/map.md)（01 DDL+持久化层 → 02 发送链路 → 03 读路径 → 04 WS 连接层与推送 → 05 撤回举报 ∥ 06 系统群 → 07 WS 集成测试 → 08 前端 WS+store → 09 前端列表详情 → 10 成员页/卡片入口/收尾）。

## Not yet specified

（无——地图走完。写放大量化在 spec §8 固化；演进口（timeline 归档/群扇出异步/前端游标持久化）为独立未来 effort，非本图缺口。）

## Out of scope

- 跑腿多人接单与群聊的业务绑定（未来 effort，本次群聊只留通用能力）
- 多端/多设备同步（单设备互踢已定，重连即全量对齐）
- 通知域重构（notification 表与 MQ 广播链路维持现状）
- 旧私信数据迁移（本地库重置，不写迁移 SQL）
