package com.betterschool.marketing.domain.raffle.service;

import com.betterschool.marketing.domain.raffle.model.valobj.RedeemIntentVO;

/**
 * 兑抽奖次数的**积分侧**（一域一仓储后本域只做自己那半边）：扣分在本域内原子完成，
 * 入账由 case 层顺序调次数域的 {@code IDrawCountPort} 推进（CLAUDE.md §1：跨域写组合归 case，
 * 不在一个事务里跨域）。
 * <p>
 * **幂等**：返回的 {@code redeemOrderId = hash(userId, token)}（见
 * {@link com.betterschool.marketing.types.common.IdempotencyKeys}）——同一 token 重放恒映射同一单号，
 * 于是两域各自的流水唯一键（积分 {@code (userId, REDEEM_DRAW_COUNT, refId)} /
 * 次数 {@code (userId, REDEEM, refId)}）既是幂等互斥又不需要重放表。
 * 「扣分成功、入账未成功」的中间态靠重试收敛：重放时积分不再扣、次数照旧入账一次。
 */
public interface IRaffleExchangeService {

    /**
     * 积分兑 1 次抽奖次数：本域只扣积分，积分不足抛 LOTTERY_INSUFFICIENT_POINTS，一分不扣。
     *
     * @param token 前端为「这一次兑换意图」生成的 uuid；重试必须带同一个（幂等键来源）
     * @return 幂等单号与本次花费，交给 case 层继续推进次数域入账
     */
    RedeemIntentVO exchangeDrawCount(String userId, String token);
}
