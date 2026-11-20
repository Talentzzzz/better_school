package com.betterschool.trigger.listener;

import com.betterschool.domain.errand.model.valobj.ErrandOrderPushEvent;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.errand.ErrandOrderCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 跑腿订单状态推送消费方（outbox → MQ → WS ORDER 帧，spec §2）：状态迁移后把新状态推给
 * 订单双方在线者，详情页收到帧后重拉详情实现实时刷新。投递对象决策与组帧
 * 在 {@link ErrandOrderCase#pushStatus}。消费失败不重试——推送是加速路径，
 * 漏推由详情页下次拉取与通知中心兜底；草稿态（DELETED/DRAFT）不推（无「双方」概念）
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.ORDER_PUSH_TOPIC, consumerGroup = "bs-order-push-group")
public class OrderPushConsumer implements RocketMQListener<String> {

    private final ErrandOrderCase errandOrderCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        try {
            ErrandOrderPushEvent event = objectMapper.readValue(payload, ErrandOrderPushEvent.class);
            errandOrderCase.pushStatus(event);
        } catch (Exception e) {
            log.error("[OrderPushConsumer] ORDER 帧推送失败 payload={}", payload, e);
        }
    }
}
