package org.apache.rocketmq.client.impl.consumer;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * 并发消费的虚拟线程版 {@link ConsumeMessageConcurrentlyService}（virtual-thread spec §4.1，票 14）。
 *
 * <p>client 5.3.2 的消费线程池是 {@code private final ThreadPoolExecutor}，反射与 setter 都够不着
 * （JDK 21 对 final 实例字段 {@code Field.set} 抛 {@code IllegalAccessException}），而官方口子
 * {@code setConsumeExecutor} 只进了 develop 未进发布版 ⇒ 只能整体换服务。放在**同包**是为了够到包私有的
 * {@code ConsumeRequest}（其 {@code run()} 串起了 ack / 重试 / DLQ / 统计 / hook 全部语义）。
 *
 * <p><b>语义继承</b>：{@code processConsumeResult}、ack 时机、重试阶梯（broker 侧
 * {@code delayLevel = 3 + reconsumeTimes}）、{@code %DLQ%}、15min {@code consumeTimeout} 阀门全部原样
 * 走父类 {@code ConsumeRequest}；本类只改「任务投给谁」。base 的平台池懒创建（{@code ThreadPoolExecutor}
 * 不 prestart）⇒ 不投它就一条 {@code ConsumeMessageThread_*} 都不产生。
 *
 * <p><b>与父类原实现的两处差异</b>（都在提交侧，与上面的语义无关）：① 提交目标换成无界 VT executor；
 * ② 批切分循环逐行照抄，但循环内被拒绝时的退路换成「记日志交给 broker 重投」——父类那条路是 private 的
 * {@code submitConsumeRequestLater(ConsumeRequest)}，且内部硬投回 base 池，够不着也用不上（见
 * {@link #submit}）。本仓 {@code consumeMessageBatchMaxSize = 1} 走的是单条分支，批切分分支不在路径上。
 *
 * <p><b>并发上限</b>：无界 + 不加信号量；上限由 client 自己的 {@code pullThresholdForQueue} 表达
 * （落地处：{@code VirtualThreadRocketMQListenerContainerRegistrar}）。{@code getCorePoolSize()} 之类
 * 诊断读数仍指父类那个空转的平台池——spec §4.1 已把 {@code consumeThreadNumber} 记为死参数，不另改。
 */
@Slf4j
public class VirtualThreadConsumeMessageConcurrentlyService extends ConsumeMessageConcurrentlyService {

    private final DefaultMQPushConsumerImpl consumerImpl;

    private final ExecutorService virtualThreadExecutor;

    /**
     * @param threadNamePrefix VT 命名前缀（spec §5.1：自己 new 的 VT 一律命名），后接自增序号
     */
    public VirtualThreadConsumeMessageConcurrentlyService(DefaultMQPushConsumerImpl consumerImpl,
                                                         MessageListenerConcurrently messageListener,
                                                         String threadNamePrefix) {
        super(consumerImpl, messageListener);
        this.consumerImpl = consumerImpl;
        this.virtualThreadExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name(threadNamePrefix, 0).factory());
    }

    @Override
    public void submitConsumeRequest(List<MessageExt> msgs, ProcessQueue processQueue, MessageQueue messageQueue,
                                     boolean dispatchToConsume) {
        final int consumeBatchSize = this.consumerImpl.getDefaultMQPushConsumer().getConsumeMessageBatchMaxSize();
        if (msgs.size() <= consumeBatchSize) {
            submit(new ConsumeRequest(msgs, processQueue, messageQueue));
            return;
        }
        for (int total = 0; total < msgs.size(); ) {
            List<MessageExt> msgThis = new ArrayList<>(consumeBatchSize);
            for (int i = 0; i < consumeBatchSize; i++, total++) {
                if (total < msgs.size()) {
                    msgThis.add(msgs.get(total));
                } else {
                    break;
                }
            }
            submit(new ConsumeRequest(msgThis, processQueue, messageQueue));
        }
    }

    /**
     * 无界 VT executor 只在 {@link #shutdown} 之后拒绝 ⇒ 本分支=停机窗口。消息不 ack、由 broker 重投，
     * 与父类在停机时「重投线程也已关闭」的效果一致，故不再复刻那条私有退路。
     */
    private void submit(ConsumeRequest consumeRequest) {
        try {
            this.virtualThreadExecutor.submit(consumeRequest);
        } catch (RejectedExecutionException e) {
            log.warn("[VirtualThreadConsume] 停机中拒绝消费请求，交 broker 重投。group={}",
                    this.consumerImpl.getDefaultMQPushConsumer().getConsumerGroup());
        }
    }

    @Override
    public void shutdown(long awaitTerminateMillis) {
        super.shutdown(awaitTerminateMillis);
        this.virtualThreadExecutor.shutdown();
        try {
            this.virtualThreadExecutor.awaitTermination(awaitTerminateMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
