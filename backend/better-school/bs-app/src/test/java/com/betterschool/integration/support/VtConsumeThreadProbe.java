package com.betterschool.integration.support;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 消费 seam 断言的测试探针（spec §5.2 #2，票 14）：一个只存在于 test classpath 的消费者，
 * 被容器注册器按与生产消费者**完全相同**的路径接上消费线程模型，监听器里记下工作线程是不是虚拟线程。
 *
 * <p>防的是消费接线被回滚（spec §5.3）：断言红 = 消费又跑在 {@code ConsumeMessageThread_*} 平台线程上。
 * 它订阅独立 topic，测试直接向它的消费服务投递消息，不依赖 broker 投递时序；生产侧零影响。
 */
@Component
@RocketMQMessageListener(topic = VtConsumeThreadProbe.TOPIC, consumerGroup = VtConsumeThreadProbe.CONSUMER_GROUP)
public class VtConsumeThreadProbe implements RocketMQListener<String> {

    public static final String TOPIC = "bs-vt-consume-probe";
    public static final String CONSUMER_GROUP = "bs-vt-consume-probe-group";

    private static final AtomicReference<Boolean> LAST_WAS_VIRTUAL = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> HANDLED = new AtomicReference<>(new CountDownLatch(1));

    @Override
    public void onMessage(String payload) {
        LAST_WAS_VIRTUAL.set(Thread.currentThread().isVirtual());
        HANDLED.get().countDown();
    }

    public static void reset() {
        LAST_WAS_VIRTUAL.set(null);
        HANDLED.set(new CountDownLatch(1));
    }

    /** 自上次 {@link #reset()} 起监听器是否被调用过（等消费是异步的） */
    public static boolean awaitHandled(long millis) throws InterruptedException {
        return HANDLED.get().await(millis, TimeUnit.MILLISECONDS);
    }

    /** 最近一次监听器调用所在线程是否为虚拟线程；未调用过时为 null */
    public static Boolean lastWasVirtual() {
        return LAST_WAS_VIRTUAL.get();
    }
}
