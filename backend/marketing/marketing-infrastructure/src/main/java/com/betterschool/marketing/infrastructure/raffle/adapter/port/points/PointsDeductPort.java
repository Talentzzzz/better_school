package com.betterschool.marketing.infrastructure.raffle.adapter.port.points;

import com.betterschool.marketing.domain.points.model.valobj.PointsDeductResultVO;
import com.betterschool.marketing.domain.points.service.IPointsAccountService;
import com.betterschool.marketing.domain.raffle.adapter.port.points.IPointsDeductPort;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.points.PointsReasonType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 积分域兑换扣分端口的实现：委托 {@code IPointsAccountService.deduct}（同进程同库，
 * reasonType 固定 REDEEM_DRAW_COUNT——REQUIRED 传播下并入调用方的兑换事务），
 * 结果对象翻成端口自己的 {@link PointsDeductOutcome}。
 */
@Component
@RequiredArgsConstructor
public class PointsDeductPort implements IPointsDeductPort {

    private final IPointsAccountService pointsAccountService;

    @Override
    public PointsDeductOutcome deduct(String userId, int amount, String refId) {
        PointsDeductResultVO result = pointsAccountService.deduct(userId, amount, PointsReasonType.REDEEM_DRAW_COUNT, refId);
        if (result.isSuccess()) {
            return PointsDeductOutcome.ok(result.getBalance());
        }
        if (ResponseCode.POINTS_INSUFFICIENT_POINTS.getCode().equals(result.getCode())) {
            return PointsDeductOutcome.insufficient(result.getBalance());
        }
        return PointsDeductOutcome.failed();
    }
}
