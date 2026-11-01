package com.betterschool.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * outbox 消息状态
 */
@Getter
@AllArgsConstructor
public enum OutboxStatus {

    PENDING(0),
    SENT(1),
    DEAD(2);

    private final int code;
}
