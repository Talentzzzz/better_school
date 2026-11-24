package com.betterschool.trigger.job;

import com.betterschool.usecase.idle.IdleCase;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 交易到期下架扫描（XXL-Job 调度：cron 0 * * * * ?）：
 * 上架满 30 天（expire_at，重提/改内容刷新）的商品汇入草稿箱
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdleExpireScanJob {

    private final IdleCase idleCase;

    @XxlJob("idleExpireScan")
    public String scan(String param) {
        idleCase.scanExpiredListings();
        return "success";
    }
}
