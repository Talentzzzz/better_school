package com.betterschool.trigger.job;

import com.betterschool.usecase.community.PostPublishCase;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 帖子待审超 24h 提醒扫描（XXL-Job 调度：cron 0 * * * * ?，注册默认不自动触发）：
 * 待审超 24h 未裁决的帖子提醒 ADMIN+AUDITOR（dedup_key 含审核轮次幂等，重提后新一轮可再次提醒；
 * 只提醒，不自动通过也不自动拒绝）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostReviewTimeoutScanJob {

    private final PostPublishCase postPublishCase;

    @XxlJob("postReviewTimeoutScan")
    public String scan(String param) {
        postPublishCase.remindPostReviewTimeouts();
        return "success";
    }
}
