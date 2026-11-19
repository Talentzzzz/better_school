package com.betterschool.marketing.domain.raffle.service.drawcount;

/**
 * 抽奖次数账户服务（桶模型，sql.md 积分段，与积分桶同构）。
 * <p>
 * **与抽奖同域**：抽奖次数是抽奖域内的资产（{@code draw_count_bucket}），不再是独立领域；
 * 抽奖主链路直接注入本服务扣次（不经 port），购买次数由同包的
 * {@link IRaffleDrawCountPurchaseService} 编排（只碰积分域的扣分边）。
 * <p>
 * 余额读口径：剩余次数 = 未过期且未扣完的桶 remain 之和（SUM 读，没有余额列）。
 */
public interface IDrawCountAccountService {

    /**
     * 积分购买次数入账：幂等键 = {@code (userId, REDEEM, purchaseOrderId)}，重试不重复加次数。
     * 「扣积分 → 入账次数」的顺序编排归 {@link IRaffleDrawCountPurchaseService}（各自域内原子，无跨域事务）。
     */
    boolean creditForPurchase(String userId, String purchaseOrderId, int amount);

    /**
     * 行为返利入账（规则见 {@code action_rebate_config} 的 DRAW_COUNT 行）：
     * 规则未配或已关闭 → 返回 false 什么都不做；命中则落一行 REBATE 桶，
     * 幂等键 = {@code (userId, REBATE, refId)}（refId = 调用方的发放幂等键）。
     */
    boolean creditForRebate(String actionType, String userId, String refId);

    /** 剩余可用次数 = 未过期且未扣完的桶 remain 之和 */
    int getBalance(String userId);

    /**
     * 抽奖扣次（每次抽恒扣 1 次，幂等键 = 抽奖记录 id）：FIFO 从最早过期的桶扣，
     * 同一记录重放返回 true 不重复扣；剩余不足返回 false 且不产生半扣。
     */
    boolean deductForDraw(String userId, String recordId);

    /** 抽奖次数有效期天数（域内 draw_count_config，后台读 + 入账算 expire_at + 抽奖读侧展示同口径） */
    int getValidDays();

    /** 改抽奖次数有效期天数（后台管理面，ADMIN）；天数必须为正，不合法抛 CONFIG_PARAM_ERROR */
    void updateValidDays(int days);
}
