package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreditLogResponseDTO {

    private Long id;

    /** 变动量（正/负，0 = 封禁/解封审计流水） */
    private int change;

    /** 变动原因类型（CreditReasonTypeEnum 枚举名） */
    private String reasonType;

    /** 变动原因中文描述（未知类型兜底显示原值） */
    private String reasonDesc;

    /** 关联业务 ID（UUID 字符串：订单/被罚内容等，可空；uuid-biz-id 票 05） */
    private String refId;

    private LocalDateTime createdAt;
}
