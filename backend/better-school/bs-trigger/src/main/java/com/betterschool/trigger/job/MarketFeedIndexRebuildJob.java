package com.betterschool.trigger.job;

import com.betterschool.domain.idle.service.IIdleService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 市集商品 feed 索引全量重建（XXL-Job 手动触发，market-feed-uv 票 06；照帖子 {@link FeedIndexRebuildJob} 样板）：
 * 兜底恢复手段——同事务直写丢更新 / 索引行与计数窄表漂移（浏览旁路命中 0 行的 warn、窄表被绕过直改）时，
 * 清空重灌索引表（商品数据源 = product + product_view_count），并把计数窄表校准成明细表行数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketFeedIndexRebuildJob {

    private final IIdleService idleService;

    @XxlJob("productFeedIndexRebuildJob")
    public String rebuildProduct(String param) {
        idleService.rebuildFeedIndex();
        return "success";
    }
}
