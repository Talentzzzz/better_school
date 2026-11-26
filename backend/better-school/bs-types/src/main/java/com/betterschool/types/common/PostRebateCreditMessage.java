package com.betterschool.types.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发帖返利入账任务消息（topic {@code bs-post-rebate-credit}）。
 * <p>
 * 领取事务内以 {@code PENDING} 落一行 outbox，提交后同步发送；msgId 确定（{@link PostRebateTaskKeys#creditOf}），
 * 重复投递撞 {@code mq_outbox.uk_msg_id}，不会产生第二条入账消息。
 * <p>
 * 金额是领取时的规则快照，消费端直接按它入账、不再读规则；{@code pointsValue} / {@code drawCountValue}
 * 为 0 表示该种返利不发，跳过对应桶。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostRebateCreditMessage {

    private String claimId;
    private String userId;
    private String postId;
    private int pointsValue;
    private int drawCountValue;
}
