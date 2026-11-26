package com.betterschool.domain.rebate.model.entity;

import com.betterschool.types.rebate.RebateClaimState;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行为返利领取记录（action_rebate_claim 一行）：一次行为（{@code refId}）抢占一次限次名额，
 * 并把领取时的返利值快照固定下来。
 * <p>
 * {@code uk (user_id, action_type, ref_id)} 保证同一次行为只领取一次（重放幂等）；
 * {@code uk (user_id, action_type, period_key)} 保证同人同行为同周期只领取一次（日限）。
 * {@code state} 只落 {@link RebateClaimState} 的两个持久化取值，{@code DENIED} / {@code NO_REBATE} /
 * {@code PENDING} 是 API 结果，不建行、不占名额。
 */
@Data
public class RebateClaimEntity {

    /** 领取记录业务 ID（UUID，与 {@code uk_claim_id} 对应） */
    private String claimId;
    private String userId;
    /** 行为类型：sign(签到) / pay(支付) / post(发帖) */
    private String actionType;
    /** 限次周期键（日限 yyyyMMdd，Asia/Shanghai，由发帖事务定死） */
    private String periodKey;
    /** 本次行为 ID（发帖 = postId） */
    private String refId;
    /** 领取时快照的积分值 */
    private int pointsValue;
    /** 领取时快照的抽奖次数 */
    private int drawCountValue;
    /** 持久化状态：CLAIMED / GRANTED */
    private RebateClaimState state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
