# chat 领域重构 spec（定稿）

> 2026-09-09，票 09 收口。汇总票 01–08 全部决策，是落地实施 effort（`.scratch/chat-impl/`）的唯一需求来源。各决策的论证过程见 [map.md](map.md) Decisions-so-far 与各票 Answer，本文只记结论。

## 0. 目标与范围

- 动机：求职作品展示 IM 架构（时间线读写扩散、长连接、能讲清取舍），实时性为辅。
- 改造对象：现有 chat 域仅 1v1 纯文本私信（`message_thread`/`message` 两表 + HTTP 5s 轮询），本次重写为 room/message/timeline 模型 + WebSocket 下行推送。
- **Out of scope**：跑腿多人单与群聊的业务绑定（未来 effort）、多端/多设备同步、通知域重构（notification 域一行不动）、旧私信数据迁移（本地库重置）。

## 1. 领域模型与 DDL 定稿

五张表定稿，DDL 全文见 [ddl-prototype.sql](ddl-prototype.sql)，**加一处修订**（票 07）：`message` 表补 `client_msg_id VARCHAR(36) NULL` + `UNIQUE KEY uk_client_msg (room_id, client_msg_id)`（SYSTEM 消息为 NULL 不受约束）。落地并入 `backend/sql/init.sql`，删除 `message_thread` 旧表定义，`content_report.target_type` 注释扩值 `MESSAGE`（无列变更）。

| 表 | 角色 | 关键结构 |
|---|---|---|
| `room` | 会话本体（1v1 与群统一） | `last_seq` 事务内原子取号；`type` DIRECT/GROUP 创建即定死；`user_min/user_max` + `uk_direct_pair`（1v1 唯一会话，群两列为 NULL）；`name` 群名仅 GROUP 非空、系统传入不可改；`last_msg_type/preview/at` 列表冗余；`status` ACTIVE/SEALED 封盘软删 |
| `room_user` | 成员状态唯一可变行 | `last_read_seq` 已读水位线；`hidden_at` 会话隐藏（新消息清空弹回）；`left_at` 被移除留痕（停止扇出、不出列表，历史可追溯）；无角色字段 |
| `message` | 内容中心只写一份 | `msg_type` TEXT/IMAGE/CARD/SYSTEM 四类定死；`content` 载荷按类型分语义；CARD 走独立 `ref_type`/`ref_id` 列；撤回三件套 `status/recalled_at/recalled_by(USER/AUDITOR)`；`client_msg_id` 幂等键 |
| `timeline` | 不可变瘦收件箱，只插不改 | `user_id/room_id/seq/msg_id/delivery_seq`，多值 INSERT 扇出；`uk(user_id, delivery_seq)` 即补拉游标；自增 `id` 禁止当游标 |
| `user_seq` | per-user 投递序号计数器 | 扇出事务内 `UPDATE seq=seq+N` 读回取号，行锁到提交保证单调=提交序 |

索引只设三条主路径：`room_user.idx_user`（会话列表）、`message.uk_room_seq`（分页游标 + WS 判重键）+ `uk_client_msg`（发送幂等）、`timeline.uk_user_delivery`（增量补拉）。`message.idx_ref`（CARD 反查）刻意不建，出现读路径再补。

**不建**：消息记录表（票 01 否决——timeline 瘦行就是它，status 被 `room_user.last_read_seq` 水位线取代）；未读计数器/COUNT（未读数 = `room.last_seq − last_read_seq` O(1)，seq 原子分配无空洞）；群 500 上限不落 DB 约束（系统侧 COUNT 校验，可配置）。

### 消息类型（票 06）

| type | content 载荷 | ref 列 | 说明 |
|---|---|---|---|
| `TEXT` | 裸文本 ≤500（单一常量） | — | 默认类型 |
| `IMAGE` | JSON `{"url","width","height"}` | — | 复用 `/api/files/upload`（jpg/png/webp ≤5MB）一行不动；单条单图多图拆条；url 须本站上传域前缀；宽高可缺省 |
| `CARD` | **恒空**（纯实时渲染无快照） | `ref_type`（PRODUCT/POST/ERRAND）+ `ref_id` | 渲染时实时查对象，失效显示「内容已失效」；发送校验=存在且未删/未拒（post 非 DELETED/REJECTED/DRAFT；product 非 DELETED/REJECTED；errand 非 REJECTED/CANCELLED） |
| `SYSTEM` | JSON `{"event",...}` + 服务端渲染文案（快照式落库） | — | 仅系统侧；event 枚举 GROUP_CREATED/MEMBER_JOINED/MEMBER_REMOVED/SEALED |

`room.last_msg` 预览规则：TEXT→原文截 200；IMAGE→`[图片]`；CARD→`[商品]`/`[帖子]`/`[跑腿卡片]` 类型标签（不查标题）；SYSTEM→渲染文案；已撤回→`[已撤回]`。

### 关键语义

- **未读/已读**：已读粒度会话级，打开会话即推水位线到 `room.last_seq`（微信「打开即已读」，未读清零与渲染解耦）；撤回占 seq 不回退、不动水位线、计入未读。
- **撤回**：发送者 2 分钟窗口（1v1 与群一致）；content 库内保留取证，读路径与下发一律脱敏「已撤回」空壳。
- **会话隐藏**：`room_user.hidden_at`，被移除成员（left_at 非空）不进会话列表，详情与历史仍可追溯。

## 2. 消息同步语义

### 双序号空间（不混用）

- `seq`：会话内单调递增（room.last_seq 取号），用于消息判重、未读数、历史分页。
- `delivery_seq`：per-user 投递序号（user_seq 计数器取号），per-user 单调且=提交序，唯一用途是增量补拉游标（微信 SyncKey 同款）。

### 发送链路（REST，票 04+07）

客户端生成 `client_msg_id`（UUID）→ 乐观上屏 pending → `POST /api/chat/rooms/{id}/messages` → 服务端事务内：撞 `uk(room_id, client_msg_id)` 则回查返回原消息 → room 取 seq → message INSERT → user_seq 取号 → timeline 扇出（新消息同时清空扇出目标 hidden_at）→ room.last_msg 冗余 → outbox 事件 → 200 返回完整消息体转正。明确失败（4xx/5xx）与超时均置失败态；重发**手动**、复用同 client_msg_id（首版不做自动重试）。WS 上行无 ACK 协议。

### 推送与判重

推送走 outbox → MQ 消费者（ChatPushConsumer）→ 连接注册表；不在线直接跳过（漏推合法）。MSG 全量载荷按 `(roomId, seq)` 判重，**排除发送者本人**（发送方以 HTTP 200 为准）；游标只被补拉推进，实时推送不推进。READ/RECALL 轻事件只带定位（READ 载荷含 readerId，见下）。

### 已读时序（票 07）

1. 打开会话立即 `POST /read` 推水位线，不等补拉完成；
2. 仅 `last_read_seq < room.last_seq` 才 UPDATE + 提交后推 READ（幂等无噪音）；
3. READ 推送范围 = 会话内其他**在线**成员，群聊不节流（O(在线) 非 O(成员)）；载荷 `{roomId, userId, readSeq}`（userId 即 readerId，1v1 群统一，供前端就地更新成员水位 map）；
4. 断线期间的已读不缓存不补推，重连后从 REST 读路径（会话列表/成员接口）取水位线初值。

### 补拉口径

`GET /api/chat/timeline?afterDeliverySeq=N&limit=200`；返回条数 < limit 即追平，否则循环拉；服务端 limit 硬顶 500；游标 0（新设备/首次登录）= 全量。timeline 永不删除 → 游标永远有效，无超窗场景。重连对齐「连接即用、对齐并行」，不设阻塞态。

### 推/拉边界与异常路径

原则：**推是加速，拉是真相**。推：MSG 新消息、READ 水位线、RECALL 撤回、KICK 互踢；永远靠拉：会话列表/未读初值、历史消息、timeline 补拉、离线期间的一切。

| 异常 | 处理规则 |
|---|---|
| 发送超时/网络中断 | 置失败态，手动重发复用同 client_msg_id，撞 uk 返回原消息，收敛同一条 |
| 重复提交（双击） | `uk(room_id, client_msg_id)` 吸收，返回原消息 |
| 推送失败/掉线 | 漏推合法，重连增量补拉兜底 |
| 补拉与实时推送竞争 | (roomId, seq) 判重合流 |
| RECALL 轻推丢失 | 界面暂不更新，重进/重连呈现终态（接受退化） |
| 断线期间对方已读 | READ 不补推，REST 读路径取水位线初值 |
| 断线重连 | 退避重连 → 补拉 afterDeliverySeq → 判重合流；游标 0 = 全量 |

## 3. 长连接方案（票 03/04/08）

**Spring 原生裸 WebSocket + 自定义 JSON 协议 + 自管连接注册表**（单机即终态）。不用 STOMP（pub-sub 错配）/Netty（无此规模）/SSE（单向）。组件与状态机详见 [research/04-连接层设计.md](research/04-连接层设计.md)，要点：

- **REST 发送 + WS 纯下行**，C→S 仅 PING。
- 握手认证：REST 换 60s 一次性票据 → `GET /ws/chat?ticket=` → `HandshakeInterceptor` 原子取删校验；浏览器 WS 不能带自定义 header，JWT 不进 URL。
- 连接注册表 `Map<userId, session>`：新连接先占位再发 KICK + close(4001)；close 回调两参注销防误删；session 用 `ConcurrentWebSocketSessionDecorator` 包装。
- 心跳：客户端 PING 25s；服务端任意入站帧刷新活跃时间，60s 无入站判死清理。
- 客户端状态机：断线（非 4001）退避重连 1s×2 上限 30s 带抖动；收到 KICK/close(4001) 停止重连提示重新登录。

协议信封 `{type, roomId?, seq?, payload?, ts}`（JSON 文本帧）：

| 方向 | type | 携带 |
|---|---|---|
| C→S | `PING` | 无 |
| S→C | `PONG` | 无 |
| S→C | `MSG` | roomId, seq, payload=完整消息体（含发送者昵称头像，气泡渲染免二次查询） |
| S→C | `READ` | roomId, payload={userId（readerId）, readSeq}（票 04：信封无独立槽位，随 payload 下发） |
| S→C | `RECALL` | roomId, seq |
| S→C | `KICK` | payload={reason=SESSION_TAKEN_OVER}，随后 close(4001) |

## 4. 接口清单（定稿）

REST 统一 `/api/chat` 前缀，替代现有 threads 端点（旧 `/threads` 系列删除，前端同步改造）：

| 方法 路径 | 用途 | 要点 |
|---|---|---|
| `GET /api/chat/rooms` | 会话列表（分页） | 排除 hidden_at 非空与 left_at 非空；服务端拼装展示字段：1v1 返回对方 userId+昵称+头像，群返回群名；含 unread（=last_seq−水位线）、last_msg_type/preview/at、status（SEALED 置灰） |
| `POST /api/chat/rooms/direct` | 建/取 1v1 会话 | body `{targetUserId}`；`uk_direct_pair` 幂等返回既有 room（「联系 TA」入口） |
| `GET /api/chat/rooms/{id}/messages` | 历史消息 | `?beforeSeq=&limit=`（默认 50）倒序分页，上滑加载更早；已撤回消息脱敏为空壳 |
| `POST /api/chat/rooms/{id}/messages` | 发送消息 | body `{clientMsgId, msgType, content?, refType?, refId?}`；SEALED 会话拒绝；200 返回完整消息体（含 roomId/seq） |
| `POST /api/chat/rooms/{id}/read` | 打开会话推水位线 | 幂等，仅水位线前进时 UPDATE+推 READ |
| `POST /api/chat/rooms/{id}/messages/{seq}/recall` | 撤回 | 发送者 2 分钟窗口，1v1 群一致；成功后推 RECALL |
| `POST /api/chat/rooms/{id}/hide` | 会话隐藏 | 置 hidden_at；新消息自动弹回 |
| `GET /api/chat/rooms/{id}/members` | 成员列表 | 仅 left_at 为空的当前成员；含 `last_read_seq`（详情页已读头像推导 + 成员页同源）与成员数 |
| `GET /api/chat/timeline` | 增量补拉 | `?afterDeliverySeq=&limit=`（默认 200 硬顶 500）；返回消息全量+roomId/seq/deliverySeq，供与推送判重合流 |
| `POST /api/chat/ws-ticket` | WS 一次性票据 | 60s 有效 |
| 举报 | 复用既有 content_report REST | `targetType=MESSAGE`，一人一消息一单，举报者须为会话成员 |

群聊系统侧无 HTTP 端点（纯领域层 service API：建群/成员进出/封盘/内部强制撤回），集成测试当调用方。

## 5. 群聊规则（票 05）

- **系统群**：群聊为跑腿多人单而生，用户无建群/拉人/踢人/解散/改群信息任何入口，无群主/管理员/禁言；本次落地为纯领域层通用能力，跑腿绑定是未来 effort，测试靠后端造数。
- 群名建群时系统传入不可改；成员上限 500 可配置（系统侧 COUNT 校验）。
- 1v1 与群统一 room 模型，类型创建即定死不可互转。
- **封盘**：宿主业务终态（COMPLETED/CANCELLED 均算）→ 软删不可逆，禁发言禁成员变更，历史全员只读可见；封盘瞬间投一条 SYSTEM 消息。
- **成员移除**：留痕不删行（left_at），停止扇出、不出会话列表，此前消息可见；被移除者不收移除事件消息。
- **系统消息**：建群/成员加入/成员移除/封盘走会话内 SYSTEM 消息（占 seq、计未读、走正常消息链路），投递对象=事件时刻成员集合；notification 域一行不动。

## 6. 审核与举报（票 05/06）

- **chat 全路径零审核**：只拆 `MessageSendCase` 敏感词初筛一处，图片也不过审；帖/评/商品/跑腿审核与 agent-review 全部不动。
- **兜底=举报**：`content_report` 扩 `target_type=MESSAGE`；一人一消息一单（复用 `uk_reporter_target`）；已撤回消息可举报（追责留痕，处理时幂等）；`content_snapshot` 存消息 content（CARD 存 ref 标识如 `CARD:PRODUCT#123`）。
- **AUDITOR 处理=强制撤回**：走 chat 系统侧内部撤回 API（`recalled_by=AUDITOR`，无窗口限制）；消息已自行撤回则只落处理结论；不走扣分流水（chat 消息无下架概念）；举报人/被举报人通知沿用既有 `REPORT_RESULT`。

## 7. 前端改造点（票 08）

现状：已是移动式布局（480px+TabBar+safe-area），无存量适配工作。三处定时轮询（unread 5s/列表 8s/详情 5s）**全拆不保留降级**（REST 拉接口保留作首屏/分页/补拉）。

1. **WS 客户端（全新）**：登录即连/登出断开；先 POST ws-ticket 再连；心跳 25s；退避重连 1s×2 上限 30s；KICK/close(4001) → 提示「账号已在其他设备登录」→ 清 token 跳登录不重连。
2. **rooms 共享 Pinia store（全新）**：会话列表数据+未读+lastMsg 单一来源，未读总数派生；MSG 推送就地更新（last_msg/未读+1/置顶重排，清 hidden_at 弹回）、READ 就地更新、进详情页清未读；列表页纯渲染；`stores/unread.ts` 轮询并入拆掉。
3. **MessagesView**：纯渲染 + 长按会话「删除该会话」（本地立即消失，POST hide）；群/单聊区分（群头像+群名）。
4. **ChatDetailView 重写**：历史按 seq 倒序分页（上滑加载更早，替代 afterId）；发送状态机 pending→200 转正→失败红叹号手动重发（复用 client_msg_id）；长按自己消息撤回（2 分钟窗口就地灰字）；图片复用 `/api/files/upload` 单条单图；卡片实时渲染+失效态；SYSTEM 居中灰字渲染服务端文案；SEALED 输入框置灰「订单已结束」；delivery_seq 游标内存持有不持久化（刷新 0=全量永远正确）。
5. **已读回执（群聊全量）**：数据流=打开详情页拉一次成员接口（捎带 last_read_seq）+ READ 推送按 readerId 就地更新成员水位 map。1v1：自己消息旁「已读/未读」小字；群：所有消息（含他人）下方显示已读者头像前 3+省略，全读（按当前成员算，被移除者不计）显示「全部已读」，SYSTEM 不显示；已读判定=消息 seq ≤ 成员水位线，纯前端推导。
6. **成员列表页（全新）**：只读，展示当前成员与水位线；被移除成员不出现。
7. **商品/帖子详情页**：「联系 TA」入口 → POST /rooms/direct → 进会话可发对应卡片。

## 8. 容量估算与演进口（票 01/04 遗留项固化）

写放大量化（单机 MySQL 可接受性论证，面试口径）：

- 基准：1 万用户、人均日发 100 条 → 100 万消息/天；平均会话 8 人（上限 500 只限单条倍数，多数会话 2~10 人）。
- timeline 扇出：800 万行/天，瘦行+索引 ~100B ≈ **0.8GB/天**；均值 ~92 写/秒、峰值 ~900 写/秒，InnoDB 单机扛得住。
- `user_seq` 计数器：每条消息 +N 行**原地 UPDATE**（1v1 +2、平均 +8），不增行数只增写 IO，已计入估算；500 人群场景扇出 +500 行/条——系统群按单建群规模小，写放大以 1v1 为主。
- 实际校园量级远低于此（人均 100 条/天为上限口径），本估算是上限论证。

演进口（记录方向，独立 effort 再做）：

1. timeline 数据归档：须先回答「游标落在归档窗口之外」的语义（降级最近窗口全量 + 更早历史走 room+seq 分页）；
2. 群聊扇出转异步（MQ 分批）——500 人群写放大翻倍时的出口；
3. 前端 delivery_seq 游标 localStorage 按 userId 持久化（省刷新后的全量补拉）。

## 9. 测试策略（票 07）

- **WS 集成测试**：真 WS 客户端（`StandardWebSocketClient` 直连 `ws://localhost:{RANDOM_PORT}/ws/chat?ticket=`）+ 直接调用 `ChatPushConsumer` 模拟 outbox 消费（不经过 MQ）。依据测试基类既有事实：namesrv 必须有（否则消费者 bean 炸上下文）、broker 刻意不启（出站失败被 OutboxMessageSender catch）；不 mock 内部 Bean，MQ→消费者一跳由 outbox 骨架背书（agent-review 同构已验证）。覆盖：握手 401、票据一次性、互踢 close(4001)、心跳判死、MSG 排除发送者+判重、断线补拉合流吸收重复段。
- 发送幂等（撞 uk 返回原消息）、水位线幂等（重复打开不推 READ）、扇出与 hidden_at 清空、撤回窗口与脱敏、补拉分页口径：常规集成测试。
- 群聊 UI 验收靠后端造数（系统群无用户入口）。

## 10. 落地票列

拆分至 `.scratch/chat-impl/issues/`（01 起，含 Blocked by 依赖），详见该 effort 的 [map.md](../chat-impl/map.md)。

## 11. 术语

chat 术语已随票 01–08 沉淀于 [CONTEXT.md](../../CONTEXT.md)「私信会话」节（21 条），票 09 校对一处：「消息序号」条目中增量同步改由投递序号承担（票 04 更正后的口径）。
