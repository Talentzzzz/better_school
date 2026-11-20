# chat 会话模块接口

chat 域接口（room / user_room + room_member 名册 / message / timeline 模型，spec 见 `.scratch/chat-refactor/spec.md` 与 `.scratch/chat-member-table-refactor/spec.md`）。所有接口需登录（JWT Bearer）。会话恒为 1v1（恰两名成员），群形态（系统群/封盘/系统消息/成员上限）已随票 04 整体下线。

## 会话

### GET /rooms
会话列表（分页，`?page=1&pageSize=50`，pageSize ≤ 200）。Query 参数可省。

- 排除 hidden_at 非空（自己隐藏）；按 last_msg_time 倒序
- 服务端拼装展示字段：`peerUserId/peerNickname/peerAvatar`（对方）
- `unread` = room.last_seq − 本人 read_seq（回查真源现算，无 COUNT）；`lastMsgType/lastMsgPreview/lastMsgAt` 为读时现算的展示字段（按 message 真相渲染，非库内副本）
- `source`（ERRAND/PRODUCT，市集入口首建才有）供市集消息页按域分 tab
- `peerRole`（TAKER/SELLER/BUYER）**按请求者现算**：闲置按「本人是不是会话创建人」分两侧（闲置创建人=买家、对家=卖家），跑腿来源列定不了方向、对家恒为跑手
- 返回 `{page, pageSize, total, list}`

### POST /rooms/{roomId}/read
打开会话推水位线到 room.last_seq（微信「打开即已读」）。幂等：仅水位线实际前进才 UPDATE + 提交后投递 READ outbox 事件（outbox `destination` = `bs-chat-read`，载荷 `{roomId, userId, readSeq}`，WS 推送归票 04），重复打开无噪音。返回 `data = readSeq`（推进后水位线）。非成员 403。

### POST /rooms/{roomId}/hide
会话隐藏（用户侧删除会话）：置 hidden_at，列表立即消失；新消息自动清空 hidden_at 弹回。非成员 403。

### GET /rooms/{roomId}/members
成员列表（恒两行）：含 `lastReadSeq`（详情页已读头像推导，已读判定 = 消息 seq ≤ 水位线纯前端推导）与 `memberCount`。非成员 403。

### POST /ws-ticket
WS 连接一次性票据（60s 有效）：Redis 原子取删，同一票据只有一次生效机会，重放失败。返回 `{ticket}`，握手连 `GET /ws/chat?ticket=`（归票 04）。

### POST /rooms/direct
建/取 1v1 会话（「联系 TA」入口）。Body: `targetUserId`（必填，不能是自己，用户须存在）、`source?`（市集两入口分别传 ERRAND/PRODUCT，首页与用户主页私信入口不传；非法值 400）。
`user_pair_room.uk_pair` 幂等：同两人重复建（任意方向）返回同一 room。**`source` 只在首次建会话时写入**（首来源钉死），既有会话原样返回、来源不被覆盖。返回 `{roomId}`。

## 消息

### GET /rooms/{roomId}/messages
历史消息（`?beforeSeq=&limit=`，limit 默认 50 硬顶 500）。`beforeSeq` 省略取最新一页；游标倒退取页（上滑加载更早），**页内 seq 升序**。已撤回消息脱敏「已撤回」空壳：`status=RECALLED`，`content/refType/refId` 不出服务端。非成员 403。（落库列名为 `message.state`，响应字段仍叫 `status`。）

### GET /timeline
增量补拉（`?afterDeliverySeq=&limit=`，默认 200 硬顶 500）。按 per-user 投递序号升序，游标 0（新设备/首次）= 全量；timeline 永不删除，游标永远有效。返回 `{list, nextCursor, hasMore}`：

- `list`：每条消息全量 + `roomId/seq/deliverySeq`（多会话按投递序全局归并），与历史/发送 200 同构
- `nextCursor`：下次补拉的游标，取**本次扫描边界**（本页最后一行的 `delivery_seq`）而非「最后一条成功条目」——扫描窗内若有 `timeline` 行在而 `message` 行缺失的漂移行被跳过，游标照样越过它，不会被钉在原地；空页时原样回传入参
- `hasMore`：`true` = 本次填满一页，可能还有，继续拉；`false` = 追平。**不再用「返回条数 < limit」推断追平**——那个推断在有过滤（漂移行、未来的清理/黑名单）时会失真，导致游标停滞、漏掉后续消息

### POST /rooms/messages
发送消息。Body: `roomId`（目标会话，必填）、`clientMsgId`（客户端 UUID，≤36）、`msgType`（TEXT/IMAGE/CARD）、`content?`、`refType?`、`refId?`。

- 类型语义（载荷按类型分）：
  - TEXT：裸文本 ≤500 字，超长报参数错误
  - IMAGE：`content` = JSON `{"url","width","height"}`；url 须本站上传域前缀（minio public-url），宽高可缺省；复用 `/api/files/upload`
  - CARD：`content` 恒空；`refType`（PRODUCT/POST/ERRAND）+ `refId` 必填；目标须存在且未删（post/product 非 DELETED/DRAFT、errand 非 DELETED/CANCELLED），失效报「内容已失效」
- 非成员 403
- **发送幂等**：同 `clientMsgId` 重复提交（双击/超时重发）撞 `uk_client_msg` 回查返回原消息，收敛同一条（已落库快路径 + 并发未落库回滚后回查两个时序）；幂等收敛优先于载荷校验，重发不受卡片目标失效影响
- 事务内：room 取 seq（UPDATE+1 读回，失败回滚无空洞）→ message INSERT → user_seq 取号 → timeline 扇出（恒两行，同时清空 hidden_at 弹回列表）→ user_room×2 只刷 `last_msg_time` 排序键（预览不落副本，读时按 message 真相现算）→ outbox MSG 推送事件（WS 推送归票 04）
- 200 返回完整消息体：`{roomId, messageId, seq, fromUserId, fromNickname, fromAvatar, msgType, status, content, refType, refId, clientMsgId, deliverySeq, createdAt}`（`status` NORMAL/RECALLED；`deliverySeq` 仅补拉返回）
- 失败消息不占 seq：所有校验拒绝发生在取号之前

### POST /rooms/{roomId}/messages/{seq}/recall
撤回消息（发送者 2 分钟窗口）。

- 仅发送者本人（非本人/会话外路人 403）；超 2 分钟拒绝（500）；消息不存在 404
- 占 seq 不回退、不动已读水位线（计入未读）；content 库内保留取证，读路径与补拉一律脱敏空壳
- 已撤回消息重复撤回幂等静默成功，不重复投递事件
- 事务内：message 三件套（state=RECALLED + recalled_at + recalled_by=USER）——**零 user_room 写**（列表「[已撤回]」由读时现算呈现，无预览维护写）→ outbox RECALL 轻事件（outbox `destination` = `bs-chat-recall`，载荷 `{roomId, seq}`，WS 推送归票 04）

## WS 长连接（票 04，spec §3）

### GET /ws/chat?ticket=

裸 WebSocket 端点（`spring-boot-starter-websocket`）。浏览器 WS 不能带自定义 header，JWT 不进 URL——先 `POST /ws-ticket` 换 60s 一次性票据再握手；拦截器对票据**原子取删**校验，无效/过期/重放返回 HTTP 401，客户端重新要票。连接存续期内不再鉴权。

- 安全链：`/ws/chat` 在 Spring Security 白名单，鉴权完全由握手拦截器承担
- 连接注册表 `Map<userId, session>`：单设备互踢——新连接先占位，旧连接收 `KICK` + close(4001)；close 回调两参注销，迟到 close 不误删新连接
- 心跳：客户端 25s 一次 `PING`；服务端任何入站帧刷新活跃时间，60s 无入站判死清理（close 1001）
- 服务端无业务路由、无 ACK：C→S 仅 `PING`；发送/已读/撤回全走 REST，WS 纯下行
- **多实例推送路由（Redis 路由表 + pub/sub 定向频道）**：推送决策在 case 推送服务（按会话成员扇出、组帧），投递统一经 domain 端口 `IWsPushPort` 收口到 infra WS 推送适配器（Redis 路由表与 pub/sub 频道均为其内部细节）：握手注册 `bs:ws-route:{userId} = 端点标识|sessionId`（TTL 90s，入站帧续期，断连 CAS 注销）；每个实例启动即订阅自己的定向频道 `bs:ws-push:{端点标识}`，适配器投递——本地注册表优先 → 路由不是自己则发布到目标实例频道（fire-and-forget，目标实例对本机注册表二次确认后推帧）；订阅者数为 0 / 本地 miss / 发布失败都清理路由条目后放弃。`better-school.ws.route.self-endpoint` 留空 = 单机形态，不注册路由、推送只走本地
- 推送失败不重试，漏推合法——重连后 `GET /timeline` 补拉兜底，客户端按 `(roomId, seq)` 判重合流

协议信封（JSON 文本帧）`{type, roomId?, seq?, payload?, ts}`：

| 方向 | type | 携带 | 说明 |
|---|---|---|---|
| C→S | `PING` | 无 | 唯一上行 |
| S→C | `PONG` | 无 | 心跳应答 |
| S→C | `MSG` | roomId, seq, payload=完整消息体（含发送者昵称头像） | 排除发送者本人 |
| S→C | `READ` | roomId, payload=`{userId, readSeq}` | userId 即 readerId；推给对方 |
| S→C | `RECALL` | roomId, seq | 轻事件只带定位，推给全部在线成员 |
| S→C | `KICK` | payload=`{reason: SESSION_TAKEN_OVER}` | 随后 close(4001)，客户端停止重连提示重新登录 |

## 站内通知（scope + 水位线已读模型，营销票 02）

已读 = 按 scope 推水位（O(1)），未读数 = 计数器 − 水位；逐行 is_read 与 read-all 已下线。scope 取值：`MARKET_ERRAND / MARKET_PRODUCT / BOTTOM_SYSTEM / INTERACTION_LIKE / INTERACTION_COMMENT`。

| 接口 | 方法 | 鉴权 | 要点 |
|---|---|---|---|
| `/api/chat/notifications` | GET | 登录 | `page/pageSize`（默认 1/10、上限 50）+ 可选 `scope` 过滤；响应 `read` 字段由 `seq ≤ 该范围水位` 算出 |
| `/api/chat/notifications/unread-count` | GET | 登录 | `?scope=` 必填；`{count}` |
| `/api/chat/notifications/read` | POST | 登录 | body `{scope}`，推水位到当前计数器值（各 scope 互不误清）；取代原 `read-all` |
| `/api/chat/notifications/broadcast` | POST | ADMIN | 公告广播（outbox + MQ 全用户落库，scope=BOTTOM_SYSTEM） |

- 非法 scope → 业务失败（`通知范围不合法`）
- 生产点归属映射见 docs/db/chat.md notification 节
