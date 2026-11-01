package com.betterschool.marketing.usecase.raffle;

import com.betterschool.marketing.domain.drawcount.service.IDrawCountAccountService;
import com.betterschool.marketing.domain.raffle.model.valobj.RedeemIntentVO;
import com.betterschool.marketing.domain.raffle.model.valobj.RedeemResultVO;
import com.betterschool.marketing.domain.raffle.service.IRaffleExchangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 兑换次数的跨域编排（一域一仓储后新增的 case 边）：**两域顺序调用，各自域内原子，无跨域事务**
 * （CLAUDE.md §1）。
 * <p>
 * 顺序与失败口径：
 * <ol>
 *   <li>抽奖域扣积分（积分域内原子；不足即抛 {@code LOTTERY_INSUFFICIENT_POINTS}，一分不扣、一桶不加）</li>
 *   <li>次数域入账 1 次（次数域内原子；幂等键 = 上一步给出的兑换单号）</li>
 * </ol>
 * 两步共用同一个 {@code hash(userId, token)} 单号作幂等键，因此中间态可安全重试：扣分成功而入账失败
 * （进程崩溃 / 次数域短暂故障）时客户端带同一 token 重试，第一步幂等跳过、第二步补上；
 * 反向不会发生（入账前必先扣分）。HTTP 路径的失败恢复就是「返回失败让客户端重试」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RaffleExchangeCase {

    private final IRaffleExchangeService raffleExchangeService;
    private final IDrawCountAccountService drawCountAccountService;

    /** 兑 1 次抽奖次数：先扣分、后入账，返回兑换单 id 与兑后次数余额 */
    public RedeemResultVO exchangeDrawCount(String userId, String token) {
        RedeemIntentVO intent = raffleExchangeService.exchangeDrawCount(userId, token);
        drawCountAccountService.creditForRedeem(userId, intent.getRedeemOrderId(), 1);
        int balance = drawCountAccountService.getBalance(userId);
        log.info("[RaffleExchangeCase] 兑换次数成功 userId={} redeemOrderId={} cost={} balance={}",
                userId, intent.getRedeemOrderId(), intent.getPointsCost(), balance);
        return new RedeemResultVO(intent.getRedeemOrderId(), balance);
    }
}
