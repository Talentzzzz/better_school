package com.betterschool.types.rebate;

/**
 * 发帖响应里的返利状态（{@code PostPublishResponseDTO.rebateState} 的取值）。
 * <p>
 * 与持久化状态 {@link RebateClaimState} 区分：只有 {@code CLAIMED} 对应落库的领取行，
 * 其余三种都不占当天名额。{@code CLAIMED} / {@code DENIED} / {@code NO_REBATE} 的两个金额
 * 分别为快照值 / 0 / 0；{@code PENDING} 的金额恒 0（等待重放，不能向用户承诺具体数字）。
 */
public enum RebateClaimApiState {

    /** 本次发帖抢到当天名额，金额为领取快照值 */
    CLAIMED,

    /** 当天名额已被该用户的其他帖子领取（日限命中） */
    DENIED,

    /** 无开放规则或快照金额为 0，不占名额 */
    NO_REBATE,

    /** 同步领取异常，领取任务留 PENDING 等待重放 */
    PENDING
}
