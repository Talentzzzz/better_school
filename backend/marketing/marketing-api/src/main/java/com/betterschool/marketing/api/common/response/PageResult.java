package com.betterschool.marketing.api.common.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 统一分页结果：形状照单体 {@code bs-api/response/PageResult}（前端 unwrap 后按同名字段取用）。
 * 分页参数一律 {@code page}/{@code pageSize}（默认 1/10、上限 50）。
 */
@Data
@AllArgsConstructor
public class PageResult<T> {

    private long page;
    private long pageSize;
    private long total;
    private List<T> list;
}
