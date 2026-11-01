# 04 调研：既有实时通道与在线状态设施盘点

> 调研日期：2026-10-13。方法：只查仓库内一手资料（源码 / DDL / 设计稿 / 架构文档），每条结论附文件路径与行号。
> 目的：给票 05（派单技术方案）判断「presence + 推送通道 + 原子 claim + 租约回收」四大件**可复用 vs 需新建**。
> 口径：**「查无实据」一律写「未找到，需新建」并列出检索范围，不臆测。**

---

## 0. 结论速览（票 05 用）

| 设施 | 现状 | 判断 |
|---|---|---|
| WebSocket 连接层 | **已设计 + 已落地**（Spring 原生裸 WS，落在单体 `bs-trigger`，端点 `/ws/chat`） | **连接层可复用**（抽通用）；**帧协议/推送服务与 chat 耦合，需扩展** |
| presence（在线集合） | **无独立设施**；只有 chat 的**内存连接注册表**（不可查、不落库、重启即失） | **需新建**（可用 chat 注册表 + Redis 作范式） |
| Redis | **已就绪并已在用**（Redisson；缓存 / 验证码 / WS 票据 / 营销库存桶；`RLock` 有先例） | **可直接用**（presence 集合、claim 锁） |
| 原子领取先例 | **有**：跑腿抢单 `grab`（条件 UPDATE，先到先得）+ 全站「状态机条件 UPDATE」范式；outbox 批量 CAS；超时中心分片槽位认领 | **范式可复用**（乐观 CAS）；**全仓无 `SELECT ... FOR UPDATE SKIP LOCKED`** |
| 管理端前端 | Vue3+Vite+Pinia 移动式；`/admin/review` 角色门控已就位；工作台**纯 onMounted 加载、零轮询** | 工作台是**拉模型雏形**；推信号+拉取需新建；独立工程 `front-review/` 需新建 |

> 一句话：**推送通道（后端连接层）+ Redis + 「条件 UPDATE 原子认领」三件都已有可复用底座；presence 在线集合与审核员派单逻辑需新建。**

---

## 1. WebSocket 现状

**结论：已设计、且已落地到生产代码（票 04/08 均 `Status: resolved`）。落点在单体 `bs-trigger`，非独立网关。**

### 1.1 设计稿（选型与方案）
- 选型：`​.scratch/chat-refactor/research/03-ws-选型调研.md` —— 定论「**Spring 原生裸 WebSocket + 自定义 JSON 信封协议 + 自管连接注册表**」，明确不选 STOMP / Netty / SSE（见该文 §5）。理由含「单机部署使内存注册表成立」。
- 方案：`​.scratch/chat-refactor/research/04-连接层设计.md` —— 连接层状态机、协议信封 `{type, roomId?, seq?, payload?, ts}`、心跳 25s / 判死 60s / 退避 1s×2 顶 30s / 互踢 close(4001) / 票据 60s 一次性。
- 落地票：`​.scratch/chat-impl/issues/04-ws-layer.md`（`Status: resolved`）、`​.scratch/chat-impl/issues/08-front-ws-store.md`（`Status: resolved`）。

### 1.2 后端落点与实现（单体 `bs-trigger`）
- 依赖：`backend/better-school/bs-trigger/pom.xml:36` `spring-boot-starter-websocket`。
- 端点注册：`backend/better-school/bs-app/src/main/java/com/betterschool/config/WebSocketConfig.java:18-28`（`@EnableWebSocket` + `WebSocketConfigurer`，映射端点）；容器空闲兜底 90s：同文件 `:36-39`。
- 端点常量：`bs-trigger/.../ws/ChatWsProtocol.java:8` `ENDPOINT = "/ws/chat"`。
- 握手鉴权：`bs-trigger/.../ws/ChatWsHandshakeInterceptor.java:29-43` —— query 参数 `ticket` 原子取删校验，失败 `401` 拒握手（浏览器 WS 不能带自定义 header，JWT 不进 URL）。
- 票据存储：`bs-infrastructure/.../adapter/port/chat/WsTicketPort.java:24-37` —— **Redis Redisson `RBucket`**，`issue` set + TTL 60s，`take` 用 `getAndDelete()` 原子取删（并发握手只有一个赢）。签发入口：`bs-trigger/.../http/ChatController.java:158-161` `POST /api/chat/ws-ticket`。
- **连接注册表（事实上的「在线集合」）**：`bs-trigger/.../ws/ChatConnectionRegistry.java:19` —— `Map<Long userId, WebSocketSession>`（`ConcurrentHashMap`，单机内存）；`register` 先占位后互踢（`:28-34`），`unregister` 两参 `remove(userId, session)` 防迟到 close 误删（`:37-39`）。
- 会话存活表：`bs-trigger/.../ws/ChatSessionRegistry.java:27,80-97` —— `lastInboundAt` + 30s 周期清扫、60s 无入站判死清理（容器层再兜 90s）。
- 帧写出器：`bs-trigger/.../ws/ChatWsPusher.java` —— JSON 序列化 + `ConcurrentWebSocketSessionDecorator` 并发写保护（JSR-356 禁并发 send）；`kick` 发 KICK 帧 + `close(4001)`。
- 推送服务：`bs-trigger/.../ws/ChatWsPushService.java:59-69` —— 按会话成员查注册表，**只给在线成员推、不在线跳过**（漏推合法，补拉兜底）。
- 推送触发（outbox→MQ→WS）：`bs-trigger/.../listener/ChatPushConsumer.java:21`（`@RocketMQMessageListener(topic = CHAT_PUSH_TOPIC)`）→ `ChatWsPushService.pushMessage`。
- Handler：`bs-trigger/.../ws/ChatWsHandler.java:43-61` —— **C→S 仅 `PING`，服务端无业务路由**（业务下行全由推送侧查注册表直推）。

### 1.3 前端 WS client（`front/`）
- 封装：`front/src/utils/chatWs.ts` —— 单例 `ChatWsClient`（导出 `chatWs`，`:190`）。含：要票据→`GET /ws/chat?ticket=` 握手（`:54-70`）；心跳 25s（`:18,144-151`）；退避 1s×2 顶 30s + 抖动（`:19-21,125-142`）；`visibilitychange`/`online` 立即重试（`:36-39,120-123`）；`KICK` / `close(4001)` → 提示 + `clearAuth` + 跳登录且**不重连**（`:84,96-99,172-181`）；`onOpen` 出口供重连对齐（`:48-52`）。
- dev 代理：`front/vite.config.ts:42-45` `/ws/chat` → `ws: true` 转发 8080。

### 1.4 「可复用 / 需新建」判断
- **连接层（票据鉴权 / 握手 / 注册表 / 心跳判死 / 互踢 / 并发写装饰器）逻辑上与业务无关，可整体复用**；但当前**端点写死 `/ws/chat`、帧类型写死 chat 语义（`PING/PONG/MSG/READ/RECALL/KICK`）、推送服务 `ChatWsPushService` 是 chat 专属**。
- 复用给审核端有两种落法：
  1. **同端点复用**：审核端也连 `/ws/chat`，服务端往注册表推一个新帧类型（如 `REVIEW_SIGNAL`）——改动最小，但语义命名错位；
  2. **泛化新建**：把注册表/票据/心跳这套骨架抽出，新增 `/ws/review` 端点与审核帧类型——**推荐**，成本可控（骨架照搬）。
- **前端 `chatWs.ts` 可复用骨架**（重连/心跳/退避/踢人处理齐全），但耦合 `@/api/chat` 的 `createWsTicket` 与 chat store 消费逻辑；复用需**泛化票据接口 + 拆出 chat 专属 handler**。
- 明确：**不存在「独立的通用 WS 网关」**，一切在单体 `bs-trigger` 内。

---

## 2. presence（在线状态）现状

**结论：未找到独立的 presence 设施，需新建。**

### 2.1 检索范围与结果
- 全仓 grep（不区分大小写）`presence` / `last_seen` / `lastSeen` / `last_active` / `lastActive` / `whoOnline` / `onlineUsers` / `在线状态` / `在线用户` → **零匹配**。
- 「谁在线」的唯一既有物是 chat 的 **`ChatConnectionRegistry`**（§1.2）：内存 `Map<userId, session>`，语义仅「该用户有一条活跃 WS 连接」。特征：
  - **不落库、不落 Redis**，进程内 `ConcurrentHashMap`；
  - **无对外查询接口**（只有 `get(userId)` 供推送用，`:41-43`）；
  - **不按角色过滤**（不区分 ADMIN / AUDITOR / 普通用户）；
  - **重启即失**（无常驻状态）。
- `ChatSessionRegistry.lastInboundAt`（`:32,62-74`）——内存中的心跳时间戳，30s 清扫，同样不可查、不落库。

### 2.2 判断
- **「在线审核员集合」需新建。** 可复用 chat 注册表的**范式**（内存 `Map<userId, session>` 或 Redis `RSet`/`RMap` 存在线 id），但**不存在现成实现**。
- 存哪：**项目是单机部署**（chat 设计稿明确「单机，内存注册表即终态」），内存即可满足；若要求「跨实例 / 可查 / 重启可恢复」，则用 **Redis**（见 §3，已有 Redisson 先例）。**票 05 需明确审核端部署形态后拍板。**

---

## 3. Redis 现状

**结论：Redis 已就绪且已在生产代码中使用（Redisson）。presence 集合与 claim 锁可直接用。**

### 3.1 中间件编排
- `backend/docker-compose.yml:91-106` —— `redis:8.8.0`，`requirepass bs123456`；dev `6379`（逻辑库 0）、test `16379`（逻辑库 1）；健康检查齐全。头注释 `:30` 亦注明 Redis 密码统一。
- `backend/scripts/test-env.sh:44,50` —— 测试栈容器含 `bs-redis`，测试端口 `redis 16379`。
- 客户端实现为 **Redisson**（`RedissonClient`）。

### 3.2 既有用途盘点（谁在用、做什么）
| 用途 | 位置 | 关键 API |
|---|---|---|
| **详情缓存 + 布隆 + 单飞锁** | `bs-infrastructure/.../adapter/cache/post/PostDetailCache.java` | `RBucket`（缓存 `:74,115`）、`RBloomFilter`（防穿透 `:56`）、**`RLock.tryLock`（防击穿单飞，`:140-143`）** |
| **短信验证码** | `bs-infrastructure/.../adapter/repository/user/UserRepository.java:108,114` | `RBucket` + TTL（`VERIFY_CODE_KEY + phone`） |
| **WS 票据** | `bs-infrastructure/.../adapter/port/chat/WsTicketPort.java:26,36` | `RBucket` + `getAndDelete`（一次性） |
| **营销库存桶/奖池桶** | `backend/marketing/.../inventory/adapter/port/InventoryBucketPort.java:39-87` | `RScript`（Lua 原子扣减）+ `RAtomicLong`；`RaffleRepository.java` 同类 |
| 测试侧 | `application-test.yml`（单体/营销） | 逻辑库 1 |

### 3.3 判断
- **presence 集合**：可用 `RSet`（在线审核员 id）或 `RMap<userId, lastSeenTs>`；**无先例但 API 现成**。
- **claim 锁**：**`RLock` 已有生产先例**（`PostDetailCache.java:140-143`，`tryLock(0, lease, SECONDS)`）；亦可走 **Lua 脚本原子操作**（营销库存桶先例）。→ **claim 的「原子取一条」可直接用 Redis，无须自建锁设施。**
- **注意口径**：仓库既有取向是「**Redis 只做快面，真相在 DB**」（见 `InventoryBucketPort.java:22-23` 注释）。派单 claim 若用 Redis 锁，**权威状态仍应落库**（与 §4 条件 UPDATE 一致），Redis 只承担加速/互斥。

---

## 4. 既有「原子领取」先例（claim 范式）

**结论：有。项目已确立「条件 UPDATE + 影响行数=1 者赢」的乐观 CAS 式原子认领范式；全仓无 `SELECT ... FOR UPDATE SKIP LOCKED`。**

### 4.1 最直接先例：跑腿「抢单」`grab`
- 契约：`bs-domain/.../errand/adapter/repository/IErrandRepository.java:24` —— 「抢单：`PUBLISHED → TAKEN`（状态 + 时限双校验，先到先得）」。
- 实现：`bs-infrastructure/.../adapter/repository/errand/ErrandRepository.java:76-82`（`dao.grab(...) > 0` 判定生效 + 落事件）。
- SQL：`bs-app/src/main/resources/mybatis/mapper/ErrandMapper.xml:98-106`：
  ```sql
  UPDATE errand_order SET taker_id=#{takerId}, state='TAKEN', update_time=NOW()
  WHERE errand_order_id=#{orderId} AND state='PUBLISHED' AND deadline > NOW()
  ```
  → 并发下**只有一条 UPDATE 影响 1 行**，其余 0 行（先到先得）。这就是本项目对「多消费者原子取一条」的标准答案。

### 4.2 全站状态机范式（可作 claim 的通用底座）
- `IErrandRepository.java:9`（类注释）：**「状态流转全部走条件 UPDATE（带状态校验），并发安全」**。所有迁移（支付/取货/送达/确认/取消/仲裁/超时）皆此形态（`ErrandMapper.xml:90-257`）。
- 该范式正是超时中心 spec 所说的「at-least-once 交给业务方，**幂等在业务方（状态机条件 UPDATE）**」（`backend/docs/architecture/timeout-center.md:104-105`）。

### 4.3 其他先例
- **outbox 消费（批量乐观 CAS）**：`bs-app/.../mybatis/mapper/OutboxMapper.xml:15-31` —— `selectPendingList`（`state=0 ORDER BY id LIMIT n`）→ `markSentBatch ... WHERE id IN (...) AND state=0`。扫描 `@XxlJob("outboxScan")`：`bs-infrastructure/.../mq/OutboxMessageSender.java:131`；**注释明写「单实例部署即可；多实例需加扫描锁」——即当前无分布式扫描锁**（`:129`）。
- **超时中心分片槽位认领**：`backend/docs/architecture/timeout-center.md:118-133` —— xxl-job `SHARDING_BROADCAST`，实例认领槽位 `g % shardTotal == shardIndex && g < 16`；批内结算三条 `UPDATE ... WHERE status IN ('WAITING','RETRYING')` 行级守卫（`:141-144`）。设计细节见 `.scratch/timeout-center/issues/04-grill-shard-scan-design.md:27-28`。
- **DB 悲观锁（`FOR UPDATE`，无 `SKIP LOCKED`）**：`backend/marketing/marketing-app/.../mapper/PointsMapper.xml:22,32`、`DrawCountMapper.xml:19,29`（FIFO 取桶 / 幂等预检 `LIMIT 1 FOR UPDATE`）。
- **Redis 锁**：见 §3.2（`RLock` 先例）。

### 4.4 判断
- **claim 可复用「条件 UPDATE（影响行数=1 者赢）」** —— 这是仓库自洽、团队已熟悉、跨域一致的范式；审核工单 `claim`（`state: PENDING → CLAIMED`，带 `WHERE state='PENDING'`）照此实现即可，**并发安全由 DB 保证，无需额外锁**。
- **不建议引入 `SELECT ... FOR UPDATE SKIP LOCKED`**（全仓无先例，且单机/单库 + 条件 UPDATE 已足够）。
- **租约回收**：无现成「租约」设施；超时中心的「`next_fire_time` 到点重投 + at-least-once + 业务方幂等」是**最接近的机制范式**（`timeout-center.md:149-153`）——审核工单超时回收可参考：`claim` 记 `lease_expire_at`，扫描 `WHERE state='CLAIMED' AND lease_expire_at < NOW()` 回 `PENDING`（照 `markTimeout` 条件 UPDATE 形态，`ErrandMapper.xml:181-188`）。

---

## 5. 管理端前端现状

**结论：Vue3+Vite+Pinia 移动式工程；`/admin/review` 角色门控已就位；工作台是纯 `onMounted` 加载、零轮询、无 WS。独立审核工程 `front-review/` 需新建。**

### 5.1 技术栈与构建
- `front/package.json:11-25` —— deps：`vue ^3.5`、`vue-router ^4.5`、`pinia ^2.3`、`axios ^1.8`；devDeps：`vite ^6.2`、`@vitejs/plugin-vue ^5.2`、`typescript ~5.7`、`sass ^1.83`、`vue-tsc ^2.2`。**无 UI 组件库**（组件手写）。
- 构建脚本：`package.json:8` `build: vue-tsc -b && vite build`；dev `vite`（`vite.config.ts:24` port 5173，`host: true`）。
- 形态：`front/` 是**移动式浏览器 Web**（布局窄、`tabbar` meta 等），非桌面后台。

### 5.2 审核工作台 `ReviewWorkbenchView.vue`（407 行）
- 路径：`front/src/views/admin/ReviewWorkbenchView.vue`。
- **拉队列方式：无轮询。** `onMounted(load)`（`:127`）单次加载；`load()`（`:43-54`）调 `listReviewQueue({targetType, page, pageSize})`；仅在**切 tab**（`:56-62`）与**翻页**（`:113-125`）时重载。**无 `setInterval` 轮询、无 WS 订阅。**
- tab：仅 `ERRAND / POST / COMMENT` 三类（`:10-14`）——**缺 `PRODUCT`（商品）tab，且无「求购」类型**（后端 `ReviewTargetTypeVO` 现为 `POST/COMMENT/ERRAND/PRODUCT`，`.scratch` 目标含「求购」，见 `map.md`）。
- 裁决接口：`decideReview(...)`（`:68,102`）→ `front/src/api/moderation.ts:14-28` `POST /api/moderation/review`（拒绝理由 8 枚举 `:17-26`，`approve=false` 时 `reasonCode` 必填）。
- 分页：`PAGE_SIZE = 20`（`:32`）。

### 5.3 后端审核接口与角色门控
- 队列与裁决（AUDITOR/ADMIN）：`bs-trigger/.../http/ModerationController.java:76-114` —— `POST /api/moderation/review`（`:76-83`，`requireAuditor()`）、`GET /api/moderation/queue`（`:85-102`，`pageSize ≤ 50`）、`GET /api/moderation/queue/{type}/{id}`（`:104-114`）。
- 统一裁决（**仅 ADMIN**）：`bs-trigger/.../http/AdminReviewController.java:21-40` —— `POST /api/admin/reviews/decide`（`requireAdmin()`，`SecurityUtils.currentRole()` 服务端强制）。**注意：前端工作台走的是 `/api/moderation/review`，不是这个 ADMIN-only 入口。**
- 角色定义：`bs-domain/.../user/model/valobj/UserRoleVO.java:9` `AUDITOR("内容审核员")`（另有 ADMIN）。
- **前端路由门控**：`front/src/router/index.ts:55` —— `/admin/review` → `meta: { requiresAuth: true, roles: ['ADMIN','AUDITOR'] }`；守卫 `:60-71` `router.beforeEach`：未登录跳 `/login`；`roles` 不含当前角色跳 `/home`（读 `auth.userInfo.role` 快照）。**服务端为权威（`SecurityUtils.currentRole()`），前端 gate 只是体验层。**

### 5.4 判断
- **审核端独立工程 `front-review/`：需新建。**
- **既有 `front/` 的复用价值**：同栈脚手架（Vue3+Vite+Pinia+vue-router+axios+SCSS，无 UI 库）、`api/http` 封装与 token 拦截、路由角色门控写法、`utils/chatWs.ts` 的 WS 骨架——可直接照搬。**但工作台 UI 需重做**（现为移动式单列卡片 + 下拉分页，票据目标是「多类型同屏 + 推信号 + 拉工单」，且缺 PRODUCT/求购 tab）。

---

## 6. 明确「未找到，需新建」清单（含检索范围）

| 项 | 检索范围 | 结论 |
|---|---|---|
| 独立 presence 设施（在线集合 / 心跳注册表对外可查 / last_seen 落库） | 全仓 grep `presence|last_seen|lastSeen|last_active|lastActive|whoOnline|onlineUsers|在线状态|在线用户` | **未找到，需新建**（仅有 chat 内存注册表，不可查、不落库） |
| 通用 WS 网关 / 独立长连接服务 | 全仓 `WebSocketConfig` / `@EnableWebSocket` / `@ServerEndpoint` | **未找到**（唯一实现在单体 `bs-trigger` 的 `/ws/chat`） |
| `SELECT ... FOR UPDATE SKIP LOCKED` | 全仓 grep `SKIP LOCKED`（大小写） | **未找到**（仓库有意走条件 UPDATE，非队列式跳锁） |
| 跨实例分布式扫描/认领锁 | outbox 扫描注释（`OutboxMessageSender.java:129`）、超时中心分片 job | **未实现**（现单机；多实例「需加扫描锁」仅留注） |
| 审核工单「租约 / 心跳续约 / 超时回收」设施 | 全仓 `lease|租约`、moderation 域 | **未找到，需新建**（可借超时中心 `next_fire_time` 重投范式） |
| 审核端独立前端工程 | `front/` 目录 | **未找到，需新建**（`front-review/`；`front/` 仅作同栈参考） |

---

## 7. 给票 05 的落点建议（非决策，仅事实推论）

1. **推送通道**：复用后端连接层骨架（票据 / 注册表 / 心跳 / 踢人 / 并发写装饰器），新增审核帧类型（如「有新工单」广播信号）+ 泛化前端 WS client。**不必自建第二条管道，也不必引 STOMP/Netty。**
2. **presence**：新建「在线审核员集合」；单机可内存，可查/可恢复则用 Redis（`RSet`/`RMap`）。角色过滤（仅 AUDITOR/ADMIN）是新逻辑。
3. **原子 claim**：**复用「条件 UPDATE，影响行数=1 者赢」**；审核工单表加 `claim_state`（`PENDING/CLAIMED/DONE`）+ `claim_by` + `lease_expire_at`，claim 即 `UPDATE ... SET claim_by=#{uid}, state='CLAIMED', lease_expire_at=... WHERE id=#{id} AND state='PENDING'`。
4. **租约回收**：参考超时中心（`next_fire_time` 到点重投 + 业务方幂等）或 `markTimeout` 条件 UPDATE 形态；无需新扫描设施，可挂现有 xxl-job 或超时中心回调（锁单超时本已要接超时中心，见 `map.md`）。
5. **前端**：`front-review/` 新建，同栈照搬；工作台补 PRODUCT/求购 tab，改造为「推信号唤醒 + 拉工单」模型（现为纯 onMounted 拉列表，改造空间干净）。

---

## 附：关键文件索引

后端（单体 `backend/better-school/`）：
- `bs-app/src/main/java/com/betterschool/config/WebSocketConfig.java`（WS 端点注册）
- `bs-trigger/src/main/java/com/betterschool/trigger/ws/`（`ChatWsHandler` / `ChatWsHandshakeInterceptor` / `ChatConnectionRegistry` / `ChatSessionRegistry` / `ChatWsPusher` / `ChatWsPushService` / `ChatWsProtocol` / `ChatWsFrame(Type)`）
- `bs-trigger/src/main/java/com/betterschool/trigger/listener/ChatPushConsumer.java`（outbox→MQ→WS）
- `bs-trigger/src/main/java/com/betterschool/trigger/http/{ChatController,ModerationController,AdminReviewController}.java`
- `bs-infrastructure/src/main/java/com/betterschool/infrastructure/adapter/port/chat/WsTicketPort.java`（Redis 票据）
- `bs-infrastructure/src/main/java/com/betterschool/infrastructure/adapter/cache/post/PostDetailCache.java`（RLock 先例）
- `bs-app/src/main/resources/mybatis/mapper/{ErrandMapper,OutboxMapper}.xml`
- `bs-domain/src/main/java/com/betterschool/domain/errand/adapter/repository/IErrandRepository.java`

Redis / 编排：
- `backend/docker-compose.yml`、`backend/scripts/test-env.sh`

文档 / 设计稿：
- `.scratch/chat-refactor/research/03-ws-选型调研.md`、`04-连接层设计.md`
- `.scratch/chat-impl/issues/04-ws-layer.md`、`08-front-ws-store.md`
- `backend/docs/architecture/timeout-center.md`、`backend/docs/db/outbox.md`
- `.scratch/timeout-center/issues/04-grill-shard-scan-design.md`

前端：
- `front/src/utils/chatWs.ts`、`front/src/views/admin/ReviewWorkbenchView.vue`、`front/src/api/moderation.ts`、`front/src/router/index.ts`、`front/vite.config.ts`、`front/package.json`
