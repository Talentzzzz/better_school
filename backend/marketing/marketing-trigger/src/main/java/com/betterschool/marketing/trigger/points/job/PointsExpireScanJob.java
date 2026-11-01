package com.betterschool.marketing.trigger.points.job;

import com.betterschool.marketing.domain.points.service.IPointsAccountService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 积分过期落账清理（cron 由调度中心配）。
 * <p>
 * 只是「落账」不是「过期判据」——真实过期以读路径的 {@code expire_at > NOW()} 为准（懒过期），
 * 本任务把已过期桶的 remain 置 0：不删行、留审计，也**不发**积分变动事件（避免噪声）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsExpireScanJob {

    private final IPointsAccountService pointsAccountService;

    @XxlJob("pointsExpireScan")
    public String execute(String param) {
        int cleaned = pointsAccountService.expireBuckets();
        if (cleaned > 0) {
            log.info("[PointsExpireScanJob] 过期桶落账清理 {} 行", cleaned);
        }
        return "success";
    }
}
