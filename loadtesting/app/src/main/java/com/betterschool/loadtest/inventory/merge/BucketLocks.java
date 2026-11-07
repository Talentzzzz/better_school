package com.betterschool.loadtest.inventory.merge;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 桶级本地互斥：同一桶的合并串行化，避免两条重复唤醒同时翻同一格（翻不动就整格回滚，纯浪费）。
 * <p>
 * 只是本机优化，不是正确性依赖 —— 正确性由 {@code markMerged} 的 affected 行数校验保证，
 * 多实例部署时仍然安全（个别格会被抢，回滚后由兜底扫描补一次）。
 */
@Component
public class BucketLocks {

    private final ReentrantLock[] locks = new ReentrantLock[64];

    public BucketLocks() {
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    public boolean tryLock(long skuId, int bucketId) {
        return locks[index(skuId, bucketId)].tryLock();
    }

    public void unlock(long skuId, int bucketId) {
        locks[index(skuId, bucketId)].unlock();
    }

    private int index(long skuId, int bucketId) {
        long h = skuId * 31L + bucketId;
        return Math.floorMod((int) (h ^ (h >>> 32)), locks.length);
    }
}
