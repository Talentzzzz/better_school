package com.betterschool.domain.search.model.valobj;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跑腿订单索引文档：仅 id + 检索/过滤字段；展示字段（悬赏/双方昵称头像）由 MySQL 反查组装
 */
@Data
public class ErrandSearchDocVO {

    /** 业务 id 是 UUID 字符串（uuid-biz-id 票 05） */
    private String orderId;
    private String title;
    private String description;
    private String pickupLocation;
    private String deliveryLocation;
    private String type;
    /** 仅公开进行中状态入库（PUBLISHED/CONFIRMED/PICKED_UP/DELIVERED） */
    private String status;
    private LocalDateTime createdAt;
}
