package com.betterschool.trigger.listener;

import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.notification.INotificationCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 通知事件消费方（topic {@code bs-notify-event}）：只做反序列化，
 * 「幂等落 notification + 落库后原地投递」统一在 {@link INotificationCase#consume}
 * （CHAT→系统消息 / BANNER→WS 横幅 / NONE→只落通知中心），不写第二跳 outbox、不经第二跳 MQ。
 * <p>
 * CHAT 投递失败向上抛，由 broker 重投本事件——通知落库按 notificationId + dedupKey 幂等、
 * 系统消息按 chat {@code client_msg_id} 幂等，重试不重复；BANNER 是加速路径，失败只记日志，
 * 漏推由前端按 (scope, seq) 水位补拉兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.NOTIFY_EVENT_TOPIC, consumerGroup = "bs-notify-event-group")
public class NotifyEventConsumer implements RocketMQListener<String> {

    private final INotificationCase notificationCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        NotificationEventMessage notificationEventMessage;
        try {
            notificationEventMessage = objectMapper.readValue(payload, NotificationEventMessage.class);
        } catch (Exception e) {
            log.error("[NotifyEventConsumer] 通知事件反序列化失败 payload={}", payload, e);
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
        }
        // 不 catch：落库/投递失败上抛交 broker 重试，消费侧按 notificationId + dedupKey 幂等
        notificationCase.consume(notificationEventMessage);
    }
}
