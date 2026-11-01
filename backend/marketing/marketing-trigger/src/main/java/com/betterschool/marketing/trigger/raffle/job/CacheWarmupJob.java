package com.betterschool.marketing.trigger.raffle.job;

import com.betterschool.marketing.domain.raffle.service.warmup.IRaffleWarmupService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 抽奖决策表预热（cron 由调度中心配；配置变更后可手动重跑，幂等）。
 * 参数 = 活动业务 ID activityId（活动 → 策略 → 概率表 + 权重表 + 特型奖品位 + 预热态标记）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmupJob {

    private final IRaffleWarmupService raffleWarmupService;

    @XxlJob("raffleCacheWarmup")
    public void execute() {
        log.info("xxl-job: 抽奖决策表预热");
        String param = XxlJobHelper.getJobParam();
        if (param == null || param.isBlank()) {
            XxlJobHelper.handleFail("缺少 activityId 参数");
            return;
        }
        try {
            raffleWarmupService.warmupByActivityId(Long.parseLong(param.trim()));
        } catch (NumberFormatException e) {
            XxlJobHelper.handleFail("参数格式错误（应为 activityId）：" + param);
            return;
        }
        log.info("xxl-job: 抽奖决策表预热成功 activityId={}", param.trim());
    }
}
