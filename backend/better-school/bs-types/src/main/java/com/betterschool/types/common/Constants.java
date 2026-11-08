package com.betterschool.types.common;

import java.util.Map;

/**
 * 通用常量：Redis key、MQ topic、上传限制等
 */
public class Constants {

    public final static String VERIFY_CODE_KEY = "bs:verify-code:";

    public final static String USER_REGISTER_TOPIC = "bs-user-register";

    public final static String ERRAND_PAY_TIMEOUT_TOPIC = "bs-errand-pay-timeout";

    public final static String ERRAND_PAID_TOPIC = "bs-errand-paid";

    public final static String NOTIFICATION_BROADCAST_TOPIC = "bs-notification-broadcast";

    public final static String SEARCH_SYNC_TOPIC = "bs-search-sync";

    /** chat 消息推送 topic：发送事务内 outbox 写入，WS 推送消费（票 04 ChatPushConsumer） */
    public final static String CHAT_PUSH_TOPIC = "bs-chat-push";

    /** chat 已读推送 topic：水位线推进事务内 outbox 写入，WS 推送消费（票 04 ChatPushConsumer） */
    public final static String CHAT_READ_TOPIC = "bs-chat-read";

    /** chat 撤回推送 topic：撤回事务内 outbox 写入（票 05 投递），WS 推送消费（票 04 ChatRecallPushConsumer） */
    public final static String CHAT_RECALL_TOPIC = "bs-chat-recall";

    /** 营销域两个 topic（生产方 = 抽奖/积分服务，outbox 投递）：单体作为消费者订阅落通知（营销票 09）。
     *  消费组必须是 bs-monolith-<topic>-group（同 topic 多方订阅，同组会抢消息、不同组各收全量） */
    public final static String LOTTERY_DRAWN_TOPIC = "bs-lottery-drawn";

    public final static String POINTS_CHANGED_TOPIC = "bs-points-changed";

    /** 超时中心 taskType（业务域前缀命名，spec §7.4；取值记于 spec §8）：商品订单 15 分钟支付超时 */
    public final static String PRODUCT_ORDER_PAY_TIMEOUT = "PRODUCT_ORDER_PAY_TIMEOUT";

    /** 超时中心 taskType：跑腿 15 分钟支付超时（计时起点 = 创建/提交时刻） */
    public final static String ERRAND_PAY_TIMEOUT = "ERRAND_PAY_TIMEOUT";

    /**
     * 超时中心 taskType：跑腿 5 分钟锁单超时（计时起点 = **锁单时刻**，5min 是接单方的确认窗口，
     * 与 {@link #ERRAND_PAY_TIMEOUT} 的「从创建时刻起算」口径不同）。
     * bizId 是复合键 {@code {orderId}:{lockSeq}}——每次锁单一个独立键，陈旧轮次不会被幂等复位成新锁的到期时刻。
     */
    public final static String ERRAND_LOCK_TIMEOUT = "ERRAND_LOCK_TIMEOUT";

    /**
     * WS 连接票据 Redis key 前缀（60s 一次性，握手原子取删）。chat WS 端点握手用。
     */
    public final static String WS_TICKET_KEY = "bs:ws-ticket:";

    /** WS 连接票据有效期（秒） */
    public final static long WS_TICKET_TTL_SECONDS = 60;

    /**
     * 跑腿防占单：主动放弃的滑动窗口计数 Redis ZSET key 前缀（票 05）。
     * member = {@code {orderId}:{lockSeq}}（每次锁单一次放弃、天然唯一），score = 放弃时刻 → 精确滑动窗口。
     */
    public final static String ERRAND_GRAB_ABANDON_KEY = "bs:errand:grab-abandon:";

    /**
     * 跑腿防占单：抢单冷却标记 Redis key 前缀（票 05）。值为冷却到期时刻（epoch millis），
     * {@code SET NX EX} 写入——幂等、不叠加延长。
     */
    public final static String ERRAND_GRAB_COOLDOWN_KEY = "bs:errand:grab-cooldown:";

    /** 上传通道单张图片大小上限：5MB */
    public final static long UPLOAD_IMAGE_MAX_BYTES = 5 * 1024 * 1024;

    /** 上传通道允许的图片扩展名 → Content-Type（jpg/png/webp） */
    public final static Map<String, String> UPLOAD_IMAGE_CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

    /** 跑腿订单配图上限（创建链路校验） */
    public final static int ERRAND_MAX_IMAGES = 6;

}
