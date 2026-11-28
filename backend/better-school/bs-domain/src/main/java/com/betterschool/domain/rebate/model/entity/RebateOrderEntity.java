package com.betterschool.domain.rebate.model.entity;

import com.betterschool.domain.rebate.model.valobj.RebateTypeEnum;
import com.betterschool.types.rebate.RebateClaimStateEnum;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行为返利订单（action_rebate_order 一行）：一次行为（{@code refId}）在某个返利类型上抢占一次限次名额，
 * 并把领取时的返利值快照固定下来。
 * <p>
 * 一次领取按返利类型拆行：POINTS 一行、DRAW_COUNT 一行；快照为 0 的行只占该类型的日限名额，
 * 落库即 GRANTED、不发入账消息。
 * <p>
 * {@code uk (user_id, action_type, ref_type, ref_id, rebate_type)} 保证同一次行为的同一返利类型只领取一次（重放幂等）；
 * {@code uk (user_id, action_type, period_key, rebate_type)} 保证同人同行为同周期同类型只领取一次（日限）。
 * {@code rebateClaimState} 只落 {@link RebateClaimStateEnum#persisted()} 为 {@code true} 的取值。
 */
@Data
public class RebateOrderEntity {

    /** 订单业务 ID（UUID，与 {@code uk_order_id} 对应） */
    private String orderId;
    private String userId;
    /** 行为类型：sign(签到) / pay(支付) / post(发帖) */
    private String actionType;
    /** 限次周期键（日限 yyyyMMdd，Asia/Shanghai，由发帖事务定死） */
    private String periodKey;
    /** 行为对象类型：post(帖子) 等，说明 {@code refId} 是谁的 ID */
    private String refType;
    /** 行为对象 ID（发帖 = postId） */
    private String refId;
    /** 返利类型：POINTS / DRAW_COUNT */
    private RebateTypeEnum rebateType;
    /** 领取时快照的返利值 */
    private int rebateValue;
    /** 持久化状态：CLAIMED / GRANTED */
    private RebateClaimStateEnum rebateClaimState;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
