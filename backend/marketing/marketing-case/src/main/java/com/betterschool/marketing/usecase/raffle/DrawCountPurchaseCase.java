package com.betterschool.marketing.usecase.raffle;

import com.betterschool.marketing.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.marketing.domain.points.service.IPointsAccountService;
import com.betterschool.marketing.domain.raffle.model.entity.DrawCountPurchaseOrderEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawCountPurchaseResultVO;
import com.betterschool.marketing.domain.raffle.service.drawcount.IRaffleDrawCountPurchaseService;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.points.PointsReasonType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用积分购买抽奖次数的三段编排（ADR-0016 票 C：组合事务端口 {@code PointsDeductPort} 退役，
 * 事务边界 = 仓储）：三个段落各自仓储本地事务，顺序调用无跨域事务，幂等靠三层唯一键/条件更新。
 * <ol>
 *   <li>**落单段**（raffle 仓储）：购买单 {@code CREATED}——购买单仍是订单兼幂等键与对账源
 *       （ADR-0014 表形态、唯一键、Stripe 幂等入口全保留）；</li>
 *   <li>**扣分段**（points 仓储）：{@code deduct} 流水先行（唯一键
 *       {@code (userId, REDEEM_DRAW_COUNT, purchaseOrderId)} 幂等）——**资金段先行**，
 *       落单已提交、扣分未执行的窗口是「扣分结果未知」，不会先入账造成短款；</li>
 *   <li>**推进段**（raffle 仓储）：次数入账（桶唯一键幂等）+ 条件更新置 {@code DONE}。</li>
 * </ol>
 * 「单在 ⇒ 分已扣」降级为「单在 ⇒ 扣分结果未知、扫描收敛」（ADR-0016）：三路重试——客户端带同
 * token 重放（Stripe 语义）、xxl {@code drawCountPurchaseScan} 定时补偿（认领 + 退避 + 死信）、
 * T+1 对账兜底（另开票）——全部踩同一购买单号，两态分叉（无扣分流水 = 补扣；有流水未 DONE =
 * 只需推进）被幂等吸收，收敛动作恒为三段全量重放。
 * <p>
 * **积分不足**是业务永久失败：单即置 {@code DEAD}（不占补偿重试轮次），一分未扣；
 * 用户充值后带原 token 重放即自愈（finish 守卫 {@code state <> 1} 从 DEAD 可达）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DrawCountPurchaseCase {

    private final IRaffleDrawCountPurchaseService raffleDrawCountPurchaseService;
    private final IPointsAccountService pointsAccountService;

    /**
     * 购买入口（HTTP {@code POST /api/lottery/purchase/draw-count}）：DONE 重放纯读返回；
     * 其余状态顺势补齐三段（每段幂等，重复执行零副作用）。
     */
    public DrawCountPurchaseResultVO purchaseDrawCount(String userId, String token, int quantity) {
        DrawCountPurchaseOrderEntity order = raffleDrawCountPurchaseService.createOrder(userId, token, quantity);
        if (order.getState() != com.betterschool.marketing.types.raffle.DrawCountPurchaseState.DONE) {
            deductForOrder(order);
            raffleDrawCountPurchaseService.completeOrder(order.getPurchaseOrderId(),
                    order.getUserId(), order.getQuantity());
        }
        return raffleDrawCountPurchaseService.resultOf(order);
    }

    /**
     * 购买单补偿（xxl {@code drawCountPurchaseScan} 调度）：扫到点的 {@code CREATED} 单，
     * 抢占式认领（条件更新 + 退避）后补齐三段——扣分（流水幂等，已扣过即跳过）→ 入账 → 置 DONE。
     * 重试耗尽（{@link IRaffleDrawCountPurchaseService#COMPENSATE_MAX_RETRY}）置 DEAD 等人工介入。
     *
     * @return 本轮真正收敛（置 DONE）的单数
     */
    public int compensateDueOrders(int max) {
        int completed = 0;
        for (DrawCountPurchaseOrderEntity order : raffleDrawCountPurchaseService.listDueOrders(max)) {
            // 抢占：条件更新递增 retry_count 并写退避时点；被其他实例抢先 / 状态漂移则跳过
            if (!raffleDrawCountPurchaseService.claimDueOrder(order)) {
                continue;
            }
            try {
                deductForOrder(order);
                raffleDrawCountPurchaseService.completeOrder(order.getPurchaseOrderId(),
                        order.getUserId(), order.getQuantity());
                completed++;
                log.info("[DrawCountPurchaseCase] 购买单补偿收敛 purchaseOrderId={} attempt={}",
                        order.getPurchaseOrderId(), order.getRetryCount() + 1);
            } catch (Exception e) {
                log.error("[DrawCountPurchaseCase] 购买单补偿失败 purchaseOrderId={} attempt={}",
                        order.getPurchaseOrderId(), order.getRetryCount() + 1, e);
                if (order.getRetryCount() + 1 >= IRaffleDrawCountPurchaseService.COMPENSATE_MAX_RETRY) {
                    raffleDrawCountPurchaseService.markOrderDead(order.getPurchaseOrderId());
                    log.error("[DrawCountPurchaseCase] 购买单进入死信，需人工介入 purchaseOrderId={}",
                            order.getPurchaseOrderId());
                }
            }
        }
        return completed;
    }

    /**
     * 第二段：积分域扣分（points 仓储本地事务，流水先行幂等）。
     * 「积分不足」以业务码落地——单置 DEAD 后抛 {@code LOTTERY_INSUFFICIENT_POINTS}（语义不被抹平）；
     * 其余失败抛系统异常，单留 CREATED 由扫描收敛（瞬时故障自愈）。
     */
    private void deductForOrder(DrawCountPurchaseOrderEntity order) {
        PointsDeductResultVO result = pointsAccountService.deduct(order.getUserId(), order.getPointsCost(),
                PointsReasonType.REDEEM_DRAW_COUNT, order.getPurchaseOrderId());
        if (result.isSuccess()) {
            return;
        }
        if (ResponseCode.POINTS_INSUFFICIENT_POINTS.getCode().equals(result.getCode())) {
            raffleDrawCountPurchaseService.markOrderDead(order.getPurchaseOrderId());
            log.error("[DrawCountPurchaseCase] 购买单积分不足置死信（充值后带原 token 重放自愈）purchaseOrderId={}",
                    order.getPurchaseOrderId());
            throw new AppException(ResponseCode.LOTTERY_INSUFFICIENT_POINTS);
        }
        log.warn("[DrawCountPurchaseCase] 购买扣分失败 purchaseOrderId={} code={}",
                order.getPurchaseOrderId(), result.getCode());
        throw new AppException(ResponseCode.SYSTEM_ERROR);
    }
}
