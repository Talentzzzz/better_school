package com.betterschool.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.MessageListener;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.impl.consumer.ConsumeMessageService;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl;
import org.apache.rocketmq.client.impl.consumer.VirtualThreadConsumeMessageConcurrentlyService;
import org.apache.rocketmq.spring.autoconfigure.RocketMQProperties;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.apache.rocketmq.spring.support.RocketMQMessageConverter;
import org.apache.rocketmq.spring.support.RocketMQMessageListenerContainerRegistrar;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Map;

/**
 * 把消费线程池整体换成虚拟线程的容器注册器（virtual-thread spec §4.1，票 14）。
 *
 * <p>为什么从这里接线：官方低成本口子不存在——client 5.3.2 的消费 executor 是
 * {@code ConsumeMessageConcurrentlyService} 构造器里 new 的 {@code private final} 字段，容器只递
 * {@code consumeThreadMin/Max} 两个 int 进去，{@code setConsumeExecutor} 只在未发布的 develop 分支。
 * 容器注册器带 {@code @ConditionalOnMissingBean}，可整体接管（见 {@code RocketMQConsumerConfig}）。
 *
 * <p>为什么换在 {@code super.startContainer()} 之后：默认 {@code ConsumeMessageService} 由
 * {@code DefaultMQPushConsumerImpl.start()} 内部 new，{@code start()} 之后才存在；因此替换时**必须**
 * 把被换下的旧服务 {@code shutdown} 掉，否则它那两条 {@code ScheduledExecutorService}
 * （CleanExpire / 重投）无人回收。
 */
@Slf4j
public class VirtualThreadRocketMQListenerContainerRegistrar extends RocketMQMessageListenerContainerRegistrar {

    /**
     * 每个 topic 的消息队列数 —— broker 未配 {@code defaultTopicQueueNums} ⇒ 默认 4
     * （本机 topicRoute 实测 {@code readQueueNums=4}）。{@code pullThresholdForQueue} 是**每队列**上限，
     * 单实例消费者拿走全部队列 ⇒ 在飞窗口 = 队列数 × N。
     */
    private static final int QUEUE_COUNT = 4;

    /** 下游容量（可同时处理的并发数），按消费者组的**下游资源拥有者**取值 */
    private static final Map<String, Integer> DOWNSTREAM_CAPACITY = Map.of();

    /** 默认下游容量 = Hikari 连接池（spec §2.3 显式化 {@code maximum-pool-size: 10}）——池是 DB 侧唯一真闸 */
    private static final int DEFAULT_DOWNSTREAM_CAPACITY = 10;

    private ApplicationContext applicationContext;

    public VirtualThreadRocketMQListenerContainerRegistrar(RocketMQMessageConverter rocketMQMessageConverter,
                                                           ConfigurableEnvironment environment,
                                                           RocketMQProperties rocketMQProperties) {
        super(rocketMQMessageConverter, environment, rocketMQProperties);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        super.setApplicationContext(applicationContext);
        this.applicationContext = applicationContext;
    }

    @Override
    public void startContainer() {
        super.startContainer();
        applicationContext.getBeansOfType(DefaultRocketMQListenerContainer.class)
                .values()
                .forEach(this::switchToVirtualThreadConsumeService);
    }

    private void switchToVirtualThreadConsumeService(DefaultRocketMQListenerContainer container) {
        DefaultMQPushConsumer consumer = container.getConsumer();
        DefaultMQPushConsumerImpl consumerImpl = consumer.getDefaultMQPushConsumerImpl();
        MessageListener messageListener = consumer.getMessageListener();
        if (!(messageListener instanceof MessageListenerConcurrently)) {
            // ORDERLY 的 listener 跑在 MessageQueueLock 的 monitor 里、JDK 21 上会 pin 载体线程，
            // 且「单队列串行」与抬并发方向相反 ⇒ 本 effort 排除（spec §4.1）；真加了就得先裁决。
            throw new IllegalStateException("消费线程模型只覆盖 CONCURRENTLY，遇到 ORDERLY 消费者："
                    + container.getConsumerGroup());
        }

        ConsumeMessageService previous = consumerImpl.getConsumeMessageService();
        VirtualThreadConsumeMessageConcurrentlyService virtualThreadService =
                new VirtualThreadConsumeMessageConcurrentlyService(consumerImpl,
                        (MessageListenerConcurrently) messageListener,
                        "mq-consume-" + container.getConsumerGroup() + "-");
        virtualThreadService.start();
        consumerImpl.setConsumeMessageService(virtualThreadService);
        // 先装后关：反过来会出现「旧服务已关、新服务还没挂上」的窗口，此时到达的 pull 回调投进已关闭的
        // 平台池 → 拒绝 → 重投（重投线程也关着）→ 异常冒到 pull 路径
        previous.shutdown(consumer.getAwaitTerminationMillisWhenShutdown());

        consumer.setPullThresholdForQueue(pullThresholdForQueue(container.getConsumerGroup()));
        log.info("[VirtualThreadConsume] 消费线程模型已切换为虚拟线程 group={} pullThresholdForQueue={}",
                container.getConsumerGroup(), consumer.getPullThresholdForQueue());
    }

    /** N = ⌈下游容量 / 队列数⌉：单实例消费者独占全部队列，N 是每队列上限 */
    private static int pullThresholdForQueue(String consumerGroup) {
        int capacity = DOWNSTREAM_CAPACITY.getOrDefault(consumerGroup, DEFAULT_DOWNSTREAM_CAPACITY);
        return (capacity + QUEUE_COUNT - 1) / QUEUE_COUNT;
    }
}
