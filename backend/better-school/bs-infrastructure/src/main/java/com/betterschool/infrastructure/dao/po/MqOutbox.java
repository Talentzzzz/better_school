package com.betterschool.infrastructure.dao.po;

import com.betterschool.types.enums.OutboxStatusEnum;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
public class MqOutbox {

    /** 发送失败累计到该次数即置死信（state=2），仓储的首发与扫描重发共用同一阈值 */
    public static final int MAX_RETRY_COUNT = 5;

    private Long id;
    private String msgId;
    /** 消息目的地：topic 或 topic:tag（destination 语法，由发送端解析） */
    private String destination;
    private String payload;
    /** RocketMQ 延迟等级：0=不延迟（默认），1=1 秒（库存合并唤起用） */
    private Integer delayLevel;
    /**
     * 毫秒级相对延迟（RocketMQ 定时消息）：非空时优先于 {@link #delayLevel}，且落库时 delay_level 恒 0。
     * 与延迟等级的差别是精度——等级只有固定档位（1s / 5s / ...），定时消息能给任意毫秒。
     */
    private Integer delayMs;
    private Integer state;
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 新的待发送行（随机 msgId）：与业务行同事务 insert，提交后由仓储同步发送 */
    public static MqOutbox pending(String destination, Object payload, ObjectMapper objectMapper) {
        return pending(UUID.randomUUID().toString(), destination, payload, objectMapper);
    }

    /** 新的待发送行（确定性 msgId）：同一业务动作重复落库会被 uk_msg_id 挡住，不会产生第二行 */
    public static MqOutbox pending(String msgId, String destination, Object payload, ObjectMapper objectMapper) {
        MqOutbox mqOutbox = new MqOutbox();
        mqOutbox.setMsgId(msgId);
        mqOutbox.setDestination(destination);
        mqOutbox.setPayload(toJson(payload, objectMapper));
        mqOutbox.setDelayLevel(0);
        mqOutbox.setState(OutboxStatusEnum.PENDING.getCode());
        mqOutbox.setRetryCount(0);
        LocalDateTime now = LocalDateTime.now();
        mqOutbox.setCreateTime(now);
        mqOutbox.setUpdateTime(now);
        return mqOutbox;
    }

    private static String toJson(Object payload, ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox 消息序列化失败", e);
        }
    }
}
