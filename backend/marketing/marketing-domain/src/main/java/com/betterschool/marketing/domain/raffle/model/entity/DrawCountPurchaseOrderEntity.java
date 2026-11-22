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
 * 不变量（ADR-0016 票 C 三段式）：{@code state = CREATED} 的行只承诺「单已落」，
 * 扣分结果未知——分叉（无扣分流水 = 重试补扣；有流水未 DONE = 只需推进）由扣分流水
 * 唯一键 + 桶唯一键 + 条件更新三层幂等统一吸收，收敛动作恒为「扣分 → 入账 → 置 DONE」。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DrawCountPurchaseOrderEntity {

    /** 物理自增 id（抢占 claim / 死信 markDead 按 id 条件更新） */
    private Long id;
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
