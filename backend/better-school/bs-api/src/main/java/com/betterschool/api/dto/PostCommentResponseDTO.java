package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PostCommentResponseDTO {

    private String commentId;
    private String postId;
    private String userId;
    private String nickname;
    private String content;
    /** 评论点赞数（当前登录用户对当前列表的已赞态见 liked） */
    private Long likeCount;
    /** 楼内可见子回复数（仅根评论有意义） */
    private Long replyCount;
    /** 当前用户是否已赞（访客恒 false） */
    private boolean liked;
    /** 审核未通过理由（仅评论作者本人可见；通过/待审为 null） */
    private String rejectReason;
    private LocalDateTime createdAt;
}
