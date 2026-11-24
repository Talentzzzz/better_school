package com.betterschool.infrastructure.timeout;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 一个回调批次结算完的账：几行置了终态、几行进重试、几行进死信。扫描侧只做累加与上报。
 * <p>
 * 三项之和恒等于批大小（结算覆盖每一行是硬要求，见 {@link TimeoutSettler#settle}）。
 */
@Getter
@AllArgsConstructor
public class SettleOutcome {

    private final int successCount;

    private final int retryCount;

    /** 重试次数已达上限、本次置 FAILED 终态的行数（退避打满后的死信） */
    private final int deadCount;
}
