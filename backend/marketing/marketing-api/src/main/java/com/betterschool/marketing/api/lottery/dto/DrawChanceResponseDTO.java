package com.betterschool.marketing.api.lottery.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 我的抽奖次数：{@code available} = 未过期桶的 {@code SUM(remain)}（没有余额列），
 * {@code todayDrawn} = 今日已抽次数（抽奖主卡 banner 的「今日已抽 N」）。
 */
@Data
@AllArgsConstructor
public class DrawChanceResponseDTO {

    private Integer available;
    private Integer todayDrawn;
}
