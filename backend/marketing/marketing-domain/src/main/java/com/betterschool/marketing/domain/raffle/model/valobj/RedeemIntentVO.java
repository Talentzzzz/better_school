package com.betterschool.marketing.domain.raffle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 兑抽奖次数的第一步产物（跨域兑换边）：由 {@code IRaffleExchangeService} 扣分成功后给出，
 * case 层拿它去次数域入账——幂等单号与花费都不再重算，避免两个域各算一遍对不上。
 */
@Data
@AllArgsConstructor
public class RedeemIntentVO {

    /** 幂等单号 = hash(userId, token)：积分流水与次数桶共用同一个 refId */
    private Long redeemOrderId;
    /** 本次扣掉的积分数（= 兑换单价；重放同一 token 时也照此返回） */
    private Integer pointsCost;
}
