package com.betterschool.trigger.listener;

import com.betterschool.types.common.Constants;
import com.betterschool.usecase.marketing.DrawReconAlertMessage;
import com.betterschool.usecase.marketing.MarketingNoticeCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 营销库存对账告警消费方（topic {@code bs-marketing-recon-alert}）：落维护者告警通知
 * （收件人 = 系统助手账号，scope = BOTTOM_SYSTEM）。与营销侧其他消费方各自独立消费组；
 * 重复消费由通知 dedup_key（轮次 + 检查类型 + 单号）幂等，只落一条。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.RECON_ALERT_TOPIC, consumerGroup = "bs-monolith-recon-alert-group")
public class ReconAlertConsumer implements RocketMQListener<String> {

    private final MarketingNoticeCase marketingNoticeCase;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        DrawReconAlertMessage message;
        try {
            message = objectMapper.readValue(payload, DrawReconAlertMessage.class);
        } catch (Exception e) {
            // 载荷残缺重试也不会成功：记日志丢弃，不触发 broker 重投
            log.error("[ReconAlertConsumer] 消息载荷解析失败，丢弃 payload={}", payload, e);
            return;
        }
        if (message.getRoundNo() == null || message.getCheckType() == null || message.getBizId() == null) {
            log.error("[ReconAlertConsumer] 消息缺 roundNo/checkType/bizId（dedupKey 三要素），丢弃 payload={}", payload);
            return;
        }
        // 解析成功后异常上抛，由 broker 自动重试；通知落库 dedup_key 幂等，重试不重复
        marketingNoticeCase.notifyReconAlert(message);
    }
}
