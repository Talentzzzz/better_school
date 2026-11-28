package com.betterschool.types.rebate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 发帖返利领取任务状态机（{@code post_rebate_claim_task}，域内最终一致任务表，ADR-0017）。
 * <p>
 * 与 {@link RebateClaimStateEnum} 的区别：后者描述「领取动作的结果」，本枚举描述「任务本身做没做完」。
 * 任务只在两种情况下离开 {@code PENDING}：请求线程同步领取返回后置 {@code DONE}（无论结果
 * CLAIMED / DENIED / NO_REBATE），或扫描重放成功（由 case 置 {@code DONE}）。
 * 重试耗尽进 {@code DEAD}，人工重置回 {@code PENDING} 即可重放（领取按 ref 幂等）。
 */
@Getter
@AllArgsConstructor
public enum PostRebateClaimTaskStateEnum {

    /** 待领取：发帖事务内插行，或同步领取抛异常后保留 */
    PENDING(0),

    /** 已完成：领取动作已有确定结果（成功 / 拒绝 / 无返利），或扫描重放成功 */
    DONE(1),

    /** 死信：扫描重放重试耗尽，人工介入；置回 PENDING 可自愈 */
    DEAD(2);

    private final int code;

    public static PostRebateClaimTaskStateEnum of(int code) {
        for (PostRebateClaimTaskStateEnum state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new IllegalArgumentException("未知发帖返利领取任务状态 code=" + code);
    }
}
