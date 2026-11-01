package com.betterschool.infrastructure.adapter.port.moderation;

import com.betterschool.domain.moderation.adapter.port.IReviewDispatchMetricsPort;
import com.betterschool.types.common.Constants;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 审核派单可观测计数器（票 11，spec §9）：Redis 原子计数领单次数与租约超时回收次数，
 * 两者之比即租约超时率。落 Redis 的依据是 spec「DB 权威，Redis 只做信号/计数」——计数非资金、
 * 非真相源（事实在 review_task.recycle_count/claim_seq，但两者混合主动放回分不出超时），
 * 进程生命周期累计、重启丢数＝少计＝观测低估，方向安全可接受。
 * 计数点：领单提交成功 +1；租约超时回收（中心回调 / 惰性兜底，含批次数）+1；主动放回**不计**。
 */
@Component
public class ReviewDispatchMetrics implements IReviewDispatchMetricsPort {

    private final RedissonClient redissonClient;

    public ReviewDispatchMetrics(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public void recordClaim() {
        counter(Constants.REVIEW_METRIC_CLAIM_COUNT_KEY).incrementAndGet();
    }

    @Override
    public void recordLeaseTimeout(int count) {
        if (count > 0) {
            counter(Constants.REVIEW_METRIC_LEASE_TIMEOUT_KEY).addAndGet(count);
        }
    }

    @Override
    public Snapshot snapshot() {
        return new Snapshot(
                counter(Constants.REVIEW_METRIC_CLAIM_COUNT_KEY).get(),
                counter(Constants.REVIEW_METRIC_LEASE_TIMEOUT_KEY).get());
    }

    private RAtomicLong counter(String key) {
        return redissonClient.getAtomicLong(key);
    }
}
