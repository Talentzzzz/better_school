package com.betterschool.marketing.types.common;

/**
 * 跨域 MQ topic（**生产方与消费方分属不同域**的 wire 契约）：多域并进同一工程后只留一份。
 * <p>
 * 为什么必须收在一处：topic 名字是生产方与消费方的**隐式约定**——两条边各自只见自己那一半代码，
 * 字面量抄两份时只改一边不会编译报错、也不会测试报错，症状是「消费方订阅到了另一个 topic、消息静默丢失」。
 * <p>
 * 域**自有**的常量不在这里（Redis key 前缀、消费组、开桶比例…），
 * 它们留在 {@code types.<域>.Constants}：那些只被本域读，放 common 会在第二域并入时撞名。
 */
public final class Constants {

    /** 中奖 topic：生产方 = 抽奖域，消费方 = 积分域（POINTS 档加分）与单体（落 notification） */
    public final static String LOTTERY_DRAWN_TOPIC = "bs-lottery-drawn";

    /** 积分变动 topic：生产方 = 积分域，消费方 = 单体（落 notification） */
    public final static String POINTS_CHANGED_TOPIC = "bs-points-changed";

    private Constants() {
    }
}
