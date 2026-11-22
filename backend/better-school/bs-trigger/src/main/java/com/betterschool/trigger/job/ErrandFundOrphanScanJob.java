package com.betterschool.trigger.job;

import com.betterschool.domain.errand.model.valobj.ErrandFundOrphanScanSummaryVO;
import com.betterschool.usecase.errand.ErrandFundOrphanScanCase;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 跑腿资金孤儿扫描 job（ADR-0016 两段式资金链路的收敛入口）：薄壳——把本轮账写进调度日志，
 * 收敛逻辑全在用例层 {@link ErrandFundOrphanScanCase}。调度侧配置（0/30 周期、阻塞策略
 * SERIAL_EXECUTION、调度失败重试 0）落点见 {@code better-school/sql/xxl-job-tasks.sql}。
 * 无状态窗口重扫：失败行永远满足扫描条件，本轮失败不补跑、下个周期照常收敛。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrandFundOrphanScanJob {

    private final ErrandFundOrphanScanCase errandFundOrphanScanCase;

    @XxlJob("errandFundOrphanScan")
    public void scanJob() {
        try {
            ErrandFundOrphanScanSummaryVO summary = errandFundOrphanScanCase.scan();
            XxlJobHelper.handleSuccess(String.format(
                    "孤儿扫描完成：冻结孤儿 %d 行（补解冻 %d）、补入账 %d、补扣分 %d",
                    summary.getFrozenOrphanTotal(), summary.getUnfrozenDone(),
                    summary.getIncomeDone(), summary.getCreditDone()));
        } catch (Exception e) {
            // 本轮失败不补跑：job 调度层重试 0，下个周期照常重扫（未收敛行永远满足扫描条件）
            log.error("[资金孤儿告警] 扫描 job 异常", e);
            XxlJobHelper.handleFail("孤儿扫描 job 异常：" + e.getMessage());
        }
    }
}
