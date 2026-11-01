Status: resolved
Type: grilling
Blocked by:

# 06: 作者主页与隐私的数据/接口口径

## Question

作者主页与隐私开关需要新后端契约，现有接口不够用。要定死并写进 spec 的是：

**① 公开用户资料接口**：作者主页顶部的昵称/头像/签名/性别/获赞数从哪来。现在 `UserController` 只有 send-code/register/login，没有任何对外资料接口。获赞数口径（自己笔记的被赞数之和？是否含其他内容类型的被赞？）。

**② 按用户列内容的公开接口**：作者主页的 笔记/赞过/评论/闲置/跑腿 五个 tab 都要"看**别人**"。而现有 `/community/posts/mine`、`/posts/liked`、`/posts/commented` 全部是本人视角（取 `SecurityUtils.currentUserId()`）。决定是新增一组公开接口（如 `/community/users/{userId}/posts`）还是给现有接口加 `userId` 参数；访客口径的过滤规则（帖子只 `NORMAL`、闲置只 `ON_SALE`、跑腿只 `PUBLISHED`/`TAKEN` 等）；分页与排序（默认时间倒序？）。

**③ "我的-评论"改为评论列表**：按 `ScreenShot_2026-09-11_022904_160.png`，评论 tab 是"评论内容 + `来自笔记 · 帖子标题` + 时间 + 点赞"，不再是"我评论过的帖子列表"。需要新增"按用户分页列评论（带源帖标题/封面，只含他人可见的）"的接口；旧 `/community/posts/commented` 的去留（还有没有别的调用方）。跳转口径：点 `来自笔记·标题` 跳到源帖详情并**锚定到该评论**（URL 带 `commentId`）。

**④ 隐私开关**：两个全局开关（看我的"赞过"/看我的"评论过"）。存储方案（`bs_user` 加列 vs 独立表）、默认值（**默认公开**）、生效点（仅影响他人从作者主页查看；本人"我的"页不受影响）、关闭时的表现（tab 展示但空态"该用户已隐藏此内容"）。注意：**不做单条评论"取消公开"**（Q14）。

**⑤ 资料编辑**：签名（长度上限、是否可空）、性别（取值集 + 是否可空/保密）；编辑接口与"我的"页设置入口的对应。

产出：接口清单（路径/方法/鉴权/关键参数）+ 表变更 + 生效口径，直接进 spec。需同步 `backend/docs/api/` 与 `backend/docs/db/`。

## Answer

**2026-09-11 收敛**：一轮 frontier（7 问）→ 用户「都按你推荐的」全部采纳。口径已进 spec、docs、迁移。

### 决策速览

| # | 问题 | 结论 |
| --- | --- | --- |
| Q1 | 公开资料接口 | `GET /api/user/{userId}/profile`（免登录）；**手机号不外露**（作者主页去掉「账号」行）；获赞 = 该用户 `NORMAL` 帖 `like_count` 之和 |
| Q2 | 「按用户列内容」接口形态 | **新增公开只读接口组**，不给现有 `/mine|liked|commented` 加 `userId` 参数 |
| Q3 | 访客口径过滤 | 写死进 spec（见下表） |
| Q4 | 「评论」tab | 新接口 `/community/users/{userId}/comments`（评论列表）；旧 `/posts/commented` 保留；跳转带 `commentId` 锚定 |
| Q5 | 隐私开关 | **赞过 / 评论**（修订票 04 的「闲置 / 跑腿」）；`bs_user.show_liked/show_comments`，默认公开，仅他视 |
| Q6 | 资料编辑 | `PUT /api/user/profile`；签名 ≤40 可空；性别 `MALE/FEMALE`，`NULL`=不展示 |
| Q7 | 路由 / 鉴权 / 私信 | 新路由 `/user/:userId`；补 Security 白名单；私信需登录，本人看自己显示「设置」 |

> 补充确认（2026-09-11）：作者主页「跑腿」tab **只列该用户发布的单**（`publisher_id`），**不含其接单**——票面原只定了状态过滤（`PUBLISHED/TAKEN`），角色口径由 code-review 追问后确认。

### 决策依据（事实底座，均已查证）

- `UserController` 只有 send-code/register/login；`bs_user` 无签名/性别/隐私列、无 `updated_at`；无更新资料接口（`UserMapper.updateById` 仅 SET `nickname/avatar/last_login_at`）。
- 线上「账号」渲染的**就是手机号**（`ProfileView.vue:182`）。
- `/posts/mine|liked|commented` 全部写死 `currentUserId()`；`mine` 含 `DRAFT/REJECTED`，`liked/commented` 含 `PENDING_REVIEW/REJECTED` → 不能直接复用给访客。
- 无「按用户列评论」接口；旧 `/posts/commented` 消费者＝`ProfileView` 评论 tab + `SearchView` `scope=comments`（两个语义不同，故保留旧接口）。
- `post_comment` 只存 `post_id`，来源帖标题/封面需 JOIN `post`；无 `user_id` 维度索引。
- 求购是独立聚合（`want_to_buy`），不属作者主页 tab。
- 迁移约定 `backend/sql/migrations/NNN_*.sql`（001–020，`init.sql` 原地同步）；`docs/api/`、`docs/db/` 均无 `user.md`。

### 接口清单

| 方法 | 路径 | 鉴权 | 用途 |
| --- | --- | --- | --- |
| GET | `/api/user/{userId}/profile` | 免登录 | 公开资料 + 获赞 + 两个隐私开关值 |
| PUT | `/api/user/profile` | 登录 | 编辑签名 / 性别（部分更新） |
| PUT | `/api/user/privacy` | 登录 | 切两个隐私开关（部分更新） |
| GET | `/api/community/users/{userId}/posts` | 免登录 | 笔记（`NORMAL`） |
| GET | `/api/community/users/{userId}/liked` | 免登录 | 赞过（源帖 `NORMAL`，按点赞时间倒序） |
| GET | `/api/community/users/{userId}/comments` | 免登录 | 评论列表（含来源帖标题/封面） |
| GET | `/api/trade/users/{userId}/products` | 免登录 | 闲置（`ON_SALE`） |
| GET | `/api/errand/users/{userId}/orders` | 免登录 | 跑腿（`publisher_id` + `PUBLISHED/TAKEN`） |

列表接口统一 `page`/`pageSize`（≤50）、时间倒序；**本人看自己主页也走公开口径**。

### 表变更

`sql/migrations/021_user_profile_and_privacy.sql`（`init.sql` 已原地同步）：

- `bs_user` 加 `signature VARCHAR(60) NULL`、`gender VARCHAR(10) NULL`、`show_liked TINYINT(1) DEFAULT 1`、`show_comments TINYINT(1) DEFAULT 1`；存量无需回填（NULL＝不展示 / DEFAULT 1＝公开）。
- `post_comment` 补 `idx_user_status_created (user_id, status, created_at)`——按用户列评论的过滤维度，现状只有 post/root 维度索引。

### 产出（已落）

- **spec**：「作者主页」节「待票 06 定」→「数据与接口口径（票 06 定稿）」；索引 ③ 状态改「已定稿」；「隐私开关（Q9）」与「我的」页「设置入口」同步把开关从「闲置/跑腿」改成「赞过/评论」。
- **docs**：新建 `backend/docs/db/user.md`、`backend/docs/api/user.md`；`docs/api/community.md` 加「按用户查询」节、`docs/api/trade.md` / `docs/api/errand.md` 各加按用户列表、`docs/db/community_comment.md` 补索引与关联。
- **迁移**：`backend/sql/migrations/021_user_profile_and_privacy.sql` + `init.sql`。

### 下游

- **frontier 移至票 07**（市集求购前端链路）；票 08（列表加载与状态修复）自票 05 起已解除阻塞。
