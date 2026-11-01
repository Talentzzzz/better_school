# 08 grilling：审核端独立工程的形态与部署

Type: grilling
Status: resolved
Blocked by: 07

## Question

用户要「**单独一个前端**」。票 07 的原型会给出可点形状，本票把工程形态与部署口径定死：

1. **代码落点**：同仓库新增 `front-review/` 独立工程（与 `front/` 并列、独立 `package.json`、可独立构建部署），还是另开仓库？（用户倾向「单独一个前端」，同仓库新目录是默认。）
2. **与既有 `/admin/review` 的关系**：现有 `front/src/views/admin/ReviewWorkbenchView.vue` 是**替换**（主工程删掉该路由与 Profile 入口卡）还是**并存**（主工程保留只读视图，操作全在审核端）？取舍要讲清（替换干净但有回滚成本；并存要防两处口径漂移）。
3. **后端 API 归属**：审核端的接口是复用现有 `POST /api/moderation/review` / `/api/admin/reviews/decide` 那套，还是新建一组「工单」API（对齐票 06 的数据模型）？接口前缀/模块归属。
4. **鉴权与角色**：沿用既有 ADMIN/AUDITOR 角色门控与登录态（同域共享 token / 跨域 CORS+token）；审核端**不做**自建账号体系（已记 Out of scope）。
5. **部署形态**：独立域名 / 端口 / 静态托管；是否要求内网可达（审核端不应挂公网，对齐超时中心「8083 别挂公网」的先例）；开发环境怎么跑（Vite dev + 主后端）。
6. **构建与发版**：是否进现有构建/CI（若有）；版本与主工程的耦合纪律（共享类型定义怎么同步——手抄 / 生成 / 共享包）。
7. **移动端**：审核端只做桌面 Web（审核员在电脑上一条条审），还是要响应式？默认桌面。

**产出**：审核端工程形态与部署口径，写进 `spec.md`；术语与边界落 `CONTEXT.md`/ADR（若够格）。

## Answer

2026-10-14 grilling 拍板。**七问全部按推荐落定**（用户答：「都按你推荐」）。事实底座已逐条核到源码（非猜测）：

- 本仓**无 git 远程仓库**（`CLAUDE.md`）、**无 CI**（无 `.github/`）；单人多端开发。
- 主前端 `front/` 已是**独立 Vite 工程**（`front/package.json`；dev `5173` / preview `4173`）；`/admin/review` 路由在 `front/src/router/index.ts:55`（`meta.roles:['ADMIN','AUDITOR']`），Profile 入口卡在 `front/src/views/ProfileView.vue:52,216`，视图 `front/src/views/admin/ReviewWorkbenchView.vue`。
- 现有审核接口：`ModerationController`（`/api/moderation`，`requireAuditor`：`queue` / `queue/{type}/{id}` / `review`）+ `AdminReviewController`（`/api/admin/reviews/decide`，`requireAdmin`）。**前端仅用 `/moderation/queue`、`/moderation/review`**；`/api/admin/reviews/decide` **前端零引用**。
- **后端无任何 CORS 配置**（全仓 grep 为空）——现状靠 Vite dev proxy 同源；登录态 = `localStorage` 的 `bs_token`（`front/src/stores/auth.ts`）+ `Authorization: Bearer`（`front/src/api/http.ts`）。
- 原型同栈（Vue 3 + Vite + SCSS），`prototype/README.md` 已写明「选仓库根则整体 `git mv` 到 `front-review/`」。

### 结论（逐问）

| # | 问题 | 裁决 |
|---|---|---|
| 1 | 代码落点 | **同仓库新增 `front-review/`**（与 `front/` 并列、独立 `package.json`、可独立构建部署），**不另开仓库**。原型整体 `git mv` 平移（**不带** `node_modules/` `dist/` `*.tsbuildinfo`），README 落点段改指 `front-review/`。 |
| 2 | 与 `/admin/review` 关系 | **替换**：主工程删该路由 + Profile 入口卡 + `ReviewWorkbenchView.vue`（及仅供其用的 `api/moderation.ts` 取数/裁决封装）；后端 `POST /api/admin/reviews/decide` **保留不动**（前端零引用）。并存会两处口径漂移，否决。 |
| 3 | 后端 API 归属 | **新建一组「审核工单」API，仍落 moderation 域**：前缀 `/api/moderation/tasks/**` + WS `/ws/review`（票 05 已定端点）。裁决**复用既有 `ReviewCase.decide`**（工单入口内调，同事务把工单置 `DONE`，对齐票 06 原子性）。**不新建域**、**不废弃**既有 `/api/moderation/review`。 |
| 4 | 鉴权与角色 | **沿用既有登录态与 ADMIN/AUDITOR 门控**，**不新建账号体系**（已 Out of scope）；因后端无 CORS 且登录态在 `localStorage`，**同域共享 token、零 CORS 改动**（见 Q5）。跨域 CORS 仅作「万一独立域名」的备选。 |
| 5 | 部署形态 | **独立工程 / 独立构建、同域挂 `/review/`（反向代理到主后端）；仅内网可达，不挂公网**（对齐超时中心「8083 别挂公网」先例）。开发：Vite **5174** + proxy `/api`、`/ws/review` → `8080`（照抄主工程代理体例）。 |
| 6 | 构建与发版 | **不进 CI**（本仓无设施）；**独立版本号、与主工程解耦发版**（后端接口当契约、只做向后兼容）；共享类型**手抄 + 以「后端枚举为唯一事实源」+ 两侧注释互指**；**不引共享包 / 不引 workspaces**（记为技术债，漂移频发再升级）。 |
| 7 | 移动端 | **仅桌面 Web**，不做移动端响应式（最小可用宽度 ~1200px），与原型一致。 |

### 定稿：审核端工程形态与部署口径

**工程形态**
- 落点 **仓库根 `front-review/`**（与 `front/` 并列），技术栈同 `front/`（Vue 3 + Vite + TypeScript + SCSS、手写组件、无 UI 库）；独立 `package.json` / `tsconfig*.json` / `vite.config.ts`。
- 由 `.scratch/errand-lock-review-hub/prototype/` **整体平移**：源码 + 配置 + README；**不带** `node_modules/`、`dist/`、`*.tsbuildinfo`。平移后 README 的「落点记录」段改指 `front-review/`。
- `.gitignore` 追加与 `front/` 同款三行：`front-review/node_modules/`、`front-review/dist/`、`front-review/*.tsbuildinfo`。
- 信息架构（承票 07，不重开）：顶部**信号条** + **类型计数条**（只读）+ 主区**单条聚焦** + 右栏**状态/事件流**；仅桌面。

**与主工程的关系（替换口径，双向清单）**
- **删**（主工程）：`front/src/router/index.ts` 的 `/admin/review` 路由；`front/src/views/ProfileView.vue` 的审核工作台入口卡（含 `canReview`）；`front/src/views/admin/ReviewWorkbenchView.vue`；`front/src/api/moderation.ts` 中仅供该视图使用的 `listReviewQueue` / `decideReview`（**仅当确认无他处引用**）。
- **留**（后端）：`POST /api/admin/reviews/decide`（独立管理入口，前端零引用，不动）；`/api/moderation/**` 其余接口。
- 取舍明写：**替换是单向门**（干净、单一口径），回滚依赖 git 历史（旧视图/card 代码可从历史取回）；**并存方案否决**（两处口径漂移 + 只读视图在派单/租约下无价值）。
- ⚠️ **替换的实际实施不在本 effort 范围**（本 effort 只出 spec，`map.md` Destination：「拆票即可开发、不含实现本身」）——本节只定口径，实施归后续 effort/spec 派生的实现票。

**后端接口归属（前缀 + 模块）**
- **域**：moderation（不新建域；审核中台就是 moderation 的人工工作面）。
- **工单 API**（新控制器，落 `bs-trigger/http`，前缀 `/api/moderation/tasks`）：

  | 方法 | 路径 | 用途 |
  |---|---|---|
  | `GET` | `/api/moderation/tasks/counts` | 各类型积压计数（驱动顶部类型计数条 + 派单信号 payload 的积压数） |
  | `POST` | `/api/moderation/tasks/claim` | 拉一条（原子指派 + 起租约；body 可选 `targetType` 过滤，对齐票 05/06） |
  | `POST` | `/api/moderation/tasks/{taskId}/release` | 主动放回（审不了） |
  | `POST` | `/api/moderation/tasks/{taskId}/decide` | 裁决（**内部调 `ReviewCase.decide`**，同事务工单置 `DONE`） |
  | `GET` | `/api/moderation/tasks/{taskId}` | 单条详情（内容/作者/历史裁决聚合——**取数口径归票 06**） |

- **WS**：`/ws/review`（派单信号 + presence，票 05）。
- 既有 `GET /api/moderation/queue`、`POST /api/moderation/review` **保留**（无工单的直接裁决/队列入口，向后兼容）。

**鉴权**
- 沿用既有 JWT（`localStorage.bs_token`）+ `Authorization: Bearer`；客户端路由守卫（非 AUDITOR/ADMIN → 跳主站登录）+ 服务端 `requireAuditor`（工单 API 全量继承）。**不新建账号体系 / SSO**。

**部署与开发环境**
- 开发：`cd front-review && npm run dev` → **5174**；`vite.config.ts` 配 proxy `/api`、`/ws/review` → `http://localhost:8080`（ws: true）；主后端照常 8080。
- 生产：独立构建 `npm run build` → 静态产物发到**主站同一域名的 `/review/` 路径**（内网），反向代理 `/api`、`/ws/review` 到主后端。**仅内网可达**。
- 独立域名 / 独立端口**不是首选**（会引入 CORS + 登录态搬运，见 Q4）。

**构建 / 发版 / 类型同步**
- 不进 CI；两工程各自 `npm run build`，**独立版本号、解耦发版**。
- 共享类型（`RejectReasonCode`、`ReviewTargetType`、工单状态枚举等）：**手抄 + 后端 Java 枚举为唯一事实源 + 两侧注释互相指路**；**不抽共享包**（避免牵动 `front/` 与主工程构建）。记为**技术债**：类型漂移频发时再考虑生成 TS / 抽 `front-shared/`。

### 供票 09 落 `spec.md` 的草稿章节

> **x. 统一审核中台 · 工程形态与部署**
> - **前端**：仓库根 `front-review/`，独立 Vite 工程（与 `front/` 并列、独立构建部署）；信息架构 = 顶部信号条/类型计数条 + **单条聚焦**主区 + 右栏状态/事件流；**仅桌面**（最小宽度 ~1200px）。
> - **与主工程关系**：**替换** `/admin/review`（删路由 + Profile 入口卡 + 旧视图）；后端 `/api/admin/reviews/decide` 保留。
> - **接口归属**：新工单 API `/api/moderation/tasks/**`（counts / claim / release / decide / detail）+ WS `/ws/review`；裁决复用 `ReviewCase.decide`（同事务工单置 `DONE`）；**不新建域**，既有 `/api/moderation/{queue,review}` 保留。
> - **鉴权**：沿用既有 JWT（`localStorage.bs_token`）+ ADMIN/AUDITOR 门控；不新建账号体系。
> - **部署**：独立工程独立构建；**同域 `/review/` 反向代理到主后端**；**仅内网可达**；dev Vite `5174` + proxy `/api`、`/ws/review` → `8080`。
> - **构建/发版**：不进 CI；独立版本；共享类型手抄、后端枚举为唯一事实源；不引共享包（技术债）。
> - **参数**：dev `5174` / preview `4174`；最小宽度 ~1200px。

### 术语与 ADR 判定（承票面「若够格」）

- **`CONTEXT.md`：本期不加词条**——工程形态 / 部署口径属实现细节，非领域词汇（`CONTEXT.md` 自陈「只收领域术语，不收实现细节」）。承票 05 的领域候选「派单信号 / 领单 / 租约 / 放回 / 回收 / 审核工单」仍归票 09。
- **ADR：本票不单开；合并为一篇「统一审核中台」ADR，编号归票 09**（票 01 已建议 `0008`）。该篇涵盖三件够格的系统性决策：① **独立审核端工程**（`front-review/`）；② **「推信号 + 拉工单」派单模型**（票 05 已标候选）；③ **替换旧 `/admin/review` 工作台**。理由：三者是同一决策簇（审核中台的产品形态），拆开写会碎。

### 转票锚点

- **票 06（数据模型）**：工单 API 的**详情取数**（内容/作者/历史裁决聚合）与 `review_task` DDL 归 06；本条只定**前缀与模块归属**。
- **票 09（spec）**：落本节「定稿」+「草稿章节」；**ADR 判定 + 编号**（候选 `0008`「统一审核中台」）；CONTEXT 词条（承 05）。
- **实现（本 effort 外）**：`front-review/` 建立与原型平移、主工程三项删除、`vite.config.ts` 代理、`.gitignore` 追加——均由 spec 派生实现票承接。

### 复用 vs 新建

- **复用**：既有登录态 / 角色门控（前端守卫 + 后端 `requireAuditor`）、`ReviewCase.decide`、Vite + SCSS 脚手架（原型直接平移）、主工程 dev 代理体例。
- **新建**：`front-review/` 工程、`/api/moderation/tasks/**`（控制器 + 用例）、`/ws/review`（票 05）、共享类型的审核端副本。
- **外部依赖**：无新增（不用 Redis/CORS 新设施；Redis 已在 `docker-compose.yml`）。
