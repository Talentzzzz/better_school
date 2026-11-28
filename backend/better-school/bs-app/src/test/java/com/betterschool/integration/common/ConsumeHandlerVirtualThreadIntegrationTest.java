package com.betterschool.integration.common;

import com.betterschool.integration.support.DrawApiTestBase;
import com.betterschool.integration.support.VtConsumeThreadProbe;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPushConsumerImpl;
import org.apache.rocketmq.client.impl.consumer.ProcessQueue;
import org.apache.rocketmq.client.impl.consumer.VirtualThreadConsumeMessageConcurrentlyService;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 消费 seam 断言（spec §5.2 #2，票 14）。两条防线各管一面：
 * <ul>
 *   <li>监听器内 {@code isVirtual()}：消费工作线程确实是虚拟线程（防接线被回滚）；</li>
 *   <li>全部容器都换上了 VT 服务 + {@code pullThresholdForQueue} 已设值（防漏配某个消费者）。</li>
 * </ul>
 * 断言对象是线程模型，不看业务结果——探针消费者订阅独立 topic，测试直接投递给它的消费服务。
 * <p>
 * 父类是 {@link DrawApiTestBase}：它拉起的完整上下文（全部消费者容器 + 域服务）才是
 * 「每个容器都换上了 VT 服务」这条断言要看的对象；PointsApiTestBase 保留，但 VT 两个类只跑一次。
 */
@DisplayName("消费 seam：消费者的工作线程为虚拟线程 + 流控旋钮已设值")
class ConsumeHandlerVirtualThreadIntegrationTest extends DrawApiTestBase {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    @DisplayName("消费监听器跑在虚拟线程上：防消费 executor 接线被回滚")
    void consumeHandlerRunsOnVirtualThread() throws Exception {
        VtConsumeThreadProbe.reset();

        DefaultMQPushConsumerImpl consumerImpl = consumeServiceImplOf(VtConsumeThreadProbe.CONSUMER_GROUP);
        MessageQueue messageQueue = new MessageQueue(VtConsumeThreadProbe.TOPIC, "broker-a", 0);
        MessageExt message = new MessageExt();
        message.setTopic(VtConsumeThreadProbe.TOPIC);
        message.setBrokerName("broker-a");
        message.setQueueId(0);
        message.setQueueOffset(0L);
        message.setBody("{\"probe\":true}".getBytes(StandardCharsets.UTF_8));

        consumerImpl.getConsumeMessageService()
                .submitConsumeRequest(List.of(message), new ProcessQueue(), messageQueue, true);

        assertThat(VtConsumeThreadProbe.awaitHandled(5000)).as("监听器应在 5s 内被调用").isTrue();
        assertThat(VtConsumeThreadProbe.lastWasVirtual())
                .as("消费工作线程应为虚拟线程——此断言红即消费线程模型接线被回滚")
                .isTrue();
    }

    @Test
    @DisplayName("每个消费者的消费服务都已换成 VT 版、pullThresholdForQueue 已按下游容量设值")
    void everyConsumerIsSwitchedWithThresholdConfigured() {
        var containers = applicationContext.getBeansOfType(DefaultRocketMQListenerContainer.class);
        assertThat(containers).as("应与生产一致地注册了全部消费者容器").hasSizeGreaterThanOrEqualTo(2);

        containers.values().forEach(container -> {
            DefaultMQPushConsumer consumer = container.getConsumer();
            assertThat(consumer.getDefaultMQPushConsumerImpl().getConsumeMessageService())
                    .as("消费者 %s 的消费服务应是 VT 版", container.getConsumerGroup())
                    .isInstanceOf(VirtualThreadConsumeMessageConcurrentlyService.class);
            assertThat(consumer.getPullThresholdForQueue())
                    .as("消费者 %s 的 pullThresholdForQueue 应已设值（不等于 client 默认 1000）",
                            container.getConsumerGroup())
                    .isPositive()
                    .isLessThan(1000);
        });
    }

    private DefaultMQPushConsumerImpl consumeServiceImplOf(String consumerGroup) {
        return applicationContext.getBeansOfType(DefaultRocketMQListenerContainer.class)
                .values()
                .stream()
                .filter(container -> consumerGroup.equals(container.getConsumerGroup()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到消费者组容器：" + consumerGroup))
                .getConsumer()
                .getDefaultMQPushConsumerImpl();
    }
}
