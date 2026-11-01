package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 按用户列评论的列表项（作者主页/「我的」页「评论」tab）：评论 + 来源帖标题/封面
 */
@Data
public class UserCommentResponseDTO {

    private String commentId;
    private String content;
    private Long likeCount;
    private LocalDateTime createdAt;
    private String postId;
    /** 来源帖标题（超长由前端省略） */
    private String postTitle;
    /** 来源帖封面（无封面帖为 null） */
    private String postCover;
}
