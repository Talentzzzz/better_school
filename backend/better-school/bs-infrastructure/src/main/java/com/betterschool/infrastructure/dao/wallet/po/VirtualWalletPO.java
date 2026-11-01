package com.betterschool.infrastructure.dao.wallet.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class VirtualWalletPO {

    private Long id;
    private Long walletId;
    private String userId;
    private BigDecimal balance;
    private BigDecimal frozenAmount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
