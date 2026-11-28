package com.betterschool.domain.draw.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账轮次（draw_recon_round）：每轮起止与计数的纯观测行，最小列。
 * 起轮 INSERT IGNORE（uk_round_no 幂等，调度重复触发跳过），终轮回填结束时间与三项计数。
 */
@Data
public class DrawReconRoundEntity {

    private Long id;
    /** 轮次号（本轮扫描开始时刻文本） */
    private String roundNo;
    private LocalDateTime scanStartTime;
    /** 扫描结束时间（终轮回填，进行中为 null） */
    private LocalDateTime scanEndTime;
    /** 本轮登记差错数（终轮回填） */
    private Integer varianceTotal;
    /** 本轮确认差异数（终轮回填） */
    private Integer confirmedTotal;
    /** 本轮自动修复数（终轮回填） */
    private Integer autoFixedTotal;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
