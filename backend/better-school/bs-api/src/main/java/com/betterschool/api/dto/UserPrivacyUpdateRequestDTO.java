package com.betterschool.api.dto;

import lombok.Data;

/**
 * 隐私设置（部分更新：null＝该项不改）
 */
@Data
public class UserPrivacyUpdateRequestDTO {

    /** 公开我的赞过 */
    private Boolean showLiked;

    /** 公开我的评论 */
    private Boolean showComments;
}
