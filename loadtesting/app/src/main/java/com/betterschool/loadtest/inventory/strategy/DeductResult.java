package com.betterschool.loadtest.inventory.strategy;

import java.util.Objects;

/**
 * 一次扣减的受理结果。注意三种策略的 accepted 语义并不相同：
 * <ul>
 *   <li>db：accepted = 事务已提交（强一致）</li>
 *   <li>redis_mq：accepted = Redis 已扣 + MQ 已收（DB 异步最终一致）</li>
 *   <li>merge：accepted = Redis 已扣 + 预扣明细已落库（台账异步合并）</li>
 * </ul>
 * 压测要同时看「受理 QPS（响应侧）」和「台账落库速率（DB 侧）」两个口径。
 */
public final class DeductResult {

    private final boolean accepted;
    private final String reason;

    public DeductResult(boolean accepted, String reason) {
        this.accepted = accepted;
        this.reason = reason;
    }

    public static DeductResult ok() {
        return new DeductResult(true, null);
    }

    public static DeductResult soldOut() {
        return new DeductResult(false, "SOLD_OUT");
    }

    public static DeductResult error(String reason) {
        return new DeductResult(false, reason);
    }

    public boolean accepted() {
        return accepted;
    }

    public String reason() {
        return reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeductResult other)) return false;
        return accepted == other.accepted && Objects.equals(reason, other.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accepted, reason);
    }

    @Override
    public String toString() {
        return "DeductResult[accepted=" + accepted + ", reason=" + reason + "]";
    }
}
