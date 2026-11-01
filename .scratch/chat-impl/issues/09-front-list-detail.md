---
Labels: wayfinder:task
Type: task
Status: resolved
Blocked by: 08
---

# chat 落地票 09：前端会话列表与详情页重写

## Scope

依据 spec §7 改造点 3/4/5。

1. **MessagesView 纯渲染**：数据全部来自 rooms store；长按会话「删除该会话」（本地立即消失 + `POST hide`，新消息推送到达自动弹回——store 已处理）；群/单聊区分（群头像+群名）；`MessagesView` 的 8s 定时器拆掉。
2. **ChatDetailView 重写**：
   - 历史消息按 `?beforeSeq=` 倒序分页，上滑加载更早（替代 afterId）；首屏即调 `POST /read`（不等补拉）。
   - 发送状态机：client_msg_id 乐观上屏 pending → 200 转正 → 失败/超时红叹号**手动**重发（复用同 client_msg_id）。
   - 长按自己消息撤回菜单（2 分钟窗口内显示），就地「已撤回」灰字。
   - 渲染：TEXT 气泡 / IMAGE 单图（复用 `/api/files/upload`，多图拆条）/ CARD 实时渲染+失效态「内容已失效」/ SYSTEM 居中灰字渲染服务端文案。
   - SEALED 会话输入框置灰「订单已结束」。
   - 5s 定时器拆掉。
3. **已读回执（群聊全量，spec §7.5）**：打开详情页拉一次 `GET members`（捎带 last_read_seq）建成员水位 map；READ 推送按 userId 就地更新。1v1 自己消息旁「已读/未读」小字；群所有消息（含他人消息）下方已读者头像前 3+省略、全读显示「全部已读」、SYSTEM 不显示、被移除者不计；判定=消息 seq ≤ 成员水位线纯前端推导。

## 验收要点

- 双开浏览器：A 发消息 B 详情页实时收到；B 打开会话 A 收到 READ 翻转「已读」。
- 断网收消息 → 恢复后重连补拉追平无重复无丢失（判重生效）。
- 撤回/失败重发/失效卡片/封盘置灰（封盘会话靠后端造数）。

## Comments

- 落地：MessagesView 长按删除会话（v-longpress 指令 + ActionSheet 组件 + store.hideRoom 本地立即消失，新消息推送经 scheduleRefresh 弹回，服务端 hidden_at 清空实证）；ChatDetailView 重写（beforeSeq 倒序分页上滑加载/滚动位置保持、发送状态机含 IMAGE 多图拆条（files/upload + content JSON {url,width,height} + blob 本地预览）、CARD 实时查对象（PRODUCT/POST/ERRAND fetcher/route 映射表）+失效态、长按撤回 2 分钟窗口 + RECALL 推送就地灰字、SEALED 置灰「订单已结束」、已读回执 members 初值 + READ 推送入 chatRooms.readSeqs、1v1 已读/未读小字、群已读者头像前 3+省略/全部已读，纯前端 seq≤水位线推导）。
- code-review 关键修正：loadOlder 原实现把旧页走 applyIncoming 被 seq 判重全部丢弃（上滑分页空转，早前「58 条全上屏」实为补拉喂入的空真）——改专用 prepend 路径绕过判重，实证 50→60 行、首行变最老消息、60 条全唯一、滚动位置保持。
- 其余 review 修正：ActionSheet 组件抽取（两视图重复 40 行）、store.mergeReadSeq 收口水位合并（READ 处理与 members 初值共用）、v-longpress 触发后 capture 一次性吞 click（卡片长按撤回不再同时触发跳转，实测 url 不变）、members 拉取失败时 1v1 回执宁缺勿错（无水位不显示「未读」）。
- Playwright 双账号验收（A 浏览器 + B REST）：实时收发、B markRead 后 A 侧未读→已读翻转、断线两条→恢复自动补拉无重复、撤回（TEXT+CARD）就地灰字、失败拦截→红叹号→重发幂等收敛一条、失效卡片「内容已失效」、SEALED 置灰（DB 造数后恢复）、hide 弹回。
- 接受边缘：2 成员 GROUP 且 rooms store 未命中（深链/刷新瞬态）按 1v1 渲染（群头像/回执缺失），room 归位后自愈；群聊已读头像未实测（系统群无用户入口，归票 10 后端造数验收）。
- 验证中复现票 08 已留档的「同浏览器同文档刷新竞态」（goto 刷新旧文档收 KICK 清共享 token 连带登出新文档），非本票引入，跨设备不存在。
