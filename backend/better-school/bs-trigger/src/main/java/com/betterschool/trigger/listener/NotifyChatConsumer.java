package com.betterschool.trigger.listener;

import com.betterschool.domain.notification.model.valobj.NotifyChatMessage;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.notification.SystemMessageCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 通知 CHAT 通道消费方（outbox → MQ → chat 发送管线，spec §2）：把 CREDIT_CHANGE
 * 等留档类通知落进用户与系统助手的 1v1 会话。投递失败向上抛由 broker 重试——
 * 幂等由 chat uk_room_client（client_msg_id = NOTIFY:<notificationId>）收敛，重试不重复落
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.NOTIFY_CHAT_TOPIC, consumerGroup = "bs-notify-chat-group")
public class NotifyChatConsumer implements RocketMQListener<String> {

    private final SystemMessageCase systemMessageCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        try {
            NotifyChatMessage message = objectMapper.readValue(payload, NotifyChatMessage.class);
            systemMessageCase.deliver(message);
        } catch (Exception e) {
            log.error("[NotifyChatConsumer] 系统消息投递失败 payload={}", payload, e);
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
        }
    }
}
