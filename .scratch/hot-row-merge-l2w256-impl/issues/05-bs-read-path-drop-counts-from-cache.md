# 05: bs 读路径——详情缓存不再存计数

**Spec:** `.scratch/hot-row-merge-l2w256/spec.md` §3.3；判据见 `acceptance.md` C5

**What to build:** 详情缓存只缓存正文 / 状态 / 作者 / 配图，`like_count` / `comment_count` / `view_count` **不进缓存**；详情读拆成「内容查询（走缓存）+ 计数查询（每次读，按主键一次取三个数）」，在 DTO 里拼起来。

收益是删掉一整条耦合：刷库后 `postDetailCache.evict` 那句消失，合并器从此不知道缓存存在。列表页仍走原来的 LEFT JOIN 投影（不进缓存 ⇒ 就是实时值），不改。

读路径**不叠加在途增量**：点赞请求返回时事务已提交，读路径不认识合并器，因此也不需要模拟「缓冲里还有几件」。

**Blocked by:** 03（C5 的断言是「先赞 → 立刻读详情，三个数就是新的」，200ms 聚合器还在时这条做不到）

**Status:** resolved

- [x] `PostEntity` 进缓存前置空三个计数字段；缓存里摸不到任何计数（`PostDetailCache.put` 入口统一 `stripCounts`，无论调用方传什么）
- [x] 详情读拆两段：内容查询进缓存，计数查询每次按主键取三个数并在 DTO 拼装（`PostMapper.selectById` 去掉 plc/pcc 两个计数 JOIN 只留内容+作者；新增 `selectCountsByPostId` 一次取回三个数；`CommunityRepository.findPostById` 命中/回源两条路都经 `withLiveCounts` 拼装）
- [x] C5 新用例 `detailCountsNeverComeFromCache` 绿：先赞 → 再读详情（缓存命中路径），三个数恒等于库值（`PostCountMergeIntegrationTest`；另断言缓存 JSON 里三个计数字段恒为空置 0）
- [x] 「点赞刷库后 `evict`」整条删除；缓存与合并器之间零引用（票 03 起落库路径本就无 evict；本票再删掉 `updatePostCommentCount` 里最后一句计数相关 evict——评论数落窄表后下一次读自然取到新值。`deletePostById` / `approvePostById` / `rejectPostById` 三处 evict 是**内容/状态**失效，保留）
- [x] 列表页投影不改，仍是实时值（不进缓存）（`baseColumns` / `baseJoins` 拆成「内容列+计数列」「计数 JOIN+用户 JOIN」两块复用，列表语句一行未动）
- [x] 读路径不引入在途增量叠加（不允许出现「合并器 + 缓存 + 缓冲件数」三处拼计数）（`withLiveCounts` 只查库，不认识合并器）

## 落地记录

- **计数拼装落在仓储边界（`findPostById`），不在 trigger 的 DTO 转换器**：`findPostById` 的消费方不止详情接口——搜索同步反查（`SearchQueryPort.findPost`）、聊天卡片反查（`CardRefQueryPort`）、点赞/审核前置可见性检查都走它。在仓储拼装，所有消费方拿到的都是「实时计数 + 缓存内容」；若只在 DTO 层拼，这些内部消费方会读到缓存实体上的 0。代价是点赞/审核的前置 `getNormalPost` 也多一次按主键计数查询（单行 PK 查，可接受）。
- **`selectById` 去掉计数列后 `toPostEntity` 需空安全**：`PostPO.likeCount/commentCount` 是 `Integer` 投影列，列缺席时为 null，直接拆箱 NPE——`toPostEntity` 三行改 null→0 兜底（列表路径 `selectByIds` 等仍带计数列，行为不变）。
- **`baseColumns` / `baseJoins` 拆块复用而不是复制**：列表 10 条语句继续 `include` 原名片段（内部改为引用 `postColumns` + `listCountColumns`），投影字节级不变；详情内容查询只 include 内容列 + 用户 JOIN（评审意见：JOIN 也抽成 `authorJoins` 与 `authorColumns` 对称）。
- **评审收敛**：测试里的 `bs:post:detail:` 键字面量从 4 份收到 1 份（`CommunityApiTestBase.DETAIL_CACHE_KEY_PREFIX` + `detailCacheBucket()` helper，两个测试类删除各自私有副本）；`PostDetailCache.put` 的「就地清零传入实体」副作用已在 javadoc 标注（唯一调用链 getOrLoad 回填后会被 `withLiveCounts` 重填，无观察面）。
- **`CommentReviewHumanizedIntegrationTest#decide_approve_rootComment_countsAndNotifies` 只改措辞不改断言**：原断言「详情读 commentCount == 1」在新机制下依然成立（缓存命中路径每次回库拼装），DisplayName 与注释从「缓存失效后回源」改为「缓存命中路径恒等库值」——HTTP 外部行为没变，正好是「缓存与计数零耦合」的旁证。
- C5 用例里 view_count 的取值时序：详情读的计数查询先于本请求的 fire-and-forget 浏览 +1，断言前先 `countMerger.awaitIdle` 排空在途浏览件，保证「取库值 → 读详情」之间库值静止——否则第一步读触发的异步 +1 可能在两刻之间落库，等值断言会抖。
