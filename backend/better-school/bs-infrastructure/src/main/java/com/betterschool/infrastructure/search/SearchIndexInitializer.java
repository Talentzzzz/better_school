package com.betterschool.infrastructure.search;

import com.betterschool.domain.search.adapter.repository.ISearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等创建 ES 索引（mapping 已存在则跳过）。
 * ES 未启动不阻塞应用：失败仅告警，恢复后由 XXL-Job 全量重建兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchIndexInitializer implements ApplicationRunner {

    private final ISearchRepository searchRepository;

    @Override
    public void run(ApplicationArguments args) {
        try {
            searchRepository.ensureIndices();
            log.info("[SearchIndexInitializer] ES 索引检查/创建完成");
        } catch (Exception e) {
            log.error("[SearchIndexInitializer] ES 索引初始化失败（应用继续启动，可稍后触发全量重建）", e);
        }
    }
}
