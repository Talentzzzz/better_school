package com.betterschool.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class WalletTransactionResponseDTO {

    private Long id;
    private String type;
    private BigDecimal amount;
    private String orderId;
    private LocalDateTime createdAt;
}
