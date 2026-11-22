package com.betterschool.infrastructure.dao.raffle.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 抽奖次数桶行（draw_count_bucket） */
@Data
public class DrawCountBucketPO {

    private Long id;
    private String bucketId;
    private String userId;
    private Integer amount;
    private Integer remain;
    private LocalDateTime expireAt;
    private String reasonType;
    private String refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
