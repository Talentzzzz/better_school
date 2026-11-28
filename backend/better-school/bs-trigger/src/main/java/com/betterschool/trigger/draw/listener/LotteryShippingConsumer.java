package com.betterschool.trigger.draw.listener;

import com.betterschool.domain.draw.model.valobj.ShippingResultVO;
import com.betterschool.types.common.Constants;
import com.betterschool.types.common.LotteryDrawnMessage;
import com.betterschool.types.draw.DrawMqConstants;
import com.betterschool.usecase.marketing.DrawCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 发货消费方（票 14，topic {@code bs-lottery-drawn}）：把中奖事件推进成发货状态机的下一步。
 * <p>
 * 与同一 topic 上的另外两个订阅方各用一组（同组会抢消息，见 {@code types.points.Constants} 的注释）：
 * <ul>
 *   <li>{@code bs-draw-shipping-group}（本类）：抽奖记录发状态机——虚拟 / 次数档推 已完成、
 *       实物档推 已发货并补库存兑换确认</li>
 *   <li>{@code bs-points-lottery-drawn-group}：积分域按 VIRTUAL 档入账</li>
 *   <li>{@code bs-monolith-lottery-drawn-group}：单体落中奖通知</li>
 * </ul>
 * <p>
 * 幂等：状态机靠条件更新 + {@code uk_record_type}（见 {@code IDrawShippingService}），
 * 重投不会重复推进；载荷残缺直接丢弃（重试也不会成功，不触发 broker 重投）。
 * 消费失败**不吞异常**：抛出让 broker 重投——实物档的配额兑换确认排在状态推进**之前**
 * （case 层三步编排），它是一次本地写（落 {@code type = 2} 明细 + 合并消息），失败重投即补上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.LOTTERY_DRAWN_TOPIC,
        consumerGroup = DrawMqConstants.LOTTERY_DRAWN_SHIPPING_CONSUMER_GROUP)
public class LotteryShippingConsumer implements RocketMQListener<String> {

    private final DrawCase drawCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        LotteryDrawnMessage lotteryDrawnMessage;
        try {
            lotteryDrawnMessage = objectMapper.readValue(payload, LotteryDrawnMessage.class);
        } catch (Exception e) {
            log.error("[LotteryShippingConsumer] 消息载荷解析失败，丢弃 payload={}", payload, e);
            return;
        }
        if (lotteryDrawnMessage.getRecordId() == null || lotteryDrawnMessage.getUserId() == null) {
            log.error("[LotteryShippingConsumer] 载荷缺 recordId / userId，丢弃 payload={}", payload);
            return;
        }
        ShippingResultVO shippingResultVO = drawCase.shipAfterDraw(lotteryDrawnMessage.getUserId(), lotteryDrawnMessage.getRecordId());
        if (shippingResultVO.isAdvanced()) {
            log.info("[LotteryShippingConsumer] 发货状态推进完成 userId={} recordId={} state={}",
                    lotteryDrawnMessage.getUserId(), lotteryDrawnMessage.getRecordId(), shippingResultVO.getState());
        }
    }
}
