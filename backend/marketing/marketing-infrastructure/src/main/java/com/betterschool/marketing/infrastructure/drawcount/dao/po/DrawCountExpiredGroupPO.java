package com.betterschool.marketing.infrastructure.drawcount.dao.po;

import lombok.Data;

/**
 * 过期次数桶分组（与积分侧同构）：过期桶加锁分组读的结果——
 * total 是该组 SUM(remain)，bucketCount 是该组桶行数（对账用）。
 */
@Data
public class DrawCountExpiredGroupPO {

    private String userId;

    /** 该组过期桶 remain 之和 */
    private int total;

    /** 该组桶行数（置 0 更新的预期影响行数） */
    private int bucketCount;
}
