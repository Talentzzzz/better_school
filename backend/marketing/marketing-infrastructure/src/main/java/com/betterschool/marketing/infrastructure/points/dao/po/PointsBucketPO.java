package com.betterschool.marketing.infrastructure.points.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 积分桶行（points_bucket） */
@Data
public class PointsBucketPO {

    private Long id;
    private Long bucketId;
    private String userId;
    private Integer amount;
    private Integer remain;
    private LocalDateTime expireAt;
    private String reasonType;
    private Long refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
