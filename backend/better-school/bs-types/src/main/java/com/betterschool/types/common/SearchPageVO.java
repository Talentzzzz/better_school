package com.betterschool.types.common;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 搜索分页结果：total 为索引命中总数（近似值），items 按相关度排序；
 * 命中阶段 T 为业务 id（UUID 字符串），组装阶段 T 为领域实体
 */
@Data
@AllArgsConstructor
public class SearchPageVO<T> {

    private long total;
    private List<T> items;
}
