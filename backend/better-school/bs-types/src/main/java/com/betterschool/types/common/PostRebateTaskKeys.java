package com.betterschool.types.common;

/**
 * 发帖返利任务的 outbox 幂等键：两段式各一条确定性任务行，一条帖子恒对应一条领取任务 + 一条入账任务。
 * <p>
 * 用确定性 {@code msg_id} 而不是随机 UUID，是为了让同一条帖子的重复落库撞上 {@code uk_msg_id}，
 * 不会产生第二条任务行、重复投递消息。
 * <p>
 * 长度受 {@code mq_outbox.msg_id VARCHAR(64)} 约束：
 * 领取任务前缀 18 + UUID 36 = 54；入账任务前缀 19 + UUID 36 = 55。
 */
public final class PostRebateTaskKeys {

    private static final String CLAIM_PREFIX = "post-rebate-claim:";

    private static final String CREDIT_PREFIX = "post-rebate-credit:";

    private PostRebateTaskKeys() {
    }

    /** 领取任务 msgId：{@code post-rebate-claim:{postId}} */
    public static String claimOf(String postId) {
        return CLAIM_PREFIX + postId;
    }

    /** 入账任务 msgId：{@code post-rebate-credit:{postId}} */
    public static String creditOf(String postId) {
        return CREDIT_PREFIX + postId;
    }
}
