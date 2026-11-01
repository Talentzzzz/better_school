# 02 · 浏览合并器接入与详情读路径换源

Type: task
Status: resolved
Blocked by: 01

## Task

浏览体系切到 UV 口径，形态是 `CommunityRepository.likePosts` 的镜像：

1. 详情读路径挂浏览事件进合并器（buffer (userId, postId)，按 post 聚组）
2. 组提交一个事务三步：`INSERT IGNORE INTO user_view_history`（affected = 新增 UV 数）→ `UPDATE viewed_at`（本组全部键，含刚插入的）→ `post_view_count += 新增数`
3. 删除 `increaseViewCount` 及其调用链；`selectCountsByPostId` 的 view 段改读 `post_view_count`
4. UV 语义：INSERT 成功才算浏览，重复进详情只刷 viewed_at
5. CONTEXT.md「浏览量」词条改写（近似累加值 → 精确 UV，真相源 = 明细表）；「点赞计数」等其余词条不动
6. 集成测试：首次浏览计数 +1、重复浏览不计数但 viewed_at 刷新、取消类语义不适用（浏览无回退）

## Answer

### 交付物

- **事件带人**：`CountEvent` 本就携带 `userId`，改动只在接线——`ICommunityRepository.recordPostView(postId)` → `recordPostView(postId, userId)`，`PostServiceImpl.postDetail` 把 `currentUserId` 传下去；`ViewMergeWriter` 新增三参 `write(action, rowId, userId)`（两参重载保留给商品浏览），**POST_VIEW 强制 `userId > 0`**（哨兵 0 会把全体匿名塌成明细表一行，运行期直接拦，单元测试锁住）。匿名访问（GET 白名单）在 case 层就是 `null`，仓储 `recordPostView` 判空直接返回——**匿名不计入**是「INSERT 成功才算浏览」的直接推论（明细表主键的一半是 user_id，无处可落）。
- **落库镜像**（`CommunityRepository.viewPosts`，替换 `increasePostViews`，出口分发不变）：一组一个事务三步，顺序按票面——`insertViewHistory`（INSERT IGNORE，影响行数 = 新增 UV 数）→ `touchViewedAt`（本组全部键，含刚插入的）→ `upsertViewCount += 新增数`（ODKU，行懒建；增量显式传 INSERT 的影响行数，不用 ODKU 的 affected rows——同秒重复刷 viewed_at 会把它打乱）。批内去重复用 `distinctUserIds`。占位结果照旧 `APPLIED` 等长回填。浏览没有取消语义，加速列语句不带 clamp。
- **increaseViewCount 删除**：DAO 方法 + XML 语句整条拔掉；`INSERT INTO post` 与 `postColumns` 投影同步摘掉 `view_count`——**停写且停读**（列表/详情投影的 view 段一律换源，`post.view_count` 冻结为退役前的旧累计值，列保留到收口票删除）。
- **读路径换源**：`listCountColumns`/`listCountJoins` 加 `post_view_count` LEFT JOIN + `COALESCE(pvc.view_count, 0)`，于是 `selectCountsByPostId`（详情）与全部列表查询的 viewCount 都出自加速列；`selectById`（进缓存的内容查询）不带计数，口径不变。
- **文档**：CONTEXT.md「浏览量」词条改写为精确 UV（真相源 = 明细表、匿名不计入、商品浏览仍是累加近似值显式划出去）；「评论总数」里「与浏览量近似口径不同」的过时交叉引用顺手修正。sql.md `post.view_count` 列注释、docs/db/community.md 两处、docs/api/community.md 详情接口说明同步。

### 决策点（票面没写死的）

- **匿名浏览不计入**：UV 明细表主键 `(user_id, post_id)` 决定了无主浏览无处可落；按「INSERT 成功才算浏览」的口径，匿名访问整次不记录。商品浏览（无明细表）不受影响。
- **`ViewMergeWriter` 在入口拦 POST_VIEW 无主件**：而不是落库层兜底——哨兵 0 落进明细表是静默的口径污染，开发期就炸比线上污染好。

### 验证

- 单测：`ViewMergeWriterTest` 全部 POST_VIEW 件带 VIEWER，新增「帖子浏览必须带浏览者」护栏用例（商品浏览两参不受影响），通过。
- 集成测试（`PostCountMergeIntegrationTest` 浏览两用例改 UV 口径）：① HTTP 端到端——首次浏览明细 1 行 + 加速列 1；重复浏览不计数但 viewed_at 从拨回的远古时刻刷新回当下；第二人 +1；匿名浏览不计数；`post.view_count` 冻结恒 0；丢弃数可读且健康路径为 0；仍不走 outbox + MQ。② flushGroup 直调——同批三新用户 = 明细 3 行加速列 3；第二组「已看过 + 同批重复 + 新人」只 +1 且已看过者的 viewed_at 被刷新。C5 缓存不存计数的 viewInDb 换源 post_view_count。
- 社区包 79 用例全绿；全量 bs-app 套件 **343 tests, 0 failures**（BUILD SUCCESS）。
