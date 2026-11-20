package com.betterschool.trigger.listener;

import com.betterschool.domain.chat.adapter.port.IWsPushPort;
import com.betterschool.domain.chat.model.valobj.ChatWsFrame;
import com.betterschool.domain.chat.model.valobj.ChatWsFrameType;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通知 BANNER 通道消费方（outbox → MQ → WS NOTIF 帧，spec §2）：点赞/评论等瞬态横幅只推在线者，
 * 排除逻辑无需（单收件人）。经 IWsPushPort 投递（本地优先 → Redis 路由 → 定向频道）。
 * 消费失败不重试——横幅是加速路径，漏推由前端按 (scope, seq) 水位补拉重放兜底
 * （保证弹过：上线后把积压的通知逐条弹完）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.NOTIFY_PUSH_TOPIC, consumerGroup = "bs-notify-push-group")
public class NotifyPushConsumer implements RocketMQListener<String> {

    private final IWsPushPort wsPushPort;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        try {
            NotifyPushMessage message = objectMapper.readValue(payload, NotifyPushMessage.class);
            wsPushPort.deliver(message.getUserId(), ChatWsFrame.builder()
                    .type(ChatWsFrameType.NOTIF)
                    .payload(Map.of(
                            "scope", message.getScope(),
                            "seq", message.getSeq(),
                            "type", message.getType(),
                            "title", message.getTitle(),
                            "content", message.getContent(),
                            "refType", message.getRefType() == null ? "" : message.getRefType(),
                            "refId", message.getRefId() == null ? "" : message.getRefId()))
                    .ts(System.currentTimeMillis())
                    .build());
        } catch (Exception e) {
            log.error("[NotifyPushConsumer] NOTIF 帧推送失败 payload={}", payload, e);
        }
    }
}
