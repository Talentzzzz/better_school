package com.betterschool.types.rebate;

/**
 * 行为返利领取的状态：一次领取尝试的全部结果 + 领取行的生命周期。
 * <p>
 * 五种取值分两类：
 * <ul>
 *   <li>落库状态（{@link #persisted()} 为 {@code true}）：{@code CLAIMED} / {@code GRANTED}，
 *       对应 {@code action_rebate_order.state} 的取值；</li>
 *   <li>仅 API 结果：{@code DENIED} / {@code NO_REBATE} / {@code CLAIM_ERROR}，不建领取行、也不占当天名额。</li>
 * </ul>
 * 对外响应统一取 {@link #apiState()}：{@code GRANTED} 折叠成 {@code CLAIMED}——对用户而言
 * 「已入账」和「刚领到」都是这次行为领到了，金额照常展示。
 */
public enum RebateClaimStateEnum {

    /** 已抢占名额、金额快照已定，等待积分/抽奖次数入账 */
    CLAIMED,

    /** 积分与抽奖次数均已入账完成 */
    GRANTED,

    /** 当天名额已被该用户的其他帖子领取（日限命中） */
    DENIED,

    /** 无开放规则或快照金额为 0，不占名额 */
    NO_REBATE,

    /**
     * 同步领取抛异常：本次结果未知（不是「领取失败」），领取任务留待 outboxScan 重放，
     * 重放按 ref 幂等，命中已有订单行时返回 {@code CLAIMED}。异常态，不是正常的中间态。
     */
    CLAIM_ERROR;

    /** 是否可落库：只有 CLAIMED / GRANTED 能写进 {@code action_rebate_order.state} */
    public boolean persisted() {
        return this == CLAIMED || this == GRANTED;
    }

    /** 对外响应取值：GRANTED 折叠成 CLAIMED，其余原样 */
    public RebateClaimStateEnum apiState() {
        return this == GRANTED ? CLAIMED : this;
    }
}
