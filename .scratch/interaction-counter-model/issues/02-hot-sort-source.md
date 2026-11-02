# 02 · 「最热」排序读哪个源

Type: grilling
Status: open
Blocked by: 01

## Question

评论点赞数一旦从「精确同事务」变成「异步近似」，`ORDER BY like_count DESC, id ASC` 读到的值会滞后（1 秒级），翻页时同分次序可能抖动——第 1 页看到的第 8 名，第 2 页可能重复出现。

要定：排序读 DB 窄表（允许秒级抖动）还是读 Redis（`ZINCRBY comment_hot:{postId}` 的 ZSET，排序源与真实增量同源、零滞后），以及分页要不要改成游标式（`(like_count, id)` 复合游标）而不是 offset。

## Answer

（待定）
