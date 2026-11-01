package com.betterschool.marketing.domain.raffle.adapter.port.drawcount;

/**
 * 抽奖域 → 抽奖次数域的写边端口（CLAUDE.md §1：跨域写组合不在域服务间直接注入）。
 * 实现在 infrastructure，委托 {@code IDrawCountAccountService}；余额读侧也走这里，
 * 让次数域只有这一个被抽奖域认识的缝。
 */
public interface IDrawCountPort {

    /**
     * 抽奖扣次（每次抽恒扣 1 次，幂等键 = 抽奖记录 id）：FIFO 从最早过期的桶扣。
     * 同一记录重放返回 true 不重复扣；剩余不足返回 false 且不产生半扣（无任何副作用）。
     */
    boolean deductForDraw(String userId, Long recordId);

    /** 积分兑换入账：幂等键 = {@code (userId, REDEEM, redeemOrderId)}，重试不重复加次数 */
    boolean creditForRedeem(String userId, Long redeemOrderId, int amount);

    /** 剩余可用次数 = 未过期且未扣完的桶 remain 之和 */
    int getBalance(String userId);
}
