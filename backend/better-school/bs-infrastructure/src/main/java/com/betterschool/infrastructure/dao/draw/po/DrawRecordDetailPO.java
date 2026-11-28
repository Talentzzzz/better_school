package com.betterschool.infrastructure.dao.draw.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 抽奖记录动作明细行（draw_record_detail；只插入，uk_record_type 幂等） */
@Data
public class DrawRecordDetailPO {

    private Long id;
    private String userId;
    private String recordId;
    private String type;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
