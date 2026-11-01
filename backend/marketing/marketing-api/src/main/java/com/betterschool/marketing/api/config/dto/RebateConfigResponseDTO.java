package com.betterschool.marketing.api.config.dto;

import lombok.Data;

import java.time.LocalDateTime;

/** 行为返利规则一行（GET /api/marketing/config/rebates 与 upsert 的返回体） */
@Data
public class RebateConfigResponseDTO {

    private Long id;
    /** 行为类型 sign(签到) / pay(支付) */
    private String actionType;
    /** 返利类型 POINTS(积分) / DRAW_COUNT(抽奖次数) */
    private String rebateType;
    private String rebateName;
    private String rebateDesc;
    /** 返利值（抽奖次数、积分值） */
    private Integer rebateValue;
    /** 有效期配置 id（POINTS → points_validity_config.id；DRAW_COUNT → draw_count_config.id） */
    private Long configId;
    /** open(开启) / close(关闭) */
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
