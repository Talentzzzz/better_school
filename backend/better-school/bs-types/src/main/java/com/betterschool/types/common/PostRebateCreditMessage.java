package com.betterschool.types.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发帖返利入账任务消息（topic {@code bs-post-rebate-credit}）：一个返利订单行一条。
 * <p>
 * 领取事务内以 {@code PENDING} 落 outbox，提交后同步发送；msgId 确定（{@link PostRebateTaskKeys#creditOf}），
 * 同一订单行重复落库撞 {@code mq_outbox.uk_msg_id}，不会产生第二条入账消息。
 * <p>
 * {@code rebateType} 决定入哪个桶（POINTS → 积分桶，DRAW_COUNT → 抽奖次数桶）；
 * {@code rebateValue} 是领取时的规则快照，消费端直接按它入账、不再读规则。快照为 0 的订单行
 * 不占用日限以外的任何资源，落库即 GRANTED、不发消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostRebateCreditMessage {

    private String orderId;
    private String userId;
    private String postId;
    /** 返利类型：POINTS / DRAW_COUNT（{@code RebateTypeEnum#name()}） */
    private String rebateType;
    /** 领取时快照的返利值 */
    private int rebateValue;
}
