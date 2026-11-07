package com.betterschool.infrastructure.mq;

import com.betterschool.infrastructure.dao.outbox.IOutboxDao;
import com.betterschool.infrastructure.dao.outbox.po.Outbox;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.xxl.job.core.handler.annotation.XxlJob;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * outbox 消息发送器：事务提交后立即异步发送一次；定时扫描兜底重发未发送消息。
 * 状态标记走"阻塞队列 + 专用线程批量 update"：MQ 回调线程只做内存入队，
 * 避免高并发下 DB 操作阻塞 RocketMQ 回调线程导致信号量耗尽
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxMessageSender {

    private static final int MAX_RETRY_COUNT = 5;
    private static final int SCAN_BATCH_SIZE = 100;
    private static final int MARK_BATCH_SIZE = 100;

    private final IOutboxDao outboxDao;
    private final RocketMQTemplate rocketMQTemplate;

    private final BlockingQueue<MarkTask> markQueue = new LinkedBlockingQueue<>();
    private final Thread markThread = new Thread(this::markLoop, "outbox-mark");

    @PostConstruct
    public void start() {
        markThread.setDaemon(true);
        markThread.start();
    }

    @PreDestroy
    public void stop() {
        markThread.interrupt();
    }

    /**
     * 异步发送一条 outbox 消息：回调只入队状态标记任务，由专用线程批量落库
     */
    public void sendAsync(Outbox outbox) {
        try {
            rocketMQTemplate.asyncSend(outbox.getDestination(), outbox.getPayload(), new SendCallback() {
                @Override
                public void onSuccess(SendResult sendResult) {
                    log.info("outbox 消息发送成功 msgId={}, id={}", outbox.getMsgId(), outbox.getId());
                    markQueue.offer(MarkTask.sent(outbox.getId()));
                }

                @Override
                public void onException(Throwable throwable) {
                    handleSendFailure(outbox, throwable);
                }
            });
        } catch (Exception e) {
            // asyncSend 同步抛错（如 broker 不可达）
            handleSendFailure(outbox, e);
        }
    }

    private void handleSendFailure(Outbox outbox, Throwable throwable) {
        log.warn("outbox 消息发送失败 msgId={}, id={}, retryCount={}", outbox.getMsgId(), outbox.getId(), outbox.getRetryCount(), throwable);
        MarkTask task = outbox.getRetryCount() + 1 >= MAX_RETRY_COUNT
                ? MarkTask.dead(outbox.getId())
                : MarkTask.retry(outbox.getId());
        markQueue.offer(task);
    }

    /**
     * 专用标记线程：阻塞等待任务，drainTo 攒批后按动作分组批量 update
     */
    private void markLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                MarkTask first = markQueue.take();
                List<MarkTask> batch = new ArrayList<>(MARK_BATCH_SIZE);
                batch.add(first);
                markQueue.drainTo(batch, MARK_BATCH_SIZE - 1);
                processBatch(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("outbox 标记线程已退出");
            } catch (Exception e) {
                // 批量标记失败不重试入队：记录保持 PENDING，由扫描兜底重发（消费端按 msgId 幂等）
                log.error("outbox 批量标记状态失败，消息保持待发送由扫描兜底", e);
            }
        }
    }

    private void processBatch(List<MarkTask> batch) {
        List<Long> sentIds = new ArrayList<>();
        List<Long> retryIds = new ArrayList<>();
        List<Long> deadIds = new ArrayList<>();
        for (MarkTask task : batch) {
            switch (task.action) {
                case MARK_SENT -> sentIds.add(task.id);
                case INCREMENT_RETRY -> retryIds.add(task.id);
                case MARK_DEAD -> deadIds.add(task.id);
            }
        }
        if (!sentIds.isEmpty()) {
            outboxDao.markSentBatch(sentIds);
        }
        if (!retryIds.isEmpty()) {
            outboxDao.incrementRetryBatch(retryIds);
        }
        if (!deadIds.isEmpty()) {
            outboxDao.markDeadBatch(deadIds);
        }
    }

    /**
     * 兜底扫描（XXL-Job 调度：cron 0/30 * * * * ?）：重发 PENDING 消息，
     * 覆盖事务提交后应用崩溃、broker 宕机恢复等场景。
     * 单实例部署即可；多实例需加扫描锁，重复发送由消费端按 msgId 幂等兜底。
     */
    @XxlJob("outboxScan")
    public String scanPending(String param) {
        List<Outbox> pendingList = outboxDao.selectPendingList(SCAN_BATCH_SIZE);
        if (!pendingList.isEmpty()) {
            log.info("outbox 扫描到 {} 条待发送消息", pendingList.size());
            pendingList.forEach(this::sendAsync);
        }
        return "success";
    }

    @AllArgsConstructor
    private static class MarkTask {
        private final Long id;
        private final Action action;

        static MarkTask sent(Long id) {
            return new MarkTask(id, Action.MARK_SENT);
        }

        static MarkTask retry(Long id) {
            return new MarkTask(id, Action.INCREMENT_RETRY);
        }

        static MarkTask dead(Long id) {
            return new MarkTask(id, Action.MARK_DEAD);
        }
    }

    private enum Action {
        MARK_SENT, INCREMENT_RETRY, MARK_DEAD
    }
}
