# 06 · 帖子 category 退役

Type: task
Status: resolved
Blocked by: 01

## Task

帖子域整体去 category（商品的 category 一行不动）：

1. DTO×4（PostPublishRequestDTO / PostDraftSaveRequestDTO / PostResponseDTO / PostSummaryResponseDTO）去字段；`ICommunityService.listPosts` 去参数
2. `PostPublishCase` / domain service 签名简化；`PostEntity` / category 枚举去掉
3. `PostMapper.selectPage` / `count` 去分类条件（旧直查路径保留作降级，条件一并去）
4. ES：`bs_post` mapping 去 category 字段，`SearchIndexRebuildJob` 重灌
5. 测试：community 集成测试去 `"category", "DAILY"` fixture；确认商品测试不受影响
6. 前端：分类 tab 下线，发帖页去分类选择
7. 兼容确认：老客户端请求多带 category 字段被 Jackson 忽略（验证 `FAIL_ON_UNKNOWN_PROPERTIES` 未开启）
8. CONTEXT.md 无 category 词条，无需改写；`docs/` 里若有提及时顺带清理

## Answer

### 交付物

- **DTO**：PostPublishRequestDTO（连 `@NotBlank 分类不能为空` 校验一起去）/ PostDraftSaveRequestDTO / PostResponseDTO 去字段；`PostSummaryResponseDTO` 本来就没有 category（票面写 4 是按接口面估的，实际 3 个带）。`ICommunityService.listPosts` 去 category 参数（签名变 `listPosts(sort, cursor, page, pageSize)`），javadoc 去掉「无 category 时」限定；`submitPostDraft` 文档同步「正文必填」。
- **签名简化链**：PostController → PostPublishCase → ICommunityPostService/PostServiceImpl → ICommunityRepository/CommunityRepository → IPostDao，category 参数全线摘除；`PostEntity` 去 `category` 字段（create/createDraft/applyDraftContent 同步收参），`PostCategoryVO` 枚举整文件删除。草稿完整性校验只剩正文必填（错误文案改「请补全正文后再提交」）。
- **读路径收敛**：`listPosts` 无条件走 post_feed_index 索引路径；旧直查 `selectPage`/`count` 保留作降级（票 04 的降级语义不变），分类条件一并去掉——降级与主路径现在同口径（全量 NORMAL）。票 04 暂存的「带分类过滤走旧直查」分支消亡。
- **SQL（PostMapper.xml）**：`postColumns` 投影、insert、updateDraft 全部不再读写 `p.category`（列停写停读，物理删除放票 07）；selectPage/count 去 `<if category>` 条件。
- **ES**：`SearchRepository.ensureIndices` 的 bs_post mapping 去 category 字段（商品/跑腿/用户 mapping 不动）。**mapping 生效机制**：`clearIndex` 删索引后调 `ensureIndices` 以新 mapping 重建——线上跑一次 `SearchIndexRebuildJob`（xxl-job 手动触发）即完成「新 mapping + 全量重灌」，无需额外迁移脚本；正式触发时机随票 07 收口。`PostSearchDocVO`/`SearchServiceImpl.toPostSearchDocVO` 去字段（顺带消掉一个 `post.getCategory().name()` 的潜在 NPE——存量老帖 category 本就可能是 NULL）。
- **前端**：HomeView 分类 chips 下线（只剩 最热/最新 双 tab），listPosts 不再传 category；发帖页去分类选择块（ref/FormSnapshot/dirty 对比/formBody/草稿回填/模板与样式全清）；`PostCategory` 类型与 PostDetail.category 删除；api/community.ts 请求类型收窄。
- **文档**：docs/api/community.md（feed 接口描述改「时间线 + 搜索」、请求体去 category、补一条「category 作为未知字段被静默忽略」兼容说明、草稿/提交口径同步）、docs/db/community.md 与 sql.md 的 post.category 列/索引标注退役（票 07 删）。

### 决策点（票面没写死的）

- **兼容确认的做法**：`application.yml` 显式 `fail-on-unknown-properties: false`（票面第 7 项的静态证据）；另外 `AnonymousRemovalIntegrationTest` 旧客户端用例的 `"category", "DAILY"` 字段**有意保留**——它就是「老客户端多带 category 被忽略」的活体回归（与该用例原本验证的 anonymous 字段同型），DisplayName 已注明。
- **直插 SQL 的测试 fixture 不动**：FeedIndexUvBackfillIntegrationTest / AuthorProfileIntegrationTest 有 `INSERT INTO post (…category…)` 直插——列在票 07 前物理存在，且前者钉的是票 01 回填脚本语义，动了反而失真。
- **FeedCursorPagingIntegrationTest 的「分类过滤走旧直查」用例删除**：被测分支（分类过滤）已不存在，非改写。

### 验证

- 全量 bs-app 357 绿（票 05 收口时 358，减 1 = 删除的分类过滤用例），BUILD SUCCESS；前端 `vue-tsc --noEmit` 过，商品域 TradeCategory/ProductCard/MarketView 一行未动。
- 商品测试（TradeFullChain / TradeDraftBox / TradePublishPendingReview 等）全绿确认商品 category 不受影响（票面第 5 项）。
