package com.betterschool.trigger.listener;

import com.betterschool.domain.search.model.valobj.SearchSyncMessage;
import com.betterschool.domain.search.service.ISearchService;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 搜索索引同步消费方：事件只带 {type,id}，反查 MySQL 构建/删除 ES 文档（幂等 + 自愈）。
 * 消息丢失可接受：由 XXL-Job 全量重建兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.SEARCH_SYNC_TOPIC, consumerGroup = "bs-search-sync-group")
public class SearchSyncConsumer implements RocketMQListener<String> {

    private final ISearchService searchService;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        try {
            SearchSyncMessage message = objectMapper.readValue(payload, SearchSyncMessage.class);
            searchService.handleSync(message);
            log.info("[SearchSyncConsumer] 索引同步 type={}, id={}", message.getType(), message.getId());
        } catch (Exception e) {
            log.error("[SearchSyncConsumer] 索引同步失败 payload={}", payload, e);
        }
    }
}
