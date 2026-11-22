package com.betterschool.trigger.points.job;

import com.betterschool.domain.points.service.IPointsAccountService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 积分桶合并（points-compaction 票，cron 由调度中心配，建议每 10 分钟一次——合并不紧急，
 * 且与每分钟的过期扫描错峰）。
 * <p>
 * 碎片桶收敛：签到返利每天入一桶，桶数随账户年龄增长，SUM 读的工作集跟着膨胀。
 * 本任务把同用户的有效小额桶并成一桶（过期时间取合并集合最大值、旧桶归零留审计），
 * SUM(remain) 合并前后守恒、余额不变，对用户不可见（合并行不进流水）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsBucketCompactionJob {

    private final IPointsAccountService pointsAccountService;

    @XxlJob("pointsBucketCompaction")
    public String execute(String param) {
        int merged = pointsAccountService.compactSmallBuckets();
        if (merged > 0) {
            log.info("[PointsBucketCompactionJob] 合并小额桶 {} 张", merged);
        }
        return "success";
    }
}
