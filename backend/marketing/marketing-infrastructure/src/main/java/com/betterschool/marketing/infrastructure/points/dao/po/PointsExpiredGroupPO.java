package com.betterschool.marketing.infrastructure.points.dao.po;

import lombok.Data;

/**
 * 过期桶分组：过期桶加锁分组读的结果——每个有「已过期且仍有余量」桶的用户一组，
 * total 是该组 SUM(remain)，bucketCount 是该组桶行数（与置 0 更新的影响行数对账）。
 */
@Data
public class PointsExpiredGroupPO {

    private String userId;

    /** 该组过期桶 remain 之和 */
    private int total;

    /** 该组桶行数（置 0 更新的预期影响行数） */
    private int bucketCount;
}
