# 05 · 词条、文档与测试收口清单

Type: grilling
Status: resolved
Blocked by: 03, 04

## Question

> **2026-10-27 修订**：票 02/03 两次形态修订后，本问第 2 项「schema.sql 两列删列注记」应读作「仅 like_count 删列（reply_count 留主行）」；第 4 项新增用例里的「回填不动点」应读作「重建 job 不动点」。其余照旧。

口径落文档、改动面落测试清单，spec 收尾段。依赖触点清单票的测试面盘点：

1. **CONTEXT.md 词条**：「点赞计数」词条现在写「与点赞关系行同事务提交：一批点赞记录与一条计数 UPDATE 合并落库」——拆表后对评论侧仍成立，但「计数行存放位置各就各位」的不对称要不要写成显式规则（本 effort 的动机注记）；「评论总数」词条要不要提 reply_count 落点变化。
2. **docs/db**：`community_comment.md` / `community.md` / `sql.md` 的表结构与读写路径描述同步；schema.sql 两列删列注记 + 新窄表注释。
3. **docs/api**：对外字段不变，读路径描述里「计数来源」的表述有没有要改的段落。
4. **测试改动清单**：按触点清单票的盘点，逐个列出要改的断言（锁计数、软删扣减、楼内计数）与要新增的用例（行诞生于审核通过、点赞命中窄表、0 行命中分支、回填不动点），以及全量回归的预期涨落。
5. **spec.md 组装**：以上四票的 Answer 收拢成 `.scratch/comment-count-narrow-table/spec.md`，结构照 hot-row-merge spec（形态表 + 语义底线表 + 写/读/迁移分节 + 验收判据引用），定稿后 map 关闭。

## Answer

2026-10-27 grilling 收敛，三问照推荐、两问照清单走：

1. **CONTEXT.md「热度」词条改多域**：一个词条写清三处「最热」口径——主页 feed 热榜与评论热评 = **点赞数**降序（同数按业务 ID 收敛确定序），市集两域推荐页 = **浏览 UV** 降序；Avoid 段随之整理（不再把帖子/评论口径当避用语，只避「热度分/加权/时间窗衰减」）。
2. **「点赞计数」词条补一句落库位置 + 动机注记**：帖子在 `post_like_count`、评论在 `post_comment_like_count`（关系行同事务合并落库的精确语义不变）；动机注记 = 计数落库形状与帖子侧统一（本 effort 存在理由），根评论另有 `post_comment_feed_index` 快照只服务热评排序、不是真值源。「评论总数」词条不提 reply_count（形态修订后零变化）。
3. **docs/api/community.md 明写热评档不自见**：评论列表接口 hot 档只返回已过审根评论，作者待审/驳回自见评论只在默认（最新）档可见；对外字段名与数值语义不变，其余读路径描述不动。
4. **docs/db 同步面**：`docs/db/community_comment.md` 两张新表结构 + `post_comment.like_count` 删列注记（「已删除的列」段，照 market-feed-uv 先例）；`docs/db/community.md` 读路径描述换源；`sql.md` 新表节 + 脚本登记；`schema.sql` 两表 DDL + 删列注记。
5. **测试改动清单**（全量落，见 spec §7）：改 5 类（两处 fixture 去列、两处直查 SQL 换窄表、hot 排序路径用例随索引序适配、间接覆盖两类 CommentDeleteSemantics / CommentRootListV2 随 hot 档不自见口径补断言）、新增 6 组（行诞生 / 索引同步 / 软删删行 / 热评档含不自见 / 重建 job 不动点 / 批量回查填充），`CommentThreadingIntegrationTest` 走 API 断言 replyCount 零改动。
6. **spec.md 已组装定稿**：结构照 hot-row-merge（目的地与边界 → 形态 DDL → 写路径 → 读路径 → 交付序与同步面 → 词条与文档 → 测试 → 残留风险），票号即出处。
