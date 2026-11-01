package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 楼内子回复项（平铺展示）：parentId/replyTo 取自被回复评论行（忽略其状态）。
 * 指向标签（▶ 被回复人昵称）仅当 parentId ≠ 楼根评论 id 时展示，由前端判定
 */
@Data
public class CommentReplyResponseDTO {

    private String commentId;
    private String postId;
    private String parentId;
    private String userId;
    private String nickname;
    private String content;
    private Long likeCount;
    /** 当前用户是否已赞（访客恒 false） */
    private boolean liked;
    private LocalDateTime createdAt;
    private String replyToUserId;
    private String replyToNickname;
    /** 审核未通过理由（仅评论作者本人可见；通过/待审为 null） */
    private String rejectReason;
}
