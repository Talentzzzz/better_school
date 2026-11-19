package com.betterschool.marketing.types.raffle;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 购买单状态机（ADR-0014）：只有三态，刻意没有 FAILED（正向重试必收敛，永不回滚）
 * 也没有 PROCESSING（并发抢占靠 {@code UPDATE ... WHERE state=0} 条件更新解决）。
 * <p>
 * `DEDUCTED` 与扣积分**同一事务**落库——单存在即积分一定已扣，所以补偿重试
 * 永远只做「次数入账 + 置 DONE」，从不碰扣分；重试耗尽进 {@code DEAD} 等人工介入
 * （置回 DEDUCTED 即重新可扫）。
 */
@Getter
@AllArgsConstructor
public enum DrawCountPurchaseState {

    /** 已扣分待入账（与扣分同事务落库的初始态） */
    DEDUCTED(0),

    /** 已完成（次数入账成功） */
    DONE(1),

    /** 死信：补偿重试耗尽，人工介入后置回 DEDUCTED 重新可扫 */
    DEAD(2);

    private final int code;

    public static DrawCountPurchaseState of(int code) {
        for (DrawCountPurchaseState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知购买单状态 code=" + code);
    }
}
