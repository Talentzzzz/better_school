package com.betterschool.domain.wallet.model.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class VirtualWalletEntity {

    private Long id;
    private String userId;
    private BigDecimal balance;
    private BigDecimal frozenAmount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static VirtualWalletEntity create(String userId, BigDecimal initialBalance) {
        VirtualWalletEntity walletEntity = new VirtualWalletEntity();
        walletEntity.userId = userId;
        walletEntity.balance = initialBalance;
        walletEntity.frozenAmount = BigDecimal.ZERO;
        walletEntity.createdAt = LocalDateTime.now();
        walletEntity.updatedAt = walletEntity.createdAt;
        return walletEntity;
    }
}
