package com.betterschool.trigger.listener;

import com.betterschool.domain.notification.model.valobj.NotificationBroadcastMessage;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 站内公告广播消费方：outbox 投递，给全用户落 notification 表。
 * 幂等：按消息内 msgId 检查 broadcast_msg_id（outbox 重发产生的重复消费被忽略）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.NOTIFICATION_BROADCAST_TOPIC, consumerGroup = "bs-notification-broadcast-group")
public class NotificationBroadcastConsumer implements RocketMQListener<String> {

    private final INotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        try {
            NotificationBroadcastMessage notificationBroadcastMessage = objectMapper.readValue(payload, NotificationBroadcastMessage.class);
            notificationService.insertBroadcast(notificationBroadcastMessage.getMsgId(), notificationBroadcastMessage.getType(), notificationBroadcastMessage.getTitle(), notificationBroadcastMessage.getContent());
            log.info("[NotificationBroadcastConsumer] 公告广播落库 msgId={}, title={}", notificationBroadcastMessage.getMsgId(), notificationBroadcastMessage.getTitle());
        } catch (Exception e) {
            log.error("[NotificationBroadcastConsumer] 公告广播落库失败 payload={}", payload, e);
        }
    }
}
