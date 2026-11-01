package com.betterschool.infrastructure.dao.errand.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ErrandOrderPO {

    private Long id;
    private Long errandOrderId;
    private String publisherId;
    private String takerId;
    private String type;
    private String description;
    private String pickupLocation;
    private String deliveryLocation;
    private BigDecimal reward;
    private Integer timeLimitHours;
    /** 配图 URL 列表（JSON 数组，与 post.images 存储约定一致） */
    private String images;
    private LocalDateTime deadline;
    private String state;
    private Boolean takerCanceled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 查询冗余字段：下单方/接单方昵称头像（JOIN bs_user 填充） */
    private String publisherNickname;
    private String publisherAvatar;
    private String takerNickname;
    private String takerAvatar;

    /** 查询冗余字段：浏览 UV 数（公开列表 LEFT JOIN errand_view_count 填充；其余查询为 null，详情另走计数窄表） */
    private Integer viewCount;
}
