package com.betterschool.infrastructure.adapter.port.errand;

import com.betterschool.domain.errand.adapter.port.IErrandGrabAbusePort;
import com.betterschool.domain.errand.adapter.repository.IErrandRepository;
import com.betterschool.types.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

/**
 * 防占单计数与冷却的 Redis 实现（票 05）：ZSET 按「放弃时刻」精确滑动窗口，String 存冷却到期时刻。
 *
 * <p>Redis 是判定加速层、不是事实源——事实源是 {@code errand_order_event}。读路径 Redis 报错时
 * 回落事件表（见 {@link #cooldownUntil}）；写路径 Redis 写失败 = 少计 = 宽恕，由调用方吞掉异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrandGrabAbusePort implements IErrandGrabAbusePort {

    private final RedissonClient redissonClient;
    private final IErrandRepository errandRepository;

    @Override
    public long recordAbandon(String userId, Long orderId, int lockSeq, LocalDateTime windowFloor) {
        RScoredSortedSet<String> window = windowOf(userId);
        // member = {orderId}:{lockSeq}：每次锁单至多一次放弃，天然唯一（同一人重抢同单是不同轮次）
        window.add((double) toMillis(LocalDateTime.now()), orderId + ":" + lockSeq);
        // 精确滑动窗口：先裁掉窗口外的旧放弃（下界含端点，与事件表回落口径 create_time >= now-24h 对齐），
        // 再取窗口内计数（非自然日，避免跨零点刷新逃逸）
        window.removeRangeByScore(Double.NEGATIVE_INFINITY, true, (double) toMillis(windowFloor), true);
        return window.size();
    }

    @Override
    public LocalDateTime startCooldown(String userId, Duration cooldown) {
        RBucket<Long> bucket = redissonClient.getBucket(Constants.ERRAND_GRAB_COOLDOWN_KEY + userId);
        long untilMillis = toMillis(LocalDateTime.now().plus(cooldown));
        // SET NX EX：已冷却中则不改（幂等，不叠加延长）
        if (bucket.trySet(untilMillis, cooldown.toSeconds(), TimeUnit.SECONDS)) {
            return fromMillis(untilMillis);
        }
        Long existing = bucket.get();
        return existing == null ? fromMillis(untilMillis) : fromMillis(existing);
    }

    @Override
    public void resetWindow(String userId) {
        windowOf(userId).delete();
    }

    @Override
    public LocalDateTime cooldownUntil(String userId, LocalDateTime windowFloor, int threshold, Duration cooldown) {
        try {
            Long untilMillis = redissonClient.<Long>getBucket(Constants.ERRAND_GRAB_COOLDOWN_KEY + userId).get();
            return untilMillis == null ? null : fromMillis(untilMillis);
        } catch (Exception e) {
            log.warn("[ErrandGrabAbuse] 冷却判定读 Redis 失败，回落事件表 userId={}", userId, e);
            return fallbackCooldownUntil(userId, windowFloor, threshold, cooldown);
        }
    }

    /**
     * Redis 降级：查事件表完成判定。窗口内主动放弃次数达阈值，且最近一次放弃往后推一个冷却时长仍在
     * 未来（即该次放弃若触发过冷却则此刻仍有效），判为冷却中；否则放行。
     *
     * <p>已知偏差（方向偏严，记录备查、不补偿）：事件表数不出「触发冷却后窗口已重置」，会把重置前的
     * 旧放弃也数进去而误判冷却；受「最近一次放弃 + 冷却仍在未来」约束，最多多封一个冷却时长。
     * 冷却触发不落事件（spec §4.6），故事件表侧无法忠实重建重置语义。
     */
    private LocalDateTime fallbackCooldownUntil(String userId, LocalDateTime windowFloor, int threshold, Duration cooldown) {
        try {
            if (errandRepository.countRecentAbandons(userId, windowFloor) < threshold) {
                return null;
            }
            LocalDateTime latest = errandRepository.latestAbandonTime(userId, windowFloor);
            if (latest == null) {
                return null;
            }
            LocalDateTime until = latest.plus(cooldown);
            return until.isAfter(LocalDateTime.now()) ? until : null;
        } catch (Exception e) {
            log.warn("[ErrandGrabAbuse] 回落事件表查询亦失败，放行 userId={}", userId, e);
            return null;
        }
    }

    private RScoredSortedSet<String> windowOf(String userId) {
        return redissonClient.getScoredSortedSet(Constants.ERRAND_GRAB_ABANDON_KEY + userId);
    }

    private long toMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private LocalDateTime fromMillis(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }
}
