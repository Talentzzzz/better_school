package com.betterschool.api.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class WalletResponseDTO {

    private BigDecimal balance;
    private BigDecimal frozenAmount;
}
