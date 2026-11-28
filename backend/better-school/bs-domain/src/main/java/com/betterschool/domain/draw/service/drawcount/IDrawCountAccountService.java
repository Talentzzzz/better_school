package com.betterschool.domain.draw.service.drawcount;

/**
 * 抽奖次数账户服务（桶模型，sql.md 积分段，与积分桶同构）。
 * <p>
 * **与抽奖同域**：抽奖次数是抽奖域内的资产（{@code draw_count_bucket}），不再是独立领域；
 * 抽奖主链路直接注入本服务扣次（不经 port），购买次数的三段编排（落单 → 扣积分 → 入账）
 * 在 case 层 {@code DrawCase}，入账是其中的第三段。
 * <p>
 * 余额读口径：剩余次数 = 未过期且未扣完的桶 remain 之和（SUM 读，没有余额列）。
 */
public interface IDrawCountAccountService {

    /**
     * 积分购买次数入账：幂等键 = {@code (userId, REDEEM, purchaseOrderId)}，重试不重复加次数。
     * 「扣积分 → 入账次数」的顺序编排归 case 层 {@code DrawCase}（各自域内原子，无跨域事务）。
     */
    boolean addForPurchase(String userId, String purchaseOrderId, int amount);

    /**
     * 按显式金额入账行为返利（{@code PostRebateCreditConsumer} 用）：金额 &le; 0 直接返回 false 不落桶；
     * 幂等键 = {@code (userId, REBATE, refId)}（撞 {@code uk_user_reason_ref} 不重复加次数）。
     */
    boolean addForRebate(String userId, String refId, int amount);

    /** 剩余可用次数 = 未过期且未扣完的桶 remain 之和 */
    int getBalance(String userId);

    /**
     * 抽奖扣次（每次抽恒扣 1 次，幂等键 = 抽奖记录 id）：FIFO 从最早过期的桶扣，
     * 同一记录重放返回 true 不重复扣；剩余不足返回 false 且不产生半扣。
     */
    boolean deductForDraw(String userId, String recordId);

    /**
     * 孤儿预扣退还（对账 job，决策不可重放时）：按次数桶幂等键入账一桶新次数，
     * {@code refId} = 抽奖记录 id（= 原扣次明细的 {@code ref_id}，幂等键不与消费侧冲突），
     * 重复退还撞 {@code uk_user_reason_ref} 不重复加。数量必须为正，不合法抛参数异常
     * （对账方据此把坏行推入退避 / 死信）。
     */
    void refundForOrphanDraw(String userId, String recordId, int amount);

    /** 抽奖次数有效期天数（域内 draw_count_config，后台读 + 入账算 expire_at + 抽奖读侧展示同口径） */
    int getValidDays();

    /** 改抽奖次数有效期天数（后台管理面，ADMIN）；天数必须为正，不合法抛 CONFIG_PARAM_ERROR */
    void updateValidDays(int days);
}
