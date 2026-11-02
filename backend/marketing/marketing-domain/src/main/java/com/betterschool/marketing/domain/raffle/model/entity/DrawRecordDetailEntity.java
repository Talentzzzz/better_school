package com.betterschool.marketing.domain.raffle.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽奖记录动作明细（draw_record_detail）：只插入。创建 draw_record 时同事务落 {@code DRAW}，
 * 此后 state 每推进一步落一条（uk_record_type 幂等，重试不重复落）。
 */
@Data
public class DrawRecordDetailEntity {

    private Long id;
    /** 冗余自 draw_record，按人查动作历史 */
    private String userId;
    /** 抽奖记录业务 ID（draw_record.record_id） */
    private String recordId;
    /** 动作类型（{@link com.betterschool.marketing.domain.raffle.model.valobj.DrawRecordActionVO}） */
    private String type;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
