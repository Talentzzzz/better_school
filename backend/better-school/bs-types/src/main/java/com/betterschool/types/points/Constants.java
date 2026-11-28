package com.betterschool.types.points;

/**
 * 积分域自有常量：本域的消费组与 Redis key 前缀。
 * 跨域 topic 已收进 {@code types.common.Constants}（生产方与消费方分属不同域的那几个）。
 */
public class Constants {

    /** 本域消费 bs-lottery-drawn 的消费组：同 topic 多方订阅必须各用一组（同组会抢消息） */
    public final static String LOTTERY_DRAWN_CONSUMER_GROUP = "bs-points-lottery-drawn-group";

    /** 本域 Redis key 前缀（与 draw / rebate / inventory 各占一段，避免同实例下串味；抽奖次数与抽奖同域，共用 draw 段） */
    public final static String POINTS_KEY_PREFIX = "bs:points:";

    /**
     * 桶合并的单桶余额阈值（points-compaction 票）：remain &le; 该值的桶算「小额桶」。
     * 同一阈值硬编码在 {@code PointsMapper.xml} 的 selectMergeCandidateGroups /
     * selectSmallBucketsForUpdate 两条 SQL 的 {@code remain <= 10} 里，改这里要同步改 SQL。
     */
    public final static int MERGE_BUCKET_THRESHOLD = 10;

    /** 桶合并的最少桶数：同用户小额桶数量达到该值才合并（单桶没有合并意义） */
    public final static int MERGE_MIN_BUCKETS = 2;

    /** 桶合并任务每轮最多处理的用户数（合并不紧急，一轮扫不完下一轮接着扫） */
    public final static int COMPACTION_BATCH_LIMIT = 100;
}
