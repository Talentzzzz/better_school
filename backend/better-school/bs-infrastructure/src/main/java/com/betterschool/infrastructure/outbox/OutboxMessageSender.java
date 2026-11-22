package com.betterschool.infrastructure.outbox;

import com.betterschool.infrastructure.dao.outbox.IOutboxDao;
import com.betterschool.infrastructure.dao.outbox.po.OutboxPO;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * outbox 消息发送器（形状照单体）：事务提交后立即异步发送一次；xxl-job 定时扫描兜底重发未发送消息。
 * 状态标记走"阻塞队列 + 专用线程批量 update"：MQ 回调线程只做内存入队，
 * 避免高并发下 DB 操作阻塞 RocketMQ 回调线程导致信号量耗尽。
 * <p>
 * 每个服务一份自己的 outbox 与扫描任务（handler 名可同名——各服务是独立 xxl 执行器 appname）。
 */
@Slf4j
@Component
public class OutboxMessageSender {

    private static final int MAX_RETRY_COUNT = 5;
    private static final int SCAN_BATCH_SIZE = 100;
    private static final int MARK_BATCH_SIZE = 100;

    /**
     * 重发参数（票 14 定型，配置键 {@code outbox.scan.timeout-seconds}）：
     * 扫描只捞 {@code state=0} 且 {@code update_time} 已超时的行——刚提交的那一批由 afterCommit 的
     * 首次异步发送负责，扫描只做兜底，抢在前面重发会重复投递（消费侧幂等能吸收，但没必要）。
     * 默认 60s：远大于正常发送耗时，又足以让「应用崩溃 / broker 宕机」在一分钟内自愈。
     */
    private final int scanTimeoutSeconds;

    private final IOutboxDao outboxDao;
    private final RocketMQTemplate rocketMQTemplate;

    public OutboxMessageSender(IOutboxDao outboxDao, RocketMQTemplate rocketMQTemplate,
                               @Value("${outbox.scan.timeout-seconds:60}") int scanTimeoutSeconds) {
        this.outboxDao = outboxDao;
        this.rocketMQTemplate = rocketMQTemplate;
        this.scanTimeoutSeconds = scanTimeoutSeconds;
    }

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
     * 异步发送一条 outbox 消息：回调只入队状态标记任务，由专用线程批量落库。
     * 延迟语义只由消息自己的列决定，不按 destination 查表——共享 sender 不能把 points / raffle 的
     * 事件也拖慢：{@code delay_ms}（定时消息，任意毫秒）优先于 {@code delay_level}（固定档位），
     * 都没有才直发。
     */
    public void sendAsync(OutboxPO outboxPO) {
        dispatch(outboxPO, false);
    }

    /**
     * @param delayExpired 这一行的延迟窗口是否已经过去（扫描重发=True）：是则带 {@code delay_ms}
     *                     的行直发，不再定时一次
     */
    private void dispatch(OutboxPO outboxPO, boolean delayExpired) {
        SendCallback callback = new SendCallback() {
            @Override
            public void onSuccess(SendResult sendResult) {
                log.info("outbox 消息发送成功 msgId={}, id={}", outboxPO.getMsgId(), outboxPO.getId());
                markQueue.offer(MarkTask.sent(outboxPO.getId()));
            }

            @Override
            public void onException(Throwable throwable) {
                handleSendFailure(outboxPO, throwable);
            }
        };
        Integer delayMs = outboxPO.getDelayMs();
        int delayLevel = outboxPO.getDelayLevel() == null ? 0 : outboxPO.getDelayLevel();
        try {
            if (delayMs != null && !delayExpired) {
                sendTimer(outboxPO, delayMs, callback);
            } else if (delayMs == null && delayLevel > 0) {
                // 异步带延迟级别只有 Message payload 的五参重载（没有 Object payload 版本）；
                // timeout 沿用 producer 配置，与两参重载的默认行为一致
                Message<String> message = MessageBuilder.withPayload(outboxPO.getPayload()).build();
                rocketMQTemplate.asyncSend(outboxPO.getDestination(), message, callback,
                        rocketMQTemplate.getProducer().getSendMsgTimeout(), delayLevel);
            } else {
                rocketMQTemplate.asyncSend(outboxPO.getDestination(), outboxPO.getPayload(), callback);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // asyncSend / send 同步抛错（如 broker 不可达）
            handleSendFailure(outboxPO, e);
        }
    }

    /**
     * 定时消息（毫秒级相对延迟）：{@link RocketMQTemplate} 的毫秒级延迟只有同步重载（票 01 实测），
     * 同步发会把 afterCommit 线程和标记队列堵在 send timeout 上，所以只能下探 producer 自己发。
     * <p>
     * 报文构造照抄模板的转换规则（String 载荷取原字节、destination 按 {@code topic:tag} 切、
     * charset 取模板配置），否则同一条消息换个延迟方式就换了字节内容。
     */
    private void sendTimer(OutboxPO outboxPO, int delayMs, SendCallback callback) throws Exception {
        String[] topicAndTags = outboxPO.getDestination().split(":", 2);
        Charset charset = Charset.forName(rocketMQTemplate.getCharset());
        org.apache.rocketmq.common.message.Message message = new org.apache.rocketmq.common.message.Message(
                topicAndTags[0],
                topicAndTags.length > 1 ? topicAndTags[1] : "",
                outboxPO.getPayload().getBytes(charset));
        message.setDelayTimeMs(delayMs);
        rocketMQTemplate.getProducer().send(message, callback, rocketMQTemplate.getProducer().getSendMsgTimeout());
    }

    private void handleSendFailure(OutboxPO outboxPO, Throwable throwable) {
        log.warn("outbox 消息发送失败 msgId={}, id={}, retryCount={}",
                outboxPO.getMsgId(), outboxPO.getId(), outboxPO.getRetryCount(), throwable);
        MarkTask task = outboxPO.getRetryCount() + 1 >= MAX_RETRY_COUNT
                ? MarkTask.dead(outboxPO.getId())
                : MarkTask.retry(outboxPO.getId());
        markQueue.offer(task);
    }

    /** 专用标记线程：阻塞等待任务，drainTo 攒批后按动作分组批量 update */
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
                // 批量标记失败不重试入队：记录保持 PENDING，由扫描兜底重发
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
     * 兜底扫描（XXL-Job 调度）：重发**超时未发出**的 PENDING 消息，覆盖事务提交后应用崩溃、
     * broker 宕机恢复等场景。单实例部署即可；多实例需加扫描锁，重复发送由消费端按业务 id 幂等兜底。
     * <p>
     * 重发时带 {@code delay_ms} 的行**不再定时**（{@code delayExpired = true}）：能走到这里说明这行已经
     * 躺了 scanTimeoutSeconds（默认 60s）没发出去，它想赶的那个时刻早过期了。再按 {@code delay_ms} 定时一次，
     * 等于把「补发」变成「再等 2 秒」——兜底越跑越追不上。
     * {@code delay_level} 行不受影响：那是 1 秒档，重发再等 1 秒无伤，且「级别随消息持久化」正是那一列的
     * 存在理由（ADR-0011）。
     */
    @XxlJob("outboxScan")
    public String scanPending(String param) {
        List<OutboxPO> pendingList = outboxDao.selectPendingList(scanTimeoutSeconds, SCAN_BATCH_SIZE);
        if (!pendingList.isEmpty()) {
            log.info("outbox 扫描到 {} 条超时未发送消息（timeout={}s）", pendingList.size(), scanTimeoutSeconds);
            pendingList.forEach(outboxPO -> dispatch(outboxPO, true));
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
