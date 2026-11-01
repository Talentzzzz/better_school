package com.betterschool.api.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class PageResult<T> {

    private long page;
    private long pageSize;
    private long total;
    private List<T> list;
    /**
     * 主页 feed 的续页游标（feed-index-uv 票 04）：非 null = 下一页必须带它走 keyset 续页；
     * null = 游标协议不适用（降级直查 / 分类过滤路径或已到末页），按 page 续页或以本页不满页收尾。
     * 其余 page 型接口恒为 null。total = -1 表示「未知」（索引路径不再精确 COUNT）
     */
    private String cursor;

    public PageResult(long page, long pageSize, long total, List<T> list) {
        this(page, pageSize, total, list, null);
    }
}
