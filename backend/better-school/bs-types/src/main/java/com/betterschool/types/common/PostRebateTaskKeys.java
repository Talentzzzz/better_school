package com.betterschool.types.common;

/**
 * 发帖返利入账任务的 outbox 幂等键（每个返利订单行一条）。
 * <p>
 * 用确定性 {@code msg_id} 而不是随机 UUID，是为了让同一个订单行的重复落库撞上
 * {@code uk_msg_id}，不会产生第二条任务行、重复投递消息。
 * <p>
 * 长度受 {@code mq_outbox.msg_id VARCHAR(64)} 约束：入账任务前缀 19 + UUID 36 = 55。用订单行
 * {@code order_id} 而不是 {@code postId:rebateType}——后者拼上 {@code DRAW_COUNT} 会超过 64 位。
 * <p>
 * 领取任务不再走 MQ：它是域内最终一致任务，落在 {@code post_rebate_claim_task}（见 ADR-0017）。
 */
public final class PostRebateTaskKeys {

    private static final String CREDIT_PREFIX = "post-rebate-credit:";

    private PostRebateTaskKeys() {
    }

    /** 入账任务 msgId：{@code post-rebate-credit:{orderId}} */
    public static String creditOf(String orderId) {
        return CREDIT_PREFIX + orderId;
    }
}
