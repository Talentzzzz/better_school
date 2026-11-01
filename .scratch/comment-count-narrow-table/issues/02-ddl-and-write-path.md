# 02 · 窄表 DDL 与写路径定稿

Type: grilling
Status: resolved
Blocked by: 01

## Question

把已定形态（一张两列表、每条评论一行、审核通过事务诞生）落成 DDL 与写路径 spec 段，收掉剩下的自由度：

1. **表名与既有 `post_comment_count`（帖子评论总数窄表）的区分**：候选如 `post_comment_stat` / `post_comment_counter` / `post_comment_count_detail`——命名要让「帖子评论总数」与「评论行自身计数」在 schema 里一眼分得清。
2. **主键口径**：照市集六表先例「投影/计数表主键直接用业务键」（PK = comment_id，不设独立 id 列）？还是沿用 post_like_count 的 id + uk 先例？两派先例都在库里，要选一个并给理由。
3. **索引**：除主键外要不要别的索引（hot 排序走回查后，窄表上的 like_count 索引有没有服务对象——楼内 hot 是单帖内集合，回查是按 comment_id 点查，怀疑没有）。
4. **回填行集**：存量行集 = 全部历史评论（含 PENDING_REVIEW / REJECTED / DELETED）还是仅 NORMAL？行诞生口径是审核通过，被驳回/软删行要不要补行（行常驻快照 vs 只为可见行建行）。
5. **写路径 SQL 形状**：① 审核通过事务在 approveById 旁带窄表 INSERT（新行诞生）；② `updateLikeCount` 改 `UPDATE <窄表> SET like_count = GREATEST(0, like_count + ?)`——0 行命中（行缺失）落 warn 交对账还是 ODKU 兜底；③ 软删的 reply_count 扣减、以及「楼内可见子回复数」的计数口径（按可见子回复数刷成绝对值还是 ±N）改落窄表的语句形状；④ `GREATEST(0, ...)` clamp 口径逐条过。
6. **reply_count 的写形状**：现在是「软删按可见子回复数扣减」（绝对值搬运），窄表化后保持同形还是趁机统一成 ±N？给结论。

## Answer

> **2026-10-27 票 03 grilling 二次修订**：用户拍板热评排序照帖子 feed 形态做，新增 `post_comment_feed_index`（根评论排序投影，行集/同步点/重建 job 见票 03 Answer）。本票 Answer 里的计数窄表（`post_comment_like_count`）结论全部仍然成立，点赞写路径在其上追加索引快照旁带同步。

2026-10-27 grilling 收敛。**形态修订（用户拍板）：不搬两列，只抽数 like_count**——`reply_count` 留在 `post_comment` 主行，读写路径零改动；本票原来 six 问中关于 reply_count 写形状（±N vs 绝对值）、软删扣减落点的子问题全部消失（且票面「软删按可见子回复数扣减」的原描述本就不准确：reply_count 全库只有 ±1 两个写点，绝对值搬运的是 post_comment_count 那张表，形态不动）。修订连带 map 的「一张两列表」定稿作废，已同步改写。

1. **表名**：`post_comment_like_count`（用户拍板）。单列后落回库内 `_count` 语法族（实体_指标_count）：主表全名前缀 + 指标，与 `post_comment_count`（帖子的评论总数）/ `post_like_count`（帖子的点赞总数）靠指标词区分。
2. **主键口径**：`id BIGINT AUTO_INCREMENT` PK + `UNIQUE KEY uk_comment (comment_id)`（用户拍板，照 post_like_count / post_comment_count 旧先例）。
3. **索引**：只留 comment_id 唯一键，**不建 like_count 索引**。理由（已向用户讲清）：热评排序要的是「本帖评论按赞排」（WHERE post_id 圈定后集合内排序），like_count 索引给的是「全站评论按赞排」，从中挑不出本帖的——排序实际走 comment_id 点查 + 取出后排，与今天主表上同形（今天主表 like_count 上也没有索引）；索引无服务对象不建，将来做「全站热评 TopN」再加不迟。
4. **回填行集**：**不管存量**（用户拍板）——无回填脚本、无停写窗口；切码后存量评论点赞显示 0，旧列删除时历史数据丢弃（后果已向用户确认接受）。运行期不变式不受影响：行诞生于审核通过事务，存量 NORMAL 行缺窄表行时点赞 UPDATE 0 命中落 warn。
5. **写路径 SQL 形状**：
   - ① **行诞生**：`approveCommentWithCount` 事务内 `INSERT IGNORE INTO post_comment_like_count (comment_id, like_count, create_time, update_time) VALUES (#{commentId}, 0, NOW(), NOW())`，affected=0 落 warn（防御性——裸 INSERT 撞漂移重复键会让整个审核事务失败）。根评论与子回复一视同仁（楼内列表也要展示子回复赞数）。
   - ② **点赞 UPDATE**：`updateLikeCount` 改 `UPDATE post_comment_like_count SET like_count = GREATEST(0, like_count + #{delta}), update_time = NOW() WHERE comment_id = #{commentId}`；**0 行命中落 warn 交对账，不 ODKU 兜底**（ODKU 会把不变式从「行诞生于审核通过」改成「行诞生于第一次点赞」；评论量级无重建 job 必要，对账=手工 SQL）。合并器落库 `likeComments` 与同步取消赞 `unlikeCommentWithCount` 两个入口都检查 affected rows。**主行 update_time 不再被计数写刷**（顺带修好「技术时间戳被污染」，主行无排序键依赖它）。
   - ③ 软删 reply_count 扣减：**零改动**（reply_count 留主行）。
   - ④ **clamp**：`GREATEST(0, ...)` 保留（与现状语义一致）；行诞生 INSERT 天然 0 无需 clamp。
6. **reply_count 写形状**：保持现状 ±N 同形、只字不动（随形态修订，本子问题收束为「不搬」）。
