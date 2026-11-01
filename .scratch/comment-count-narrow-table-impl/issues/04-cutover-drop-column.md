# 04 · 存量库割接：删列 + 脚本 + fixture 清理

Type: task
Status: resolved
Blocked by: 02, 03

## What to build

走到本 effort 的不可逆点：删掉 `post_comment.like_count`，并给出存量库照做的脚本。删列前必须确认无任何读方残留（02 已换源）**且重建 job 已在位**（03）——列一删，索引漂移的唯一自愈手段就只剩它，所以 03 是本票的硬前置。

1. **`schema.sql`**：`post_comment` 去 `like_count` 列并留一行删列注记（照 `post.view_count` 的先例）；两张新表在 01/02 已就位，本票核对形态与注释无漂移。
2. **存量库脚本** `sql/backfill/comment-like-count-cutover.sql`：**不幂等**，头注释写清——
   - 执行序：① 建 `post_comment_like_count` ② 建 `post_comment_feed_index` ③ 起新 jar（读窄表 + 过审造行 + 索引同步；新 jar 的前提是两表已在）④ 删 `post_comment.like_count`；新库由 `schema.sql` 直接建全，开发库/测试库不需要跑；
   - **不管存量**：不回填、不搬历史点赞；存量评论点赞显示 0，旧列删除时历史数据丢弃——已用户确认接受；运营侧若要「历史热评」只能重放 `post_comment_like` 明细（本 effort 不做）；
   - 回滚口径：不可逆点就是删列。删列前旧 jar 可无脑回滚（两张新表多余但无害）；删列后回滚需手工反向 DDL，历史点赞数据不回来。
3. **mapper / PO 收口**：`baseColumns` 去列（`reply_count` 留）、`insert` 去列、四条读语句列清单确认已去列；主表版 `updateLikeCount` 随 02 换落点后退役（mapper 与 DAO 声明一并删干净，不留死语句）；`PostCommentPO.likeCount` 保留为投影填充字段。
4. **fixture 与直查**：两处直插评论夹具去掉 `like_count` 列（作者主页、点赞落库两个测试类）；直查主列的辅助 SQL 在 02 已换窄表，本票复核无残留。
5. **全量回归**：bs-app 全量套件绿。effort 是单 PR 交付，无停写窗口、无双读观察期。

## Acceptance criteria

- [x] `schema.sql` 的 `post_comment` 无 `like_count`，删列注记在表定义处可查
- [x] 割接脚本头注释含完整执行序、「不管存量/存量显示 0」、回滚口径；脚本在本 effort 内可被人工照做（测试库不依赖它）
- [x] mapper 与 DAO 中主表版点赞计数语句无残留死代码；`baseColumns` / `insert` 去列
- [x] 两处直插评论夹具去列后可跑；测试库重灌 schema 后新老用例全绿
- [x] 全量 bs-app 套件绿（BUILD SUCCESS），无 `Unknown column 'like_count'` 一类残留

## Comments

## Answer

**落点**

- `sql/schema.sql`：`post_comment` 去 `like_count` 列（`reply_count` 留），表定义后按 `post.view_count` 先例留删列注记——真源 `post_comment_like_count`、排序快照 `post_comment_feed_index`、存量点赞不搬（用户已确认接受，历史评论显示 0）、存量库走割接脚本。两张新表的形态与注释经核对与 01/02 落地形状无漂移（`post_comment_feed_index.like_count` 注释里「票 02 前为主列」是过去时表述，不动）。
- `sql/backfill/comment-like-count-cutover.sql`（新增，不幂等）：头注释四段——**执行序**（① 建窄表 ② 建索引表 ③ 起新 jar ④ 删列，并写明 ③ 的前提是两表已在：新代码的审核通过与点赞事务直接写它们）；**前置确认**（两张新表有活写入 = 新 jar 已在跑——effort 之前的 jar 压根不认这两张表；旧列值域冻结靠隔一段时间跑两次 `COUNT(*) ... WHERE like_count > 0` 观察，同 `feed-index-uv-drop-columns.sql` / `market-feed-uv-drop-columns.sql` 的口径）；**不管存量**（不回填不搬数 → 存量评论窄表缺行按 0 显示；索引表从空表起步，故「最热」档在存量帖上只见切码后过审的根评论，要补齐跑一次 `commentFeedIndexRebuildJob`，补齐行 like_count 按窄表抄 = 0、热评序对这些行退化成 comment_id 升序；要「历史热评/历史点赞数」只能重放 `post_comment_like`，本 effort 不做）；**回滚口径**（不可逆点就是删列；删列前回滚到 effort 之前的 jar 无害——那版只认旧列、不读不写新表；删列后必须先反向 DDL `ADD COLUMN like_count INT NOT NULL DEFAULT 0`，加回来是全 0 空列、历史点赞不回来；单 PR 交付故线上只有「之前/之后」两版 jar）。脚本**不复制 DDL**，唯一来源仍是 `schema.sql`（同 `market-feed-uv-backfill.sql` 的口径）。
- **mapper / DAO / PO 收口（复核结论：零改动）**：`baseColumns` 与 `insert` 票 02 已去列；四条读语句（`rootCommentColumns` 两档共用 / `selectRepliesByRootId` / `selectPageByUser` / `selectById`）列清单无 `like_count`；主表版 `updateLikeCount` 在票 02 是**就地换落点**改成写窄表，故 mapper 里只剩一条指向 `post_comment_like_count` 的 `updateLikeCount`、DAO 只剩一条同名声明——**没有残留死语句**。`PostCommentPO.likeCount` 保留为投影填充字段并补 javadoc（「表里没有这一列，真源在窄表，缺行补 0」）。
- **测试收口**：两处直插评论夹具（`AuthorProfileIntegrationTest.insertComment`、`LikeCountFlushHandlerIntegrationTest` 的评论夹具）票 02 已去列，本票复核无残留。真会因删列报错的是**三处直查主列的断言**（`legacyMainColumnLikeCountOf` ×2 类、`legacyMainColumnLikeCount` ×1 类，共 7 个断言点 + 2 个私有 helper）——全部删除：删列后「主列已停写」不再可断言，它由「列不存在」结构性保证；「计数不再刷主行 `update_time`」那条留着（那是「计数不碰主行」的直接证据）。顺带改准命名与过期注释（`likeSync_..._withMainColumn_...` → `_withNarrowCount_...`、类注释 / DisplayName 里「主列与主行都不动」、`commentLikeCountOf` 三处 helper 注记）。
- **脚本实测**：临时库 `bs_cutover_check` 造一张带 `like_count` 的 `post_comment` → 跑脚本列消失；二次执行 `ERROR 1091`，与「不幂等」头注释一致；临时库已删。`reset-test-db` 重灌后 `information_schema` 确认 `better_school_test.post_comment` 无该列（开发库 `better_school` 仍留旧列，按票面「开发库/测试库不需要跑」不动）。

**测试**：`CommentFeedIndexIntegrationTest` 9/9、`CommentLikeCountNarrowTableIntegrationTest` 5/5、`LikeCountFlushHandlerIntegrationTest` 3/3 绿；全量 bs-app **410 tests, 0 failures, BUILD SUCCESS**（`mvn-t04-full3.log`），无 `Unknown column 'like_count'`。

**环境注记（三跑，前两跑栽在环境）**：
1. 首跑 35 errors：namesrv 容器 JVM abort（Exit 134）后 `TRUNCATE errand_order` 撞 `Communications link failure`（mysqld Exit 2，整栈随之退出），级联到 35 例全红——非代码。重启整栈 + 重灌测试库。
2. 第二跑 410 例 1 failure：`CommentFeedIndexIntegrationTest.hotSort_isIndexOrder_pagingNoDuplicateNoMiss` 的点赞返回 999。日志落在 `CountMergeWriter` 的「点赞类写入需重试 action=COMMENT_LIKE ... 等窗口超时」——`wait-timeout-ms: 100`（5ms 窗口 + 8ms 落库 + 抖动）在冷 buffer pool 的全量负载下被打穿；单跑该类 9/9 绿、第三跑全量绿。与票 03 记的 `FeedCursorPaging` 999 同一机制，本票未碰点赞路径。
3. 第三跑（重灌测试库后）410/0 BUILD SUCCESS。

**留给票 05 的口子**：`docs/db/community_comment.md` 仍写着「评论点赞**不走窄表**，就留在本行」（L28/L50/L86）。按票 05 的分工（`docs/db` 两新表 + 「已删除的列」段 + 全文无「主表行 like_count」残留）由票 05 收；本票只改 `schema.sql` 的注记，不动文档（CONTEXT / docs/api / docs/db / sql.md 全部是票 05 的票面范围）。

**code-review 双轴**：standards 轴 2 条硬伤已修——① 回滚段「旧 jar 在四条读语句和计数 UPDATE 里都写着 like_count」措辞歧义（会被读成上一版构建），按单 PR 事实改准为「effort 之前的 jar」并补一句「线上只有前/后两版」；② 「何时执行」里「跑着旧读路径的库」与 ④ 要求新 jar 已上线自相矛盾，改为「尚未删列的存量库」。另采纳一条判断项：补「前置确认」段，与同目录两份删列脚本对称。未采纳一条：脚本名 `*-cutover.sql` 与先例 `*-drop-columns.sql` 不一致——票面点名该文件名。spec 轴（子代理未返回，由我逐条核对）：五个 AC 全过（见上）；核对 01~03 的落点前提时确认脚本头注释里「四条读语句已无该列」「宽表缺行显示 0」「重建 job 只兜索引不兜窄表」三条与代码一致；唯一边界是把 `docs/db` 的过期表述显式登记给票 05。

