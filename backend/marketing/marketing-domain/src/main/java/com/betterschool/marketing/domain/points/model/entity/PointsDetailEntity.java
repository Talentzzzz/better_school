package com.betterschool.marketing.domain.points.model.entity;

import com.betterschool.marketing.types.points.PointsReasonType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分消费明细（points_detail）：一次消费按 FIFO 跨几个桶就落几行，每行只记「该桶被扣掉多少」。
 */
@Data
public class PointsDetailEntity {

    private Long id;
    private String userId;
    /** 被扣的桶业务 ID（FIFO 顺序） */
    private String bucketId;
    /** 本次从该桶扣掉的积分数（恒正） */
    private Integer amount;
    private PointsReasonType reasonType;
    /** 来源业务 id = 兑换单 id（兑换幂等键） */
    private String refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
