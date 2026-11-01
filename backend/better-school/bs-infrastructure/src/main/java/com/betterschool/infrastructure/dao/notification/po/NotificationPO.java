package com.betterschool.infrastructure.dao.notification.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationPO {

    private Long id;
    /** 业务 ID（对外唯一标识；与物理自增同值，插入后同事务回写） */
    private Long notificationId;
    private String userId;
    private String scope;
    private Long seq;
    private String senderId;
    private String type;
    private String title;
    private String content;
    /** 仅查询路径的计算列（seq ≤ 该范围水位），非表列 */
    private Boolean isRead;
    private String refType;
    private String refId;
    private String broadcastMsgId;
    private String dedupKey;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
