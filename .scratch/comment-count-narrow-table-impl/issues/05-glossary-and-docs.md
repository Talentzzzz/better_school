# 05 · 词条与文档收口

Type: task
Status: resolved
Blocked by: 04

## What to build

把落地后的形状写回词汇表与文档，让「热度」「点赞计数」这两条词条与三份文档不再与代码打架。删列已在 04 完成，本票的「已删除的列」段落才成立，所以 04 是硬前置。

1. **CONTEXT.md「热度」改多域**：三处「最热」一个词条写清——主页 feed 热榜与评论热评 = 点赞数降序（同数按业务 ID 收敛确定序），市集两域推荐页 = 浏览 UV 降序；_Avoid_ 段只避「热度分 / 加权 / 时间窗衰减」，不再把「热门」当禁忌词（它现在是同一个词条的另一域）。
2. **CONTEXT.md「点赞计数」补一句**：落库位置各就各位（帖子 `post_like_count`、评论 `post_comment_like_count`），根评论另有 `post_comment_feed_index` 快照只服务排序、不是真值源；再加动机注记——计数落库形状与帖子侧统一。「评论总数」词条不动（`reply_count` 零变化）。
3. **`docs/api/community.md`**：明写评论列表 hot 档只返回已过审根评论，待审/驳回的自见评论只在默认档可见；对外字段名与数值语义不变（前端零感知）。
4. **`docs/db`**：`community_comment.md` 增两新表（形态、行诞生时机、谁写谁读）+「已删除的列」段记 `post_comment.like_count`（删列 + 存量点赞不搬、显示 0）；`community.md` 的评论读路径段换源说明（批量回查 + hot 索引序 + 重建 job 兜底）。
5. **`sql.md`**：两张新表节、头部表数同步、割接脚本登记（照 `market-feed-uv` 的脚本登记先例）。

## Acceptance criteria

- [x] 「热度」是一条多域词条，三处「最热」口径都查得到，_Avoid_ 段不再与「热门」冲突
- [x] 「点赞计数」写清三处落库位置与「索引快照只服务排序」的分工，并含形状统一的动机注记
- [x] `docs/api/community.md` 明确 hot 档不自见，且声明对外字段与数值语义不变
- [x] `docs/db/community_comment.md` 有两新表与「已删除的列」段；`docs/db/community.md` 读路径段与新实现一致
- [x] `sql.md` 含两新表、表数更新、割接脚本登记，与 `schema.sql` 无口径漂移
- [x] 全文无「评论点赞在主表行上」「主表行 like_count」一类过期说法残留

## Comments

## Answer

**落点（7 个文件，全文档向；唯一代码文件改动是 `schema.sql` 两条注释）**

- `CONTEXT.md`「热度」改多域：一条词条写全三处档位——主页 feed 叫「热榜」、评论叫「最热」、市集推荐页直接叫「热度」；每处单一因子（点赞数 / 浏览量）+ 同值按业务 ID 收敛（feed 降序、评论 `comment_id` 升序）。_Avoid_ 只剩「热度分 / 加权热度 / 时间窗衰减 / 综合排序」，「热门」不再是禁忌词。顺带把「最热」词条与代码对齐（同数按 `comment_id` 升序＝发布时间先后，原文只写「按发布时间先后」没写收敛键），并加一句指回「热度」，三处口径互查得到。
- `CONTEXT.md`「点赞计数」补落库位置与分工：帖子 `post_like_count`（行随首次点赞 upsert 诞生）、评论 `post_comment_like_count`（根评论与子回复同形、行随审核通过诞生、全量评论都有行）；根评论另有 `post_comment_feed_index` 快照只服务「热度」排序、不是真值源；动机注记写明「形状统一而非性能改造」。「评论总数」词条一字未动（`reply_count` 零变化）。
- `docs/api/community.md`：① hot 档不自见**写成「默认即不自见」**（票 01 的口径要点：不带 `sort` 时默认走 hot，作者发完刷新详情看不到自己待审的根评论，切 `sort=new` 才见），自见行只在 `sort=new` 档、`total` 与 hot 档同行数；② 声明对外契约不变（字段名 + `likeCount`/`total` 数值语义 + offset 分页，前端零感知）；③ 点赞接口与「链路」段的落点改准（窄表 + 根评论旁带快照，子回复只动窄表），并写明「展示值来自批量回查、顺序来自快照」两条取数通路；④ 审核通过段的计数动作补「同事务造窄表行 + 根评论索引行」。
- `docs/db/community_comment.md`（基本重写）：表清单补两新表；`post_comment` 去 `like_count` 行字段，按 `post` 的先例留「已删除的列」段（删列 + **存量点赞不搬、显示 0** + 割接脚本名与执行序 + 票 02 已停写停读）；新增 `post_comment_like_count`（形态 / 行诞生时机 / 谁写谁读 / 为什么无二级索引 / 0 行只 warn 不 ODKU）与 `post_comment_feed_index`（行集 = 可见根评论、软删物理删行、为什么不设 `state` 列、子回复不入、不是真值源、重建 job 兜底、热评档不自见）两节；`post_comment_like` 的「计数回写本表」旧口径改为窄表；新增「评论读路径」节（五条读路径 × 排序键 × 计数来源的表 + 最热档两级取数 + 批量回查 + 重建兜底）。票 04 交接的 L28/L50/L86 三处过期表述全部消化。
- `docs/db/community.md`：关联段补一条评论侧读数说明（评论 `likeCount` 来自窄表批量回查、hot 序来自热评索引、漂移由 `commentFeedIndexRebuildJob` 兜底，指向 `community_comment.md`）；`post_like_count` 的「评论赞走合并器」改为「走同一台合并器但落评论窄表」。
- `sql.md`：两新表节（DDL 与 `schema.sql` **逐字一致**，各带一段说明）、`post_comment` 去列 + 三行删列注记 + 割接脚本登记（照 `feed-index-uv` / `market-feed-uv` 的「存量库…用 backend/better-school/sql/backfill/xxx.sql」先例）、头部表数与 §1 收录数同步、主键口径例外段补 `post_comment_feed_index`。
- `docs/db/README.md`：表清单补登 12 张（`review_task` + feed/浏览族 9 张 + 本次 2 张）——原文写「29 张业务表 = 45」而 `schema.sql` 实为 40+16=56，缺口自 feed-index-uv / market-feed-uv 起就存在，本次一并补齐并与 `schema.sql` 对账到逐张一致；主键口径补「feed / 明细 / 计数 / 热评索引投影表不设 `id`」的例外指向。
- `schema.sql`：只改 `post_comment_feed_index` 两条 COMMENT（`post_id` 列注释与 `idx_post_like` 的 KEY 注释）。原注释写「单帖内索引序扫描」，而 `PostCommentMapper.xml:58-62` 明写 `(post_id, like_count, comment_id)` 前缀只用来收窄范围、`like_count DESC` 与 `comment_id ASC` 方向相反索引给不出混合序（EXPLAIN: covering index lookup + Sort）。不改会与 `sql.md` 同节新增的说明**自相矛盾**，故一并改准；无行为变化。

**顺带勘误（三处，均为既有错误，不改会与本次新写的口径打架）**

1. **「点赞一次即回正」被证伪**：票 03 已核过——点赞只 `UPDATE` 窄表、命中 0 行仅 warn，不 ODKU 造行，所以窄表缺行的根评论重灌为 0 后点赞补不回行（票面明写「建议票 05 顺带勘误」）。文档改为「点赞不会自愈；要回正得先手工补窄表行、再重跑重建」。
2. **营销表数**：`sql.md` 头部「20（营销）」与 §2「共 20 张表」、`backend/docs/db/README.md`「20 张」都是旧值，`backend/marketing/sql/schema.sql` 实为 18 张（§2 也只有 18 节）。改准为 18 后全库 = **40 + 16 + 18 = 74**；若只把 38 改 40 而沿用 20 会得出 76 的错误总数。同票修 `backend/docs/db/README.md` 的「20 张」以免留下新矛盾。
3. **`community.md` 的「近似值」**：`post_like_count` 的清单行与表头仍写「MQ 窗口聚合刷盘，近似值」，而同一节正文与 `CONTEXT`「点赞计数」都已是「精确值」（`CountMerger` 进程内合并、请求等落库）。改准为「计数真源，进程内合并同步落库 / 精确值」。`docs/db/user.md`、`docs/api/user.md` 里同源的「近似值、MQ 异步刷盘」仍在，属获赞聚合的另一处表述、不在本票三份文档内，登记待后续票。

**验收方式（可复跑）**

- `.scratch/comment-count-narrow-table-impl/check_ddl_drift.py`：从 `schema.sql` 与 `sql.md` 各抽 5 张评论表的列清单逐列比对 → `ALL IDENTICAL`；另用 `difflib` 对 8 张相关表（含 `post` / `post_like_count` / `post_feed_index`）的整段 DDL 做逐字 diff → 全部 `diff=0`（口径漂移的机器判据，不靠肉眼）。
- `docs/db/README.md` 表清单与 `schema.sql` 的 `CREATE TABLE` 名集做 `diff` → 一致（40 张）。
- 全仓 grep：`主表行` / `主列` / `就留在本行` / `不走窄表` 无残留（余下命中均为「原 `post_comment.like_count` 主列，票 04 已删」这类过去时注记、「`post_comment` 主行 `update_time`」这类仍在的事实，或 ADR-0013 讲 `view_count` 的无关上下文）；`索引序扫描` 只剩市集两域（那里确实是纯索引序）与已改准前的快照无关。
- Markdown 表格逐行校验（表头与数据行的 `|` 数一致）：六个文件全部通过。
- **未跑测试**：改动不含任何 Java/SQL 行为变更（`schema.sql` 只动两条 COMMENT），故未重跑 bs-app 全量套件；`reset-test-db` 亦不需要（注释不进行为）。

**code-review 双轴（子代理并行，逐条裁决）**

- standards 轴 5 条硬伤，全部采纳并修：① 表数算错（40+16+20=76）→ 核出营销实为 18 张，改 74 并同步 §2 与 `backend/docs/db/README.md`；②「该文件其余 6 张」与 `schema.sql` 无此 6 表矛盾 → 改为「以下 6 张不在本文档范围」的表述，去掉「其余」的歧义；③「索引序扫描」与 mapper 的 filesort 事实相反 → 见上（连 `schema.sql` 注释一并改准）；④ `docs/db/README.md` 主键口径无例外、与 `sql.md` 例外清单不同步 → README 补例外指向；⑤「点赞计数」把「行诞生于审核通过」摊到两张表 → 拆开写（帖子随首次点赞 upsert、评论随审核通过）。
- spec 轴 2 条，全部采纳：① 按票 01 的口径改成「默认请求即不自见」（原文写成「只在默认档可见」，而默认请求正是 hot，等于写反了）；② README 计数句漏记 `review_task` → 改成「漏登 12 张」并列全。
- spec 轴另 1 条判断项采纳：`community_comment.md` 里「点赞一次即回正」照抄 spec §8 的旧句，而票 03 已证伪并要求本票勘误 → 已改。
- 未采纳 3 条（并记理由）：① 「`community.md` 讲评论侧属 Feature Envy」——票面第 4 条点名要求 `community.md` 的读路径段换源说明，删掉即漏票面；② 「`post_comment_feed_index` 未进 README『无业务 ID 列的表』清单」——该清单列的是「有 `id` 但无业务列」的表，热评索引与 `post_feed_index` 一样是「不设 `id`、主键即业务键」的另一族，进错清单反而更乱（已在主键口径的例外里指向）；③ 「`api/community.md` 的『对外契约不变』是迁移叙事」——票面第 3 条明写要「声明对外字段与数值语义不变」，是本票的验收项本身。
- 采纳 judgement 项 1 条：「快照刷偏只动顺序不动数字」原在 5 处重复 → 删掉 `community.md`（纯指路）与 `sql.md`（DDL 汇总）两处，留在 `CONTEXT`（词条）、`api`（接口语义）、`community_comment`（表语义）三处各自独立成立的文档里。

