package com.betterschool.marketing.trigger.drawcount.job;

import com.betterschool.marketing.domain.drawcount.service.IDrawCountAccountService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 抽奖次数桶过期落账清理（cron 由调度中心配）。
 * <p>
 * 只是「落账」不是「过期判据」——真实过期以读路径的 {@code expire_at > NOW()} 为准（懒过期），
 * 本任务把已过期桶的 remain 置 0：不删行、留审计。
 * （旧 lottery 域的 {@code lotteryDrawCountExpireScan} 已随票 13 删除，本任务是唯一过期扫描。）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrawCountBucketExpireScanJob {

    private final IDrawCountAccountService drawCountAccountService;

    @XxlJob("drawCountBucketExpireScan")
    public String execute(String param) {
        int cleaned = drawCountAccountService.expireBuckets();
        if (cleaned > 0) {
            log.info("[DrawCountBucketExpireScanJob] 过期次数桶落账清理 {} 行", cleaned);
        }
        return "success";
    }
}
