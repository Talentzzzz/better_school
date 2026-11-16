package com.betterschool.marketing.types.points;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 积分变动原因：入账落 {@code points_bucket.reason_type}、出账落 {@code points_detail.reason_type}。
 * <p>
 * 用户可见的积分变动只有下面前四个取值（sql.md 积分段各列 COMMENT）；入账与出账分别只出现在各自的表里。
 * {@code COMPACTION} 只落桶表、是余额不变的账务整形，**不出现在用户流水里**
 * （{@code selectTransactions} / {@code countTransactions} 的桶分支按它过滤）。
 */
@Getter
@AllArgsConstructor
public enum PointsReasonType {

    /** 抽奖中奖（积分档）：入账，{@code ref_id} = 抽奖记录 id（MQ 幂等键） */
    LOTTERY_WIN("抽奖中奖"),

    /** 行为返利（签到/支付等，规则见 action_rebate_config）：入账，{@code ref_id} = 发放幂等键 */
    REBATE("行为返利"),

    /** 兑换抽奖次数：出账，{@code ref_id} = 兑换单 id（Dubbo 幂等键） */
    REDEEM_DRAW_COUNT("兑换抽奖次数"),

    /** 兑换奖品：出账，{@code ref_id} = 兑换单 id（Dubbo 幂等键） */
    REDEEM_PRIZE("兑换奖品"),

    /** 桶合并（points-compaction 票）：入账（余额不变的账务整形），{@code ref_id} = 合并批次号 */
    COMPACTION("桶合并");

    private final String desc;
}
