package com.betterschool.marketing.infrastructure.points.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 积分消费明细行（points_detail） */
@Data
public class PointsDetailPO {

    private Long id;
    private String userId;
    private Long bucketId;
    private Integer amount;
    private String reasonType;
    private Long refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
