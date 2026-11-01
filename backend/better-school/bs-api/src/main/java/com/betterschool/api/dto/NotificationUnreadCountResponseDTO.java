package com.betterschool.api.dto;

import lombok.Data;

@Data
public class NotificationUnreadCountResponseDTO {

    /** 该范围未读数（计数器 − 水位） */
    private long count;
}
