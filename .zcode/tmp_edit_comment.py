def edit(p, pairs):
    s = open(p, encoding='utf-8').read()
    for old, new in pairs:
        assert old in s, (p, old[:80])
        s = s.replace(old, new)
    open(p, 'w', encoding='utf-8', newline='').write(s)
    print("ok", p)

# ---- PostServiceImpl unused import ----
edit('bs-domain/src/main/java/com/betterschool/domain/community/service/post/PostServiceImpl.java', [
("""import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;""",
"""import org.springframework.stereotype.Service;

import java.util.HashMap;"""),
])

# ---- ICommunityCommentService ----
edit('bs-domain/src/main/java/com/betterschool/domain/community/service/comment/ICommunityCommentService.java', [
("     * 或 new（create_time 升序 + 业务 ID 升序），缺省 hot。viewerId 非空时附带其本人 PENDING_REVIEW 评论（无标识展示）。",
 "     * 或 new（create_time 升序 + 业务 ID 升序），缺省 hot。"),
("""     * 发布根评论（ADR-0002 零同步审核）：落库即 PENDING_REVIEW，同事务写 outbox 审核消息；
""",
 """     * 发布根评论：落库即可见，计数与索引同事务推进；
"""),
("""    /** 审核通过：PENDING_REVIEW → NORMAL + 评论数 +1（同事务）；返回是否本次刚通过（已通过返回 false，供互动通知只在首次触发） */
    boolean approveComment(String commentId);

    /** 审核驳回：PENDING_REVIEW → REJECTED（终态，待审从未计数） */
    void rejectComment(String commentId);
""",
 ""),
])

# ---- CommentServiceImpl ----
edit('bs-domain/src/main/java/com/betterschool/domain/community/service/comment/CommentServiceImpl.java', [
("""        getNormalPost(postId);
        PostCommentEntity commentEntity = PostCommentEntity.create(postId, userId, content, PostStatusVO.PENDING_REVIEW);
        publishForAsyncReview(commentEntity);""",
"""        getNormalPost(postId);
        PostCommentEntity commentEntity = PostCommentEntity.create(postId, userId, content, PostStatusVO.NORMAL);
        publishVisible(commentEntity);"""),
("""        // 允许回复待审中的评论（其作者本人可见，指向昵称也需可读）；驳回/下架评论对所有人不可见，视同不存在
        if (targetEntity == null || targetEntity.getStatus() == PostStatusVO.REJECTED || targetEntity.getStatus() == PostStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "评论不存在");
        }""",
"""        // 下架评论对所有人不可见，视同不存在
        if (targetEntity == null || targetEntity.getStatus() == PostStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "评论不存在");
        }"""),
("""        PostCommentEntity replyEntity = PostCommentEntity.createReply(targetEntity.getPostId(), rootId,
                targetEntity.getCommentId(), userId, content, PostStatusVO.PENDING_REVIEW);
        publishForAsyncReview(replyEntity);""",
"""        PostCommentEntity replyEntity = PostCommentEntity.createReply(targetEntity.getPostId(), rootId,
                targetEntity.getCommentId(), userId, content, PostStatusVO.NORMAL);
        publishVisible(replyEntity);"""),
("""    /** 零同步审核（ADR-0002）：落库即待审，同事务（仓储内）写 outbox 审核消息；计数在审核通过时 +1 */
    private void publishForAsyncReview(PostCommentEntity commentEntity) {
        communityRepository.saveCommentWithReviewMessage(commentEntity);
    }
""",
"""    /** 落库即可见：评论行 + 评论数 +1 + 楼根 reply_count +1 + 计数窄表行 + 热评索引行（根评论）同一事务 */
    private void publishVisible(PostCommentEntity commentEntity) {
        communityRepository.saveVisibleCommentWithCount(commentEntity);
    }
"""),
("""    @Override
    public boolean approveComment(String commentId) {
        PostCommentEntity commentEntity = communityRepository.findCommentById(commentId);
        if (commentEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "评论不存在");
        }
        if (commentEntity.getStatus() == PostStatusVO.NORMAL) {
            return false; // 幂等：已通过，非本次
        }
        if (commentEntity.getStatus() != PostStatusVO.PENDING_REVIEW) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "评论已被处理，无法通过");
        }
        // 状态翻转 + 评论数 +1 + 楼根 reply_count +1 在仓储内同一事务；
        // 0 行（并发双审）由仓储抛 BUSINESS_ERROR，故走到这里即本次刚通过
        communityRepository.approveCommentWithCount(commentId, commentEntity.getPostId(), commentEntity.getRootId());
        return true;
    }

    @Override
    public void rejectComment(String commentId) {
        PostCommentEntity commentEntity = communityRepository.findCommentById(commentId);
        if (commentEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "评论不存在");
        }
        if (commentEntity.getStatus() == PostStatusVO.REJECTED) {
            return; // 幂等：已驳回
        }
        if (commentEntity.getStatus() != PostStatusVO.PENDING_REVIEW) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "评论已被处理，无法驳回");
        }
        if (!communityRepository.rejectCommentById(commentId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "审核状态已变化，请刷新重试");
        }
    }

    @Override
    public void markDeleted(String commentId) {""",
"""    @Override
    public void markDeleted(String commentId) {"""),
("        // 仅对可见评论有效：待审/驳回/下架一律拒绝（作者本人的待审评论也不可赞）",
 "        // 仅对可见评论有效：下架一律拒绝"),
("""     * 统一软删（用户删除与举报下架同轨，物理 DELETE 废弃）：状态转 DELETED、行保留。
     * 扣减与否由条件 UPDATE 实际命中的前置状态决定（读到的旧态仅用于定位，防审核并发漂移）；
     * 状态翻转与计数增减在仓储内同一事务""",
"""     * 统一软删（用户删除与举报下架同轨，物理 DELETE 废弃）：状态转 DELETED、行保留。
     * 扣减与否由条件 UPDATE 实际命中的前置状态决定（读到的旧态仅用于定位，防并发漂移）；
     * 状态翻转与计数增减在仓储内同一事务"""),
])
