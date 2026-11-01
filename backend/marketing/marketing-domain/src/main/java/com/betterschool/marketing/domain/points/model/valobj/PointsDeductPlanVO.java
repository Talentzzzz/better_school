package com.betterschool.marketing.domain.points.model.valobj;

import com.betterschool.marketing.domain.points.model.entity.PointsBucketEntity;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一笔扣分在内存里算好的方案：**被扣光的桶** + **至多一张部分扣的桶**。
 * <p>
 * 方案只回答「扣哪个桶、扣多少」，不触库；落库是另一段，照这份方案原样写下。
 * 「部分扣的桶最多一张」是 FIFO 顺序本身的性质，不是额外约束：一旦某个桶的 remain 大于还差的额度，
 * 扣掉差额后本笔归零、摊派即止，后面不会再出现第二个。两个集合分开摆、且摊派在出现部分扣时就结束，
 * 都是为了让这条性质长在类型上，而不是散落在循环里。
 */
@Getter
public class PointsDeductPlanVO {

    /** 被扣光的桶（FIFO 顺序；各自扣掉桶上全部 remain） */
    private final List<PointsBucketDeductionVO> drainedBuckets;
    /** 至多一条：remain 大于还差的额度，只扣差额（整笔恰好扣完时为 null） */
    private final PointsBucketDeductionVO partialBucket;

    private PointsDeductPlanVO(List<PointsBucketDeductionVO> drainedBuckets, PointsBucketDeductionVO partialBucket) {
        this.drainedBuckets = List.copyOf(drainedBuckets);
        this.partialBucket = partialBucket;
    }

    /**
     * 按 FIFO 顺序把本次额度摊到桶上——纯内存，不读也不写库。
     *
     * @param buckets 该用户全部可用桶，已按 {@code (expire_at, id)} 排好、{@code remain} 恒大于 0
     *                （加锁读的结果原样传入）
     * @param amount  本次要扣的额度（恒正）；够不够由调用方按同一批加锁行先判
     */
    public static PointsDeductPlanVO allocate(List<PointsBucketEntity> buckets, int amount) {
        List<PointsBucketDeductionVO> drainedBuckets = new ArrayList<>();
        PointsBucketDeductionVO partialBucket = null;
        int remaining = amount;
        for (PointsBucketEntity bucket : buckets) {
            if (remaining == 0) {
                break;
            }
            int take = Math.min(bucket.getRemain(), remaining);
            PointsBucketDeductionVO deduction = new PointsBucketDeductionVO(bucket.getBucketId(), take);
            if (take == bucket.getRemain()) {
                drainedBuckets.add(deduction);
                remaining -= take;
                continue;
            }
            // remain 大于还差的额度：扣掉差额、桶上留下余额。这就是本次摊派的最后一条
            partialBucket = deduction;
            remaining = 0;
            break;
        }
        if (remaining > 0) {
            // 调用方已判过「够不够」，这条不可达；留着是为了某天不变量被破坏时宁可整笔失败，
            // 也不写出一份「扣不足额」的方案
            throw new IllegalStateException("可用桶合计不足本次扣分，凑不齐 amount=" + amount + "，还差 " + remaining);
        }
        return new PointsDeductPlanVO(drainedBuckets, partialBucket);
    }

    /** 方案覆盖的全部扣减：扣光的桶在前、部分扣的桶在最后（落库按这个顺序走） */
    public List<PointsBucketDeductionVO> deductions() {
        List<PointsBucketDeductionVO> deductions = new ArrayList<>(drainedBuckets);
        if (partialBucket != null) {
            deductions.add(partialBucket);
        }
        return List.copyOf(deductions);
    }
}
