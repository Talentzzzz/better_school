package com.betterschool.loadtest.inventory.mq;

import com.betterschool.loadtest.inventory.config.InventoryProperties;
import com.betterschool.loadtest.inventory.metrics.RunStats;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 两类消息共用一个 topic，用 tag 分流：
 * <ul>
 *   <li>deduct —— 逐条扣减（数量 = 请求量）</li>
 *   <li>merge  —— 合并唤醒（数量 ≈ 桶数 × 1/秒）</li>
 * </ul>
 */
@Component
public class InventoryMqProducer {

    public static final String TOPIC = "lt-inventory-deduct";
    public static final String TAG_DEDUCT = "deduct";
    public static final String TAG_MERGE = "merge";

    private static final long SEND_TIMEOUT_MS = 3000L;

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final InventoryProperties properties;
    private final RunStats stats;

    public InventoryMqProducer(RocketMQTemplate rocketMQTemplate, ObjectMapper objectMapper,
                               InventoryProperties properties, RunStats stats) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.stats = stats;
    }

    /** 同步发送：发送失败时调用方负责回补 Redis 余量，保证「redis 余额 == 台账 + 在途」可对账 */
    public void sendDeduct(DeductMessage message) {
        String dest = TOPIC + ":" + TAG_DEDUCT;
        rocketMQTemplate.syncSend(dest, toJson(message), SEND_TIMEOUT_MS);
        stats.inc("mq_sent:deduct");
    }

    /** 延迟消息：delayLevel 1 = 1s，就是「时间格节拍」的来源 */
    public void sendMergeDelay(MergeMessage message) {
        String dest = TOPIC + ":" + TAG_MERGE;
        rocketMQTemplate.syncSend(dest, MessageBuilder.withPayload(toJson(message)).build(),
                SEND_TIMEOUT_MS, properties.getMerge().getDelayLevel());
        stats.inc("mq_sent:merge");
    }

    private String toJson(Object message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("消息序列化失败", e);
        }
    }
}
