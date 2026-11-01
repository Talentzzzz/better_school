package com.betterschool.domain.search.model.valobj;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品索引文档：仅 id + 检索/过滤字段；展示字段（价格/图片等）由 MySQL 反查组装
 */
@Data
public class ProductSearchDocVO {

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
