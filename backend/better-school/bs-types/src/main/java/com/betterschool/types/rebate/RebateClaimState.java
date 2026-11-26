package com.betterschool.types.rebate;

/**
 * 行为返利领取记录的持久化状态（{@code action_rebate_claim.state} 的取值）。
 * <p>
 * 只允许下两个值：{@code DENIED} / {@code NO_REBATE} / {@code PENDING} 是 API 侧的结果，
 * 不落库、也不占名额（见 {@link RebateClaimApiState}）。
 */
public enum RebateClaimState {

    /** 已抢占名额、金额快照已定，等待积分/抽奖次数入账 */
    CLAIMED,

    /** 积分与抽奖次数均已入账完成 */
    GRANTED
}
