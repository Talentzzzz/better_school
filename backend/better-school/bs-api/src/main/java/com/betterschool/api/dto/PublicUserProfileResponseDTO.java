package com.betterschool.api.dto;

import lombok.Data;

/**
 * 公开用户资料：作者主页与「我的」页资料区共用。**不含手机号**（PII）
 */
@Data
public class PublicUserProfileResponseDTO {

    private String userId;
    private String nickname;
    private String avatar;
    /** 个性签名（可空＝不展示） */
    private String signature;
    /** 性别 MALE/FEMALE（可空＝不展示） */
    private String gender;
    /** 获赞：该用户 NORMAL 帖 like_count 之和 */
    private long receivedLikeCount;
    /** 隐私开关当前值：公开我的赞过（始终返回，前端据此渲染空态） */
    private boolean showLiked;
    /** 隐私开关当前值：公开我的评论（始终返回，前端据此渲染空态） */
    private boolean showComments;
}
