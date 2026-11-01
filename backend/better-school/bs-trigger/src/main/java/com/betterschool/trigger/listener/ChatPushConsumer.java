package com.betterschool.trigger.listener;

import com.betterschool.domain.chat.model.valobj.ChatMessagePushMessage;
import com.betterschool.trigger.ws.ChatWsPushService;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * chat MSG 推送消费方（outbox → MQ → WS，spec §2）：新消息推给会话内其他在线成员
 * （排除发送者本人，发送方以 HTTP 200 为准）。判重键 (roomId, seq) 在客户端合流补拉结果；
 * 消费失败不重试——推送是加速路径，漏推由补拉兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.CHAT_PUSH_TOPIC, consumerGroup = "bs-chat-push-group")
public class ChatPushConsumer implements RocketMQListener<String> {

    private final ChatWsPushService chatWsPushService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        try {
            ChatMessagePushMessage message = objectMapper.readValue(payload, ChatMessagePushMessage.class);
            chatWsPushService.pushMessage(message);
        } catch (Exception e) {
            log.error("[ChatPushConsumer] MSG 推送失败 payload={}", payload, e);
        }
    }
}
