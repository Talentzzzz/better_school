package com.betterschool.trigger.job;

import com.betterschool.usecase.post.IPostCase;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 发帖返利领取任务收敛扫描（XXL-Job {@code postRebateClaimScan}）：把到点的 PENDING 领取任务
 * 在进程内重放一遍。
 * <p>
 * 这不是 MQ 消费：领取任务是 post 域的「域内最终一致任务」（{@code post_rebate_claim_task}，ADR-0017），
 * 收敛动作 = 调 case 的幂等领取（按 ref 幂等），与 ADR-0016「每条两段式链路一个收敛 job」同构。
 * 正常情况下扫描窗口内只有两类行：发帖事务提交后请求线程同步领取抛异常（结果未知），或进程在
 * 同步领取前崩溃。cron 与 outboxScan 同口径（0/30）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostRebateClaimScanJob {

    /** 单轮扫描上限：正常水位下 PENDING 行只有分钟级窗口内的少量记录 */
    private static final int SCAN_LIMIT = 100;

    private final IPostCase postCase;

    @XxlJob("postRebateClaimScan")
    public void execute() {
        int completed = postCase.compensateDueRebateClaims(SCAN_LIMIT);
        if (completed > 0) {
            log.info("xxl-job: 发帖返利领取任务收敛 {} 条", completed);
        }
        XxlJobHelper.handleSuccess();
    }
}
