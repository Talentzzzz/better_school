package com.betterschool.domain.credit.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreditDetailEntity {

    private Long id;
    private String userId;
    private Integer change;
    private String reasonType;
    /** 引用业务 id（UUID 字符串）或物理流水 id 的字符串形（uuid-biz-id 票 05，user_credit_detail.ref_id 已是 VARCHAR(36)） */
    private String refId;
    private LocalDateTime createTime;

    public static CreditDetailEntity create(String userId, int change, String reasonType, String refId) {
        CreditDetailEntity creditDetailEntity = new CreditDetailEntity();
        creditDetailEntity.userId = userId;
        creditDetailEntity.change = change;
        creditDetailEntity.reasonType = reasonType;
        creditDetailEntity.refId = refId;
        return creditDetailEntity;
    }
}
