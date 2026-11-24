package com.betterschool.types.common;

import java.util.Map;

/**
 * 通用常量：Redis key、MQ topic、上传限制等
 */
public class Constants {

    public final static String VERIFY_CODE_KEY = "bs:verify-code:";

    public final static String USER_REGISTER_TOPIC = "bs-user-register";

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

    /** 营销库存对账告警 topic（生产方 = 营销对账轮 drawReconScan，outbox 独立落库投递）：
     *  单体消费落 BOTTOM_SYSTEM 维护者告警通知，dedupKey = MARKETING:RECON:<roundNo>:<checkType>:<bizId> */
    public final static String RECON_ALERT_TOPIC = "bs-marketing-recon-alert";

    /**
     * 系统助手账号（系统消息通道发送者）：固定 user_id，schema.sql 种子行（phone 占位不参与登录）。
     * 信用分等 CHAT 通道通知由它经 chat 发送管线落入用户的「系统助手」1v1 会话
     */
    public final static String SYSTEM_USER_ID = "00000000-0000-0000-0000-000000000001";

    /** 通知事件 topic：业务事务内写 outbox，通知域消费者统一落 notification + 按通道投递（事件驱动改造） */
    public final static String NOTIFY_EVENT_TOPIC = "bs-notify-event";

    /** 通知 CHAT 通道投递 topic：通知落库事务内 outbox 写入，消费后经 chat 发送管线落系统消息 */
    public final static String NOTIFY_CHAT_TOPIC = "bs-notify-chat";

    /** 通知 BANNER 通道推送 topic：通知落库事务内 outbox 写入，消费后经 WS 推 NOTIF 帧（在线者，漏推由前端 seq 水位补拉兜底） */
    public final static String NOTIFY_PUSH_TOPIC = "bs-notify-push";

    /** 超时中心 taskType（业务域前缀命名，spec §7.4；取值记于 spec §8）：商品订单 15 分钟支付超时 */
    public final static String PRODUCT_ORDER_PAY_TIMEOUT = "PRODUCT_ORDER_PAY_TIMEOUT";

    /**
     * WS 连接票据 Redis key 前缀（60s 一次性，握手原子取删）。chat WS 端点握手用。
     */
    public final static String WS_TICKET_KEY = "bs:ws-ticket:";

    /** WS 连接票据有效期（秒） */
    public final static long WS_TICKET_TTL_SECONDS = 60;

    /**
     * WS 连接路由表 Redis key 前缀（多实例推送路由）：value = {@code 实例 URL|sessionId}。
     * 握手注册、心跳续期、断连 CAS 注销；推送侧查它定向到目标实例的 pub/sub 频道（漏推仍由补拉兜底）
     */
    public final static String WS_ROUTE_KEY = "bs:ws-route:";

    /** WS 路由条目 TTL（秒）：客户端心跳 25s、60s 判死，90s 容忍丢两次心跳 */
    public final static long WS_ROUTE_TTL_SECONDS = 90;

    /**
     * WS 跨实例推送 pub/sub 定向频道前缀（多实例推送路由）：channel = 前缀 + 实例 URL。
     * 每个实例只订阅自己的频道，发布方按路由查到的实例 URL 精确发布——fire-and-forget，
     * 无回执无持久化，漏推由客户端 (roomId, seq) 增量补拉兜底；Redis Cluster 下如需定向语义换 Sharded Pub/Sub
     */
    public final static String WS_PUSH_CHANNEL_PREFIX = "bs:ws-push:";

    /** 上传通道单张图片大小上限：5MB */
    public final static long UPLOAD_IMAGE_MAX_BYTES = 5 * 1024 * 1024;

    /** 上传通道允许的图片扩展名 → Content-Type（jpg/png/webp） */
    public final static Map<String, String> UPLOAD_IMAGE_CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp");

}
