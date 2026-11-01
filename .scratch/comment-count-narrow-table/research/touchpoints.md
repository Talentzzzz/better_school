# 触点清单：post_comment.like_count / reply_count 的全部读写点与消费方

查证日期：2026-10-27。全部 file:line 经 grep 逐条验证（路径省略前缀 `backend/better-school/`）。

**三个易混对象先划清**：

| 对象 | 物理位置 | 本票对象？ |
| --- | --- | --- |
| 评论行自身计数 | `post_comment.like_count` / `reply_count` 两列 | **是** |
| 帖子评论总数窄表 | `post_comment_count`（post_id 唯一，comment_count） | 否（形态不动，Out of scope） |
| 帖子点赞窄表 | `post_like_count`（post_id 唯一，like_count），及 `post_feed_index.like_count` 快照 | 否（已是同形窄表，只作先例参照） |

## ① 读点（SELECT 里取这两列）

全部在 `bs-app/src/main/resources/mybatis/mapper/PostCommentMapper.xml`，共 4 条语句（`baseColumns` :5-8 含两列，但实际语句都是显式列清单，不走 baseColumns）：

| # | 语句 | 行 | 用途 | 取这两列 | 排序键 | 取数形态 |
| --- | --- | --- | --- | --- | --- | --- |
| R1 | `selectRootPageByPostId` | :24-49 | 根评论分页列表 | like_count + reply_count（:26） | hot：`ORDER BY c.like_count DESC, c.id`（:42）；默认 `ORDER BY c.id`（:45） | 批量分页；LEFT JOIN bs_user、content_review |
| R2 | `selectRepliesByRootId` | :62-82 | 楼内子回复平铺列表 | 只取 like_count（:64，**不取 reply_count**） | `ORDER BY c.id`（:80） | 批量分页；JOIN bs_user ×2、post_comment parent、content_review |
| R3 | `selectPageByUser` | :112-123 | 作者主页「评论」tab | like_count + reply_count（:114） | `ORDER BY c.create_time DESC, c.id DESC`（:121） | 批量分页；JOIN post 取来源帖标题/封面 |
| R4 | `selectById` | :134-141 | 单行定位（审核/删除/点赞前置检查） | 只取 like_count（:136，**不取 reply_count**） | 无（单行，按 uk_comment_id） | 单行；LEFT JOIN bs_user |

排序键结论（与 map.md 事实基线一致）：like_count 作排序键仅 R1 的 hot 一处；reply_count 从不作排序键；两处时间排序（R1 默认 id、R3 create_time）不回查。

## ② 写点（UPDATE 这两列）

两条 UPDATE 语句、四个事务入口：

**W1. `updateLikeCount`**（PostCommentMapper.xml:170-175，`SET like_count = GREATEST(0, like_count + ?), update_time = NOW()`）

1. **点赞合并落库**（L2W256）：`likeCommentWithMergedCount`（CommunityRepository.java:717-720）→ `countMergeWriter.write(CountAction.COMMENT_LIKE, ...)`（:719）→ `CountFlushDispatcher.java:57` 分派 → `CommunityRepository.java:437` `case COMMENT_LIKE -> likeComments(...)` → `likeComments`（:513-521）：`insertCommentLikes`（INSERT IGNORE，mapper :146-152）影响行数 N → `commentDao.updateLikeCount(commentId, inserted)`（:518）。一组一个事务，事务由落库方法内 `transactionTemplate` 开（:437）。上游调用链：`CommentServiceImpl.likeComment`（CommentServiceImpl.java:185）← `CommentLikeCase.java:31` ← `PostController` 的 like 端点。
2. **取消赞（同步，不走合并器）**：`unlikeCommentWithCount`（CommunityRepository.java:723-731）：同事务内 `deleteCommentLike`（:725，mapper :163-167）+ `updateLikeCount(commentId, -1)`（:728）。上游：`CommentServiceImpl.unlikeComment`（:195）。

**W2. `updateReplyCount`**（PostCommentMapper.xml:103-108，`SET reply_count = GREATEST(0, reply_count + ?), update_time = NOW()`；只按 rootId 命中楼根）

1. **审核通过事务**：`approveCommentWithCount`（CommunityRepository.java:680-693）——`approveById`（条件 UPDATE，:683 / mapper :195-201）+ `updatePostCommentCount(postId, +1)`（:686，mapper :186-192 语句 id `upsertPostCommentCount`，帖子评论总数窄表）+ 子回复时 `updateReplyCount(rootId, +1)`（:687-690）。上游：`CommentServiceImpl.approveComment`（CommentServiceImpl.java:140）。
2. **软删事务**：`softDeleteCommentWithCount`（CommunityRepository.java:696-714）——子回复分支 `updateReplyCount(rootId, -1)`（:701）+ `upsertPostCommentCount(postId, -1)`（:700）；根评论分支只扣帖子评论总数（按 `countRepliesByRootId` :703 算整楼量，:704-705），**不动 reply_count**（楼消失）。上游：`CommentServiceImpl.softDelete`（:176）← `markDeleted`（:167）。

**行诞生点（新窄表行的插入时机参照）**：评论行本身诞生于 `saveComment`（CommunityRepository.java:564-570，`insert` mapper :10-14 + `bindBizId` :17-19），insert 不写两列（DB 默认 0）；按 map 定稿，窄表行应改诞生于 `approveCommentWithCount` 事务（②-W2-1）。

**同事务伴随写但不直接碰这两列的语句**：`approveById`（:195-201）、`rejectById`（:204-210）、`markDeletedFromStatus`（:214-220）——只翻 state，计数增减由调用方在同一事务补发。

**接口层签名**：`IPostCommentDao.updateReplyCount`（IPostCommentDao.java:54）、`updateLikeCount`（:70）；`ICommunityRepository.updateReplyCount`（ICommunityRepository.java:194）、`updateLikeCount`（:200）。

## ③ PO / 域实体 / DTO 字段搬运链

```
PostCommentPO.likeCount  :19 / replyCount :20     （bs-infrastructure/.../infrastructure/dao/comment/po/PostCommentPO.java）
  ↓ CommunityRepository.toCommentEntity（:811-833）：setLikeCount :823、setReplyCount :824
PostCommentEntity.likeCount :21 / replyCount :23  （bs-domain/.../community/model/entity/PostCommentEntity.java）
  ↓ PostController（bs-trigger/.../trigger/http/PostController.java）三个组装方法：
  · toCommentResponseDTO（:449-462）：setLikeCount :456、setReplyCount :457   → 根评论列表
  · toReplyResponseDTO（:480-495）：setLikeCount :488（无 replyCount）        → 楼内列表
  · toUserCommentDTO（:468-478）：setLikeCount :472（无 replyCount）          → 作者主页评论 tab
DTO（bs-api/.../api/dto/）：
  · PostCommentResponseDTO.likeCount :16 / replyCount :18
  · CommentReplyResponseDTO.likeCount :20
  · UserCommentResponseDTO.likeCount :15
```

拆表后要改取数来源的类：`PostCommentPO`（去两列或改窄表 JOIN 填充）、`CommunityRepository.toCommentEntity` 与四个读方法（R1-R4 的 SQL）、`PostController` 组装方法若 entity 字段保留则零改动。域接口注释需同步：`ICommunityRepository.java:246`（「返回时 post_comment.like_count 已精确（最热排序直接读它）」）、`CommentServiceImpl.java:184`、`CommunityRepository.java:512/:718`、`CountAction.java:17`（「主表行 like_count（没有窄表）」）、`IPostCommentDao.java:22/:59`。

## ④ 测试面（直接断言这两个数的用例）

**CommentLikeIntegrationTest**（bs-app/src/test/java/com/betterschool/integration/community/CommentLikeIntegrationTest.java）
- 点赞幂等（@Test :21-22）：:32 赞后 like_count=1、:39 取消后 0、:45 再赞 1；辅助 `commentLikeCountOf`（:216-220）**直查 `post_comment.like_count`**（:218）——拆表后这条 SQL 必改。
- 重复点赞不重复计（:129-130）：:137-141 关系行数与 like_count 双断言。
- hotSortByRealLikeCounts（:149-150）：:167 hot 序 containsExactly（like_count 排序行为）、:173-175 列表项 likeCount=2/1/0。
- repliesAreLikable_withLikedStateInList（:183-184）：:201 直查计数、:203 楼内列表项 likeCount。

**LikeCountFlushHandlerIntegrationTest**（同目录）
- 评论点赞落库方法用例（@Test :72-73）：fixture INSERT 显式带 `like_count, reply_count` 列（:77-79）；:84/:89 断言主表 like_count=2；`commentLikeCount()`（:117-120）直查 `post_comment.like_count`（:119）。

**CommentThreadingIntegrationTest**（同目录）
- :60 首楼 replyCount=1；:139 replyCount=13（与楼内 total 一致）；:160→:167 待审子回复通过后 replyCount 0→1；:210 删子回复后 replyCount 回落；辅助 `replyCountOnRootList`（:268-273）走 API 的 replyCount 字段。

**AuthorProfileIntegrationTest**（bs-app/src/test/java/com/betterschool/integration/user/）
- fixture `insertComment`（:260-266）：INSERT INTO post_comment 显式列含 `like_count, reply_count`（:262）——**删列后此 fixture 直接报 Unknown column**，必改。

**间接受影响（不断言两列但走同一事务/排序路径）**：
- CommentDeleteSemanticsIntegrationTest（同目录）：软删事务行为（:24-49 整楼、:52-72 待审、:73-92 子回复 reply_count -1 语义经 `replies.total` 间接覆盖）。
- CommentRootListV2IntegrationTest（同目录）：:15-17 注释声明 hot 退化行为，:26 起用例覆盖 `ORDER BY c.like_count` / `ORDER BY c.id` 两条排序路径（无赞退化）。

## ⑤ 暗触点（评论列表之外的消费方）

逐一排查，**无真实消费方**，只有注释与同名混淆点：

- **PostMapper.xml 全部 like_count 都是帖子侧，不碰 post_comment**：:16 `COALESCE(plc.like_count,0)` 与 :190 hot 排序是 `post_like_count` 窄表（帖子点赞）；:354/:362/:489 是帖子计数 UPDATE；:436-456/:506-515 是 `post_feed_index.like_count` 快照。:37 `LEFT JOIN post_comment_count pcc` 是帖子评论总数窄表——三者均非本票对象。
- **作者聚合**：UserMapper.xml:88-95 `sumNormalPostLikeCount` 只 SUM `post_like_count`，注释明示「不含评论获赞」——作者主页不消费评论 like_count。
- **通知**：CommentLikeCase.java:31 发 COMMENT_LIKED 通知只用 `comment.getUserId()`，不读计数。
- **搜索 doc**：PostSearchDocVO.java:8 注释明示仅帖子文档、展示字段由 MySQL 反查组装；不存在评论搜索 doc。
- **缓存**：评论列表无缓存对象；PostCountMergeIntegrationTest.java:107-116 的 likeCount 是帖子详情缓存（post 侧）。
- **合并器元数据**：CountAction.java:17-18（COMMENT_LIKE 注释「没有窄表」）、CountFlushDispatcher.java:57、CountMergeWriter.java:59——行为不用改，注释要改。

## ⑥ 物理删除路径

**没有**。PostCommentMapper.xml 全文唯一的 DELETE 是 `deleteCommentLike`（:163-167，删 post_comment_like 关系行）；评论本体统一软删 `markDeletedFromStatus`（:214-220），注释明言「物理 DELETE 已废弃、行保留」。→ 窄表行无需跟随删逻辑；软删后评论行仍在（state=DELETED），窄表行同理保留即可。

## ⑦ 改动面小结

- **读**：4 处（R1-R4，全在 PostCommentMapper.xml），其中仅 R1 hot 排序是真正的「回查」场景（map 已定：时间排序不回查、热度回查）；R2/R4 本就不取全两列。
- **写**：2 条 UPDATE 语句（updateLikeCount :170、updateReplyCount :103）、4 个事务/落库入口（likeComments :513、unlikeCommentWithCount :723、approveCommentWithCount :680、softDeleteCommentWithCount :696）+ 窄表行诞生点（approveCommentWithCount 事务内）。
- **链路类**：PO 1、entity 1、DTO 3、转换/组装方法 4 处、接口注释约 6 处。
- **测试**：直接断言 4 个测试类（CommentLikeIntegrationTest、LikeCountFlushHandlerIntegrationTest、CommentThreadingIntegrationTest、AuthorProfileIntegrationTest 的 fixture）；间接受覆盖 2 个（CommentDeleteSemanticsIntegrationTest、CommentRootListV2IntegrationTest）。
- **暗触点**：0 个真实消费方（通知/搜索/作者聚合/缓存均不读这两列）；2 处易混淆的同名窄表（post_comment_count、post_like_count / post_feed_index.like_count）要在词条里划清。
- **物理删除**：无，窄表行生命周期 = 评论行生命周期。
