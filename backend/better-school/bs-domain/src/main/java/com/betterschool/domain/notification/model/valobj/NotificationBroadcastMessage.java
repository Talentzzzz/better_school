package com.betterschool.domain.notification.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 站内公告广播消息载荷：outbox + MQ 投递，消费方给全用户落 notification 表。
 * msgId 由业务生成（UUID），消费方用它做广播幂等检查（notification.broadcast_msg_id）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationBroadcastMessage {

    private String msgId;
    private String type;
    private String title;
    private String content;
}
