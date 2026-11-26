package com.betterschool.trigger.job;

import com.betterschool.infrastructure.dao.IMqOutboxDao;
import com.betterschool.infrastructure.dao.po.MqOutbox;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * outbox 兜底扫描（XXL-Job {@code outboxScan}）：重发**超时未发出**的 PENDING 消息。
 * <p>
 * 仓储在业务事务内 insert、提交后立即同步发送；发送成功就标记 SENT，所以能走到扫描的行只剩三种：
 * 应用在发送前崩溃、broker 当时不可达、标记 SENT 前崩溃。扫描按 ({@code update_time} 超时) 捞行重发，
 * 消费侧按业务幂等键吸收重复投递。
 * <p>
 * 发送继续用同步方式：单条失败只记日志并累计 {@code retry_count}，到 {@link MqOutbox#MAX_RETRY_COUNT}
 * 次置死信（state=2），不再重发。延迟字段不再参与扫描（当前无延迟消息；补发也早过了原定时刻）。
 */
@Slf4j
@Component
public class OutboxScanJob {

    private static final int SCAN_BATCH_SIZE = 100;

    private final IMqOutboxDao mqOutboxDao;
    private final RocketMQTemplate rocketMQTemplate;
    private final int scanTimeoutSeconds;

    public OutboxScanJob(IMqOutboxDao mqOutboxDao, RocketMQTemplate rocketMQTemplate,
                         @Value("${outbox.scan.timeout-seconds:60}") int scanTimeoutSeconds) {
        this.mqOutboxDao = mqOutboxDao;
        this.rocketMQTemplate = rocketMQTemplate;
        this.scanTimeoutSeconds = scanTimeoutSeconds;
    }

    @XxlJob("outboxScan")
    public String scanPending(String param) {
        List<MqOutbox> pendingList = mqOutboxDao.selectPendingList(scanTimeoutSeconds, SCAN_BATCH_SIZE);
        if (pendingList.isEmpty()) {
            return "success";
        }
        List<Long> sentIds = new ArrayList<>();
        List<Long> retryIds = new ArrayList<>();
        List<Long> deadIds = new ArrayList<>();
        for (MqOutbox mqOutbox : pendingList) {
            if (send(mqOutbox)) {
                sentIds.add(mqOutbox.getId());
            } else if (mqOutbox.getRetryCount() + 1 >= MqOutbox.MAX_RETRY_COUNT) {
                deadIds.add(mqOutbox.getId());
            } else {
                retryIds.add(mqOutbox.getId());
            }
        }
        if (!sentIds.isEmpty()) {
            mqOutboxDao.markSentBatch(sentIds);
        }
        if (!retryIds.isEmpty()) {
            mqOutboxDao.incrementRetryBatch(retryIds);
        }
        if (!deadIds.isEmpty()) {
            mqOutboxDao.markDeadBatch(deadIds);
        }
        log.info("outbox 扫描重发 {} 条（成功 {} / 待重试 {} / 死信 {}）",
                pendingList.size(), sentIds.size(), retryIds.size(), deadIds.size());
        return "success";
    }

    private boolean send(MqOutbox mqOutbox) {
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(mqOutbox.getDestination(), mqOutbox.getPayload());
            if (sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK) {
                return true;
            }
            log.warn("outbox 扫描重发未确认 msgId={}, id={}, status={}", mqOutbox.getMsgId(), mqOutbox.getId(),
                    sendResult == null ? null : sendResult.getSendStatus());
            return false;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("outbox 扫描重发失败 msgId={}, id={}", mqOutbox.getMsgId(), mqOutbox.getId(), e);
            return false;
        }
    }
}
