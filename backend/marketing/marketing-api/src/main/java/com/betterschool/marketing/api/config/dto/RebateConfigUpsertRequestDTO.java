package com.betterschool.marketing.api.config.dto;

import lombok.Data;

/** 行为返利规则 upsert 请求体（POST /api/marketing/config/rebates，uk = actionType + rebateType） */
@Data
public class RebateConfigUpsertRequestDTO {

    /** 行为类型 sign(签到) / pay(支付) */
    private String actionType;
    /** 返利类型 POINTS(积分) / DRAW_COUNT(抽奖次数) */
    private String rebateType;
    private String rebateName;
    private String rebateDesc;
    /** 返利值（>=0） */
    private Integer rebateValue;
    /** 有效期配置 id（POINTS → points_validity_config.id；DRAW_COUNT → draw_count_config.id） */
    private Long configId;
    /** open(开启) / close(关闭) */
    private String state;
}
