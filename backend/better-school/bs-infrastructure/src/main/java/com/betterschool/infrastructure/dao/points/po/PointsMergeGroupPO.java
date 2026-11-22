package com.betterschool.infrastructure.dao.points.po;

import lombok.Data;

/**
 * 桶合并候选分组（points-compaction 票）：合并任务第一轮快照扫描的结果——
 * 每个拥有「≥ MERGE_MIN_BUCKETS 张有效小额桶」的用户一组，total 是该组 SUM(remain)
 * （合并后新桶的 amount/remain），bucketCount 是该组桶行数（与置 0 更新的影响行数对账）。
 */
@Data
public class PointsMergeGroupPO {

    private String userId;

    /** 该组小额桶 remain 之和（合并后新桶的 amount = remain） */
    private int total;

    /** 该组桶行数（>= MERGE_MIN_BUCKETS；与清零更新的预期影响行数对账） */
    private int bucketCount;
}
