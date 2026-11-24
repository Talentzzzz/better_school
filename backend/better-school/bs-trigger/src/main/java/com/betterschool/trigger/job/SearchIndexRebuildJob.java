package com.betterschool.trigger.job;

import com.betterschool.usecase.search.SearchRebuildCase;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 搜索索引全量重建（XXL-Job 手动触发）：
 * 兜底恢复手段——MQ 增量同步丢失消息时，清空重建三类实体索引（数据源 = MySQL 真实状态）。
 * 每类索引的重建由所属域仓储执行，「重建哪几类、什么顺序」的编排在 {@link SearchRebuildCase}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexRebuildJob {

    private final SearchRebuildCase searchRebuildCase;

    @XxlJob("searchIndexRebuildJob")
    public String rebuild(String param) {
        return searchRebuildCase.rebuildAll();
    }
}
