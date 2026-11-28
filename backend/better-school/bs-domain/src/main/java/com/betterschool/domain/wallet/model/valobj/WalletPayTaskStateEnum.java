package com.betterschool.domain.wallet.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 支付回调任务状态机（{@code wallet_pay_task}，域内最终一致任务表，ADR-0017 同形）。
 * <p>
 * 任务行由钱包支付事务**同事务**写入（与支付单 PAID、PRODUCT_FROZEN 流水、余额扣减一起提交）：
 * 行在 ⇒ 钱已冻且支付单已 PAID。商户回调失败或进程崩溃留下的 PENDING 行由
 * {@code productPayTaskScan} 进程内重放（回调幂等），重试耗尽进 {@link #DEAD}。
 */
@Getter
@AllArgsConstructor
public enum WalletPayTaskStateEnum {

    /** 待收敛：冻结已提交，订单翻转还没确认完成 */
    PENDING(0),

    /** 已完成：订单已越过待支付，或已取消无需再支付 */
    DONE(1),

    /** 死信：扫描重放重试耗尽，人工介入；置回 PENDING 可自愈 */
    DEAD(2);

    private final int code;

    public static WalletPayTaskStateEnum of(int code) {
        for (WalletPayTaskStateEnum state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知钱包支付任务状态 code=" + code);
    }
}
