package com.betterschool.trigger.listener;

import com.betterschool.types.common.Constants;
import com.betterschool.types.common.LotteryDrawnMessage;
import com.betterschool.usecase.marketing.MarketingNoticeCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 抽奖中奖消费方（topic {@code bs-lottery-drawn}）：落中奖通知（文案按 prizeType 呈现）。
 * 与积分服务各自独立消费组；重复消费由通知 dedup_key 幂等，只落一条。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.LOTTERY_DRAWN_TOPIC, consumerGroup = "bs-monolith-lottery-drawn-group")
public class LotteryDrawnConsumer implements RocketMQListener<String> {

    private final MarketingNoticeCase marketingNoticeCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        LotteryDrawnMessage message;
        try {
            message = objectMapper.readValue(payload, LotteryDrawnMessage.class);
        } catch (Exception e) {
            // 载荷残缺重试也不会成功：记日志丢弃，不触发 broker 重投
            log.error("[LotteryDrawnConsumer] 消息载荷解析失败，丢弃 payload={}", payload, e);
            return;
        }
        if (message.getRecordId() == null || message.getUserId() == null || message.getPrizeType() == null) {
            log.error("[LotteryDrawnConsumer] 消息缺业务 id 或 prizeType，丢弃 payload={}", payload);
            return;
        }
        // 解析成功后异常上抛，由 broker 自动重试；通知落库 dedup_key 幂等，重试不重复
        marketingNoticeCase.notifyLotteryDrawn(message);
    }
}
