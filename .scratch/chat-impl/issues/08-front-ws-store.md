---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by: 04
---

# chat 落地票 08：前端 WS 客户端与 rooms store

## Scope

依据 spec §7 改造点 1/2（+KICK 处理），`front/` 为移动式 Vue3+Pinia，无存量适配工作。

1. **WS 客户端（全新模块）**：登录即连/登出断开；先 `POST /api/chat/ws-ticket` 再连 `GET /ws/chat?ticket=`；心跳 PING 25s；断线退避重连 1s×2 上限 30s 带抖动、visibilitychange/网络恢复立即重试；退避到顶仍失败停在断开并提示重新登录；KICK/close(4001) → 提示「账号已在其他设备登录」→ 清 token 跳登录**不重连**。
2. 重连对齐：连上后并行触发 `GET /api/chat/timeline?afterDeliverySeq=N`（不阻塞渲染），delivery_seq 游标**内存持有不持久化**（刷新 0=全量永远正确，spec §2）；不足一页即追平否则循环拉。
3. **rooms 共享 Pinia store（全新）**：会话列表数据+unread+lastMsg 单一来源，未读总数派生（替代 `stores/unread.ts` 轮询，5s 定时器拆掉）；消费 MSG（last_msg/未读+1/置顶重排/hidden 弹回）、READ（水位线就地更新）、RECALL 事件就地更新。
4. 协议判重：MSG 按 (roomId, seq) 判重（补拉与推送竞争合流）；游标只被补拉推进。
5. `src/api/chat.ts` 按票 03 接口清单重写（rooms 模型）。

## 验收要点

- 全前端无任何 setInterval 轮询残留（三处定时器全拆）。
- 收到 MSG 推送未进详情页时会话列表未读+1 置顶；刷新页面全量补拉追平。
- 被踢场景手动模拟（双开 tab）跳登录。

## Comments

resolved：WS 客户端 + rooms store + 三定时器全拆，构建绿 + Playwright 双账号实链路验收——api/chat.ts 按票 03 十端点重写（ws-ticket 返回 {ticket} 对象非字符串，实测修正）；utils/chatWs 单例（票据换连/25s PING 心跳/退避 1s×2 顶 30s+抖动/退避到顶停提示重新登录/visibilitychange+online 立即重试/KICK 与 close(4001) 双入口→提示+clearAuth+跳登录不重连，onOpen 出口供重连对齐）；stores/chatRooms 单一来源（未读派生 unreadTotal、(roomId,seq) 判重 appliedSeq、delivery_seq 游标仅 syncLoop 推进内存持有、首屏全量补拉 unread 以 loadRooms 服务端值为准不双计、MSG 置顶重排+未知会话去抖刷新（hidden 弹回）、READ 就地更新成员水位 map[roomId][userId]（详情页回执数据源）、RECALL 撤最后一条就地改预览、onMessage 判重后统一出口推送+补拉双源）；stores/unread.ts 删除，unread 5s/列表 8s/详情 5s 定时器全拆（残留 setInterval 仅登录页倒计时与 25s 心跳，均非轮询）；vite 代理补 /ws/chat ws:true；三详情页 createThread→createDirectRoom；路由参 threadId→roomId。code-review 修正：READ 消费与重连补拉两处票面缺口补齐、applyMessage 监听通知移到状态更新后（否则详情页打开期间自动已读因 unread 未就绪而漏推水位线，实测水位线停在旧值）、sending 死 ref 删除、SYSTEM 文案/展示名/头像三助手收口 utils/chat.ts（DIRECT 会话 name 为空须用 peerNickname）、退避到顶文案对齐票面「请重新登录」。留档：双开 tab 互踢时同文档刷新竞态（旧文档 KICK 处理清共享 token 会连带登出新文档）属同浏览器共享 localStorage 固有现象，跨设备无此问题，接受；ChatDetailView 已含票 09 部分要素（乐观发送/失败重发/SYSTEM 渲染/SEALED 置灰），票 09 重写时按其票面重审。
