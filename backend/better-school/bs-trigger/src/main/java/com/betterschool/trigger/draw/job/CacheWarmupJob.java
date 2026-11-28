package com.betterschool.trigger.draw.job;

import com.betterschool.domain.draw.service.warmup.IDrawWarmupService;
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

    private final IDrawWarmupService drawWarmupService;

    @XxlJob("drawCacheWarmup")
    public void execute() {
        log.info("xxl-job: 抽奖决策表预热");
        String param = XxlJobHelper.getJobParam();
        if (param == null || param.isBlank()) {
            XxlJobHelper.handleFail("缺少 activityId 参数");
            return;
        }
        // activityId 已 UUID 化（uuid-biz-id 票 07）：参数直接以字符串传递，不再做数值解析
        drawWarmupService.warmupByActivityId(param.trim());
        log.info("xxl-job: 抽奖决策表预热成功 activityId={}", param.trim());
    }
}
