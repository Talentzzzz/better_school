package com.betterschool.marketing.domain.drawcount.model.entity;

import com.betterschool.marketing.types.drawcount.DrawCountReasonType;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽奖次数桶（draw_count_bucket）：与积分桶同构——每笔入账一行，带自己的过期时间，
 * 同时是「发放流水」与「事实源」，没有余额列。
 * <p>
 * * {@code bucketId} 是业务标识（统一 IdGenerator 发号，uuid-biz-id 票 07），
 * 消费明细挂它，不挂物理自增 id。REDEEM 入账与 points_detail 的 REDEEM_DRAW_COUNT 同值同事务写入（票 13 接）。
 */
@Data
public class DrawCountBucketEntity {

    private Long id;
    /** 业务 ID（对外唯一标识） */
    private String bucketId;
    private String userId;
    /** 入账次数（恒正） */
    private Integer amount;
    /** 该桶剩余可用（消费按 FIFO 递减；过期清理置 0） */
    private Integer remain;
    private LocalDateTime expireAt;
    private DrawCountReasonType reasonType;
    /** 来源业务 id：REDEEM = 兑换单 id；REBATE = 发放幂等键 */
    private String refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
