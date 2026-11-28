package com.betterschool.domain.notification.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知事件（业务事务内写 outbox → 通知域消费者统一落库 + 按通道投递）：
 * notificationId 由生产点预生成（消费端幂等第一锚点，uk_notification_id），
 * dedupKey 可空（互动/系统通知的生产点语义键，uk_dedup 第二锚点）。
 * 文案由生产点渲染（方案 A）；scope/type 沿用现有枚举名
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationEventMessage {

    /** 生产点预生成 UUID（消费端幂等第一锚点：uk_notification_id） */
    private String notificationId;
    /** 收件人 */
    private String userId;
    /** NotificationScopeEnum.name() */
    private String scope;
    /** NotificationTypeEnum.name() */
    private String type;
    /** 触发者，可空 */
    private String senderId;
    private String title;
    private String content;
    /** 可空 */
    private String refType;
    /** 可空 */
    private String refId;
    /** 可空（互动/系统通知的生产点语义键，uk_dedup 第二锚点） */
    private String dedupKey;
}
