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
    private Integer state;
    private Integer retryCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
