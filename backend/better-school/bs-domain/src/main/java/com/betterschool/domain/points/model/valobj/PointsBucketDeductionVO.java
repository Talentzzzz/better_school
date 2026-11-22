package com.betterschool.domain.points.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 扣减方案里的一条：某个桶 + 这一笔从它身上扣掉多少（恒正）。
 * 一条落一行消费明细——明细的 (bucket_id, amount) 就是照它写下来的。
 */
@Getter
@AllArgsConstructor
public class PointsBucketDeductionVO {

    /** 桶业务 ID（points_bucket.bucket_id，不是物理自增 id） */
    private final String bucketId;
    /** 这一笔从该桶扣掉的积分 */
    private final Integer amount;
}
