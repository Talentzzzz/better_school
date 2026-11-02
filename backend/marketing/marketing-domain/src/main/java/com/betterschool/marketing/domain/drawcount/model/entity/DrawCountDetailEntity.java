package com.betterschool.marketing.domain.drawcount.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽奖次数消费明细（draw_count_detail）：一次抽奖扣 1 次，按 FIFO 跨几个桶就落几行。
 * **没有 amount 列**——每行恒代表从该桶扣掉 1 次；幂等键 = 抽奖记录 id（ref_id）。
 */
@Data
public class DrawCountDetailEntity {

    private Long id;
    private String userId;
    /** 被扣的桶业务 ID（FIFO 顺序） */
    private String bucketId;
    /** 抽奖记录业务 id（draw_record.record_id，消费幂等键） */
    private String refId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
