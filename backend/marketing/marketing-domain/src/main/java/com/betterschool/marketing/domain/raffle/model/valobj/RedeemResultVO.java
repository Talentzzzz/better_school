package com.betterschool.marketing.domain.raffle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 兑抽奖次数的结果：兑换单 id（幂等键落点）与兑后的可用次数余额。
 */
@Data
@AllArgsConstructor
public class RedeemResultVO {

    private String redeemOrderId;
    private Integer drawCount;
}
