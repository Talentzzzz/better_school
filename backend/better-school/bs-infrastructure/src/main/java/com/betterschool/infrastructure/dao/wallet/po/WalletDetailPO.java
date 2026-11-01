package com.betterschool.infrastructure.dao.wallet.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class WalletDetailPO {

    private Long id;
    private Long walletId;
    private String userId;
    private String type;
    private BigDecimal amount;
    private Long orderId;
    private int bizRound;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
