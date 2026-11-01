package com.betterschool.domain.search.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索索引同步消息（outbox 持久化后经 MQ 投递）：
 * 事件只带 type+id，消费方从 MySQL 反查构建文档——实体不存在或非公开状态则删文档，天然幂等自愈
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SearchSyncMessage {

    private String type;
    private String id;
}
