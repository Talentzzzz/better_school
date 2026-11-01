package com.betterschool.marketing.api.config.dto;

import lombok.Data;

/** 抽奖次数有效期（GET / PUT /api/marketing/config/draw-count-validity） */
@Data
public class DrawCountValidityResponseDTO {

    /** 抽奖次数有效期天数（次数入账时算 expire_at） */
    private Integer drawCountValidDays;
}
