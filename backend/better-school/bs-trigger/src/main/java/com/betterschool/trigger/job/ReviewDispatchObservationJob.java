package com.betterschool.trigger.job;

import com.betterschool.domain.moderation.adapter.port.IReviewDispatchMetricsPort;
import com.betterschool.domain.moderation.adapter.port.IReviewPresencePort;
import com.betterschool.domain.moderation.model.valobj.ReviewPendingWaitStats;
import com.betterschool.domain.moderation.service.IReviewService;
import com.betterschool.trigger.ws.support.AbstractHeartbeatSessionRegistry;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 审核派单可观测与积压告警（票 11，spec §9；XXL-Job 调度：cron 0 * * * * ?，注册默认不自动触发）。
 * <p>
 * 四项可观测口径（INFO 一条汇总，人工/面板可查）：
 * ① 在线审核员数——presence 按 60s 心跳判死阈值过滤（崩溃残留 lastSeenMs 停走即剔除）；
 * ② 队列深度（按类型）——工单池 PENDING 计数；
 * ③ 平均等待时长——PENDING 行 entered_at 起算均值；
 * ④ 租约超时率——Redis 累计计数（领单数 vs 租约超时回收数）。
 * <p>
 * 积压告警**两条都只告警、不做自动扩容、不自动派给离线者**：
 * 队列深度达 {@code review.backlog-warn-threshold}，或最老待审等待时长达
 * {@code review.oldest-wait-warn-minutes}——沿用 outbox 积压告警命名先例（backlog-warn-threshold），
 * WARN 一条、无处置动作（处置是拉取侧审核员自己的事）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewDispatchObservationJob {

    private final IReviewService reviewService;
    private final IReviewDispatchMetricsPort reviewDispatchMetrics;
    private final IReviewPresencePort reviewPresencePort;

    /** 队列深度积压告警阈值（PENDING 总条数；只告警不处置） */
    @Value("${review.backlog-warn-threshold:50}")
    private long backlogWarnThreshold;

    /** 最老待审等待时长告警阈值（分钟；只告警不处置） */
    @Value("${review.oldest-wait-warn-minutes:30}")
    private long oldestWaitWarnMinutes;

    @XxlJob("reviewDispatchObservation")
    public String observe(String param) {
        Map<String, Long> backlog = reviewService.backlogCounts();
        long backlogTotal = reviewService.backlogTotal();
        ReviewPendingWaitStats waitStats = reviewService.pendingWaitStats();
        IReviewDispatchMetricsPort.Snapshot counters = reviewDispatchMetrics.snapshot();
        long claimCount = counters.claimCount();
        long leaseTimeoutCount = counters.leaseTimeoutCount();
        String timeoutRate = claimCount == 0
                ? "n/a（暂无领单）"
                : "%.1f%%（%d/%d）".formatted(leaseTimeoutCount * 100.0 / claimCount, leaseTimeoutCount, claimCount);
        int onlineCount = reviewPresencePort.countOnline(AbstractHeartbeatSessionRegistry.DEAD_THRESHOLD_MS);
        log.info("[审核派单观测] 在线审核员 {} 人；队列深度 {}（按类型 {}）；平均等待 {}s、最老待审 {}s；"
                        + "租约超时率 {}",
                onlineCount, backlogTotal, backlog, waitStats.avgWaitSeconds(),
                waitStats.oldestWaitSeconds(), timeoutRate);

        if (backlogTotal >= backlogWarnThreshold) {
            log.warn("[审核积压告警] 工单池积压 {} 条（阈值 {}）：请增派审核员或关注拉取侧消化速度"
                    + "（只告警，不做自动扩容/自动派单）", backlogTotal, backlogWarnThreshold);
        }
        if (waitStats.oldestWaitSeconds() >= oldestWaitWarnMinutes * 60) {
            log.warn("[审核积压告警] 最老待审已等待 {}s（阈值 {} 分钟）：请优先消化早期工单"
                    + "（FIFO 拉取自会先取最老；只告警，不做自动处置）",
                    waitStats.oldestWaitSeconds(), oldestWaitWarnMinutes);
        }
        return "success（backlog=%d, online=%d, oldestWait=%ds）"
                .formatted(backlogTotal, onlineCount, waitStats.oldestWaitSeconds());
    }
}
