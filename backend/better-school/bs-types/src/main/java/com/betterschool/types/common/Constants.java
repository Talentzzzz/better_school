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

    public final static String COMMENT_REVIEW_TOPIC = "bs-comment-review";

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
     * 超时中心 taskType：审核租约 5 分钟超时（spec §5.3，计时起点 = **领单时刻**，5min 是审核员的裁决窗口）。
     * bizId 是复合键 {@code {taskId}:{claimSeq}}——理由同 {@link #ERRAND_LOCK_TIMEOUT}：只用 taskId 时
     * 「回收 → 再领单」会撞上未取消的旧任务被静默忽略，导致租约永久失效。
     */
    public final static String REVIEW_LEASE_TIMEOUT = "REVIEW_LEASE_TIMEOUT";

    /**
     * WS 连接票据 Redis key 前缀（60s 一次性，握手原子取删）。票据与端点无关——chat 与 review
     * 两个 WS 端点共用同一票据签发能力（票 10）；角色过滤由 review 握手拦截器承担。
     */
    public final static String WS_TICKET_KEY = "bs:ws-ticket:";

    /** WS 连接票据有效期（秒） */
    public final static long WS_TICKET_TTL_SECONDS = 60;

    /**
     * 审核端 presence 在线集合 Redis Hash key（票 10，spec §5.3）：field=userId、
     * value=最近活跃毫秒；连接建立写入、心跳刷新、断开删除。在线集合可查可共享，不是锁。
     */
    public final static String REVIEW_PRESENCE_KEY = "bs:review:presence";

    /**
     * 审核派单可观测：累计领单次数 Redis 计数 key（票 11，spec §9）。领单提交成功 +1，
     * 租约超时回收 +1 落 {@link #REVIEW_METRIC_LEASE_TIMEOUT_KEY}，两者之比即租约超时率。
     * 口径为进程生命周期累计（Redis 只做计数不做真相源，重启丢数 = 少计 = 观测低估，可接受）；
     * 主动放回不计（那是审核员主动行为，不是超时）。
     */
    public final static String REVIEW_METRIC_CLAIM_COUNT_KEY = "bs:review:metric:claim-count";

    /** 审核派单可观测：累计租约超时回收次数 Redis 计数 key（票 11；含惰性兜底回收的批次数） */
    public final static String REVIEW_METRIC_LEASE_TIMEOUT_KEY = "bs:review:metric:lease-timeout-count";

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
