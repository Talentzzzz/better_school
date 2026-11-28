package com.betterschool.domain.rebate.model.entity;

import com.betterschool.domain.rebate.model.valobj.RebateTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行为返利规则一行（action_rebate_config）：签到 / 支付等行为发放抽奖次数或积分的规则。
 * {@code uk (action_type, rebate_type)}：同一行为同一返利类型一条规则，upsert 以这两列为键。
 */
@Data
public class RebateConfigEntity {

    private Long id;
    /** 行为类型：sign(签到) / pay(支付) / post(发帖) */
    private String actionType;
    private RebateTypeEnum rebateType;
    private String rebateName;
    /** 返利描述（可空） */
    private String rebateDesc;
    /** 返利值（抽奖次数、积分值，>= 0） */
    private Integer rebateValue;
    /** 有效期配置 id（rebate_type=POINTS → points_validity_config.id；DRAW_COUNT → draw_count_config.id） */
    private Long configId;
    /** 状态 open(开启) / close(关闭) */
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
