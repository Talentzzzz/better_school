package com.betterschool.infrastructure.dao.notification.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Notification {

    private Long id;
    /** 业务 ID（对外唯一标识，UUID 字符串；uuid-biz-id 票 06：UUID.randomUUID() 随 insert 一次写入，物理自增 id 保留纯内部键） */
    private String notificationId;
    private String userId;
    private String scope;
    private Long seq;
    private String senderId;
    private String type;
    private String title;
    private String content;
    /** 仅查询路径的计算列（seq ≤ 该范围水位），非表列；仓储按页内 scope 集查水位后 Java 计算填充 */
    private Boolean isRead;
    private String refType;
    private String refId;
    private String broadcastMsgId;
    private String dedupKey;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
