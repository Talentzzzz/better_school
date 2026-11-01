package com.betterschool.marketing.domain.drawcount.service;

/**
 * 抽奖次数账户服务（桶模型，sql.md 积分段，与积分域同构）。
 * <p>
 * 本域在票 10 只建**能力**（入账 / 扣次 / 余额 / 过期），供票 13 的抽奖链路消费——
 * 兑换入账与抽奖扣次的调用编排归抽奖域，本票不改抽奖业务。
 */
public interface IDrawCountAccountService {

    /**
     * 积分兑换入账：幂等键 = {@code (userId, REDEEM, redeemOrderId)}，重试不重复加次数。
     * 「扣积分 → 入账次数」的跨域顺序编排归 case 层（各自域内原子，无跨域事务）。
     */
    boolean creditForRedeem(String userId, Long redeemOrderId, int amount);

    /**
     * 行为返利入账（规则见 {@code action_rebate_config} 的 DRAW_COUNT 行）：
     * 规则未配或已关闭 → 返回 false 什么都不做；命中则落一行 REBATE 桶，
     * 幂等键 = {@code (userId, REBATE, refId)}（refId = 调用方的发放幂等键）。
     */
    boolean creditForRebate(String actionType, String userId, Long refId);

    /** 剩余可用次数 = 未过期且未扣完的桶 remain 之和 */
    int getBalance(String userId);

    /**
     * 抽奖扣次（每次抽恒扣 1 次，幂等键 = 抽奖记录 id）：FIFO 从最早过期的桶扣，
     * 同一记录重放返回 true 不重复扣；剩余不足返回 false 且不产生半扣。
     */
    boolean deductForDraw(String userId, Long recordId);

    /** 懒过期落账清理（xxl-job 调度）：只置零、不删行 */
    int expireBuckets();
}
