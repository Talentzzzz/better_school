package com.betterschool.api.lottery.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 活动信息（免登录）：活动页 banner 与抽奖主卡的定价都取自这里。
 * <p>
 * 相对旧模型：{@code slogan} 与 {@code dailyLimit} 无新模型载体，已删除；
 * {@code drawCountPrice} 暂以后端常量返回（旧定价列随旧活动表删除）。
 */
@Data
public class LotteryActivityResponseDTO {

    private String name;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    /** NOT_STARTED / ONGOING / ENDED（state=close 与过了 end_at 都归 ENDED） */
    private String status;
    /** 一次抽奖次数的积分价（前端展示「100 积分 = 1 次」） */
    private Integer drawCountPrice;
    /** 抽奖次数有效期天数 */
    private Integer drawCountValidDays;
}
