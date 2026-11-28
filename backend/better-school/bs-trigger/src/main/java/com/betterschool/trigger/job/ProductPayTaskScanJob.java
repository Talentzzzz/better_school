package com.betterschool.trigger.job;

import com.betterschool.usecase.payment.PaymentCase;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 支付回调收敛任务扫描（XXL-Job {@code productPayTaskScan}）：把到点的 PENDING 回调任务
 * 在进程内重放一遍（钱包已扣冻，只差商户翻转订单，幂等）。
 * <p>
 * 这不是 MQ 消费：回调任务是钱包域的「域内最终一致任务」（{@code wallet_pay_task}，ADR-0017），
 * 与支付单 PAID + 冻结同事务落行（行在 ⇒ 钱已冻），cron 与 outboxScan 同口径（0/30）。
 * 正常情况下扫描窗口内只有一类行：支付已提交、但商户回调失败或进程在回调前崩溃。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductPayTaskScanJob {

    /** 单轮扫描上限：正常水位下 PENDING 行只有分钟级窗口内的少量记录 */
    private static final int SCAN_LIMIT = 100;

    private final PaymentCase paymentCase;

    @XxlJob("productPayTaskScan")
    public void execute() {
        int completed = paymentCase.replayDueNotifyTasks(SCAN_LIMIT);
        if (completed > 0) {
            log.info("xxl-job: 支付回调收敛 {} 条", completed);
        }
        XxlJobHelper.handleSuccess();
    }
}
