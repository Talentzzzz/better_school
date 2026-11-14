package com.betterschool.domain.wallet.model.entity;

import com.betterschool.domain.wallet.model.valobj.WalletDetailTypeVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class WalletDetailEntity {

    private Long id;
    /** 钱包业务 ID（UUID 字符串，uuid-biz-id 票 06） */
    private String walletId;
    private String userId;
    private WalletDetailTypeVO type;
    private BigDecimal amount;
    /** 订单业务 id 是 UUID 字符串（uuid-biz-id 票 05） */
    private String orderId;
    private int bizRound;
    private LocalDateTime createTime;
}
