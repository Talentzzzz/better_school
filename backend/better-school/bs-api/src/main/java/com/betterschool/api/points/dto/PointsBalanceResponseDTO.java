package com.betterschool.api.points.dto;

import lombok.Data;

/**
 * 积分余额（对外 HTTP）：余额 = 未过期桶的 {@code SUM(remain)}，没有余额列，也没有余额为负的可能。
 */
@Data
public class PointsBalanceResponseDTO {

    private Integer balance;
}
