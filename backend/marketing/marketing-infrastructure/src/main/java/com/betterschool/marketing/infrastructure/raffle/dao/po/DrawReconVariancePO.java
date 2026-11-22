package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 对账差错行（draw_recon_variance；对账轮纯旁路只写本表，登记 INSERT IGNORE 撞 uk 静默跳过） */
@Data
public class DrawReconVariancePO {

    private Long id;
    private String roundNo;
    private String checkType;
    /** 定位键：award_id（守恒类）/ record_id（配对类与点名） */
    private String scopeKey;
    /** 业务单号（幂等键末位） */
    private String bizId;
    private String expectSnapshot;
    private String actualSnapshot;
    private Integer state;
    private LocalDateTime fixedTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
