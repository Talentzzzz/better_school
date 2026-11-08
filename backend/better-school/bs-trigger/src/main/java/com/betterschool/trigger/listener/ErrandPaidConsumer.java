package com.betterschool.trigger.listener;

import com.betterschool.domain.errand.model.valobj.ErrandPaidMessage;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.errand.ErrandOrderCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 跑腿订单支付成功消费方（topic bs-errand-paid）：给作者发"订单已进入人工审核"通知。
 * 重复消费由用例内 dedup_key 幂等，只落一条通知
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.ERRAND_PAID_TOPIC, consumerGroup = "bs-errand-paid-group")
public class ErrandPaidConsumer implements RocketMQListener<String> {

    private final ErrandOrderCase errandOrderCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        ErrandPaidMessage message;
        try {
            message = objectMapper.readValue(payload, ErrandPaidMessage.class);
        } catch (Exception e) {
            // 载荷残缺重试也不会成功：记日志丢弃，不触发 broker 重投
            log.error("[ErrandPaidConsumer] 消息载荷解析失败，丢弃 payload={}", payload, e);
            return;
        }
        // 解析成功后异常上抛，由 broker 自动重试；通知落库 dedup_key 幂等，重试不重复
        errandOrderCase.notifyPaid(message.getOrderId());
        log.info("[ErrandPaidConsumer] 支付成功通知处理完成 orderId={}", message.getOrderId());
    }
}
