package com.betterschool.trigger.draw.job;

import com.betterschool.usecase.marketing.DrawCase;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 购买单补偿扫描（ADR-0014 表形态 + ADR-0016 票 C 口径扩展）：把「已落单待收敛」的
 * CREATED 购买单推进到 DONE。收敛动作 = 三段全量重放——扣分（积分流水唯一键幂等，
 * 已扣过即跳过）→ 次数入账（桶唯一键幂等）→ 条件更新置 DONE；两态分叉
 * （无扣分流水 = 补扣 / 有流水未 DONE = 只需推进）被幂等吸收，编排归 case 层
 * {@code DrawCase}。cron 与 outboxScan 同口径（0/30）；正常情况下
 * 扫描窗口内只有宕机残留的少量行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrawCountPurchaseScanJob {

    /** 单轮扫描上限：正常水位下 state=CREATED 的行只有分钟级窗口内的少量单据 */
    private static final int SCAN_LIMIT = 100;

    private final DrawCase drawCase;

    @XxlJob("drawCountPurchaseScan")
    public void execute() {
        int completed = drawCase.compensateDuePurchaseOrders(SCAN_LIMIT);
        if (completed > 0) {
            log.info("xxl-job: 购买单补偿收敛 {} 笔", completed);
        }
        XxlJobHelper.handleSuccess();
    }
}
