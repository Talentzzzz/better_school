package com.betterschool.marketing.domain.raffle.service.drawcount;

import com.betterschool.marketing.domain.raffle.model.valobj.DrawCountPurchaseResultVO;

/**
 * 用积分购买抽奖次数（抽奖域内的次数资产，购买是它唯一的「进」口之一，另一条是行为返利）。
 * <p>
 * **抽奖本身只消耗次数**（{@link IDrawCountAccountService#deductForDraw}），任何情况下都不直接扣积分；
 * 积分不足时前端引导用户走本能力换次数，再回到抽奖。两条路径互不交叉。
 * <p>
 * **一致性（ADR-0014）**：购买单与扣积分**同事务**落库（单在 ⇒ 分已扣），次数入账幂等
 * （桶唯一键 {@code (userId, PURCHASE, refId)}）。收敛靠三路重试踩同一单号：
 * 客户端带同 token 重放（Stripe 语义：DONE 返回原结果、中间态顺势补齐）、
 * {@link #compensateDueOrders} 定时补偿（扫描 job 调）、T+1 对账兜底（另开票）。
 */
public interface IRaffleDrawCountPurchaseService {

    /**
     * 用积分购买 {@code quantity} 次抽奖次数：单价见
     * {@link com.betterschool.marketing.types.raffle.Constants#DRAW_COUNT_PURCHASE_PRICE}。
     * 同 token 重放不重复扣分、不重复加次数（购买单唯一键 + 桶唯一键双层吸收）。
     *
     * @param token    前端为「这一次购买意图」生成的 uuid；重试必须带同一个（幂等键来源）
     * @param quantity 购买份数（1..{@code DRAW_COUNT_PURCHASE_MAX_QUANTITY}）
     * @throws com.betterschool.marketing.types.common.AppException 积分不足抛
     *         {@code LOTTERY_INSUFFICIENT_POINTS}（一分不扣、一单不落）；份数不合法抛 {@code LOTTERY_PARAM_ERROR}
     */
    DrawCountPurchaseResultVO purchaseDrawCount(String userId, String token, int quantity);

    /**
     * 购买单补偿（xxl {@code drawCountPurchaseScan} 调度）：扫到点的 {@code DEDUCTED} 单，
     * 抢占式认领（条件更新 + 退避 {@code next_retry_at}）后补「次数入账 + 置 DONE」——
     * 从不碰扣分（单在即分已扣）。重试耗尽（{@code COMPENSATE_MAX_RETRY}）置 DEAD 等人工介入。
     *
     * @return 本轮真正收敛（置 DONE）的单数
     */
    int compensateDueOrders(int max);
}
