package com.betterschool.trigger.listener;

import com.betterschool.types.common.Constants;
import com.betterschool.types.common.PostRebateClaimTaskMessage;
import com.betterschool.usecase.post.PostRebateCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 发帖返利领取任务消费方（topic {@code bs-post-rebate-claim}）：重放同步领取失败的领取任务。
 * <p>
 * 发帖事务内落 PENDING outbox，正常路径由请求线程同步领取后标记已处理；只有同步领取抛异常时任务
 * 才留 PENDING，由 {@code outboxScan} 重发到本 topic。领取按 {@code (userId, actionType, refId)}
 * 幂等，重复投递零副作用；{@code periodKey} 缺失（旧载荷）由 case 按当天兜底。
 * <p>
 * 解析失败直接丢弃（重试也不会成功）；业务失败必须上抛，让 broker 重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.POST_REBATE_CLAIM_TOPIC,
        consumerGroup = "bs-monolith-post-rebate-claim-group")
public class PostRebateClaimConsumer implements RocketMQListener<String> {

    private final PostRebateCase postRebateCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        PostRebateClaimTaskMessage message;
        try {
            message = objectMapper.readValue(payload, PostRebateClaimTaskMessage.class);
        } catch (Exception e) {
            log.error("[PostRebateClaimConsumer] 消息载荷解析失败，丢弃 payload={}", payload, e);
            return;
        }
        if (message.getPostId() == null || message.getPostId().isBlank()
                || message.getUserId() == null || message.getUserId().isBlank()) {
            log.error("[PostRebateClaimConsumer] 消息缺 postId/userId，丢弃 payload={}", payload);
            return;
        }
        // periodKey 允许为空：旧载荷由 case 按当天兜底
        // 不 catch：业务异常上抛给 broker 重试；领取幂等，重投零副作用
        postRebateCase.replayClaim(message);
    }
}
