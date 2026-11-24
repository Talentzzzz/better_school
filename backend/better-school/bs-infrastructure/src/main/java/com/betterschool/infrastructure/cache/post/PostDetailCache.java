package com.betterschool.infrastructure.cache.post;

import com.betterschool.domain.post.model.entity.PostEntity;
import com.betterschool.domain.post.model.valobj.PostStatusVO;
import com.betterschool.infrastructure.dao.post.IPostDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 帖子详情缓存（cache-aside 完整版）：
 * - 布隆过滤器防穿透（一定不存在的 id 直接拦截，不再打 DB）
 * - 分布式锁单飞防击穿（同 postId 只放一个请求回填，其余等待后重读缓存）
 * - 随机 TTL + 读时刷新防雪崩（热 key 滑动过期永不过期，冷 key 自然淘汰）
 * - 只缓存 NORMAL 状态的帖子（待审/驳回/删除不缓存，作者视角走 DB）
 * - 只缓存内容（正文/状态/作者/配图，票 05）：三个计数进缓存前置空，读路径每次回库拼装——
 *   缓存与计数写入零耦合，任何计数变化都不需要失效缓存
 * - 所有 Redis 操作异常降级（放行/直查 DB），缓存不可用不影响业务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostDetailCache {

    private static final long BASE_TTL_SECONDS = 5 * 60;
    private static final long RANDOM_TTL_MAX_SECONDS = 5 * 60;
    private static final long EXPECTED_INSERTIONS = 100_000L;
    private static final double FALSE_POSITIVE_PROBABILITY = 0.01;
    private static final int LOCK_RETRY_TIMES = 3;
    private static final long LOCK_WAIT_MILLIS = 50;
    private static final long LOCK_LEASE_SECONDS = 5;

    private static final String DETAIL_KEY = "bs:post:detail:";
    private static final String LOCK_KEY = "bs:post:detail:lock:";
    private static final String BLOOM_KEY = "bs:post:bloom";

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final IPostDao postDao;

    private volatile RBloomFilter<String> bloomFilter;

    @PostConstruct
    public void initBloom() {
        try {
            bloomFilter = redissonClient.getBloomFilter(BLOOM_KEY);
            if (bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_PROBABILITY)) {
                // 首次创建：加载全量帖子 id（软删的也在内，无害——布隆只加不删）
                List<String> postIdList = postDao.selectAllIds();
                postIdList.forEach(bloomFilter::add);
                log.info("帖子布隆过滤器初始化完成，加载 {} 个 id", postIdList.size());
            }
        } catch (Exception e) {
            log.warn("帖子布隆过滤器初始化失败，降级为全部放行", e);
            bloomFilter = null;
        }
    }

    /**
     * 读缓存：命中则刷新 TTL（滑动过期，热数据永不过期）
     */
    public PostEntity get(String postId) {
        try {
            RBucket<String> bucket = redissonClient.getBucket(DETAIL_KEY + postId);
            String json = bucket.get();
            if (json == null) {
                return null;
            }
            bucket.expire(Duration.ofSeconds(randomTtl()));
            return objectMapper.readValue(json, PostEntity.class);
        } catch (Exception e) {
            log.warn("详情缓存读取失败，降级直查 DB postId={}", postId, e);
            return null;
        }
    }

    /**
     * 布隆过滤器判断：false = 一定不存在（拦截）；true = 可能存在（放行，DB 确认）。
     * 降级（Redis 不可用/未初始化）= 放行
     */
    public boolean mightContain(String postId) {
        try {
            return bloomFilter == null || bloomFilter.contains(postId);
        } catch (Exception e) {
            log.warn("布隆过滤器判断失败，放行 postId={}", postId, e);
            return true;
        }
    }

    public void add(String postId) {
        try {
            if (bloomFilter != null) {
                bloomFilter.add(postId);
            }
        } catch (Exception e) {
            log.warn("布隆过滤器 add 失败 postId={}", postId, e);
        }
    }

    /**
     * 回填缓存：仅 NORMAL 帖子可缓存（非 NORMAL 由调用方判断，这里仅存放）。
     * 进缓存前置空三个计数字段（票 05 / spec §3.3）：缓存只存正文/状态/作者/配图，
     * 计数由读路径每次回库拼装——无论调用方传入的实体带没带计数，缓存里都摸不到任何计数。
     * <b>注意</b>：清零是<b>就地</b>修改传入实体（当前唯一调用链 {@link #getOrLoad} 回填后
     * 实体还会被读路径重填实时计数，无观察面；若将来有「put 之后还要用计数」的调用方，先拷贝再传）
     */
    public void put(String postId, PostEntity postEntity) {
        try {
            stripCounts(postEntity);
            redissonClient.getBucket(DETAIL_KEY + postId)
                    .set(objectMapper.writeValueAsString(postEntity), Duration.ofSeconds(randomTtl()));
        } catch (Exception e) {
            log.warn("详情缓存回填失败 postId={}", postId, e);
        }
    }

    /** 计数不进缓存（spec §3.3）：置空即「空值」，读路径拼装库值，不存在陈旧计数快照 */
    private static void stripCounts(PostEntity postEntity) {
        postEntity.setLikeCount(0);
        postEntity.setCommentCount(0);
        postEntity.setViewCount(0);
    }

    public void evict(String postId) {
        try {
            redissonClient.getBucket(DETAIL_KEY + postId).delete();
        } catch (Exception e) {
            log.warn("详情缓存失效失败 postId={}", postId, e);
        }
    }

    /**
     * 缓存读取 + 单飞回填：布隆拦截由调用方先行判断；
     * 回填数据由 loader 提供（DB 查询），仅 NORMAL 状态回填缓存
     */
    public PostEntity getOrLoad(String postId, Supplier<PostEntity> loader) {
        for (int i = 0; i <= LOCK_RETRY_TIMES; i++) {
            PostEntity cachedEntity = get(postId);
            if (cachedEntity != null) {
                return cachedEntity;
            }
            RLock lock = redissonClient.getLock(LOCK_KEY + postId);
            boolean locked = false;
            try {
                locked = lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("详情缓存单飞锁获取失败，直接查 DB postId={}", postId, e);
            }
            if (locked) {
                try {
                    PostEntity againEntity = get(postId); // 双检：等待期间可能已被回填
                    if (againEntity != null) {
                        return againEntity;
                    }
                    PostEntity loadedEntity = loader.get();
                    if (loadedEntity != null && loadedEntity.getStatus() == PostStatusVO.NORMAL) {
                        put(postId, loadedEntity);
                    }
                    return loadedEntity;
                } catch (Exception e) {
                    throw e;
                } finally {
                    try {
                        lock.unlock();
                    } catch (Exception unlockEx) {
                        log.warn("详情缓存单飞锁释放失败 postId={}", postId, unlockEx);
                    }
                }
            }
            // 没拿到锁：等待后重读缓存（等回填完成）
            try {
                Thread.sleep(LOCK_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 重试耗尽：直接查 DB 兜底（保证正确性优先）
        return loader.get();
    }

    private long randomTtl() {
        return BASE_TTL_SECONDS + ThreadLocalRandom.current().nextLong(RANDOM_TTL_MAX_SECONDS);
    }
}
