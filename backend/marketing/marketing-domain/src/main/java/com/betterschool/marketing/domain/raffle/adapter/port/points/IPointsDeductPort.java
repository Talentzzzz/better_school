package com.betterschool.marketing.domain.raffle.adapter.port.points;

/**
 * 抽奖域 → 积分域的兑换扣分端口（CLAUDE.md §1：跨域写组合不在域服务间直接注入）。
 * 实现在 infrastructure，委托 {@code IPointsAccountService.deduct}（reasonType 固定
 * REDEEM_DRAW_COUNT——抽奖域只兑次数）。业务失败以结果对象返回、不抛异常：
 * 「积分不足」的语义不能被异常抹平。
 */
public interface IPointsDeductPort {

    /** 扣分结果：success = 已扣（或幂等重放）；insufficient = 积分不足；balance = 扣后余额 */
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
     * 兑换扣分（幂等键 = refId，即兑换单 id）：FIFO 跨桶扣减，凑不够返回「积分不足」而不扣任何一行。
     */
    PointsDeductOutcome deduct(String userId, int amount, String refId);
}
