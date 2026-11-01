# 01 · 触点清单：like_count / reply_count 的全部读写点

Type: research
Status: resolved
Blocked by: —

## Question

`post_comment.like_count` 与 `reply_count` 在 `backend/better-school/` 里的**全部**读写点与消费方盘点，产出 `.scratch/comment-count-narrow-table/research/touchpoints.md`（file:line 级引用）。这是后面所有票的事实地基：读路径票、写路径票、迁移票都按它圈改动面。

要覆盖：

1. **读点**：所有 SELECT 里带 `like_count` / `reply_count` 的语句（PostCommentMapper.xml 已知五处，找全），标明每处的用途（列表展示 / 排序键 / 判断逻辑 / 聚合）与取数形态（单行 / 批量 / JOIN）。
2. **写点**：所有 UPDATE 这两列的语句（已知 `updateLikeCount`、审核通过与软删的 reply_count 维护、approveById），标明各自所在事务与调用链（合并器落库方法 / 仓储方法）。
3. **PO/VO/DTO 链路**：`PostCommentPO` → 域实体 → `ResponseDTO` 的字段搬运链，拆表后哪些类要改取数来源。
4. **测试面**：断言了这两个数的集成测试（已知 `CommentLikeIntegrationTest` 六条、审核/软删相关用例），逐个列出会受影响的断言。
5. **暗触点**：列表之外的消费方——通知、搜索 doc、作者主页聚合、缓存对象、`PostMapper.xml` 里对评论数的 JOIN——有没有读这两列的，有则单列一节。
6. **物理删除路径**：评论有没有物理 DELETE 语句（决定窄表行要不要跟随删）。

## Answer

已盘点完毕，逐条 grep 验证过行号：[research/touchpoints.md](../research/touchpoints.md)。要点：

- **读点 4 处**，全在 PostCommentMapper.xml：selectRootPageByPostId（:24，like_count 同时是 hot 排序键 :42 + reply_count）、selectRepliesByRootId（:62，只取 like_count）、selectPageByUser（:112，两列、create_time 排序）、selectById（:134，只取 like_count）。
- **写点 2 条 UPDATE / 4 个事务入口**：updateLikeCount（:170）走合并器落库 likeComments（CommunityRepository.java:513-521，COMMENT_LIKE）与同步取消赞 unlikeCommentWithCount（:723-731）；updateReplyCount（:103）走审核通过事务 approveCommentWithCount（:680-693）与软删事务 softDeleteCommentWithCount（:696-714）。
- **链路**：PostCommentPO:19-20 → toCommentEntity（CommunityRepository:823-824）→ PostCommentEntity:21/:23 → PostController 三个组装方法（:456-457/:472/:488）→ 3 个 ResponseDTO。DTO 字段名对外不变。
- **测试**：直接断言 4 类（CommentLikeIntegrationTest、LikeCountFlushHandlerIntegrationTest、CommentThreadingIntegrationTest、AuthorProfileIntegrationTest 的 fixture INSERT 显式带两列）；间接覆盖 2 类（CommentDeleteSemantics、CommentRootListV2）。
- **暗触点：0 个真实消费方**——通知、搜索 doc、作者聚合（UserMapper 获赞只 SUM post_like_count）、缓存都不读这两列；PostMapper.xml 的全部 like_count 是帖子侧三样东西（post_like_count 窄表 / post_feed_index 快照 / post_comment_count JOIN），不是本票对象。
- **物理删除：没有**（唯一 DELETE 是 deleteCommentLike 删关系行；评论本体统一软删行保留）→ 窄表行无需跟随删。
- 计数写会刷 `update_time = NOW()`（两条 UPDATE 都带），与 map 事实基线「update_time 被污染」一致。
