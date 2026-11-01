package com.betterschool.domain.errand.model.valobj;

import lombok.Data;

/**
 * 锁单超时释放结果（null = 幂等忽略）：case 层据此给被释放的接单方发结果通知。
 *
 * <p>释放会把 {@code taker_id} 清空，所以域方法必须在**清空前**读出接单方 id 并装进本对象返回——
 * 回调侧拿到的是「本轮被释放的那个人」，不是事后查库（那时已是 NULL）。
 */
@Data
public class ErrandLockTimeoutResult {

    /** 本轮被释放的接单方用户 ID（清空前读出） */
    private String takerId;

    public ErrandLockTimeoutResult(String takerId) {
        this.takerId = takerId;
    }
}
