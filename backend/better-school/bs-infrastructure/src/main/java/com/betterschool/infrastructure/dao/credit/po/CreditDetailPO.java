package com.betterschool.infrastructure.dao.credit.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreditDetailPO {

    private Long id;
    private String userId;
    private Integer change;
    private String reasonType;
    /** user_credit_detail.ref_id 是 VARCHAR(36)（uuid-biz-id 票 05） */
    private String refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
