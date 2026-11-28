package com.betterschool.domain.draw.model.entity;

import com.betterschool.types.draw.DrawCountReasonTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽奖次数桶（draw_count_bucket）：与积分桶同构——每笔入账一行，带自己的过期时间，
 * 同时是「发放流水」与「事实源」，没有余额列。
 * <p>
 * {@code bucketId} 是业务标识（统一 IdGenerator 发号，uuid-biz-id 票 07），消费明细挂它，
 * 不挂物理自增 id。积分购买入账（REDEEM）与 points_detail 的 REDEEM_DRAW_COUNT 共用同一个购买单号。
 * <p>
 * 本实体归**抽奖域**（抽奖次数是抽奖域内的资产，与 draw 同域），不再有独立的次数域。
 */
@Data
public class DrawCountBucketEntity {

    private Long id;
    /** 业务 ID（对外唯一标识） */
    private String bucketId;
    private String userId;
    /** 入账次数（恒正） */
    private Integer amount;
    /** 该桶剩余可用（消费按 FIFO 递减；过期桶不计入读数，由 expire_at 懒判） */
    private Integer remain;
    private LocalDateTime expireAt;
    private DrawCountReasonTypeEnum reasonType;
    /** 来源业务 id：REDEEM = 积分购买单 id；REBATE = 发放幂等键 */
    private String refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
