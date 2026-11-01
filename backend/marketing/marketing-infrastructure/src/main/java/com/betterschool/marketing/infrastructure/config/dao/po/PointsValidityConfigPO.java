package com.betterschool.marketing.infrastructure.config.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 积分有效期配置行（points_validity_config，单行 id=1） */
@Data
public class PointsValidityConfigPO {

    private Long id;
    private Integer pointsValidDays;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
