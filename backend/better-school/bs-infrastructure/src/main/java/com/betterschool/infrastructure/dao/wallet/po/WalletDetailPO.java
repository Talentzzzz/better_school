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
    /** wallet_detail.order_id 是 VARCHAR(36)，存订单业务 UUID（uuid-biz-id 票 05） */
    private String orderId;
    private int bizRound;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
