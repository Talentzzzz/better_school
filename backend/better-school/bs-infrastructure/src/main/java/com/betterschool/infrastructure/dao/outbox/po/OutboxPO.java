package com.betterschool.infrastructure.dao.outbox.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OutboxPO {

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
}
