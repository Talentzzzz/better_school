package com.betterschool.marketing.api.config.dto;

import lombok.Data;

/** 积分有效期（GET / PUT /api/marketing/config/points-validity） */
@Data
public class PointsValidityResponseDTO {

    /** 积分有效期天数（积分入账时算 expire_at） */
    private Integer pointsValidDays;
}
