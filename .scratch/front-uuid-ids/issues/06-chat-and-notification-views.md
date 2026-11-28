# 06: 会话与通知视图簇改字符串 ID（含业务卡片复合参数）

**What to build:** 会话列表（判重 / 未读 / 删除）、聊天详情（分页 / 已读回执 / 撤回 / 业务卡片渲染与发送）、市集消息页、联系人搜索、通知页与顶部横幅，ID 全部按字符串；`?card=<refType>:<id>` 的合法性判据从「正整数」换成「非空且形如 UUID」。

**Blocked by:** 01, 02

**Status:** ready-for-agent

票 01 之后 `ChatMessage.roomId` / `messageId` / `refId`、`ChatRoomSummary.roomId`、`Notification.id` / `refId` 等都是 `string`；票 02 之后 `/chat/:roomId` 不限数字，`chatRooms` 的 `appliedSeq` / `readSeqs` 已是字符串键、`roomById(roomId: string)`。本票只改视图层。

## 1. `front/src/views/chat/ChatDetailView.vue`（本簇主战场）

- `const roomId = String(route.params.roomId ?? '')`（禁止 `Number()`）。
- `optimisticRow`：`messageId: ''`（原来占位 `0`）；`seq: Number.MAX_SAFE_INTEGER` **保持**（消息序号仍是数值）；`fromUserId: auth.userId` 现在推到 `string | null`，与 `ChatMessage.fromUserId` 一致。
- `CARD_FETCHERS: Record<string, (id: string) => Promise<string>>`、`CARD_ROUTES: Record<string, (id: string) => string>`。
- `interface PendingCard { refType: 'PRODUCT' | 'POST'; refId: string }`。
- `initPendingCard()` 改为「按**第一个冒号**切分 + UUID 形态校验」：
  - `const i = raw.indexOf(':')`；`i < 0` 直接 return；`const refType = raw.slice(0, i)`、`const refId = raw.slice(i + 1)`；
  - 判据：`(refType === 'PRODUCT' || refType === 'POST')` 且 `refId` 匹配 `/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i`（删除 `Number.isInteger` / `<= 0` 判据，删除 `Number(idStr)`）；
  - 不合法 → 静默丢弃（原行为）；合法 → `pendingCard.value = { refType, refId }` 并 `ensureCard`。
- `watermark(userId: string)`：`chatRooms.readSeqs[roomId]?.[userId] ?? 0` —— 水位线返回值仍是 `number`。
- `READ` 载荷：`{ roomId: string; userId: string; readSeq: number }`；`RECALL` 载荷：`{ roomId: string; seq: number }`。载荷字段为字符串后按等值比对（详情页 `payload?.roomId !== roomId`）、`seq` 仍用真值判断；`stores/chatRooms.ts` 的 READ 真值判空保留（空串不是合法 ID）。
- `systemInfoOf` 的 `SystemPayload.refId` 本来就是 `string`，不动；`CARD` 气泡的 `cardInfos` 键 `${refType}:${refId}` 是字符串拼接，不动。
- 其余 `msg.roomId !== roomId` / `row.msg.clientMsgId` / `msg.seq <= maxSeq` 比对保持。

## 2. `front/src/views/MessagesView.vue`

- `menuRoomId = ref<string | null>(null)`（会话菜单选中项）。
- `chatRooms.roomById(menuRoomId.value ?? '')`。
- `openMenu(roomId: string)`。

## 3. `front/src/views/msg/MarketMessagesView.vue`

- `openRoom(roomId: string)`。

## 4. 审计文件（预计零改动）

`front/src/views/chat/ContactSearchView.vue`（`:key="r.roomId"`、`/chat/${r.roomId}`）、`front/src/views/msg/NotificationsView.vue`（`:key="n.id"`）、`front/src/components/NotifBanner.vue`（横幅按字符串 id 去重）。发现 `number` 注解或数值解析才改。

## 不碰

`front/src/stores/chatRooms.ts`、`stores/notices.ts`（票 02 已改）、`api/*`、`types/*`、`router`；帖子 / 交易 / 营销视图（票 03–05、07）。不改版式、文案、交互。

## 验收（静态检索自查，**不跑构建 / 类型检查 / 测试**）

- [ ] `front/src/views/chat/`、`front/src/views/MessagesView.vue`、`front/src/views/msg/` 中 `Number(` / `parseInt(` 命中 0（`Number.MAX_SAFE_INTEGER` 不算解析，保留）。
- [ ] `?card=` 判据只剩 UUID 正则 + `refType ∈ {PRODUCT, POST}`，没有 `Number.isInteger`、没有 `<= 0`、没有 `parseInt`。
- [ ] 聊天链路逐行读一遍：会话列表 → 进聊天（字符串 roomId）→ 拉消息分页 → 已读回执（`readSeqs[roomId][peerUserId]` 字符串键）→ 撤回 → 卡片渲染 / 点击跳转 / 发送；推送 MSG 按 `(roomId, seq)` 判重两侧口径一致。
- [ ] `git diff --stat` 只包含本票文件。
