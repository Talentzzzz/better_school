package com.betterschool.domain.draw.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * draw_record_detail.type 的动作枚举：落库与 draw_record.state 每推进一步各落一条
 * —— DRAW(抽中落库，state 起点 待兑换) / EXCHANGE(兑换确认，→已兑换) /
 * SHIP(发货，→已发货) / COMPLETE(完成，→已完成)。
 * uk_record_type 保证同一记录同一动作只落一条：state 推进重试幂等（重投只会在 UPDATE 守卫上落空）。
 * <p>
 * 票 14 的发货闭环落位（动作 × 奖品类型）：
 * <ul>
 *   <li>VIRTUAL / DRAW_COUNT：消费中奖事件时落 EXCHANGE + COMPLETE（同一事务两条，
 *       state 终点 已完成）——积分 / 次数由消费该事件的积分域入账，本域只做状态事实</li>
 *   <li>PHYSICAL：消费中奖事件时落 SHIP（state 终点 已发货），再调库存页的
 *       confirmIssued 登记兑换确认（库存侧落 {@code type = 2} 明细，{@code waiting → issued}
 *       由奖品域的合并异步完成）；用户确认收货落 COMPLETE（终点 已完成）</li>
 * </ul>
 * 每个动作的 {@code targetState} 恒等于「落这条明细时推进到的状态」。
 */
@AllArgsConstructor
@Getter
public enum DrawRecordActionEnum {

    DRAW("DRAW", "待兑换"),
    EXCHANGE("EXCHANGE", "已兑换"),
    SHIP("SHIP", "已发货"),
    COMPLETE("COMPLETE", "已完成"),
    ;

    /** 动作类型列值 */
    private final String code;
    /** 该动作推进后的 draw_record.state */
    private final String targetState;

    public static DrawRecordActionEnum fromCode(String code) {
        return Arrays.stream(values()).filter(action -> action.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知抽奖记录动作类型: " + code));
    }
}
