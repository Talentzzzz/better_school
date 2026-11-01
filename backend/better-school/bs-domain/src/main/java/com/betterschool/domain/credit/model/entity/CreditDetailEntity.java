package com.betterschool.domain.credit.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreditDetailEntity {

    private Long id;
    private String userId;
    private Integer change;
    private String reasonType;
    private Long refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static CreditDetailEntity create(String userId, int change, String reasonType, Long refId) {
        CreditDetailEntity creditDetailEntity = new CreditDetailEntity();
        creditDetailEntity.userId = userId;
        creditDetailEntity.change = change;
        creditDetailEntity.reasonType = reasonType;
        creditDetailEntity.refId = refId;
        creditDetailEntity.createTime = LocalDateTime.now();
        creditDetailEntity.updateTime = creditDetailEntity.createTime;
        return creditDetailEntity;
    }
}
