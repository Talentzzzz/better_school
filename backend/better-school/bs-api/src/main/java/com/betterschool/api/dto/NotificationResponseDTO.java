package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationResponseDTO {

    /** 通知业务 ID（notification_id，UUID 字符串，uuid-biz-id 票 06） */
    private String id;
    /** per-(user, scope) 收件序号（前端横幅补拉游标锚点：seq > 本地水位即未弹过） */
    private Long seq;
    /** 触发者用户 ID（互动通知：谁评/回/赞了你；广播类为空） */
    private String senderId;
    private String type;
    private String title;
    private String content;
    private boolean isRead;
    private String refType;
    private String refId;
    private LocalDateTime createdAt;
}
