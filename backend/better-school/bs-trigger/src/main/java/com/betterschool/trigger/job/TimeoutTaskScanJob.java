package com.betterschool.trigger.job;

import com.betterschool.infrastructure.timeout.ScanSummary;
import com.betterschool.infrastructure.timeout.TimeoutScanner;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 超时任务扫描 job（超时中心内嵌化票 04）：**扫描 = 重试 = 死信重发的前身，超时中心就这一个 job 入口**。
 * <p>
 * 只做三件事——取 xxl-job 分片参数、把到点动作（{@link TimeoutActionRouter}）交给扫描器、把本轮账写进调度日志。
 * 调度侧的配置（10s 周期、分片广播、阻塞策略 DISCARD_LATER、调度失败重试 0、misfire FIRE_ONCE_NOW）
 * 在 admin 库里，落点见 {@code better-school/sql/xxl-job-tasks.sql}；防重的四层里，
 * 前三层（表级归属 / DISCARD_LATER / 重试 0）分别由「槽位认领」「阻塞策略」「重试 0」承担。
 * <p>
 * 与中心时代的差异只有名字（{@code timeoutTaskScanJob → timeoutTaskScan}）与调用目标（同进程的技术组件）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutTaskScanJob {

    private final TimeoutScanner timeoutScanner;
    private final TimeoutActionRouter timeoutActionRouter;

    @XxlJob("timeoutTaskScan")
    public void scanJob() {
        int shardIndex = XxlJobHelper.getShardIndex();
        int shardTotal = XxlJobHelper.getShardTotal();
        try {
            ScanSummary scanSummary = timeoutScanner.scan(shardIndex, shardTotal, timeoutActionRouter);
            XxlJobHelper.handleSuccess(String.format(
                    "扫描完成：槽位 %d 个、批次 %d、任务 %d 条（成功 %d / 重试 %d / 进死信 %d）",
                    scanSummary.getSlotCount(), scanSummary.getBatchCount(), scanSummary.getTaskCount(),
                    scanSummary.getSuccessCount(), scanSummary.getRetryCount(), scanSummary.getDeadCount()));
        } catch (Exception e) {
            // 本轮失败不补跑：job 调度层重试 0，下个周期照常重扫（未终态行永远满足扫描条件）
            log.error("[超时中心告警] 扫描 job 异常：shardIndex={}, shardTotal={}", shardIndex, shardTotal, e);
            XxlJobHelper.handleFail("扫描 job 异常：" + e.getMessage());
        }
    }
}
