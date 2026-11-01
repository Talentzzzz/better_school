# 04 research：既有实时通道与在线状态设施盘点

Type: research
Status: resolved
Blocked by: 无

## Question

**AFK 调研**。票 05（派单技术方案）要在「presence（在线状态）+ 推送通道 + 原子 claim + 租约回收」上拍板。用户对基础设施的态度是「**有没有不重要，缺什么做什么**」——但**先盘清有什么，才能判断该建什么、别重复造**。

盘点范围（仓库内一手资料）：

1. **WebSocket 现状**：`.scratch/chat-refactor/`（私信重构）与 `.scratch/chat-impl/` 是否已设计/落地了一套 WS 连接层？技术选型是什么（原生 WS / STOMP / SSE）？落在哪个模块（`bs-trigger`？独立网关？）？`front/` 侧有没有可复用的 WS client 封装与重连逻辑？**能否直接给审核端复用**，还是审核端要自建一条。
2. **在线状态（presence）现状**：既有代码里有没有「谁在线」的维护（心跳、last_seen、连接注册表）？存在哪（Redis / 内存 / DB）？
3. **Redis 现状**：`backend/docker-compose.yml` 与 `scripts/test-env.sh` 里有没有 Redis？既有代码哪个域在用、用来做什么（缓存 / 分布式锁 / 会话）？——决定 presence 集合与 claim 锁能不能直接用 Redis，还是必须走 DB（`SELECT ... FOR UPDATE SKIP LOCKED`）。
4. **既有「原子领取」先例**：仓库里有没有「多消费者原子取一条任务/消息」的既有实现（outbox 消费、MQ 消费、xxl-job 分片认领）可作 claim 的范式参考。
5. **管理端前端现状**：`front/src/views/admin/ReviewWorkbenchView.vue`（407 行）现在怎么拉队列、轮询还是别的；`front/` 的技术栈与构建方式（Vue 3 + Vite？`front/src/router` 的角色门控怎么写的）——供票 07 原型与票 08 独立工程形态参考。

**产出**：`.scratch/errand-lock-review-hub/research/04-realtime-presence-inventory.md`，逐项结论 + 关键文件路径与行号 + 「可复用 / 需新建」判断；作为票 05 的事实底座。

## Answer

调研正文：[research/04-realtime-presence-inventory.md](../research/04-realtime-presence-inventory.md)。五项结论：

1. **WebSocket：已落地，可复用（需泛化）。** chat 私信已实装一套「Spring 原生裸 WS + 自定义 JSON 信封 + 自管内存注册表」连接层，落在**单体 `bs-trigger`**（非独立网关），端点 `/ws/chat`（`bs-app/.../config/WebSocketConfig.java:18-28`）；含一次性票据鉴权（`ChatWsHandshakeInterceptor.java` + Redis `WsTicketPort.java`）、连接注册表（`ChatConnectionRegistry.java:19`）、心跳判死（`ChatSessionRegistry.java:27`）、互踢 close(4001)（`ChatWsPusher.java`）、前端单例 client（`front/src/utils/chatWs.ts`）。**骨架与业务无关可整体复用；端点与帧类型写死 chat 语义，需扩展审核帧类型**（推荐泛化出 `/ws/review`）。
2. **presence：未找到，需新建。** 全仓无 `presence/last_seen/online` 设施；唯一「谁在线」是 chat 的**内存** `Map<userId,session>`，不可查、不落库、不按角色过滤、重启即失。审核端「在线审核员集合」需新建——单机内存即可，可查/可恢复则用 Redis。
3. **Redis：已就绪并已在用，可直接复用。** `docker-compose.yml:91-106`（redis:8.8.0，dev 6379 / test 16379），客户端为 **Redisson**；既有用途：帖子详情缓存 + 布隆 + **`RLock` 单飞锁**（`PostDetailCache.java:140-143`）、验证码、WS 票据、营销库存桶（Lua）。→ **presence 集合与 claim 锁都能直接用 Redis，`RLock` 已有生产先例。**
4. **原子领取：有范式，可复用。** 最直接先例是跑腿**抢单 `grab`**（`ErrandMapper.xml:98-106`：`UPDATE ... WHERE state='PUBLISHED' AND deadline>NOW()`，「条件 UPDATE、影响行数=1 者赢、先到先得」），也是全站状态机的统一并发范式（`IErrandRepository.java:9`）；另有 outbox 批量 CAS（`OutboxMapper.xml:15-31`）与超时中心分片槽位认领（`timeout-center.md:120-133`）。**全仓无 `SELECT ... FOR UPDATE SKIP LOCKED`**，claim 照「条件 UPDATE」实现即可，租约回收可借超时中心「到点重投 + 业务方幂等」范式。
5. **管理端前端：工作台是拉模型雏形、独立工程需新建。** `ReviewWorkbenchView.vue` **纯 `onMounted` 加载、零轮询、无 WS**（`:127,43-54`），裁决走 `POST /api/moderation/review`（`api/moderation.ts:14-28`），tab 仅 ERRAND/POST/COMMENT（缺 PRODUCT/求购）；`front/` 为 Vue3.5+Vite6+Pinia2+vue-router4+axios+SCSS（无 UI 库）的移动式工程，`/admin/review` 已按 `meta.roles:['ADMIN','AUDITOR']` 门控（`router/index.ts:55,60-71`）。→ **`front-review/` 独立工程需新建，可照搬同栈与 WS client 骨架。**
