package com.betterschool.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一响应码
 */
@Getter
@AllArgsConstructor
public enum ResponseCodeEnum {

    SUCCESS("200", "成功"),
    PARAM_ERROR("400", "参数错误"),
    UNAUTHORIZED("401", "未登录或登录已过期"),
    FORBIDDEN("403", "无权限"),
    NOT_FOUND("404", "资源不存在"),
    BUSINESS_ERROR("500", "业务处理失败"),
    SYSTEM_ERROR("999", "系统繁忙，请稍后重试"),
    // —— 营销域（合并前 marketing types.common.ResponseCodeEnum，同职责枚举按拍板归并进本枚举）——
    // 四个 400 段域别名与 PARAM_ERROR 同值，保留域语义命名
    POINTS_PARAM_ERROR("400", "参数错误"),
    LOTTERY_PARAM_ERROR("400", "参数不合法"),
    CONFIG_PARAM_ERROR("400", "参数错误"),
    INVENTORY_PARAM_ERROR("400", "参数错误"),
    POINTS_INSUFFICIENT_POINTS("4101", "积分不足"),
    LOTTERY_ACTIVITY_NOT_STARTED("4301", "活动还没开始"),
    LOTTERY_ACTIVITY_ENDED("4302", "活动已结束"),
    NO_CHANCE("4303", "抽奖次数不足"),
    DAILY_LIMIT_REACHED("4304", "今天的抽奖次数已用完"),
    PRIZE_NOT_FOUND("4305", "奖品不存在"),
    LOTTERY_INSUFFICIENT_POINTS("4306", "积分不足"),
    PRIZE_NOT_REDEEMABLE("4307", "该奖品不可兑换"),
    OUT_OF_STOCK("4308", "该奖品已兑完"),
    PRIZE_NOT_CLAIMABLE("4309", "该奖品不可领取"),
    PRIZE_NOT_RECEIVABLE("4314", "该奖品不可确认收货"),
    DRAW_WARMUP_MISSING("4311", "抽奖活动准备中，请稍后再试"),
    DRAW_ISSUE_FAILED("4313", "奖品发放失败，请稍后再试");

    private final String code;
    private final String info;
}
