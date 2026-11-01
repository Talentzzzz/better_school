package com.betterschool.trigger.listener;

import com.betterschool.types.common.Constants;
import com.betterschool.usecase.marketing.MarketingNoticeCase;
import com.betterschool.usecase.marketing.PointsChangedMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 积分变动消费方（topic {@code bs-points-changed}）：落积分变动通知（变动量 + 变动后余额）。
 * 重复消费由通知 dedup_key 幂等，只落一条。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.POINTS_CHANGED_TOPIC, consumerGroup = "bs-monolith-points-changed-group")
public class PointsChangedConsumer implements RocketMQListener<String> {

    private final MarketingNoticeCase marketingNoticeCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        PointsChangedMessage message;
        try {
            message = objectMapper.readValue(payload, PointsChangedMessage.class);
        } catch (Exception e) {
            // 载荷残缺重试也不会成功：记日志丢弃，不触发 broker 重投
            log.error("[PointsChangedConsumer] 消息载荷解析失败，丢弃 payload={}", payload, e);
            return;
        }
        if (message.getUserId() == null || message.getDelta() == null || message.getBalanceAfter() == null
                || message.getReasonType() == null || message.getRefId() == null) {
            log.error("[PointsChangedConsumer] 消息缺业务字段，丢弃 payload={}", payload);
            return;
        }
        // 解析成功后异常上抛，由 broker 自动重试；通知落库 dedup_key 幂等，重试不重复
        marketingNoticeCase.notifyPointsChanged(message);
    }
}
