package com.betterschool.trigger.points.listener;

import com.betterschool.domain.points.service.IPointsAccountService;
import com.betterschool.types.common.LotteryDrawnMessage;
import com.betterschool.types.points.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 抽奖中奖消费方（topic {@code bs-lottery-drawn}）：只有虚拟积分档加分。
 * <p>
 * 幂等键 = {@code (userId, LOTTERY_WIN, recordId)}，MQ 重投不重复加分；
 * 谢谢参与不发事件，实物档也发但本消费方直接忽略。
 * <p>
 * 票 13 起奖品位类型口径是 {@code VIRTUAL / PHYSICAL / THANKS}（旧 {@code POINTS} 值已随旧模型作废），
 * 这里跟着改判 {@code VIRTUAL}——写死旧值会让积分档永远加不上分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = com.betterschool.types.common.Constants.LOTTERY_DRAWN_TOPIC,
        consumerGroup = Constants.LOTTERY_DRAWN_CONSUMER_GROUP)
public class LotteryDrawnConsumer implements RocketMQListener<String> {

    private static final String PRIZE_TYPE_VIRTUAL = "VIRTUAL";

    private final IPointsAccountService pointsAccountService;
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
        if (!PRIZE_TYPE_VIRTUAL.equals(message.getPrizeType()) || message.getPointsAmount() == null) {
            log.info("[LotteryDrawnConsumer] 非积分档，忽略 recordId={}, prizeType={}",
                    message.getRecordId(), message.getPrizeType());
            return;
        }
        pointsAccountService.addForLotteryWin(message.getUserId(), message.getRecordId(), message.getPointsAmount());
        log.info("[LotteryDrawnConsumer] 中奖加积分完成 userId={}, recordId={}, pointsAmount={}",
                message.getUserId(), message.getRecordId(), message.getPointsAmount());
    }
}
