package com.betterschool.marketing.api.lottery.dto;

import lombok.Data;

/**
 * 奖品目录的一格（免登录）。
 * <p>
 * 库存**只暴露台账口径**：{@code remaining} = 台账剩余份数（surplus_qty）、
 * {@code total} = 投放总量（surplus + 在途/已发放）——不暴露 Redis 桶与在途预扣，
 * 实现细节的抖动不该传导到页面。
 * <p>
 * 不限量档（inv_id=0，含 THANKS 与不限量的 VIRTUAL）给 {@code unlimited = true}、
 * {@code remaining} 与 {@code total} 都为 null。
 * <p>
 * 相对旧模型：{@code image} 与 {@code redeemPoints} 无新模型载体，已删除；
 * {@code type} 取值 VIRTUAL / PHYSICAL / THANKS（旧 POINTS 档归入 VIRTUAL）。
 */
@Data
public class LotteryPrizeResponseDTO {

    private Long prizeId;
    private Integer tier;
    private String name;
    /** VIRTUAL / PHYSICAL / THANKS */
    private String type;
    /** 积分档发放的积分数，其余为 null */
    private Integer pointsAmount;
    private Integer remaining;
    private Integer total;
    private Boolean unlimited;
}
