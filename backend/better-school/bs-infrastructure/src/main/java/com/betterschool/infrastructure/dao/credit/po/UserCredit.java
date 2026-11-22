package com.betterschool.infrastructure.dao.credit.po;

import lombok.Data;

import java.time.LocalDateTime;

/** user_credit 表 PO（credit 域独占；user/search 域只读） */
@Data
public class UserCredit {

    private Long id;
    private String userId;
    private Integer creditScore;
    private String state;
    private LocalDateTime updateTime;
}
