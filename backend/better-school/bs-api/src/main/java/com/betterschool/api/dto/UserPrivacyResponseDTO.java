package com.betterschool.api.dto;

import lombok.Data;

/**
 * 隐私开关当前值
 */
@Data
public class UserPrivacyResponseDTO {

    private boolean showLiked;

    private boolean showComments;
}
