package com.betterschool.marketing.domain.raffle.service.drawcount;

import com.betterschool.marketing.domain.raffle.adapter.port.points.IPointsDeductPort;
import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.entity.DrawCountPurchaseOrderEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.DrawCountPurchaseResultVO;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.IdempotencyKeys;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.raffle.Constants;
import com.betterschool.marketing.types.raffle.DrawCountPurchaseState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用积分购买抽奖次数（ADR-0014）：**购买单与扣积分同事务落库**（组合事务在 port 适配器，
 * 「单在 ⇒ 分已扣」），次数入账幂等（桶唯一键 {@code (userId, PURCHASE, refId)}），
 * 收敛靠三路重试踩同一单号——客户端带同 token 重放、{@link #compensateDueOrders} 定时补偿、
 * 对账兜底（另开票）。本类不注入 {@code TransactionTemplate}：每一步都是单语句或
 * 仓储/端口自带的原子用例，跨步的一致性由幂等与状态机守卫承担。
 * <p>
 * 与抽奖主链路的关系：**抽奖只扣次数**，从不碰积分；本类只负责把积分换成次数，
 * 两条路径的入口、幂等键都独立。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaffleDrawCountPurchaseServiceImpl implements IRaffleDrawCountPurchaseService {

    /** 补偿重试上限：第 5 次尝试（retry_count 达 4 时认领）仍失败即置 DEAD，人工介入 */
    static final int COMPENSATE_MAX_RETRY = 5;
    /** 第 n 次尝试失败后的退避档（1/2/4/8/16 分钟），由认领动作写进 next_retry_at */
    private static final List<Duration> COMPENSATE_BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4),
            Duration.ofMinutes(8), Duration.ofMinutes(16));

    private final IPointsDeductPort pointsDeductPort;
    private final IDrawCountAccountService drawCountAccountService;
    private final IRaffleRepository raffleRepository;

    @Override
    public DrawCountPurchaseResultVO purchaseDrawCount(String userId, String token, int quantity) {
        if (token == null || token.isBlank() || quantity <= 0
                || quantity > Constants.DRAW_COUNT_PURCHASE_MAX_QUANTITY) {
            throw new AppException(ResponseCode.LOTTERY_PARAM_ERROR);
        }
        String purchaseOrderId = IdempotencyKeys.of(userId, token);
        int pointsCost = quantity * Constants.DRAW_COUNT_PURCHASE_PRICE;

        // Stripe 幂等入口：同 token 重放先查单——DONE 返回原结果；中间态（含死信）顺势补齐
        DrawCountPurchaseOrderEntity existing = raffleRepository.queryPurchaseOrder(purchaseOrderId);
        if (existing == null) {
            DrawCountPurchaseOrderEntity order = new DrawCountPurchaseOrderEntity(
                    purchaseOrderId, userId, quantity, pointsCost,
                    DrawCountPurchaseState.DEDUCTED, 0, null, null, null, null);
            IPointsDeductPort.PointsDeductOutcome outcome;
            try {
                // 组合事务：INSERT 购买单 + 扣积分同一物理事务；积分不足/失败整体回滚，单不存在
                outcome = pointsDeductPort.deductForOrder(order);
            } catch (DuplicateKeyException e) {
                // 并发同 token：另一请求已落单（唯一键是唯一互斥点），转幂等推进
                log.info("[RaffleDrawCountPurchase] 并发同 token 撞购买单唯一键，转幂等推进 purchaseOrderId={}",
                        purchaseOrderId);
                existing = raffleRepository.queryPurchaseOrder(purchaseOrderId);
                if (existing == null) {
                    throw new AppException(ResponseCode.SYSTEM_ERROR);
                }
                return advanceOrder(existing);
            }
            if (!outcome.success) {
                if (outcome.insufficient) {
                    throw new AppException(ResponseCode.LOTTERY_INSUFFICIENT_POINTS);
                }
                log.warn("[RaffleDrawCountPurchase] 积分扣分失败 purchaseOrderId={}", purchaseOrderId);
                throw new AppException(ResponseCode.SYSTEM_ERROR);
            }
            return advanceOrder(order);
        }
        return advanceOrder(existing);
    }

    @Override
    public int compensateDueOrders(int max) {
        List<DrawCountPurchaseOrderEntity> dueOrders = raffleRepository.listDuePurchaseOrders(max);
        int completed = 0;
        for (DrawCountPurchaseOrderEntity order : dueOrders) {
            // 抢占：条件更新递增 retry_count 并写退避时点；被其他实例抢先 / 状态漂移则跳过
            if (!raffleRepository.claimPurchaseOrder(order.getId(), nextBackoffAt(order.getRetryCount()))) {
                continue;
            }
            try {
                completeOrder(order.getPurchaseOrderId(), order.getUserId(), order.getQuantity());
                completed++;
                log.info("[RaffleDrawCountPurchase] 购买单补偿收敛 purchaseOrderId={} attempt={}",
                        order.getPurchaseOrderId(), order.getRetryCount() + 1);
            } catch (Exception e) {
                log.error("[RaffleDrawCountPurchase] 购买单补偿失败 purchaseOrderId={} attempt={}",
                        order.getPurchaseOrderId(), order.getRetryCount() + 1, e);
                if (order.getRetryCount() + 1 >= COMPENSATE_MAX_RETRY) {
                    raffleRepository.markPurchaseOrderDead(order.getId());
                    log.error("[RaffleDrawCountPurchase] 购买单进入死信，需人工介入 purchaseOrderId={}",
                            order.getPurchaseOrderId());
                }
            }
        }
        return completed;
    }

    /** 幂等推进：DONE 直接返回原结果；DEDUCTED / DEAD 顺势补「入账 + 置 DONE」 */
    private DrawCountPurchaseResultVO advanceOrder(DrawCountPurchaseOrderEntity order) {
        if (order.getState() != DrawCountPurchaseState.DONE) {
            completeOrder(order.getPurchaseOrderId(), order.getUserId(), order.getQuantity());
        }
        return new DrawCountPurchaseResultVO(order.getPurchaseOrderId(), order.getPointsCost(),
                drawCountAccountService.getBalance(order.getUserId()));
    }

    /** 收敛动作：次数入账（桶 uk 幂等，重放不重复加）→ 条件更新置 DONE */
    private void completeOrder(String purchaseOrderId, String userId, int quantity) {
        drawCountAccountService.creditForPurchase(userId, purchaseOrderId, quantity);
        raffleRepository.finishPurchaseOrder(purchaseOrderId);
    }

    /** 第 retryCount+1 次尝试的退避档下标（封顶最后一档 16 分钟） */
    private LocalDateTime nextBackoffAt(Integer retryCount) {
        int index = Math.min(retryCount == null ? 0 : retryCount, COMPENSATE_BACKOFF.size() - 1);
        return LocalDateTime.now().plus(COMPENSATE_BACKOFF.get(Math.max(index, 0)));
    }
}
