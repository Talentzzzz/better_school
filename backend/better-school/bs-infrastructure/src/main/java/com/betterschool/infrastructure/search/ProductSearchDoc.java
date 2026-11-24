package com.betterschool.infrastructure.search;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品索引文档：仅 id + 检索/过滤字段；展示字段（价格 / 图片等）由业务库反查组装。
 * 技术组件内部结构，领域层不引用
 */
@Data
public class ProductSearchDoc {

    /** 业务 id 是 UUID 字符串（uuid-biz-id 票 05） */
    private String productId;
    private String title;
    private String description;
    private String tradeLocation;
    private String category;
    /** 仅 ON_SALE 入库 */
    private String status;
    private LocalDateTime createdAt;
}
