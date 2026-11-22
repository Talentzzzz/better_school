package com.betterschool.types.raffle;

/**
 * 抽奖域的 MQ 常量（票 14）：本域**自有**的消费组名。
 * 跨域 topic 在 {@code types.common.Constants}（生产方与消费方各只见自己那一半代码，故必须收在一处）；
 * 消费组只被本域读，放这里（与 {@code types.points.Constants} 同构）。
 */
public final class RaffleMqConstants {

    /**
     * 本域消费 {@code bs-lottery-drawn} 的消费组（票 14 发货闭环）：同 topic 多方订阅必须各用一组
     * （同组会抢消息）——本组只管发货状态机，积分域是 {@code bs-points-lottery-drawn-group}、
     * 单体是 {@code bs-monolith-lottery-drawn-group}。
     */
    public final static String LOTTERY_DRAWN_SHIPPING_CONSUMER_GROUP = "bs-raffle-shipping-group";

    private RaffleMqConstants() {
    }
}
