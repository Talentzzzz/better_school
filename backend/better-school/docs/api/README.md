# 接口总览

单体与营销服务各自一套 controller，HTTP 路径统一 `/api/**`（前端单实例，Vite 按前缀代理到不同服务）。

## 单体（`backend/better-school/bs-*`，8080）

| 模块 | 文档 | 路径前缀 |
| --- | --- | --- |
| 登录注册与公开资料 | [user.md](user.md) | `/api/user` |
| 校园墙（帖子/评论/点赞/举报） | [community.md](community.md) | `/api/community` |
| 二手交易（商品/订单/收藏） | [trade.md](trade.md) | `/api/trade` |
| 跑腿 | [errand.md](errand.md) | `/api/errand` |
| 私信 / 通知（含已读水位线） | [chat.md](chat.md) | `/api/chat` |
| 文件上传（MinIO） | [files.md](files.md) | `/api/files` |
| 内容治理（审核/举报/敏感词） | [moderation.md](moderation.md) | `/api/moderation` |
| 站内搜索 | [search.md](search.md) | `/api/search` |
| 虚拟钱包 | [wallet.md](wallet.md) | `/api/wallet` |
| 信用分 | [credit.md](credit.md) | `/api/credit` |
| 内嵌超时中心管理面（死信，运维面） | [timeout.md](timeout.md) | `/api/admin/timeout-tasks` |

营销服务接口文档在 `backend/marketing/docs/api/`。超时任务没有业务 HTTP/Dubbo 接口——注册与回调都在进程内。
