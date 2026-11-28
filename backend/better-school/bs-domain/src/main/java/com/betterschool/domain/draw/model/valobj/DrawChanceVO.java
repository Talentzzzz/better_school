package com.betterschool.domain.draw.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 我的抽奖次数（读侧出参）：available = 未过期桶 SUM(remain)，todayDrawn = 今日已抽数。
 */
@Data
@AllArgsConstructor
public class DrawChanceVO {

    private Integer available;
    private Integer todayDrawn;
}
