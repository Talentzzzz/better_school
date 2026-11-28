package com.betterschool.domain.draw.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 用积分购买抽奖次数的结果：购买单 id（幂等键落点）、本次花费、买完之后的可用次数余额。
 * <p>
 * {@code drawCount} = **可用次数余额**（未过期桶 `SUM(remain)`），前端拿它直接刷
 * 「我的抽奖次数」大数字，不必再打一次 `draw-chances`。
 */
@Data
@AllArgsConstructor
public class DrawCountPurchaseResultVO {

    /**
     * 购买单 id（= 幂等键）：由请求的 token 确定性映射而来，同一 token 重放恒同一单号；
     * 所以**重试要带的是同一个 token**（不是这个 id）。
     */
    private String purchaseOrderId;
    /** 本次（或重放时报价的）积分数 = 份数 × 单价 */
    private Integer pointsCost;
    private Integer drawCount;
}
