# 接口总览

单体与营销服务各自一套 controller，HTTP 路径统一 `/api/**`（前端单实例，Vite 按前缀代理到不同服务）。

**ID 口径（uuid-biz-id 起）**：所有业务 id 字段（`userId` / `postId` / `commentId` / `productId` / `productOrderId` / `roomId` / `msgId` / `notificationId` / `walletId` / `taskId` / `refId` / 营销侧 `recordId` / `prizeId` / `redeemOrderId` 等）一律为 **UUID 字符串**（36 位连字符规范形，如 `3f2a1c9e-8b47-4d20-9a15-7e6c0d84f1ab`），可被 `UUID.fromString` 解析；客户端把 id 当不透明字符串透传即可，**不要做数字化处理**（JS `Number()` 会精度丢失，路由正则不得限 `\d+`）。新建资源返回的 id 直接可用于后续所有引用。

## 单体（`backend/better-school/bs-*`，8080）

| 模块 | 文档 | 路径前缀 |
| --- | --- | --- |
| 登录注册与公开资料 | [user.md](user.md) | `/api/user` |
| 校园墙（帖子/评论/点赞） | [post.md](post.md) | `/api/community` |
| 二手交易（商品/订单/收藏） | (idle.md)(idle.md) | `/api/idle` |
| 私信 / 通知（含已读水位线） | [chat.md](chat.md) | `/api/chat` |
| 文件上传（MinIO） | [files.md](files.md) | `/api/files` |
| 站内搜索 | [search.md](search.md) | `/api/search` |
| 虚拟钱包 | [wallet.md](wallet.md) | `/api/wallet` |
| 信用分 | [credit.md](credit.md) | `/api/credit` |
| 内嵌超时中心管理面（死信，运维面） | [timeout.md](timeout.md) | `/api/admin/timeout-tasks` |

营销域接口文档在 `backend/docs/marketing/api/`（票 05/07 起营销四域并入单体，HTTP 8080；路径前缀 `/api/points`、`/api/lottery`、`/api/marketing/*` 不变）。超时任务没有业务 HTTP/Dubbo 接口——注册与回调都在进程内。
