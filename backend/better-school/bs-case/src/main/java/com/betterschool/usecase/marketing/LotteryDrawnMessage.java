package com.betterschool.usecase.marketing;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 抽奖中奖事件（topic {@code bs-lottery-drawn}，生产方 = 抽奖服务）：单体消费后落中奖通知。
 * <p>
 * 积分服务按 {@code prizeType == POINTS} 消费同一条事件加分，单体按它落通知——两个消费方互不知晓。
 * **谢谢参与不发**（生产方约定），通知文案按 {@code prizeType} 呈现（事件体不含奖品名，不反查生产方）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LotteryDrawnMessage {

    /** 抽奖记录 id：本服务落通知的幂等键（ref_id） */
    private Long recordId;
    private String userId;
    private Long prizeId;
    /** PHYSICAL / POINTS / THANKS（字符串而非枚举：跨服务序列化最稳） */
    private String prizeType;
    /** 仅 prizeType = POINTS 有意义 */
    private Integer pointsAmount;
}
