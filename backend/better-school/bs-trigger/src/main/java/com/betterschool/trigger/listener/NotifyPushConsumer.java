package com.betterschool.trigger.listener;

import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.notification.NotifyPushCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 通知 BANNER 通道消费方（outbox → MQ → WS NOTIF 帧，spec §2）：点赞/评论等瞬态横幅只推在线者，
 * 排除逻辑无需（单收件人）。组帧与投递决策在 {@link NotifyPushCase}。
 * 消费失败不重试——横幅是加速路径，漏推由前端按 (scope, seq) 水位补拉重放兜底
 * （保证弹过：上线后把积压的通知逐条弹完）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.NOTIFY_PUSH_TOPIC, consumerGroup = "bs-notify-push-group")
public class NotifyPushConsumer implements RocketMQListener<String> {

    private final NotifyPushCase notifyPushCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        try {
            NotifyPushMessage message = objectMapper.readValue(payload, NotifyPushMessage.class);
            notifyPushCase.push(message);
        } catch (Exception e) {
            log.error("[NotifyPushConsumer] NOTIF 帧推送失败 payload={}", payload, e);
        }
    }
}
