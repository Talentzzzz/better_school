package com.betterschool.marketing.api.lottery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 用积分购买抽奖次数的结果。
 * <p>
 * {@code drawCount} = 买完之后的**可用次数余额**（未过期桶 `SUM(remain)`），
 * 前端拿它直接刷「我的抽奖次数」大数字，不必再打一次 `draw-chances`。
 * <p>
 * 字段名沿用历史的 {@code redeemOrderId}（= 购买单 id），不改名以免前端与联调脚本白改一遍。
 */
@Data
@AllArgsConstructor
public class RedeemDrawCountResponseDTO {

    /**
     * 购买单 id（= 幂等键）：由请求的 token 确定性映射而来，同一 token 重放恒同一单号；
     * 所以**重试要带的是同一个 token**（不是这个 id）。
     */
    private String redeemOrderId;
    /** 本次（或重放时报价的）花费积分 = 份数 × 单价 */
    private Integer pointsCost;
    private Integer drawCount;
}
