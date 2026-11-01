package com.betterschool.marketing.types.points;

/**
 * 积分域自有常量：本域的消费组与 Redis key 前缀。
 * 跨域 topic 已收进 {@code types.common.Constants}（生产方与消费方分属不同域的那几个）。
 */
public class Constants {

    /** 本域消费 bs-lottery-drawn 的消费组：同 topic 多方订阅必须各用一组（同组会抢消息） */
    public final static String LOTTERY_DRAWN_CONSUMER_GROUP = "bs-points-lottery-drawn-group";

    /** 本域 Redis key 前缀（与 drawcount / config / raffle / inventory 各占一段，避免同实例下串味） */
    public final static String POINTS_KEY_PREFIX = "bs:points:";
}
