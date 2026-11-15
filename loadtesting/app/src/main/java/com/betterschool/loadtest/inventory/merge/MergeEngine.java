package com.betterschool.loadtest.inventory.merge;

import com.betterschool.loadtest.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * 时间格合并引擎：一次只合「一个桶 + 一个秒格」。
 * <p>
 * 为什么必须只合最老一格：多格并行合并会让后面的格先翻，前面的格还留在 state=0，
 * 后续按时间顺序推进的状态（兑换确认）就会落到预扣之前，守卫失真。
 * 压测里只有 DEDUCT 一段，但保持和线上同构，结论才可迁移。
 * <p>
 * 一格一个事务；翻账行数必须等于统计行数，对不上说明格被别的实例抢走了 —— 整格回滚，交给下一次唤醒。
 */
@Service
public class MergeEngine {

    private final InventoryRepository repository;

    public MergeEngine(InventoryRepository repository) {
        this.repository = repository;
    }

    @Transactional(rollbackFor = Exception.class)
    public MergeOutcome mergeOldestCell(long skuId, int bucketId) {
        Timestamp cell = repository.oldestCell(skuId, bucketId);
        if (cell == null) {
            return MergeOutcome.none();
        }
        InventoryRepository.CellStat stat = repository.cellStat(skuId, bucketId, cell);
        if (stat.rows() == 0) {
            return MergeOutcome.none();
        }

        int marked = repository.markMerged(skuId, bucketId, cell, stat.maxId());
        if (marked != stat.rows()) {
            throw new CellStolenException(
                    "时间格被其他实例抢先合并 sku=" + skuId + " bucket=" + bucketId
                            + " cell=" + cell + " expect=" + stat.rows() + " actual=" + marked);
        }

        repository.applyWaiting(skuId, stat.qty());
        repository.decBucketCopy(skuId, bucketId, stat.qty());
        return new MergeOutcome(true, stat.rows(), stat.qty());
    }

    public static final class MergeOutcome {

        private final boolean merged;
        private final long rows;
        private final long qty;

        private MergeOutcome(boolean merged, long rows, long qty) {
            this.merged = merged;
            this.rows = rows;
            this.qty = qty;
        }

        public static MergeOutcome none() {
            return new MergeOutcome(false, 0, 0);
        }

        public boolean merged() {
            return merged;
        }

        public long rows() {
            return rows;
        }

        public long qty() {
            return qty;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MergeOutcome other)) return false;
            return merged == other.merged && rows == other.rows && qty == other.qty;
        }

        @Override
        public int hashCode() {
            return Objects.hash(merged, rows, qty);
        }

        @Override
        public String toString() {
            return "MergeOutcome[merged=" + merged + ", rows=" + rows + ", qty=" + qty + "]";
        }
    }

    public static class CellStolenException extends RuntimeException {
        public CellStolenException(String message) {
            super(message);
        }
    }
}
