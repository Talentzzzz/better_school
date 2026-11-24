package com.betterschool.domain.post.service.comment;

import com.betterschool.domain.post.adapter.port.IUserQueryPort;
import com.betterschool.domain.post.adapter.repository.IPostRepository;
import com.betterschool.domain.post.model.entity.PostCommentEntity;
import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.model.valobj.PostUserVO;
import com.betterschool.domain.post.model.valobj.LikeStateVO;
import com.betterschool.domain.post.model.valobj.PostStatusVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements ICommentService {

    private final IPostRepository postRepository;
    private final IUserQueryPort userQueryPort;

    @Override
    public List<PostCommentEntity> listComments(String postId, String viewerId, String sort, int page, int pageSize) {
        String sortType = "new".equals(sort) ? "new" : "hot";
        return postRepository.pageRootByPostId(postId, viewerId, sortType, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countVisibleRootComments(String postId) {
        return postRepository.countVisibleRootsByPostId(postId);
    }

    @Override
    public List<PostCommentEntity> listCommentsByUser(String userId, String viewerId, int page, int pageSize) {
        if (!isCommentsVisibleTo(userId, viewerId)) {
            return List.of();
        }
        return postRepository.pageCommentsByUser(userId, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countCommentsByUser(String userId, String viewerId) {
        return isCommentsVisibleTo(userId, viewerId) ? postRepository.countCommentsByUser(userId) : 0;
    }

    @Override
    public PostCommentEntity addComment(String postId, String userId, String content) {
        getNormalPost(postId);
        PostCommentEntity commentEntity = PostCommentEntity.create(postId, userId, content, PostStatusVO.NORMAL);
        publishVisible(commentEntity);
        commentEntity.setAuthorNickname(nicknameOf(userId));
        commentEntity.setReplyCount(0L);
        return commentEntity;
    }

    @Override
    public PostCommentEntity addReply(String targetCommentId, String userId, String content) {
        PostCommentEntity targetEntity = postRepository.findCommentById(targetCommentId);
        // 下架评论对所有人不可见，视同不存在
        if (targetEntity == null || targetEntity.getStatus() == PostStatusVO.DELETED) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "评论不存在");
        }
        // 树模型（ADR-0001）：回复根评论 root=parent=根评论 id；回复子回复 root=楼根、parent=被回复子回复。
        // 归属一律由服务端推导，客户端无从伪造
        String rootId = targetEntity.isRoot() ? targetEntity.getCommentId() : targetEntity.getRootId();
        PostCommentEntity replyEntity = PostCommentEntity.createReply(targetEntity.getPostId(), rootId,
                targetEntity.getCommentId(), userId, content, PostStatusVO.NORMAL);
        publishVisible(replyEntity);
        replyEntity.setAuthorNickname(nicknameOf(userId));
        replyEntity.setReplyToUserId(targetEntity.getUserId());
        replyEntity.setReplyToNickname(targetEntity.getAuthorNickname());
        return replyEntity;
    }

    /** 落库即可见：评论行 + 评论数 +1 + 楼根 reply_count +1 + 计数窄表行 + 热评索引行（根评论）同一事务 */
    private void publishVisible(PostCommentEntity commentEntity) {
        postRepository.saveVisibleCommentWithCount(commentEntity);
    }

    @Override
    public PostCommentEntity getComment(String commentId) {
        return postRepository.findCommentById(commentId);
    }

    @Override
    public List<PostCommentEntity> listReplies(String rootId, String viewerId, int offset, int limit) {
        if (!threadVisible(rootId)) {
            return List.of();
        }
        return postRepository.pageRepliesByRootId(rootId, viewerId, offset, limit);
    }

    @Override
    public long countReplies(String rootId, String viewerId) {
        if (!threadVisible(rootId)) {
            return 0;
        }
        return postRepository.countRepliesForThread(rootId, viewerId);
    }

    /** 删根整楼消失（票 07）：楼根被软删后，楼内子回复随楼一起不可达；其余状态维持原行为 */
    private boolean threadVisible(String rootId) {
        PostCommentEntity rootEntity = postRepository.findCommentById(rootId);
        return rootEntity != null && rootEntity.getStatus() != PostStatusVO.DELETED;
    }

    @Override
    public void deleteComment(String commentId, String currentUserId) {
        PostCommentEntity commentEntity = postRepository.findCommentById(commentId);
        if (commentEntity == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "评论不存在");
        }
        boolean isCommentOwner = commentEntity.getUserId().equals(currentUserId);
        PostEntity postEntity = postRepository.findPostById(commentEntity.getPostId());
        boolean isPostOwner = postEntity != null && postEntity.isOwner(currentUserId);
        if (!isCommentOwner && !isPostOwner) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "只能删除自己的评论或自己帖子下的评论");
        }
        softDelete(commentEntity);
    }

    @Override
    public void markDeleted(String commentId) {
        PostCommentEntity commentEntity = postRepository.findCommentById(commentId);
        if (commentEntity == null) {
            return; // 幂等：不存在
        }
        softDelete(commentEntity);
    }

    @Override
    public void rebuildCommentFeedIndex() {
        postRepository.rebuildCommentFeedIndex();
    }

    /**
     * 统一软删（用户删除与举报下架同轨，物理 DELETE 废弃）：状态转 DELETED、行保留。
     * 扣减与否由条件 UPDATE 实际命中的前置状态决定（读到的旧态仅用于定位，防并发漂移）；
     * 状态翻转与计数增减在仓储内同一事务
     */
    private void softDelete(PostCommentEntity commentEntity) {
        postRepository.softDeleteCommentWithCount(
                commentEntity.getCommentId(), commentEntity.getPostId(), commentEntity.getRootId());
    }

    @Override
    public PostCommentEntity likeComment(String commentId, String userId) {
        // 仅对可见评论有效：下架一律拒绝
        PostCommentEntity commentEntity = getNormalComment(commentId);
        // 合并写：评论点赞计数落窄表 post_comment_like_count（真源），不是主表列；
        // 返回的是点赞前读到的评论快照（前置检查用），计数由合并器在同一事务推进。
        // 最热档的排序键是根评论索引的 like_count 快照（同一事务旁带推进），同样不是这次返回的实体字段
        LikeStateVO state = postRepository.likeCommentWithMergedCount(commentId, userId);
        if (state == LikeStateVO.DUPLICATE) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "请勿重复点赞");
        }
        return commentEntity;
    }

    @Override
    public void unlikeComment(String commentId, String userId) {
        getNormalComment(commentId);
        postRepository.unlikeCommentWithCount(commentId, userId);
    }

    @Override
    public List<String> listLikedCommentIds(String userId, List<String> commentIds) {
        return postRepository.selectLikedCommentIds(userId, commentIds);
    }

    private PostCommentEntity getNormalComment(String commentId) {
        PostCommentEntity commentEntity = postRepository.findCommentById(commentId);
        if (commentEntity == null || commentEntity.getStatus() != PostStatusVO.NORMAL) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "评论不存在");
        }
        return commentEntity;
    }

    /** 发布响应 DTO 需作者昵称：写入路径无 JOIN，按 id 读用户补齐（只读跨域 port） */
    private String nicknameOf(String userId) {
        PostUserVO user = userQueryPort.findById(userId);
        return user == null ? null : user.getNickname();
    }

    private PostEntity getNormalPost(String postId) {
        PostEntity postEntity = postRepository.findPostById(postId);
        if (postEntity == null || postEntity.getStatus() != PostStatusVO.NORMAL) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "帖子不存在");
        }
        return postEntity;
    }

    /**
     * 隐私开关「公开我的评论」对他视是否可见：开关关闭且 viewer ≠ owner 时为 false；
     * viewer = owner（本人看自己）恒 true；profile 读不到（用户不存在）按可见处理（列表本就为空）。
     * 开关原始值由 user 只读 port 取回，判据留在本域
     */
    private boolean isCommentsVisibleTo(String ownerId, String viewerId) {
        if (viewerId != null && viewerId.equals(ownerId)) {
            return true;
        }
        PostUserVO owner = userQueryPort.findById(ownerId);
        return owner == null || !Boolean.FALSE.equals(owner.getShowComments());
    }
}
