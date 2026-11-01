package com.betterschool.trigger.job;

import com.betterschool.domain.search.service.ISearchService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 搜索索引全量重建（XXL-Job 手动触发）：
 * 兜底恢复手段——MQ 增量同步丢失消息时，清空重建四个实体索引（数据源 = MySQL 真实状态）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexRebuildJob {

    private final ISearchService searchService;

    @XxlJob("searchIndexRebuildJob")
    public String rebuild(String param) {
        searchService.rebuildAllIndices();
        return "success";
    }
}
