package com.betterschool.trigger.listener;

import com.betterschool.domain.chat.model.valobj.ChatReadPushMessage;
import com.betterschool.trigger.ws.ChatWsPushService;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * chat READ 推送消费方（outbox → MQ → WS，spec §2）：水位线推进事件推给会话内
 * reader 之外的其他在线成员（实时已读回执）；重复打开幂等不产生事件（票 03 写侧保证），
 * 重复消费同水位线为无害噪音，断线期间不补推
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.CHAT_READ_TOPIC, consumerGroup = "bs-chat-read-group")
public class ChatReadPushConsumer implements RocketMQListener<String> {

    private final ChatWsPushService chatWsPushService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        try {
            ChatReadPushMessage message = objectMapper.readValue(payload, ChatReadPushMessage.class);
            chatWsPushService.pushRead(message);
        } catch (Exception e) {
            log.error("[ChatReadPushConsumer] READ 推送失败 payload={}", payload, e);
        }
    }
}
