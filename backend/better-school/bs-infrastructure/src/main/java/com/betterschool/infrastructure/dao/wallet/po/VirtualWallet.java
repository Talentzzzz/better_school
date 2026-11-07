package com.betterschool.infrastructure.dao.wallet.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class VirtualWallet {

    private Long id;
    /** 业务 ID（UUID 字符串；uuid-biz-id 票 06：UUID.randomUUID() 随 insert 一次写入） */
    private String walletId;
    private String userId;
    private BigDecimal balance;
    private BigDecimal frozenAmount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
