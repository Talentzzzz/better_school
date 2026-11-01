package com.betterschool.marketing.domain.raffle.service;

import com.betterschool.marketing.domain.raffle.adapter.port.points.IPointsDeductPort;
import com.betterschool.marketing.domain.raffle.model.valobj.RedeemIntentVO;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.IdempotencyKeys;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.raffle.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 兑抽奖次数的积分侧实现：只在积分域内扣分（域内原子），入账由 case 层顺序推进次数域，
 * 不在一个事务里跨域（CLAUDE.md §1）。
 * <p>
 * **幂等**：{@code redeemOrderId = hash(userId, token)} —— 同一 token 重放恒映射同一单号
 * （不落重放表），积分流水唯一键 {@code (userId, REDEEM_DRAW_COUNT, refId)} 就是幂等互斥：
 * 重放时积分不再扣。这也是本方法**必须**由调用方传 token 的原因——单号不能现编，
 * 否则网络重试就是一笔新兑换。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaffleExchangeServiceImpl implements IRaffleExchangeService {

    private final IPointsDeductPort pointsDeductPort;

    @Override
    public RedeemIntentVO exchangeDrawCount(String userId, String token) {
        long redeemOrderId = IdempotencyKeys.of(userId, token);
        IPointsDeductPort.PointsDeductOutcome outcome =
                pointsDeductPort.deduct(userId, Constants.DRAW_COUNT_EXCHANGE_PRICE, redeemOrderId);
        if (!outcome.success) {
            if (outcome.insufficient) {
                throw new AppException(ResponseCode.LOTTERY_INSUFFICIENT_POINTS);
            }
            log.warn("[RaffleExchange] 积分扣分失败 redeemOrderId={}", redeemOrderId);
            throw new AppException(ResponseCode.SYSTEM_ERROR);
        }
        log.info("[RaffleExchange] 积分扣分完成 userId={} redeemOrderId={} cost={}",
                userId, redeemOrderId, Constants.DRAW_COUNT_EXCHANGE_PRICE);
        return new RedeemIntentVO(redeemOrderId, Constants.DRAW_COUNT_EXCHANGE_PRICE);
    }
}
