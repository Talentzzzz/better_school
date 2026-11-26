package com.betterschool.infrastructure.raffle.adapter.port.alert;

import com.betterschool.domain.raffle.adapter.port.alert.IDrawReconAlertPort;
import com.betterschool.infrastructure.dao.IMqOutboxDao;
import com.betterschool.infrastructure.dao.po.MqOutbox;
import com.betterschool.types.common.Constants;
import com.betterschool.types.common.DrawReconAlertMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对账告警端口的实现：wire 消息 {@link DrawReconAlertMessage} 由调用方（对账服务）构造好整包传入，
 * 这里直接落一行 outbox 并同步发送；发送成功标记 SENT，失败留 PENDING 由 {@code outboxScan} 兜底重发，
 * 消费侧按 dedupKey 幂等吸收。
 * <p>
 * 对账服务没有事务模板，本方法也不在事务里：单行 insert 自带隐式事务，插入后立即同步发送即可，
 * 不走需要业务事务包裹的 afterCommit 路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrawReconAlertPort implements IDrawReconAlertPort {

    private final IMqOutboxDao mqOutboxDao;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void sendReconAlert(DrawReconAlertMessage alertMessage) {
        MqOutbox mqOutbox = MqOutbox.pending(Constants.RECON_ALERT_TOPIC, alertMessage, objectMapper);
        mqOutboxDao.insert(mqOutbox);
        send(mqOutbox);
    }

    private void send(MqOutbox mqOutbox) {
        boolean sent;
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(mqOutbox.getDestination(), mqOutbox.getPayload());
            sent = sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK;
            if (!sent) {
                log.warn("outbox 同步发送未确认 msgId={}, status={}", mqOutbox.getMsgId(),
                        sendResult == null ? null : sendResult.getSendStatus());
            }
        } catch (Exception e) {
            log.warn("outbox 同步发送失败 msgId={}", mqOutbox.getMsgId(), e);
            sent = false;
        }
        if (sent) {
            mqOutboxDao.markSentByMsgId(mqOutbox.getMsgId());
        } else if (mqOutbox.getRetryCount() + 1 >= MqOutbox.MAX_RETRY_COUNT) {
            mqOutboxDao.markDeadBatch(List.of(mqOutbox.getId()));
        } else {
            mqOutboxDao.incrementRetryBatch(List.of(mqOutbox.getId()));
        }
    }
}
