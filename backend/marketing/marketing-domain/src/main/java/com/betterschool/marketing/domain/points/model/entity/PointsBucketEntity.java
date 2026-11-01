package com.betterschool.marketing.domain.points.model.entity;

import com.betterschool.marketing.types.points.PointsReasonType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 积分桶（points_bucket）：每笔入账一行，带自己的过期时间。
 * 它同时是「发放流水」与「事实源」——没有余额列，余额由未过期桶的 remain 求和得出。
 * <p>
 * {@code bucketId} 是业务标识（{@link com.betterschool.marketing.types.common.SnowflakeIdGenerator} 生成），
 * 消费明细与对外引用挂它，不挂物理自增 id。
 */
@Data
public class PointsBucketEntity {

    private Long id;
    /** 业务 ID（对外唯一标识） */
    private Long bucketId;
    private String userId;
    /** 入账积分数（恒正） */
    private Integer amount;
    /** 该桶剩余可用（消费按 FIFO 递减；过期清理置 0） */
    private Integer remain;
    private LocalDateTime expireAt;
    private PointsReasonType reasonType;
    /** 来源业务 id：LOTTERY_WIN = 抽奖记录 id；REBATE = 发放幂等键 */
    private Long refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
