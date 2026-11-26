package com.betterschool.trigger.listener;

import com.betterschool.types.common.Constants;
import com.betterschool.types.common.PostRebateTaskMessage;
import com.betterschool.usecase.post.PostPublishCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 发帖返利消费方（topic {@code bs-post-rebate}）。
 * <p>
 * 发帖仓储在业务事务内落 PENDING 任务，提交后立即同步发送到本 topic（broker 确认即标记 SENT，
 * 失败留 PENDING 交 {@code outboxScan}），这里负责真正的返利发放。
 * <p>
 * 解析失败直接丢弃（重试也不会成功）；业务失败必须上抛，让 broker 重试——
 * 返利桶的 {@code uk (user_id, reason_type, ref_id)} 吸收重投，不会重复发放。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.POST_REBATE_TOPIC, consumerGroup = "bs-monolith-post-rebate-group")
public class PostRebateConsumer implements RocketMQListener<String> {

    private final PostPublishCase postPublishCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        PostRebateTaskMessage message;
        try {
            message = objectMapper.readValue(payload, PostRebateTaskMessage.class);
        } catch (Exception e) {
            log.error("[PostRebateConsumer] 消息载荷解析失败，丢弃 payload={}", payload, e);
            return;
        }
        if (message.getPostId() == null || message.getUserId() == null) {
            log.error("[PostRebateConsumer] 消息缺 postId/userId，丢弃 payload={}", payload);
            return;
        }
        // 不 catch：业务异常上抛给 broker 重试；发放幂等，重投零副作用
        postPublishCase.grantPostRebate(message.getUserId(), message.getPostId());
    }
}
