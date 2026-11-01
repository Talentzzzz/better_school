package com.betterschool.domain.search.model.valobj;

/**
 * 可检索实体类型：消息 payload 与 ES 索引名按 name().toLowerCase() 映射
 */
public enum SearchEntityType {
    POST, PRODUCT, ERRAND, USER
}
