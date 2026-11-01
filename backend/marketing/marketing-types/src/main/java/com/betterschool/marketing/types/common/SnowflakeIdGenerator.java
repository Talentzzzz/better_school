package com.betterschool.marketing.types.common;

/**
 * 桶业务 ID（bucket_id）生成器：snowflake 形状（41 位毫秒时间戳 + 10 位 worker + 12 位序列），
 * 自定义纪元 2025-01-01。纯 Java、无依赖，points / drawcount 两域共用一份。
 * <p>
 * 为什么不用自增主键：桶表的主键 id 是无业务含义的物理自增（本库口径），
 * 消费明细与对外引用挂的是 {@code bucket_id} 这个业务标识，两者必须分开。
 * <p>
 * workerId 取随机 10 位：本服务单实例部署，碰撞概率可忽略；真撞了由
 * {@code uk_bucket_id} 吸收（INSERT IGNORE 会把它误判成入账幂等命中，漏记一笔——
 * 概率远低于硬件故障，接受）。跨实例部署前应改为显式分配 workerId。
 */
public final class SnowflakeIdGenerator {

    /** 自定义纪元：2025-01-01 00:00:00 UTC */
    private static final long EPOCH = 1735689600000L;
    private static final long WORKER_ID_BITS = 10;
    private static final long SEQUENCE_BITS = 12;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private static final SnowflakeIdGenerator INSTANCE = new SnowflakeIdGenerator();

    private final long workerId;
    private long sequence;
    private long lastTimestamp = -1L;

    private SnowflakeIdGenerator() {
        this.workerId = (long) (Math.random() * (1L << WORKER_ID_BITS));
    }

    /** 生成一个全局唯一的桶业务 ID */
    public static synchronized long nextId() {
        long now = System.currentTimeMillis();
        if (now < INSTANCE.lastTimestamp) {
            // 时钟回拨：退回上一时间戳自旋等追平（本服务单实例，等待时间即回拨量）
            now = INSTANCE.lastTimestamp;
        }
        if (now == INSTANCE.lastTimestamp) {
            INSTANCE.sequence = (INSTANCE.sequence + 1) & SEQUENCE_MASK;
            if (INSTANCE.sequence == 0) {
                while ((now = System.currentTimeMillis()) <= INSTANCE.lastTimestamp) {
                    // 序列耗尽，自旋到下一毫秒
                }
            }
        } else {
            INSTANCE.sequence = 0;
        }
        INSTANCE.lastTimestamp = now;
        return ((now - EPOCH) << TIMESTAMP_SHIFT) | (INSTANCE.workerId << WORKER_ID_SHIFT) | INSTANCE.sequence;
    }
}
