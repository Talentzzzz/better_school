---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by: 09, 06
---

# chat 落地票 10：前端成员列表页 / 卡片入口 / 全链路收尾

## Scope

依据 spec §7 改造点 6/7 + 收尾。

1. **成员列表页（全新路由页）**：详情页点顶栏进入，只读展示当前成员（昵称/头像）与成员数；被移除成员不出现（left_at 过滤由接口保证）；无任何操作入口。
2. **商品/帖子详情页「联系 TA」**：`POST /api/chat/rooms/direct`（幂等）→ 进会话；会话输入区提供卡片发送入口（CARD：PRODUCT/POST，ref 指向当前对象）；卡片渲染实时查对象。
3. **全链路收尾**：
   - 后端造数走一遍验收清单：1v1 全交互 + 系统群（建群/进出/封盘/SYSTEM 消息/群已读头像）。
   - 旧轮询接口残留确认：threads 系列端点前端无引用、三处定时器无残留。
   - 举报链路冒烟：举报消息 → AUDITOR 强制撤回 → 前端呈现。
   - `docs/` 或 README 无需变更则不动；CONTEXT.md 术语已收口不再改。

## 验收要点

- spec §0 Out of scope 未越界：无任何用户建群/拉人入口；notification 域零改动。
- 群会话可达性仅靠后端造数/集成测试（跑腿绑定是未来 effort）。

## Comments

- 落地：ChatMembersView 全新只读成员页（/chat/:roomId/members，NavBar 顶栏「成员」入口全房型可用，memberCount + 昵称/头像 + 「我」标记，非成员 403 显「无法获取成员列表」）；商品/帖子详情页「联系卖家/联系」push /chat/{roomId}?card=PRODUCT|POST:{id}，ChatDetailView 解析 query 建 pendingCard（实时查对象取标题，失效仅展示禁发），输入区上方卡片发送条（tag+标题+发送+移除，发送即清 query 刷新不重现），发送走 sendMessage CARD 分支乐观上屏 + ensureCard 喂 cardInfos 缓存（修「加载中…」停驻缺陷），失败重发复用 client_msg_id（retry 扩 CARD 分支）；ERRAND 不加发送入口（票面口径 PRODUCT/POST）。
- 全链路收尾验收（Playwright 双账号 A 浏览器 + B REST，造数群 room id=5）：1v1 实时收发（B REST 发 A 在线即收）、联系入口幂等（重复「联系卖家」复用 room 3）、商品/帖子卡片发送+点击跳转、1v1 成员页 2 人；系统群 SYSTEM 三类居中渲染（建群/加入/移除）、列表群名+群标+SYSTEM 预览+未读、群成员页 3 人且被移除者（left_at）不出现、A 群发真实消息全链路（扇出/timeline/WS）、B REST markRead → READ 推送 → A 侧已读者头像就地出现、封盘后「会话已结束」SYSTEM + 输入置灰「订单已结束」+ 历史可读；举报冒烟：B 发消息 → A REST 举报（targetType=MESSAGE）→ B(ADMIN) process PROCESSED → 强撤 recalled_by=AUDITOR + 读路径 content 置 null + 前端「已撤回」灰字 + 举报单 PROCESSED/handler 落值；旧轮询残留确认：threads 零引用，残留定时器仅 WS 25s 心跳与验证码倒计时（均既有接受项）。
- 造数注意：docker exec mysql 须加 --default-character-set=utf8mb4，否则中文群名/SYSTEM 文案以 latin1 落库变乱码（首次造数踩坑，重灌解决）；联调库 schema 漂移修复：content_report 缺票 05 的 handler_id/handled_at 两列（本地库未随 init.sql 同步），ALTER 补齐后举报处理链路通；造数群验收后已删除还原，举报/强撤数据（合法业务数据）保留。
- Out of scope 确认：无用户建群/拉人入口，notification 域零改动，docs 无需变更未动。
- code-review 采纳修正：发送条复用同文件既有 .card-tag/.card-title 样式（删第三份重复 tag 样式块）；PendingCard 收窄为 {refType,refId}，标题/失效态经 pendingCardInfo 与气泡渲染共用 cardInfos 缓存（同对象查一次，消除 initPendingCard 与 ensureCard 的 fetch-or-invalid 双份逻辑与重复请求）；ChatMembersView 通用命名 data→detail、.members-failed→.members-tip（加载态共用）。复验：卡片条+乐观上屏+发送全链路重走通过。
- code-review 留档未改项：①doSendText/doSendImage/doSendCard 三份发送状态机形状相同但载荷组装各异（图片上传/宽高/卡片 ref），沿用仓库既有惯例（票 07 同类判定）不强行抽参数化 sender；②?card=PRODUCT:{id} 线格式跨三文件为字面契约，规模（2 写 1 读）不足以立共享 helper，票面演进口若扩展 ERRAND 入口时再收口；③initPendingCard 把 404/403 与网络错误统一标失效态禁发（后端同样拒绝），与「宁缺勿错」既有口径一致，不做错误分类重试；④非成员带 ?card= 直达时发送必 403 落红叹号手动重发，后端守卫兜底接受。
- spec §7.6「展示当前成员与水位线」与票面 Scope 1「昵称/头像与成员数」出入留档：水位线数据已由详情页回执（members 初值+READ 推送）消费，成员页按票面收窄只读展示成员与数，不重复展示裸水位数字。
