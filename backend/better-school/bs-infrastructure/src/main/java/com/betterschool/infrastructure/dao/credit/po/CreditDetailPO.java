package com.betterschool.infrastructure.dao.credit.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreditDetailPO {

    private Long id;
    private String userId;
    private Integer change;
    private String reasonType;
    private Long refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
