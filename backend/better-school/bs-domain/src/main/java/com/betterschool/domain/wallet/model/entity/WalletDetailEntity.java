package com.betterschool.domain.wallet.model.entity;

import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class WalletDetailEntity {

    private Long id;
    private Long walletId;
    private String userId;
    private WalletDetailTypeVO type;
    private BigDecimal amount;
    private Long orderId;
    private int bizRound;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
