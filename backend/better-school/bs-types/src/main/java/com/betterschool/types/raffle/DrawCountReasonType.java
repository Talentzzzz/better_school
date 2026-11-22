package com.betterschool.types.raffle;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 抽奖次数变动原因（桶模型，sql.md 积分段）：入账落 {@code draw_count_bucket.reason_type}。
 * <p>
 * 消费明细（{@code draw_count_detail}）**没有 reason_type 列**——每次抽奖扣 1 次，
 * 幂等键直接是抽奖记录 id（{@code ref_id}）。消费侧没有枚举取值。
 * <p>
 * 抽奖次数是**抽奖域内的资产**（与积分桶同构、有效期各自独立配置），故与抽奖域常量同包。
 */
@Getter
@AllArgsConstructor
public enum DrawCountReasonType {

    /** 积分购买次数：入账，{@code ref_id} = 购买单 id（与 points_detail 的 REDEEM_DRAW_COUNT 同单号写入） */
    PURCHASE("积分购买"),

    /** 行为返利（签到/支付等，规则见 action_rebate_config）：入账，{@code ref_id} = 发放幂等键 */
    REBATE("行为返利"),

    /** 孤儿预扣退还（对账 job，决策不可重放时）：入账，{@code ref_id} = 抽奖记录 id（= 原扣次明细 ref_id，幂等键不重落） */
    ORPHAN_REFUND("孤儿预扣退还");

    private final String desc;
}
