package com.betterschool.integration.consume;

import com.betterschool.integration.support.CommunityApiTestBase;
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
 */
@DisplayName("消费 seam：12 个消费者的工作线程为虚拟线程 + 流控旋钮已设值")
class ConsumeHandlerVirtualThreadIntegrationTest extends CommunityApiTestBase {

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
        // 容器清单 = 11 个生产消费者（票 02/03/04 重构各下线对应域消费者、票 09 下线成团消费者；
        // 内嵌化票 03 下线商品支付超时消费者 bs-product-pay-timeout；
        // hot-row-merge 票 04 下线 bs-post-count-group / bs-product-count-group——浏览计数改走进程内合并）
        // + 测试探针 1 个；
        // 任一消费者容器漏注册（或接线时漏接）都会让本断言红
        assertThat(containers).as("应与生产一致地注册了全部消费者容器").hasSizeGreaterThanOrEqualTo(12);

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

    @Test
    @DisplayName("pullThresholdForQueue 按下游容量定：消费者下游都是 DB，按连接池取值")
    void pullThresholdIsAnchoredOnDownstreamCapacity() {
        // 消费者的下游是 DB，资源拥有者是 Hikari 池（maximum-pool-size: 10）⇒ N = ⌈10 / 4⌉：
        // 12 略高于池，保证单个消费者单独运行时也能把池打满
        assertThat(thresholdOf("bs-comment-review-group"))
                .as("DB 绑定消费者按连接池 10 定")
                .isEqualTo(3);
    }

    private int thresholdOf(String consumerGroup) {
        return consumeServiceImplOf(consumerGroup).getDefaultMQPushConsumer().getPullThresholdForQueue();
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
