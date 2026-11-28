package com.betterschool.trigger.listener;

import com.betterschool.types.common.Constants;
import com.betterschool.types.common.PostRebateCreditMessage;
import com.betterschool.usecase.post.IPostCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 发帖返利入账消费方（topic {@code bs-post-rebate-credit}）：一个返利订单行一条消息，
 * 按领取快照给对应的桶（积分 / 抽奖次数）入账。
 * <p>
 * 消息里的 {@code rebateType} 决定入哪个桶，金额是领取时的规则快照，消费端不再读规则；
 * 桶的唯一键 {@code (userId, REASON, postId)} 吸收重复投递，重试不会重复发放。
 * 入账完成后把该订单行标记 GRANTED。
 * <p>
 * 解析失败直接丢弃（重试也不会成功）；业务失败必须上抛，让 broker 重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.POST_REBATE_CREDIT_TOPIC,
        consumerGroup = "bs-monolith-post-rebate-credit-group")
public class PostRebateCreditConsumer implements RocketMQListener<String> {

    private final IPostCase postCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        PostRebateCreditMessage postRebateCreditMessage;
        try {
            postRebateCreditMessage = objectMapper.readValue(payload, PostRebateCreditMessage.class);
        } catch (Exception e) {
            log.error("[PostRebateCreditConsumer] 消息载荷解析失败，丢弃 payload={}", payload, e);
            return;
        }
        if (postRebateCreditMessage.getOrderId() == null || postRebateCreditMessage.getOrderId().isBlank()
                || postRebateCreditMessage.getUserId() == null || postRebateCreditMessage.getUserId().isBlank()
                || postRebateCreditMessage.getPostId() == null || postRebateCreditMessage.getPostId().isBlank()
                || postRebateCreditMessage.getRebateType() == null || postRebateCreditMessage.getRebateType().isBlank()) {
            log.error("[PostRebateCreditConsumer] 消息缺 orderId/userId/postId/rebateType，丢弃 payload={}", payload);
            return;
        }
        // 不 catch：业务异常上抛给 broker 重试；桶唯一键 + markGranted 幂等，重投零副作用
        postCase.grantPostRebate(postRebateCreditMessage);
    }
}
