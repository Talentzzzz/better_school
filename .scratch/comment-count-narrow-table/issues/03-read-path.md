# 03 · 读路径口径：展示取数、hot 排序、时间排序键

Type: grilling
Status: resolved
Blocked by: 01, 02

## Question

**2026-10-27 修订**：票 02 拍板只抽数 like_count（reply_count 留主行零改动），本票收窄为只裁 like_count 的读路径。已定方向：时间排序不回查、hot 排序回查（接受集合内排序现状不变）。要收掉的口：

1. **列表展示取数形态**：根评论列表、楼内平铺、作者主页评论 tab 的 `like_count` 展示——SQL 里 LEFT JOIN `post_comment_like_count`（`IFNULL(plc.like_count,0)`），还是照市集「viewed 批量回查」先例（页内 IN 一次回查、Java 侧拼装）？两派都要在评论一页 20 条的量级下给成本账，选一个全站统一（R4 selectById 单行定位也取 like_count，取数形态要兼容单行场景）。
2. **时间排序键裁决**：用户原提「update_time」，已被事实推翻（审核流转仍刷主行 update_time，票 02 后计数写不再刷它，但污染未绝；且现状键本来就在主行上——根列表 `c.id`、主页 tab `create_time DESC`，零回查零改动）。确认「保持现状键」，词条里要不要给「技术时间戳不作排序键」的惯例在这张表上加一条注记。
3. **hot 排序的退化形状**：`ORDER BY c.like_count DESC, c.id` 改 JOIN 后 SQL 内排序（`ORDER BY IFNULL(plc.like_count,0) DESC, c.id`，与今天同形的 filesort）还是「回查 + Java 排序」？LIMIT offset,limit 分页与它的兼容性（现状就是 offset 分页，不动）。
4. **待审/驳回态的自见评论**：根列表查询带 `PENDING_REVIEW/REJECTED AND user_id = viewer` 分支（自见），这些行窄表行不存在（行诞生于审核通过）——LEFT JOIN + IFNULL 自然落 0，口径要不要明说（数值上也对：待审态不可被赞）。
5. **PO/DTO 改动面**：`PostCommentPO` 保不保留 likeCount 字段（照 ProductPO 先例「字段保留、由投影列填充」还是彻底删字段走 VO 拼装）。replyCount 字段随形态修订零改动。

## Answer

2026-10-27 grilling 收敛。**过程中用户拍板了第二次形态升级：照帖子 feed 形态做热评排序**——先选了「展示批量回查」，与「SQL 内 ORDER BY」在旧列删除后发生先后矛盾（排序发生在取数之前，批量回查拿到的数排不了序），摆出三条化解路后用户选「照 feed 建索引表」，并听完成本对比（feed 索引是为全站规模设计的、评论只有单帖内几十条排序）后仍确认。四项拍板：

1. **两件套确认**：计数窄表 `post_comment_like_count`（全量评论计数真源，照票 02 已定 id+uk）+ **新增 `post_comment_feed_index`**（根评论排序投影，照 post_feed_index 同构）。帖子侧本来就是这两张同构表（post_like_count + post_feed_index），评论照搬职责各清：窄表管计数真值，索引管热评序。
2. **索引表 DDL**：PK = `comment_id`（业务键，feed 先例）；列 `comment_id, post_id, like_count, create_time, update_time`；索引 `(post_id, like_count, comment_id)`——热评档沿索引序扫描，不再 filesort。**无 state 列、行集 = 当前可见根评论**：过审事务 INSERT、软删事务物理 DELETE（评论软删是终态无恢复路径，帖子侧的 state 快照镜像在评论这里永远只有 NORMAL，不维护）。子回复不入索引（不按赞排），只进窄表。
3. **同步点**：根评论过审 INSERT（赞数 0 诞生）；根评论软删 DELETE（同事务）；根评论点赞 ±N 在合并事务/取消赞事务里旁带刷索引快照（0 行命中落 warn，与窄表同款口径）；子回复点赞只动窄表。
4. **重建 job**：配——照帖子 `FeedIndexRebuildJob` / 市集 `MarketFeedIndexRebuildJob` 样板新增 XXL-Job（手动触发 trigger_status=0）：清空索引 → 从 post_comment 可见根评论重灌（like_count 取窄表真值）。
5. **热评档不自见**（用户拍板）：索引只收已过审根评论，作者待审/驳回自见行不再出现在热评档（今天以 0 赞垫底参与排序）；默认档/时间档自见照旧。total 口径不变（他人可见 = 仅 NORMAL），热评档省略自见行与 total 口径反而更一致。

读路径其余各口：

- **展示取数 = 批量回查**（用户拍板，照市集 viewed / feed listByIds 思路）：主查询不再带 like_count 列；页内评论 id 集 → 一次 IN 回查 `post_comment_like_count` → Java 填充 `PostCommentPO.likeCount`。三个列表（根列表、楼内、作者主页 tab）统一此形态；R4 `selectById` 单行同通路（单 id 回查）。热评分支的取数序 = 索引序扫描出 comment_id 页 → 回查 post_comment 行（带 bs_user/content_review JOIN，WHERE comment_id IN）→ 按扫描序重排 → 批量回查计数填充。
- **时间排序键：保持现状键**（根列表 `c.id`、主页 tab `create_time DESC`），词条不加新注记（「技术时间戳不作排序键」惯例库内已成文）。
- **offset 分页 + total 契约不动**（前端零感知是 map Out of scope；索引序扫描配 offset LIMIT 在单帖楼数量级无问题）。
- **PostCommentPO.likeCount 字段保留、由批量回查投影填充**（ProductPO 先例），toCommentEntity → PostController 组装 → DTO 链零改动；replyCount 字段随形态修订零改动。
