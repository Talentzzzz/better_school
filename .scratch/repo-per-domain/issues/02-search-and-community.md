# search 跨域只读收口 + community 两仓储合一

Type: task
Status: resolved
Blocked by:

## Question

**1. search**（已是一域一仓储、零事务）

- `SearchServiceImpl:55-58` 注入 post/product/errand/user 四个他域仓储（同步/重建时反查实体，`:73`–`:240` 各处）→ 改 search 自有 port，实现放 `bs-infrastructure/.../adapter/port/search/`。**一个 port 带多个方法就够，不要按调用点拆细。**
- `SearchRepository` 从 `adapter/search/` 挪到 `adapter/repository/search/`（对齐 `backend/CLAUDE.md` §3）。

**2. community**

- `IPostRepository`(35 方法) + `IPostCommentRepository`(19) → `ICommunityRepository` + `CommunityRepository`：两个实现的方法体原样搬进一个类，该类持有 `IPostDao` + `IPostCommentDao`；**PO / mapper XML 位置不动**，`PostDetailCache`、`PostCountAggregator` 注入 `IPostDao` 的现状不动。
- `PostServiceImpl:29`、`CommentServiceImpl:26` 的 `IUserRepository`（点赞/评论可见性、补昵称）→ community 自有 port。
- 12 处事务（`PostServiceImpl:189/210/234/259/275/292/305`、`CommentServiceImpl:84/145/192/213/227`）原样搬成 `ICommunityRepository` 方法。

判据：bs 侧编译过；`PostCountAggregationIntegrationTest`、`PostCommentBaselineIntegrationTest`、`TradeFullChainIntegrationTest` 绿；`domain/search`、`domain/community` 不 import 他域仓储、不出现 `TransactionTemplate`。

## Answer

**1. search 跨域只读收口**

- 四个他域仓储注入（`IPostRepository`/`IProductRepository`/`IErrandRepository`/`IUserRepository`）→ **一个** search 自有 port `ISearchQueryPort`（12 方法，按被读实体分段，不按调用点拆细），实现 `infrastructure/.../adapter/port/search/SearchQueryPort`（内部只读 community / trade / errand / user 四域读路径，帖子走详情缓存口径）。
- 端口只取实体、不搬判据：「NORMAL 才进索引 / ON_SALE 才进索引 / 公开单状态集 / 用户 NORMAL」的判定全部留在 `SearchServiceImpl`（`PUBLIC_ERRAND_STATUSES` 等常量原样）。
- `SearchRepository` 从 `infrastructure/adapter/search/` 归位到 `infrastructure/adapter/repository/search/`（`SearchIndexInitializer` 留在 `adapter/search/`，它只做启动期索引创建）。

**2. community 两仓储合一**

- `IPostRepository`(35) + `IPostCommentRepository`(19) → `ICommunityRepository`；实现 `CommunityRepository` 持有 `IPostDao` + `IPostCommentDao`，两个实现类的方法体原样搬入。**PO / mapper XML 位置不动**；`PostDetailCache`、`PostCountAggregator` 仍只依赖 `IPostDao`（不经仓储类）。
- 合并后**同名方法显式消歧**（不消歧无法编译）：`findById` → `findPostById` / `findCommentById`；`addLike`/`removeLike` → `addPostLike`/`removePostLike`（`addCommentLike`/`removeCommentLike`）；`pageByUser`/`countByUser` → `pagePostsByUser`/`countPostsByUser`（`pageCommentsByUser`/`countCommentsByUser`）；`approveById`/`rejectById` → `approvePostById`/`rejectPostById`（`approveCommentById`/`rejectCommentById`）。
- `IUserRepository`（`isLikedVisibleTo`/`isCommentsVisibleTo`/`findById` 补昵称）→ community 自有 `IUserQueryPort`，返回域内 `CommunityUserVO`（昵称 + 两个开关原始值）；「viewer = owner 恒可见、开关 false 才不可见、读不到 profile 按可见」的谓词留在 `PostServiceImpl` / `CommentServiceImpl`。
- 12 处事务原样搬成 `ICommunityRepository` 方法（实现内 `TransactionTemplate`，走同一个默认 REQUIRED 的 `TransactionConfig` bean）：帖子 7 处 `saveViewCountMessage` / `deleteWithSearchSync` / `approveWithSearchSync` / `rejectWithSearchSync` / `forceDeleteWithSearchSync` / `likePostWithCountMessage` / `unlikePostWithCountMessage`；评论 5 处 `saveCommentWithReviewMessage` / `approveCommentWithCount` / `softDeleteCommentWithCount` / `likeCommentWithCount` / `unlikeCommentWithCount`。域服务删掉 `TransactionTemplate` 注入。
- 事务内抛异常（重复点赞 `PARAM_ERROR`、审核 0 行 `BUSINESS_ERROR`）原样留在仓储 lambda 内，回滚语义不变。
- 适配调用方：`CardRefQueryPort`（chat）→ `ICommunityRepository.findPostById`；`PostCountConsumer` → `ICommunityRepository.submitCountMessage`；`UnboundedQueryCursorIntegrationTest` → `ICommunityRepository.listIdsAfter`。

**判据结果**

- `mvn -o clean package -DskipTests`：BUILD SUCCESS（bs 七模块全过）。
- 必测三类绿：`PostCountAggregationIntegrationTest`(3) / `PostCommentBaselineIntegrationTest`(7) / `TradeFullChainIntegrationTest`(9)；另跑 `SearchIndexRebuildJobIntegrationTest`(1) 与 `UnboundedQueryCursorIntegrationTest`(3)（search 重建链路），以及 `mvn -o test -pl bs-app -am` 全量 230 项、0 失败 0 错误。
- `domain/search`、`domain/community` 只 import 本域 `adapter.repository`、`TransactionTemplate` 0 命中；全仓无 `IPostRepository`/`IPostCommentRepository` 残留引用。

**执行中的两处偏差（有意，非票面原文）**

- 「一个 port 带多个方法就够」按**一个** `ISearchQueryPort` 落地（首版曾按被读实体拆成四个 port，一个端口 12 方法收口）。
- 合并后必须消歧五个方法名（同名同参无法在一个接口里共存）——票面「方法体原样搬进一个类」在实现层照做，仅公开方法名加了域限定。
- `bs-domain/.../errand/ErrandTimeLimitVO.java` 与 `bs-domain/docs/api/errand.md` 有本 session 之前就在工作区的改动，未纳入本票提交。

**遗留（不在本票）**

- `IUserRepository.isLikedVisibleTo`/`isCommentsVisibleTo` 已无生产调用方（community 改走 port），按 map 决议 2「已是单仓储的域不改名」未删；测试侧无引用。留待票 04/06 决定是否清理。
- 仓储实现分包仍是聚合名（`adapter/repository/post`、`comment`、`product`、`productorder`），与 map 决议 2 的「`<领域包>` 按域命名」尚未对齐——本票未要求，票 06 收口时可一并看。

