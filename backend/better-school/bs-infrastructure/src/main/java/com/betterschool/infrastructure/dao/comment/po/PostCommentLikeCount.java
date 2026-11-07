package com.betterschool.infrastructure.dao.comment.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 评论点赞计数窄表 {@code post_comment_like_count}（comment-count-narrow-table 票 02 起的计数真源）。
 *
 * <p>只用于展示取数的批量回查（{@code comment_id → like_count}）与行诞生/变更语句；
 * 评论内容、状态、reply_count 仍在 {@code post_comment} 主行（{@link PostComment}）。
 */
@Data
public class PostCommentLikeCount {

    private Long id;
    /** 评论业务 ID（唯一；根评论与子回复都入表） */
    private String commentId;
    /** 评论点赞数（真源 = post_comment_like 条数） */
    private Long likeCount;
    /** 创建时间（= 审核通过时刻） */
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
