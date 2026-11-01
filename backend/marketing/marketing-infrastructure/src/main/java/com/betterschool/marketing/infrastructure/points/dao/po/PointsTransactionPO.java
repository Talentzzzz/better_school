package com.betterschool.marketing.infrastructure.points.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 流水归并行（points_bucket ∪ points_detail 的查询投影，不对应单表） */
@Data
public class PointsTransactionPO {

    private Long id;
    /** IN = 入账（points_bucket）/ OUT = 消费（points_detail） */
    private String direction;
    private Integer amount;
    private String reasonType;
    private Long refId;
    private LocalDateTime createdAt;
    /** 仅入账行有值 */
    private LocalDateTime expireAt;
}
