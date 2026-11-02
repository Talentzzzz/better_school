package com.betterschool.marketing.infrastructure.raffle.adapter.port.drawcount;

import com.betterschool.marketing.domain.drawcount.service.IDrawCountAccountService;
import com.betterschool.marketing.domain.raffle.adapter.port.drawcount.IDrawCountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 抽奖次数域端口的实现：纯委托 {@code IDrawCountAccountService}（票 10 的桶模型账户），
 * 事务边界留在次数域自己的域内事务里，本类不做任何编排。
 */
@Component
@RequiredArgsConstructor
public class DrawCountPort implements IDrawCountPort {

    private final IDrawCountAccountService drawCountAccountService;

    @Override
    public boolean deductForDraw(String userId, String recordId) {
        return drawCountAccountService.deductForDraw(userId, recordId);
    }

    @Override
    public boolean creditForRedeem(String userId, String redeemOrderId, int amount) {
        return drawCountAccountService.creditForRedeem(userId, redeemOrderId, amount);
    }

    @Override
    public int getBalance(String userId) {
        return drawCountAccountService.getBalance(userId);
    }
}
