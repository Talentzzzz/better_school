package com.betterschool.domain.notification.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知 CHAT 通道投递消息（NotifyEventConsumer 落库后直接调 NotificationCase.deliver → chat 发送管线落系统消息）：
 * 字段从落库的通知行抄出，消费端免回查；幂等由 chat 侧 client_msg_id = "NOTIFY:" + notificationId 收敛
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyChatMessage {

    /** 来源通知业务 ID（幂等锚点） */
    private String notificationId;
    /** 收件用户 */
    private String userId;
    private String type;
    private String title;
    private String content;
    /** 关联对象（SYSTEM 消息载荷 JSON 内引用，不落 message.ref 列） */
    private String refType;
    private String refId;
}
