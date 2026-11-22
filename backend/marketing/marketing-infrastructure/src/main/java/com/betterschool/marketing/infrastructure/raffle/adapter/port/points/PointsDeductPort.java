package com.betterschool.marketing.infrastructure.raffle.adapter.port.points;

import com.betterschool.marketing.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.marketing.domain.points.service.IPointsAccountService;
import com.betterschool.marketing.domain.raffle.adapter.port.points.IPointsDeductPort;
import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.entity.DrawCountPurchaseOrderEntity;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.points.PointsReasonType;
import com.betterschool.marketing.types.raffle.DrawCountPurchaseState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 积分域扣分端口的实现（只服务「用积分购买抽奖次数」），并承担 ADR-0014 的**组合事务边界**：
 * 一个 {@code TransactionTemplate}（REQUIRED）内先 INSERT 购买单（经 {@link IRaffleRepository}，
 * 同为 REQUIRED 并入）再委托 {@code IPointsAccountService.deduct}（积分仓储同为 REQUIRED 并入）
 * ——「单在 ⇒ 分已扣」在这一个物理事务里成立。积分不足 / 失败 {@code setRollbackOnly}，
 * 购买单与扣分一起消失，再以结果对象把语义交回调用方（不足翻 LOTTERY_INSUFFICIENT_POINTS，
 * 不被异常抹平）。
 * <p>
 * 为什么组合事务在本类而不是 case 层或积分侧：域服务不注入事务模板（仓库纪律），
 * 事务边界归基础设施；跨域的两张表由**消费方（raffle）**写自己的单、经端口委托积分域扣分，
 * points 域不知道积分被花去买了什么。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointsDeductPort implements IPointsDeductPort {

    private final IPointsAccountService pointsAccountService;
    private final IRaffleRepository raffleRepository;
    private final TransactionTemplate transactionTemplate;

    @Override
    public PointsDeductOutcome deductForOrder(DrawCountPurchaseOrderEntity order) {
        PointsDeductOutcome[] holder = new PointsDeductOutcome[1];
        transactionTemplate.execute(status -> {
            // 同事务第一步：落购买单（DEDUCTED）；撞 uk 抛 DuplicateKeyException 由调用方转幂等推进
            raffleRepository.insertPurchaseOrder(toCommittedShape(order));
            // 同事务第二步：积分域扣分（幂等键 = 购买单 id，REQUIRED 并入本事务）
            PointsDeductResultVO result = pointsAccountService.deduct(
                    order.getUserId(), order.getPointsCost(), PointsReasonType.REDEEM_DRAW_COUNT,
                    order.getPurchaseOrderId());
            if (result.isSuccess()) {
                holder[0] = PointsDeductOutcome.ok(result.getBalance());
                return null;
            }
            // 业务失败：整事务回滚（购买单不落、积分不扣），语义以结果对象交回
            status.setRollbackOnly();
            if (ResponseCode.POINTS_INSUFFICIENT_POINTS.getCode().equals(result.getCode())) {
                holder[0] = PointsDeductOutcome.insufficient(result.getBalance());
            } else {
                log.warn("[PointsDeductPort] 购买扣分失败 purchaseOrderId={} code={}",
                        order.getPurchaseOrderId(), result.getCode());
                holder[0] = PointsDeductOutcome.failed();
            }
            return null;
        });
        return holder[0] != null ? holder[0] : PointsDeductOutcome.failed();
    }

    /** 落库形态：状态固定 DEDUCTED、审计时间取事务内时点（调用方传入的草稿态字段不落库） */
    private DrawCountPurchaseOrderEntity toCommittedShape(DrawCountPurchaseOrderEntity order) {
        LocalDateTime now = LocalDateTime.now();
        return new DrawCountPurchaseOrderEntity(order.getPurchaseOrderId(), order.getUserId(),
                order.getQuantity(), order.getPointsCost(),
                DrawCountPurchaseState.DEDUCTED,
                0, null, null, now, now);
    }
}
