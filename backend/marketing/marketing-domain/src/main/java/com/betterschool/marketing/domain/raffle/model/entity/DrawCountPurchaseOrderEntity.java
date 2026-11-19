package com.betterschool.marketing.domain.raffle.model.entity;

import com.betterschool.marketing.types.raffle.DrawCountPurchaseState;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 抽奖次数购买单（ADR-0014，订单兼 outbox）：一次「用积分购买次数」的持久记录，
 * 同时是这条链路的幂等入口、状态机载体与对账源。
 * <p>
 * 不变量：{@code state = DEDUCTED} 的行与扣积分**同一事务**落库——单存在即积分一定已扣，
 * 因此补偿重试永远只做「次数入账 + 置 DONE」，从不碰扣分。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DrawCountPurchaseOrderEntity {

    /** 购买单业务 ID（= hash(userId, token)，贯穿 points_detail.ref_id 与 draw_count_bucket.ref_id） */
    private String purchaseOrderId;
    private String userId;
    /** 购买份数 */
    private Integer quantity;
    /** 本次扣减积分 = 份数 × 单价 */
    private Integer pointsCost;
    private DrawCountPurchaseState state;
    /** 补偿重试次数（抢占时递增） */
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
