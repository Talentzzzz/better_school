package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreditLogResponseDTO {

    private Long id;

    /** 变动量（正/负，0 = 封禁/解封审计流水） */
    private int change;

    /** 变动原因类型（CreditReasonTypeVO 枚举名） */
    private String reasonType;

    /** 变动原因中文描述（未知类型兜底显示原值） */
    private String reasonDesc;

    /** 关联业务 ID（订单/评价等，可空） */
    private Long refId;

    private LocalDateTime createdAt;
}
