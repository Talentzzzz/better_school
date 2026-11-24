package com.betterschool.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NotificationReadRequestDTO {

    /** 收件范围（MARKET_PRODUCT/BOTTOM_SYSTEM/INTERACTION_LIKE/INTERACTION_COMMENT） */
    @NotBlank(message = "通知范围不能为空")
    private String scope;
}
