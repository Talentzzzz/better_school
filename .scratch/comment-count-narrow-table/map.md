# map：评论计数拆窄表（统一落库形状）

Label: wayfinder:map

**Status: 目的地达成（2026-10-27）** —— `spec.md` 定稿（形态经两次用户拍板修订：票 02 收成单列窄表 + 不管存量；票 03 升级为照 feed 形态建热评投影索引）。本 map 关闭；落地另起 `-impl` effort。

## Destination

`.scratch/comment-count-narrow-table/spec.md` 定稿：`post_comment.like_count` 抽数到单列窄表 `post_comment_like_count`（**2026-10-27 票 02 修订：reply_count 留主行不搬**，读写路径零改动），写路径换落点、点赞语义档位不变（点赞与关系行同事务精确）；热评排序**照帖子 feed 形态加根评论投影索引 `post_comment_feed_index`**（**票 03 二次修订，用户拍板**：索引序扫描 + offset 分页不动 + 重建 job 兜底 + 热评档不自见）；展示取数批量回查；建表与删列序（**不管存量**，无回填）；词条、文档与测试收口清单。**只出决策与 spec，不写业务代码**；落地另起 `-impl` effort。

## Notes

- **动机**（用户 2026-10-27 拍板）：统一计数落库形状——与 post 侧「批量关系行 + 一条合并 UPDATE，一组一个事务」同形。是形状统一，不是性能改造：读写路径的成本（评论列表多取数、hot 排序索引序退化）已明知并接受，不再重开「要不要拆」。
- **已定口径**（本轮对话定稿，票内不再重开）：
  1. 只出 spec，落地另起 `-impl`（照 hot-row-merge-l2w256 / interaction-counter-model 先例）。
  2. ~~窄表形态：一张两列表~~ **（2026-10-27 票 02 grilling 修订，用户拍板：只抽数 like_count，单列窄表 `post_comment_like_count`；reply_count 留主行，读写零改动）**。行诞生于**审核通过事务**（post_feed_index / 市集六表「行诞生于进入可见轮」先例），点赞 UPDATE 恒命中行、不用 ODKU 补行。
  3. ~~旧列删列（market-feed-uv 票 06 先例：不幂等 backfill 脚本 + 头注释执行序）~~ **（票 02 修订：不管存量——无回填脚本，存量历史点赞丢弃显示 0，用户确认接受；仍删列，建表 → 切码 → 删列）**。
  4. reply_count 语义档位不动：审核动作不批量化（hot-row-merge 票 04 结论沿用），本 effort 只换 UPDATE 落点。
  5. ~~时间排序不回查：留在主行；热度排序回查（接受索引序退化）~~ **（票 03 二次修订：时间排序保持现状键零回查；热度排序照帖子 feed 形态走新增的根评论投影索引 `post_comment_feed_index` 索引序扫描，配重建 job 兜底，热评档不自见）**。
- **事实基线**（2026-10-27 查证）：
  - 根评论列表默认排序 `ORDER BY c.id`、hot 排序 `ORDER BY c.like_count DESC, c.id`（PostCommentMapper.xml:42-45）；作者主页评论 tab `ORDER BY c.create_time DESC, c.id DESC`（:121）；楼内子回复 `ORDER BY c.id`。
  - `updateLikeCount` 刷 `update_time = NOW()`（:171-175），审核通过与软删同样刷——update_time 是被计数/审核写污染的技术时间戳，**不可当排序键**（schema 惯例原文「排序键永远不用 update_time」）。用户曾提「时间排序用 update_time」，以此事实改判为「保持现状键」，在读路径票送裁决。
- 用到的 skill：口径票 `grilling` + `domain-modeling`；事实票 `research`。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## Answer 里 -->

- [01 · 触点清单：like_count / reply_count 的全部读写点](issues/01-touchpoints-research.md)：读点 4 处、写点 2 条 UPDATE / 4 个事务入口、直接断言测试 4 类（间接 2 类）、暗触点 0 个真实消费方（仅注释与两个同名窄表易混淆），评论无物理 DELETE——细节见 [research/touchpoints.md](research/touchpoints.md)
- [02 · 窄表 DDL 与写路径定稿](issues/02-ddl-and-write-path.md)：**形态修订拍板——只抽数 like_count，单列窄表 `post_comment_like_count`（id+uk、无二级索引），reply_count 留主行零改动；行诞生于审核通过事务（INSERT IGNORE + 0 行落 warn）；点赞 UPDATE 落窄表、0 行命中落 warn 不 ODKU、主行 update_time 不再被计数写刷；不管存量无回填**（票 03 二次修订追加 feed 索引，见下）
- [03 · 读路径口径](issues/03-read-path.md)：**二次形态升级拍板——热评排序照帖子 feed 形态：新增 `post_comment_feed_index`（仅根评论、可见行集、软删删行、(post_id,like_count,comment_id) 索引、无 state 列）+ 索引序扫描 + offset/total 契约不动 + 重建 job 照帖子样板 + 热评档不自见；展示取数三列表统一批量回查窄表；时间键保持现状；PO 字段保留投影填充**
- [04 · 建表与删列序](issues/04-migration-and-cutover.md)：单 PR 一步到位（无停写窗口、无回填）；存量库独立脚本三条 DDL 不幂等 + 头注释执行序（建窄表 → 建索引 → 切码 → 删列）；重建 job 照市集票 06 样板落地；不可逆点 = 删列
- [05 · 词条、文档与测试收口](issues/05-docs-tests-and-spec.md)：「热度」词条改多域（三处「最热」口径一个词条写清）、「点赞计数」词条补落库位置 + 形状统一动机、docs/api 明写热评档不自见、测试改动清单（改 5 类 + 新增 6 组）、spec.md 组装定稿

## Not yet specified

<!-- 见「Fog of war」：还在雾里、尚不能成票的 in-scope 问题 -->

- **批审事务化后的形状**：将来审核动作若批量化（hot-row-merge 票 04 留的口子），窄表 INSERT 与索引 INSERT 随同一批事务走的合并形态——本 effort 已留口子（spec §8），不设计。

<!-- 以下雾项已随票 01 收雾：暗触点 0 个真实消费方；评论无物理 DELETE，窄表行生命周期 = 评论行生命周期，feed 索引行软删物理删行。 -->

## Out of scope

<!-- 见「Out of scope」：被明确划到本 effort 终点之外的工作；已关闭，永不毕业 -->

- **批审接口 / 审核吞吐改造**：hot-row-merge 票 04 已定「真到了再做」，本 effort 不混入。
- **post_comment_count（帖子评论总数窄表）**：它已经是窄表，形态不动，只可能在词条里被顺带提及。
- **post_comment_like 关系表结构变更**：关系表是事实源，不动。
- **语义档位变更**（点赞 / reply_count 改近似或异步）：已定精确不动。
- **前端改动**：对外字段名与数值语义不变，前端零感知。

## 交接

落地另起 `comment-count-narrow-table-impl` effort，从 `spec.md` §5 交付序开始；形态/口径不再重开，遇「为什么不是另一种」回票 `## Answer`。
