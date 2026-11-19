package com.betterschool.marketing.domain.raffle.adapter.port.points;

import com.betterschool.marketing.domain.raffle.model.entity.DrawCountPurchaseOrderEntity;

/**
 * 抽奖域 → 积分域的扣分端口：**只服务「用积分购买抽奖次数」这一条路径**
 * （抽奖主链路只扣次数，从不碰积分）。实现在 infrastructure，除委托
 * {@code IPointsAccountService.deduct}（reasonType 固定 REDEEM_DRAW_COUNT）外，
 * 还承担 ADR-0014 的组合事务边界：**INSERT 购买单与扣积分同一个物理事务**——
 * 「单在 ⇒ 分已扣」由此成立，补偿重试才永远安全。
 * 业务失败以结果对象返回、不抛异常：「积分不足」的语义不能被异常抹平
 * （失败路径由实现内部回滚事务，购买单不落、积分不扣）。
 */
public interface IPointsDeductPort {

    /** 扣分结果：success = 单已落 + 分已扣；insufficient = 积分不足（事务已回滚，单不存在）；balance = 扣后余额 */
    class PointsDeductOutcome {

        public final boolean success;
        public final boolean insufficient;
        public final Integer balance;

        public PointsDeductOutcome(boolean success, boolean insufficient, Integer balance) {
            this.success = success;
            this.insufficient = insufficient;
            this.balance = balance;
        }

        public static PointsDeductOutcome ok(Integer balance) {
            return new PointsDeductOutcome(true, false, balance);
        }

        public static PointsDeductOutcome insufficient(Integer balance) {
            return new PointsDeductOutcome(false, true, balance);
        }

        public static PointsDeductOutcome failed() {
            return new PointsDeductOutcome(false, false, null);
        }
    }

    /**
     * 组合事务「落购买单 + 扣分」（ADR-0014）：先 INSERT 购买单（{@code state = DEDUCTED}）
     * 再 FIFO 跨桶扣分，同一物理事务提交或回滚。撞 {@code uk_purchase_order_id}
     * 抛 {@code DuplicateKeyException}——并发同 token 的唯一互斥点，由调用方转幂等推进。
     */
    PointsDeductOutcome deductForOrder(DrawCountPurchaseOrderEntity order);
}
