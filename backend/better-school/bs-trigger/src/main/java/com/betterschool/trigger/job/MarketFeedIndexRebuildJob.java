package com.betterschool.trigger.job;

import com.betterschool.domain.errand.service.IErrandOrderService;
import com.betterschool.domain.trade.service.product.IProductService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 市集两域 feed 索引全量重建（XXL-Job 手动触发，market-feed-uv 票 06；照帖子 {@link FeedIndexRebuildJob} 样板）：
 * 兜底恢复手段——同事务直写丢更新 / 索引行与计数窄表漂移（浏览旁路命中 0 行的 warn、窄表被绕过直改）时，
 * 各自的 handler 清空重灌索引表（商品数据源 = product + content_review + product_view_count；
 * 跑腿 = errand_order + content_review + errand_view_count），并把计数窄表校准成明细表行数。
 *
 * <p>两域各一个 handler 而不是一个带域参数的 job：两侧重建的数据源、索引列（price = 售价 / 悬赏）与
 * 驻留口径（商品行可见态流转、跑腿行常驻）都不同，参数化只会在入口处长出分支，域内各走各的仓储备份更直白。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketFeedIndexRebuildJob {

    private final IProductService productService;
    private final IErrandOrderService errandOrderService;

    @XxlJob("productFeedIndexRebuildJob")
    public String rebuildProduct(String param) {
        productService.rebuildFeedIndex();
        return "success";
    }

    @XxlJob("errandFeedIndexRebuildJob")
    public String rebuildErrand(String param) {
        errandOrderService.rebuildFeedIndex();
        return "success";
    }
}
