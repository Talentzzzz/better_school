package com.betterschool.infrastructure.dao.raffle.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 对账轮次行（draw_recon_round；纯观测用，起轮 INSERT IGNORE、终轮计数回填） */
@Data
public class DrawReconRoundPO {

    private Long id;
    private String roundNo;
    private LocalDateTime scanStartTime;
    private LocalDateTime scanEndTime;
    private Integer varianceTotal;
    private Integer confirmedTotal;
    private Integer autoFixedTotal;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
