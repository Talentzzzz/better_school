# 04: 作者主页（含隐私开关与资料编辑）

**Spec:** `.scratch/ui-revamp/spec.md` §「作者主页（含隐私开关与资料编辑）」（spec 索引 ③）

**What to build:** 新增作者主页 `/user/:userId`（免登录可浏览）。页面自上而下：深色渐变资料头（返回圆钮 + 右上角设置 pill + 头像 + 昵称/性别/签名 + 行内私信 chip + 只显「获赞」）+ 吸顶下划线 tab（**笔记 / 赞过 / 评论 / 闲置 / 跑腿**，以 spec 顺序为准）+ 内容区。笔记与赞过为双列瀑布流，**复用 01 的卡面规范**；评论为评论列表形态；闲置为商品网格；跑腿为列表（评论与跑腿都不画分割线）。**本人自视才显示设置入口，访客看不到**；本人不显示私信 chip。

后端补齐（同步 `backend/docs/api/user.md`、`backend/docs/db/user.md`，并按需补 community/trade/errand 文档）：
- `GET /api/user/{userId}/profile`（免登录）→ 昵称/头像/签名/性别/获赞/两个隐私开关；**不返回手机号**。注意 `SecurityConfig` 现白名单只放行 user 的三个 POST，**必须补这条 GET**。
- 按用户列内容的 5 个**公开只读** GET：帖（`NORMAL`）、赞过（源帖 `NORMAL`、按点赞时间倒序）、评论（评论与源帖均 `NORMAL`、带来源帖标题/封面）、闲置（`ON_SALE`）、跑腿（`publisher_id` 且 `PUBLISHED`/`TAKEN`，**只列发布不含接单**）。全部免登录、分页。给现有本人接口加 `userId` 参数是**错误做法**（泄露私有状态），要新增公开接口。
- 获赞口径＝该用户 `NORMAL` 帖的 `like_count` 之和（不含评论获赞、不含待审/驳回/草稿）。
- 资料编辑 `PUT /api/user/profile`（签名 ≤40 可空、性别 `MALE`/`FEMALE`/`NULL` 不展示）与隐私开关 `PUT /api/user/privacy`（`showLiked`/`showComments`）。
- 隐私两开关**仅他视生效**：他人视角关闭时对应 tab 返回空、前端渲染空态「该用户已隐藏此内容」；本人不受影响。
- `bs_user` 的 `signature`/`gender`/`show_liked`/`show_comments` 列与迁移 **021 已存在**；剩余是 Java 侧：`UserPO` 补字段、mapper 的列与更新语句扩列（勿把 role/status/credit_score 卷进更新）。

**Blocked by:** 01（笔记/赞过复用其卡面规范）

**Status:** resolved

- [x] 从帖子详情/商品详情/跑腿详情/评论区的头像或昵称进入可打开作者主页；访客免登录可见
- [x] 五个 tab 各自按访客口径过滤返回并渲染；赞过按点赞时间倒序；评论项带来源帖标题，点击跳 `/post/{postId}?commentId={commentId}`
- [x] 走公开口径：本人看自己主页也不出现草稿/驳回内容
- [x] 资料头只显「获赞」（＝该用户 `NORMAL` 帖 `like_count` 之和），资料行不出现手机号
- [x] 隐私开关他视生效：他人视角关闭时对应 tab 显示空态「该用户已隐藏此内容」，本人视角不受影响
- [x] 资料编辑：签名（≤40 字、可空）与性别（男/女/不展示，`NULL`＝不展示）可改并可回显
- [x] 私信 chip：访客点按建 1v1 会话（未登录跳登录）；本人视角不显示私信、显示设置入口
- [x] `SecurityConfig` 已放行 `GET /api/user/*/profile`；确认为 community/trade/errand 新 GET 已被现有 `HttpMethod.GET` 通配覆盖
- [x] 后端集成测试覆盖访客口径过滤（各 tab）与隐私他视生效
- [x] 构建通过；访客视角与本人视角各手测一遍


## 实现要点（票 04）

**后端 user 域**：`UserPO`/`UserEntity` 补 `signature`/`gender`（新 `GenderVO`，`null`＝不展示）/`showLiked`/`showComments`；`UserMapper.xml` 的 `baseColumns` 扩列，并**新增两条专用更新语句** `updateProfile`（只写 signature/gender）/`updatePrivacy`（只写两开关），**不扩 `updateById`**（它 SET nickname/avatar/last_login_at，role/status/credit_score 归其他域，扩列会让登录覆盖并发封禁/调分）+ 获赞聚合 `sumNormalPostLikeCount`。新 DTO `PublicUserProfileResponseDTO`/`UserProfileUpdateRequestDTO`/`UserPrivacyUpdateRequestDTO`/`UserPrivacyResponseDTO`；新域服务 `domain/user/service/profile/IUserProfileService` + `UserProfileServiceImpl`；`UserController` 加三个端点；`SecurityConfig` 放行 `GET /api/user/*/profile`（`PUT /profile`、`/privacy` 仍走 `anyRequest().authenticated()`）。

**文档口径落定（与票 06 契约的一处修订）**：`PUT /api/user/profile` 落地为**整体覆盖可编辑两字段**（`signature` 空串/null＝清空，`gender` null/空串＝不展示），而非 `docs/api/user.md` 原写的「部分更新」——编辑页每次提交both字段，且 `null` 在 `gender` 上是「不展示」的合法值，与「缺省＝不改」在 JSON 里无法区分。已同步 `docs/api/user.md`；`PUT /privacy` 保持部分更新（boolean 无「有意义的 null」）。`docs/db/user.md` 的「落地需扩列」改为「另起两条专用语句」。

**后端 community**：`IPostDao` 新增 `selectNormalPageByUser`/`countNormalByUser`（笔记＝仅 `NORMAL`）与 `selectPublicLikedPageByUser`/`countPublicLikedByUser`（赞过＝**源帖仅 `NORMAL`**，按点赞时间倒序）；`IPostCommentDao` 新增 `selectPageByUser`/`countByUser`（评论与源帖**均** `NORMAL`，JOIN `post` 取来源帖标题/封面，按评论时间倒序），`PostCommentPO`/`PostCommentEntity` 补查询冗余字段 `postTitle`/`postCover`；新 DTO `UserCommentResponseDTO`；`PostController` 加 `/users/{userId}/posts|liked|comments` 三个公开端点。

**后端 trade/errand**：`ProductController` 加 `GET /users/{userId}/products`（仅 `ON_SALE`）；`ErrandController` 加 `GET /users/{userId}/orders`（`publisher_id` 且 `status IN ('PUBLISHED','TAKEN')`，**只列发布不含接单**）；各自的 DAO/XML/Repository/Service（errand 经 Case）链路补齐。

**隐私「仅他视生效」的落点（code-review Standards 轴采纳）**：判据收进 **user 域仓储** `IUserRepository.isLikedVisibleTo`/`isCommentsVisibleTo`（本人恒可见；owner 不存在按可见处理，列表本就为空）。社区域 `PostServiceImpl`/`CommentServiceImpl` 只调谓词，不再各写一份 4 行同形逻辑。跨域只读仓储注入符合 `backend/CLAUDE.md` 的既有例外（`CommentServiceImpl` 早前已注入 `IUserRepository` 取昵称）。

**前端**：新路由 `/user/:userId(\d+)` → `views/AuthorProfileView.vue`（`meta.tabbar`，**免登录**）；资料头（深色渐变、返回 40×40 圆钮、设置 pill 仅本人、头像 72、昵称 21/600、性别 18 圆底、**只显获赞**、签名「」包裹）＋吸顶下划线 tab（46px / 笔记·赞过·评论·闲置·跑腿）＋内容区（笔记/赞过＝双列瀑布流复用 `PostCard`，闲置＝双列网格复用 `ProductCard`，评论＝评论列表，跑腿＝列表；评论与跑腿均不画分割线）。新 `api/user.ts`；`community`/`trade`/`errand` 各补按用户接口；`types/api.ts` 补 `PublicUserProfile`/`AuthorProfileComment`/`Gender`。入口：`PostDetailView`（票 02 已留）＋新增 `ProductDetailView` 卖家行、`ErrandDetailView` 发布者/接单者行可点进主页。

**踩坑（本票揪出的真 bug）｜`overflow: hidden` 会杀死吸顶**：原型 D 的 `.panel` 写了 `overflow: hidden` 裁圆角——它会成为滚动容器，使子元素 `position: sticky` 失效，**吸顶 tab 实际不吸顶**。实测（390×844、30 张卡）：挂 `overflow: hidden` 滚动 900px 后 tab `top = −681.6`，去掉后 `= 0`。落地改为**圆角挂在 `.tabs` 自身、`.panel` 不裁**。原型同理没做到 spec 的「吸顶」。

**code-review 采纳（Spec 轴真 bug）**：「公开我的赞过」关闭时**连带把「笔记」tab 也渲染成「该用户已隐藏此内容」**——原实现用 `likedHidden` 同时门控 `posts|liked` 两个分支。已改为 `currentTabHidden = tab === 'liked' && likedHidden`（笔记 tab 恒不受开关影响）；Playwright 复验：`showLiked=false` 的访客视角下笔记仍出 1 张卡、只有赞过是空态。

**code-review 采纳（Standards 轴）**：除上面的隐私谓词下沉外，跑腿类型中文名抽 `front/src/utils/errand.ts#errandTypeText`，把 `ErrandCard`/`ErrandDetailView`/`AuthorProfileView` 三处同名 map 收敛为一处。

**code-review 未采纳留档（Standards 轴 judgement call）**：①`UserMapper.sumNormalPostLikeCount` 读 `post` 表——本仓 mapper 跨域 JOIN 是既有惯例（`PostMapper`/`ProductMapper`/`ErrandMapper` 都 JOIN `bs_user`），且获赞是「用户资料读模型」属性，保留不搬；②按用户的 5 条新查询与既有 `selectPageByUser`/`selectLikedPageByUser`/`selectPageBySeller`/`selectMyPublished` 近似但**过滤口径不同**（公开口径 vs 本人视角含私有状态），且 spec 明令「不给现有本人接口加 `userId` 参数」，保留独立查询；③`postCover` 前端暂未渲染——spec 明列该字段属「按用户列评论」响应契约（票 06 定稿），保留为契约字段；④作者主页跑腿 tab **未复用 `ErrandCard`**——spec 明写该 tab 是**列表**（不画分割线、14px 行距自然分隔），`ErrandCard` 是「灰底白卡」（票 01 卡面规范），形态不同故不复用。

**设置入口的通达口径（与票 05 对齐）**：票 04 spec 字面是「齿轮 → 底部面板『隐私设置』」，票 05 的口径是「设置内含两页（编辑资料 / 隐私设置）」。落地按 **05 的结构**：齿轮 → 底部面板「设置」（两行：编辑资料 / 隐私设置）→ 两个独立页 `/settings/profile`、`/settings/privacy`（`meta.requiresAuth`）。票 04 的验收项（签名 ≤40/可空/回显、性别男/女/不展示、本人见设置入口不见私信）全部满足，票 05 只需在 `ProfileView` 挂同一入口即可复用。开关样式按 spec 04：44×26 胶囊（关 `#d9d9de` / 开 `$primary`，白圆钮 22）、行高 50、行间 `0.5px $line`。

**已知波及 / 不在本票范围**：①`ProfileView.vue`（「我的」页）本票**未动**——票 05 负责它的头部改版、tab 顺序改「笔记/赞过/评论/闲置/跑腿」、以及把「评论」tab 换成评论列表形态（后端接口与页面形态本票已就绪，05 直接复用）；②资料编辑只做**签名＋性别**，昵称/头像不改（map Out of scope）；③跑腿订单详情页 `ErrandDetailView.vue` 底栏收口仍未做（票 03 留档项，归其后续实施票）；④市集/首页的 `useWaterfall` 归票 05。

**验收**：后端 `mvn -o test -pl bs-app -am`（全量套件）绿；新增 `bs-app/src/test/.../integration/user/AuthorProfileIntegrationTest`（11 例：公开资料免登录且不含手机号、获赞只算 `NORMAL` 帖、用户不存在 404、资料编辑写入/清空/超长与非法性别拒绝、隐私部分更新、隐私他视生效且本人不受影响、笔记/赞过/评论/闲置/跑腿五个 tab 的访客口径过滤与点赞时间倒序、跑腿只列发布不含接单）。前端 `npm run build`（vue-tsc + vite）绿（`AuthorProfileView` chunk 8.98 kB）。Playwright 拦 `/api`（正则限定 `localhost:5173` 主机——glob `**/api/**` 会把 Vite 的 `/src/api/*.ts` 模块请求也拦成 JSON 致白屏）伪造数据，390×844 逐 tab 读数＋计算样式核对（tab 下划线 22×3/`$primary`/`bottom 1px`/`550`、面板 `-14px`/圆角 14、资料头渐变与 `6px 20px 26px`、评论与跑腿行 `14px 16px` 且 `border-bottom: 0`、跑腿 badge `rgba(255,36,66,.1)`/圆角 5、路线块 `#f7f7f8`/圆角 6、开关 44×26）、本人/访客两种视角、隐私空态、吸顶实测；截图 12 张存 `.scratch/ui-revamp-impl/verify-04/`。
