package com.betterschool.marketing.types.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 营销服务统一响应码（points / lottery 两域合并成一份）。成功码固定 "200"（前端 unwrap 已认）；
 * 业务失败用本服务自带的 4xxx 码（无全局注册表），info 放逐条固定的中文文案。
 * <p>
 * 码值（字符串）是前端 {@code api/marketing.ts} 的分支依据，**一字不改**；
 * 两域里同名不同码、或同名同码但 info 文案不同的常量，按域加前缀区分
 * （{@code POINTS_} / {@code LOTTERY_}）。
 */
@Getter
@AllArgsConstructor
public enum ResponseCode {

    SUCCESS("200", "成功"),
    UNAUTHORIZED("401", "未登录或登录已过期"),
    FORBIDDEN("403", "无权限"),
    NOT_FOUND("404", "资源不存在"),
    SYSTEM_ERROR("999", "系统繁忙，请稍后重试"),

    /*
     * 400 是**跨域同码但文案不同**的常量：合并前积分侧是「参数错误」、
     * 抽奖侧是「参数不合法」，而集成测试逐字断言这条 info（不带 info 的 AppException 重载也用 getInfo()），
     * 所以按域拆开——码值同为 "400"，前端只认码，行为零变更。域内自行选对的那个。
     * （票 10 起配置域自取第三个：CONFIG_PARAM_ERROR，文案与积分侧一致。）
     */
    POINTS_PARAM_ERROR("400", "参数错误"),
    LOTTERY_PARAM_ERROR("400", "参数不合法"),
    CONFIG_PARAM_ERROR("400", "参数错误"),

    /*
     * 4xxx 业务码段（无全局注册表）：info 是**逐条固定**的中文文案——
     * 前端按 code 分支取用，不做拼接、不由后端动态生成。
     */

    // points（4101 与 lottery 的 4306 同名不同码，故加 POINTS_ 前缀）
    POINTS_INSUFFICIENT_POINTS("4101", "积分不足"),

    // lottery（4306 与 points 同名不同码，故加 LOTTERY_ 前缀）
    LOTTERY_ACTIVITY_NOT_STARTED("4301", "活动还没开始"),
    LOTTERY_ACTIVITY_ENDED("4302", "活动已结束"),
    NO_CHANCE("4303", "抽奖次数不足"),
    DAILY_LIMIT_REACHED("4304", "今天的抽奖次数已用完"),
    PRIZE_NOT_FOUND("4305", "奖品不存在"),
    /** 积分不够兑：文案与积分服务 4101 逐字一致，前端按码出 toast */
    LOTTERY_INSUFFICIENT_POINTS("4306", "积分不足"),
    /** 没配兑换价（`redeem_points` 为 NULL）或不是实物档 */
    PRIZE_NOT_REDEEMABLE("4307", "该奖品不可兑换"),
    /** 兑奖品的库存扣不出来（池子空了且非池库存也见底） */
    OUT_OF_STOCK("4308", "该奖品已兑完"),
    /** 领奖：不存在 / 不是本人的 / 状态不是待领取（已领取或积分入账） */
    PRIZE_NOT_CLAIMABLE("4309", "该奖品不可领取"),
    /** 确认收货（票 14 发货闭环）：记录不存在 / 不是本人的 / 状态不是已发货 */
    PRIZE_NOT_RECEIVABLE("4314", "该奖品不可确认收货"),

    // raffle（draw_* 新模型，票 11 起；前端尚无分支，文案给票 13 出错时直接用）
    /** 默认概率表未预热（Redis 无 bs:raffle:table:{strategyId}:default）：DB 直算降级入口归票 13 接。
     *  4310 留空未占用（旧 lottery 段止于 4309，新段从 4311 起以便肉眼区分新旧模型）。 */
    RAFFLE_WARMUP_MISSING("4311", "抽奖活动准备中，请稍后再试"),

    /** 中奖奖品的库存预扣与兜底都扣不出来（配额空 + 兜底空 / 兜底没配）：本次发放失败 */
    RAFFLE_ISSUE_FAILED("4313", "奖品发放失败，请稍后再试"),

    // inventory（draw_* 之外的库存新段，票 12 起；尚无 HTTP 面，文案给票 13/14 出错时直接用）
    /** 库存 / 桶配置不存在或已停用（inv_id 查无此行、state 非 open、兑换确认找不到预扣结转行等配置与状态错） */
    INVENTORY_PARAM_ERROR("400", "参数错误");

    private final String code;
    private final String info;
}
