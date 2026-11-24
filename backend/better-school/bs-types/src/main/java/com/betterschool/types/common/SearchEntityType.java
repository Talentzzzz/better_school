package com.betterschool.types.common;

/**
 * 可检索实体类型：outbox 消息 payload 的 type 字段按 name().toLowerCase() 取值。
 *
 * <p>落 types 而不是某个领域——三个域（community / trade / user）各自发同一条 MQ 契约，
 * 而 domain 不能依赖 infrastructure，公共契约只能住在被所有模块依赖的 types。
 */
public enum SearchEntityType {
    POST, PRODUCT, USER
}
