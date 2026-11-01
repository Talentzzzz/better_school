package com.betterschool.domain.community.service.comment;

import com.betterschool.domain.community.model.entity.PostCommentEntity;

import java.util.List;

/**
 * 社区评论领域服务：发布/列表/删除/审核状态流转
 */
public interface ICommunityCommentService {

    /**
     * 根评论列表（root_id='0'）：sort=hot（like_count 降序，同数按 create_time 升序 + 业务 ID 升序收敛成确定序）
     * 或 new（create_time 升序 + 业务 ID 升序），缺省 hot。viewerId 非空时附带其本人 PENDING_REVIEW 评论（无标识展示）。
     */
    List<PostCommentEntity> listComments(String postId, String viewerId, String sort, int page, int pageSize);

    /** 根评论真实总数：他人可见口径（仅 NORMAL 根评论，不含审核中） */
    long countVisibleRootComments(String postId);

    /**
     * 发布根评论（ADR-0002 零同步审核）：落库即 PENDING_REVIEW，同事务写 outbox 审核消息；
     * 不计数，审核通过时（消费者/人工）再 +1。返回完整实体（含 commentId、作者昵称）供响应 DTO。
     */
    PostCommentEntity addComment(String postId, String userId, String content);

    /**
     * 回复评论（楼中楼，树模型语义见 ADR-0001）：root/parent 由被回复评论推导，不接受客户端传入。
     * 发布语义与根评论一致（落库即待审 + 同事务 outbox，不计数）。返回完整实体供响应 DTO。
     */
    PostCommentEntity addReply(String targetCommentId, String userId, String content);

    /** 读单条评论（无副作用；审核消费者/通知场景用，驳回/下架评论不可从列表读出但按 id 仍可读） */
    PostCommentEntity getComment(String commentId);

    /** 楼内子回复平铺分页（create_time 升序 + 业务 ID 升序）：NORMAL 公开 + viewerId 本人待审/未通过评论可见（不对外展示） */
    List<PostCommentEntity> listReplies(String rootId, String viewerId, int offset, int limit);

    /** 楼内可见子回复总数（viewerId 非空时含其本人待审/未通过评论，与分页口径一致） */
    long countReplies(String rootId, String viewerId);

    /** 按用户列评论（作者主页/「我的」页「评论」tab）：该用户 NORMAL 评论且源帖 NORMAL，按评论时间倒序 */
    List<PostCommentEntity> listCommentsByUser(String userId, String viewerId, int page, int pageSize);

    /** 按用户列评论总数（隐私开关 showComments 关闭且 viewer≠owner 时为 0） */
    long countCommentsByUser(String userId, String viewerId);

    /** 评论作者本人或帖子作者可删 */
    void deleteComment(String commentId, String currentUserId);

    /**
     * 点赞评论（根评论与子回复通用）：仅 NORMAL 可见评论可赞（待审/驳回/下架一律拒绝），
     * 点赞记录（唯一键幂等）与 like_count +1 同事务。返回被赞评论实体供编排层发通知（含作者 id）。
     */
    PostCommentEntity likeComment(String commentId, String userId);

    /** 取消点赞：点赞记录删除与 like_count -1 同事务；未赞过报错 */
    void unlikeComment(String commentId, String userId);

    /** 当前用户在给定评论集合中已点赞的评论 id（未登录返回空集） */
    List<String> listLikedCommentIds(String userId, List<String> commentIds);

    /** 审核通过：PENDING_REVIEW → NORMAL + 评论数 +1（同事务）；返回是否本次刚通过（已通过返回 false，供互动通知只在首次触发） */
    boolean approveComment(String commentId);

    /** 审核驳回：PENDING_REVIEW → REJECTED（终态，待审从未计数） */
    void rejectComment(String commentId);

    /** 举报下架软删：→ DELETED，NORMAL 评论同步评论数 -1（同事务；幂等：已下架静默成功） */
    void markDeleted(String commentId);

    /** 热评索引全量重建（comment-count-narrow-table 票 03，兜底恢复手段：索引行与窄表漂移时手动触发） */
    void rebuildCommentFeedIndex();
}
