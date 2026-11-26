package com.betterschool.types.common;

/**
 * 发帖返利任务的 outbox 幂等键：一条帖子恒对应一条任务行。
 * <p>
 * 用确定性 {@code msg_id} 而不是随机 UUID，是为了让同一条帖子的重复落库撞上 {@code uk_msg_id}，
 * 不会产生第二条任务行、重复投递返利消息。
 * <p>
 * 长度受 {@code mq_outbox.msg_id VARCHAR(64)} 约束：前缀 12 + UUID 36 = 48。
 */
public final class PostRebateTaskKeys {

    private static final String PREFIX = "post-rebate:";

    private PostRebateTaskKeys() {
    }

    public static String of(String postId) {
        return PREFIX + postId;
    }
}
