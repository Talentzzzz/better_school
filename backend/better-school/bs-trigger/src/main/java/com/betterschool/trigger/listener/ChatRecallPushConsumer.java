package com.betterschool.trigger.listener;

import com.betterschool.domain.chat.model.valobj.ChatRecallPushMessage;
import com.betterschool.trigger.ws.ChatWsPushService;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * chat RECALL 推送消费方（outbox → MQ → WS，spec §2）：撤回轻事件推给会话全部在线成员，
 * 只带定位（roomId+seq），内容终态以拉取呈现；轻推丢失可接受，重进/重连自然收敛（spec §2）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.CHAT_RECALL_TOPIC, consumerGroup = "bs-chat-recall-group")
public class ChatRecallPushConsumer implements RocketMQListener<String> {

    private final ChatWsPushService chatWsPushService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        try {
            ChatRecallPushMessage message = objectMapper.readValue(payload, ChatRecallPushMessage.class);
            chatWsPushService.pushRecall(message);
        } catch (Exception e) {
            log.error("[ChatRecallPushConsumer] RECALL 推送失败 payload={}", payload, e);
        }
    }
}
