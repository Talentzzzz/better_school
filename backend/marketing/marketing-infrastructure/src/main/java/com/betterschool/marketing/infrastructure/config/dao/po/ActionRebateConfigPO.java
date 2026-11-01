package com.betterschool.marketing.infrastructure.config.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 行为返利规则行（action_rebate_config） */
@Data
public class ActionRebateConfigPO {

    private Long id;
    private String actionType;
    private String rebateType;
    private String rebateName;
    private String rebateDesc;
    private Integer rebateValue;
    private Long configId;
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
