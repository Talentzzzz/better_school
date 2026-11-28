package com.betterschool.domain.wallet.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WalletDetailTypeEnum {

    RECHARGE("充值"),
    FROZEN("托管冻结"),
    UNFROZEN("解冻退回"),
    INCOME("接单收入"),
    PRODUCT_FROZEN("商品托管冻结"),
    PRODUCT_UNFROZEN("商品托管解冻"),
    PRODUCT_INCOME("商品售卖收入");

    private final String desc;
}
