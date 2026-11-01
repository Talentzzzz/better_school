package com.betterschool.marketing.types.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 抽奖中奖事件（topic {@code bs-lottery-drawn}）：生产方 = 抽奖域，
 * 消费方 = 本服务的积分域（按 {@code prizeType == VIRTUAL} 加分）与单体（落 notification）。
 * <p>
 * 谢谢参与**不发**这条事件，但每次抽奖都会落一行抽奖记录；
 * 积分域只在 {@code prizeType == "VIRTUAL"} 时加分（实物档 / 谢谢参与忽略）。
 * <p>
 * 票 07 起这里是全工程唯一一份：合并前 lottery 侧（生产）与 points 侧（消费）各有一份同字段副本，
 * 分别用全参构造与 setter 构造；现落一份 {@code @Data @NoArgsConstructor @AllArgsConstructor}，
 * 两种用法都保留（消费侧 Jackson 反序列化要无参构造）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LotteryDrawnMessage {

    /** 抽奖记录 id：本服务入账幂等键的 ref_id（MQ 重投不重复加分） */
    private Long recordId;
    private String userId;
    private Long prizeId;
    /** VIRTUAL / PHYSICAL / THANKS（票 13 新模型口径；旧的 POINTS 值已作废） */
    private String prizeType;
    /** 积分档发放的积分数（非积分档为空） */
    private Integer pointsAmount;
}
