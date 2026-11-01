package com.betterschool.domain.search.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 搜索分页结果：total 为索引命中总数（近似值），items 按相关度排序；
 * 命中反查阶段 T 为实体 id（Long），组装阶段 T 为实体
 */
@Data
@AllArgsConstructor
public class SearchPageVO<T> {

    private long total;
    private List<T> items;
}
