# 审核中台（front-review）

统一审核中台的独立前端工程（票 12 · effort `errand-lock-review-hub-impl`）：同屏审**帖子 / 评论 / 商品 / 跑腿**四类内容，交互闭环为「收到派单信号 → 拉一条 → 租约倒计时 → 审完可再拉 → 租约到点自动回收 → 回收后可再拉」。

由设计期可点原型（原 `.scratch/errand-lock-review-hub/prototype/`，票 07 产出）按 spec §5.4 整体平移而来，并已把离线假数据全部换成真链路（后端票 06–11：工单池 / 详情聚合 / 领单租约 / 裁决强绑定 / WS 派单信号）。

## 技术栈与工程形态

与主前端 `front/` 同栈：**Vue 3 + Vite + TypeScript + SCSS**（手写组件、无 UI 库）；独立 `package.json` / `tsconfig` / `vite.config`，**独立版本、解耦发版**（不进 CI）。信息架构承原型：顶部**信号条** + **类型计数条**（只读）+ 主区**单条聚焦**工单（内容 / 作者 / 历史裁决 / 拒绝理由枚举 + 补充说明）+ 右栏**状态与事件流**；**仅桌面**（最小宽度 1200px）。

## 跑起来

```bash
# 前置：主后端已在 8080（MySQL/Redis 等按仓库惯例起）
cd front-review
npm install
npm run dev          # http://localhost:5174/
# 或：npm run build && npm run preview
```

开发端口独立 **5174**（主前端 5173）；`vite.config.ts` 已把 `/api` 与审核长连接 `/ws/review`（ws: true）代理到主后端 8080。

## 鉴权与角色门控

沿用既有登录令牌（`localStorage.bs_token` / `bs_user`，与主站同 key 同形）+ `Authorization: Bearer`，**不新建账号体系/SSO**。双保险：

- **客户端路由守卫**：非 AUDITOR/ADMIN 一律 `window.location.href` 跳**主站登录页**；
- **服务端角色校验**：工单 API 全量 `requireAuditor`，`/ws/review` 握手叠加 AUDITOR/ADMIN 角色过滤（票 10）。

### 开发环境登录态

生产环境本工程部署在主站**同域名 `/review/` 子路径**，与主站共享 localStorage，登录一次两边通用。开发环境因端口不同（5173/5174）localStorage 天然隔离，两种用法：

1. **推荐**：主站登录后，在主站 DevTools Console 执行 `copy(localStorage.bs_token + ' ' + localStorage.bs_user)`，再到 5174 的 DevTools 里分别写入同名两个 key；或
2. 直接在 5174 访问时被守卫跳去主站登录，登录后手工把两个值粘回 5174 的 localStorage 后刷新。

亦可用 `VITE_MAIN_SITE_ORIGIN` 环境变量覆盖守卫跳转的主站地址（缺省 `http://localhost:5173`）。

## 部署

独立构建 `npm run build`（`base: '/review/'`）→ 静态产物发到**主站同一域名的 `/review/` 子路径**（仅内网可达），反向代理 `/api` 与 `/ws/review` 两条路径到主后端。**不选独立域名/端口**（会引入跨域与登录态搬运，spec §5.4）。

## 接口对照（唯一事实源在后端）

| 前端用途 | 接口 |
|---|---|
| 类型积压计数条 | `GET /api/moderation/tasks/counts` |
| 拉一条（原子指派 + 起租约） | `POST /api/moderation/tasks/claim` |
| 单条详情聚合（内容/作者/历史裁决） | `GET /api/moderation/tasks/{taskId}` |
| 裁决（通过 / 拒绝理由枚举 + 补充说明） | `POST /api/moderation/tasks/{taskId}/decide` |
| 主动放回 | `POST /api/moderation/tasks/{taskId}/release` |
| 长连接票据 | `POST /api/moderation/ws-ticket` |
| 派单信号 + presence | WS `/ws/review`（帧型 `REVIEW_SIGNAL {backlog}` / `PING` / `PONG` / `KICK`） |
| 最早已等待锚点（只读辅助） | `GET /api/moderation/queue?pageSize=1` |

## 共享类型同步口径

本工程与主前端不引共享包 / workspaces（记为技术债）：`src/types/review.ts` 的 `ReviewTargetType` / `RejectReasonCode` / `ReviewTaskClaim` / `ReviewTaskDetail` 等**手抄自后端 Java 枚举与 DTO，后端是唯一事实源，两侧注释互指**——改后端枚举必须同步改这里与 `front/src/types/api.ts`。

## 目录

```
front-review/
├─ index.html / vite.config.ts / tsconfig*.json / package.json / .gitignore
└─ src/
   ├─ main.ts / App.vue / router/      # 单路由 + 客户端角色守卫（非审核员 → 主站登录）
   ├─ views/ConsoleView.vue            # 中台主视图
   ├─ stores/                          # auth（登录态）/ console（派单状态机）
   ├─ api/                             # http 客户端 / 工单接口
   ├─ utils/                           # reviewWs（/ws/review 客户端）/ mainSite / format
   ├─ types/                           # 手抄共享类型（后端为唯一事实源）
   ├─ components/                      # TopBar / SignalBanner / TypeCounterBar / TaskWorkbench / SidePanel / Toast
   └─ styles/                          # 深色控制台变量 + 全局
```
