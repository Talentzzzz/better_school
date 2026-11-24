package com.betterschool.infrastructure.search;

import java.util.List;

/**
 * 三个搜索索引的名字与可检索字段（纯技术元数据，与 mapping 一起定义在 {@link SearchIndex}）。
 *
 * <p>状态白名单不在这里：那是业务判据（NORMAL / ON_SALE 等域枚举），由各域仓储传参。
 */
public final class SearchIndexName {

    public static final String POST = "bs_post";
    public static final String PRODUCT = "bs_product";
    public static final String USER = "bs_user";

    public static final List<String> POST_SEARCH_FIELDS = List.of("title", "content");
    public static final List<String> PRODUCT_SEARCH_FIELDS = List.of("title", "description", "tradeLocation");
    public static final List<String> USER_SEARCH_FIELDS = List.of("nickname");

    private SearchIndexName() {
    }
}
