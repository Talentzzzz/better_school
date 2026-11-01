package com.betterschool.marketing.types.drawcount;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 抽奖次数变动原因（桶模型，sql.md 积分段）：入账落 {@code draw_count_bucket.reason_type}。
 * <p>
 * 消费明细（{@code draw_count_detail}）**没有 reason_type 列**——每次抽奖扣 1 次，
 * 幂等键直接是抽奖记录 id（{@code ref_id}）。消费侧没有枚举取值。
 * <p>
 * （旧 {@code types.lottery.enums.DrawCountReasonType} 已随票 13 删除，本枚举是唯一口径。）
 */
@Getter
@AllArgsConstructor
public enum DrawCountReasonType {

    /** 积分兑换：入账，{@code ref_id} = 兑换单 id（与 points_detail 的 REDEEM_DRAW_COUNT 同值、同事务写入） */
    REDEEM("积分兑换"),

    /** 行为返利（签到/支付等，规则见 action_rebate_config）：入账，{@code ref_id} = 发放幂等键 */
    REBATE("行为返利");

    private final String desc;
}
