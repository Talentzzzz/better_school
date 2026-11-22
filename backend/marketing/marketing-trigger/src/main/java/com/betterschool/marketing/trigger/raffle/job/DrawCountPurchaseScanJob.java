package com.betterschool.marketing.trigger.raffle.job;

import com.betterschool.marketing.domain.raffle.service.drawcount.IRaffleDrawCountPurchaseService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 购买单补偿扫描（ADR-0014）：把「已扣分待入账」的购买单收敛到 DONE。
 * 只做「次数入账 + 置 DONE」（入账桶唯一键幂等），从不碰扣分——单在即分已扣。
 * cron 与 outboxScan 同口径（0/30）；正常情况下扫描窗口内只有宕机残留的少量行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrawCountPurchaseScanJob {

    /** 单轮扫描上限：正常水位下 state=DEDUCTED 的行只有分钟级窗口内的少量单据 */
    private static final int SCAN_LIMIT = 100;

    private final IRaffleDrawCountPurchaseService raffleDrawCountPurchaseService;

    @XxlJob("drawCountPurchaseScan")
    public void execute() {
        int completed = raffleDrawCountPurchaseService.compensateDueOrders(SCAN_LIMIT);
        if (completed > 0) {
            log.info("xxl-job: 购买单补偿收敛 {} 笔", completed);
        }
        XxlJobHelper.handleSuccess();
    }
}
