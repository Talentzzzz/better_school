package com.betterschool.marketing.types.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * outbox 消息状态（形状照单体）：0 待发送 / 1 已发送 / 2 发送失败（重试耗尽 = 死信）
 */
@Getter
@AllArgsConstructor
public enum OutboxStatus {

    PENDING(0),
    SENT(1),
    DEAD(2);

    private final int code;
}
