# 02: 缝② 路由参数不限数字 + 全局状态按字符串建索引

**What to build:** 六条业务详情路由去掉 `(\d+)` 限制，参数作为不透明字符串；`chatRooms` / `notices` 两个全局状态里按 ID 建的索引改成字符串键。这是「点了没反应」与「判重/未读/已读全失效」的直接原因。

**Blocked by:** 无（与票 01 文件不重叠，可并行；语义上依赖票 01 的线契约）

**Status:** ready-for-agent

## 1. `front/src/router/index.ts`

六条路由去掉数字正则（**保留参数名**，`to.params.id` 在视图里仍是 `String`）：

- `/post/:id(\d+)` → `/post/:id`
- `/product/:id(\d+)` → `/product/:id`
- `/user/:userId(\d+)` → `/user/:userId`
- `/chat/:roomId(\d+)` → `/chat/:roomId`
- `/idle/order/:id(\d+)` → `/idle/order/:id`
- `/trade/order/:id(\d+)` → `/trade/order/:id`（兼容重定向保留，参数原样转发）

`/pay/:payNo` 已经是非限制参数，不动。catch-all 兜底保持（旧数字形链接不迁移：UUID 解析失败落到视图的空态，不为下线形态写兼容分支）。

## 2. `front/src/stores/chatRooms.ts`

- `appliedSeq: {} as Record<string, number>`
- `readSeqs: {} as Record<string, Record<string, number>>`（外层会话 ID、内层用户 ID 都是字符串；水位线 `number` 不变）
- `roomById: (s) => (roomId: string) => ...`（比对关系不变，只是两侧同为字符串）
- `bindWs`：`READ` 载荷改 `{ roomId: string; userId: string; readSeq: number }`；`RECALL` 载荷改 `{ roomId: string; seq: number }`；`MSG` 载荷就是 `ChatMessage`，无需改。
- `applyRecall(roomId: string, seq: number)`、`markRead(roomId: string)`、`hideRoom(roomId: string)`、`mergeReadSeq(roomId: string, userId: string, readSeq: number)`。
- `mergeReadSeq` 里的 `Math.max(map[userId] ?? 0, readSeq)` 是水位线比较，保持不动。

## 3. `front/src/stores/notices.ts`

- `NoticeBanner.refId: string | null`
- `NoticeNotifPayload.refId?: string`
- 其余（`seq` 水位、`id: \`${scope}:${n.seq}\``、`loadCursor` 的 `Number(raw)`）都是数值语义，**保持不动**——`Number(raw)` 读的是 localStorage 里的 seq，不是 ID。

## 4. 不碰

`front/src/types`、`front/src/api`（票 01）；`front/src/views`、`front/src/components`（票 03–07 各自文件）。`front/src/stores/auth.ts` 的 `userId` getter 由类型自动推成 `string | null`，本票不改。

## 验收（静态检索自查，**不跑构建 / 类型检查 / 测试**）

- [ ] `front/src/router/index.ts` 中 `\\d+` 命中 0。
- [ ] `front/src/stores/` 中 `Record<number` 命中 0。
- [ ] `chatRooms.ts` 里所有 roomId / userId 形参与载荷类型都是 `string`，`seq` / `readSeq` / `unread` 仍是 `number`。
- [ ] `git diff --stat` 只包含本票文件。
