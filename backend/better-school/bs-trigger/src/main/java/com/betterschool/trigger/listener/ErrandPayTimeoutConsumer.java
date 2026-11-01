package com.betterschool.trigger.listener;

import com.betterschool.usecase.errand.ErrandOrderCase;
import com.betterschool.domain.errand.model.valobj.ErrandTimeoutMessage;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 待支付超时消费方：创建订单时投递（15 分钟），未支付则订单取消（未冻结无退款）。
 * 处理幂等由 service 内条件 UPDATE 保证；消息丢失由 XXL-Job 兜底（后置）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.ERRAND_PAY_TIMEOUT_TOPIC, consumerGroup = "bs-errand-pay-timeout-group")
public class ErrandPayTimeoutConsumer implements RocketMQListener<String> {

    private final ErrandOrderCase errandOrderCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        try {
            ErrandTimeoutMessage message = objectMapper.readValue(payload, ErrandTimeoutMessage.class);
            errandOrderCase.handlePayTimeout(message.getOrderId());
            log.info("[ErrandPayTimeoutConsumer] 待支付超时处理 orderId={}", message.getOrderId());
        } catch (Exception e) {
            log.error("[ErrandPayTimeoutConsumer] 待支付超时处理失败 payload={}", payload, e);
        }
    }
}
