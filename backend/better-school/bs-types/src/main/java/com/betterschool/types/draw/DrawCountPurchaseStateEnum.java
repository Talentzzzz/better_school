package com.betterschool.types.draw;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 购买单状态机（ADR-0014 表形态保留，ADR-0016 票 C 修正段落顺序）：只有三态，
 * 刻意没有 FAILED（正向重试必收敛，永不回滚；唯一像失败的是积分不足——业务永久失败
 * 直接进 {@code DEAD}，充值后带原 token 重放可自愈）也没有 PROCESSING（并发抢占靠
 * {@code UPDATE ... WHERE state=0} 条件更新解决）。
 * <p>
 * ADR-0016 起 INSERT 购买单与扣积分**不再是同一物理事务**：{@code CREATED} 只意味着
 * 「扣分结果未知」——无扣分流水 = 从未扣过（重试补扣）；有流水而未 DONE = 只需推进。
 * 两条路径的重放被幂等统一吸收：扣分（流水唯一键）→ 入账（桶唯一键）→ 置 DONE（条件更新），
 * 调用方无需先判「有没有扣分流水」。
 */
@Getter
@AllArgsConstructor
public enum DrawCountPurchaseStateEnum {

    /** 已落单、扣分结果未知（三段式的初始态；扣分流水存在与否由积分域流水唯一键裁决） */
    CREATED(0),

    /** 已完成（扣分 + 次数入账收敛完毕） */
    DONE(1),

    /** 死信：补偿重试耗尽或积分不足（业务永久失败），人工重置回 CREATED 或带原 token 重放自愈 */
    DEAD(2);

    private final int code;

    public static DrawCountPurchaseStateEnum of(int code) {
        for (DrawCountPurchaseStateEnum drawCountPurchaseState : values()) {
            if (drawCountPurchaseState.code == code) {
                return drawCountPurchaseState;
            }
        }
        throw new IllegalArgumentException("未知购买单状态 code=" + code);
    }
}
