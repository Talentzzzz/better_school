package com.betterschool.domain.raffle.model.entity;

import com.betterschool.types.raffle.DrawReconCheckType;
import com.betterschool.types.raffle.DrawReconVarianceState;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对账差错（draw_recon_variance）：每日全量对账轮发现的不一致登记。
 * 幂等键 =（轮次号, 检查类型, 业务单号），登记 INSERT IGNORE；
 * 状态机 PENDING → CONFIRMED → AUTO_FIXED / RESOLVED_MANUAL / FALSE_ALARM（二次核对语义见枚举 javadoc）。
 */
@Data
public class DrawReconVarianceEntity {

    private Long id;
    /** 对账轮次号（= draw_recon_round.round_no） */
    private String roundNo;
    /** 检查类型（四条不变量 + 死信点名） */
    private DrawReconCheckType checkType;
    /** 定位键：award_id（守恒类）/ record_id（配对类与点名） */
    private String scopeKey;
    /** 业务单号（幂等键末位） */
    private String bizId;
    /** 期望值快照 */
    private String expectSnapshot;
    /** 实际值快照 */
    private String actualSnapshot;
    private DrawReconVarianceState state;
    /** 自动修复时间（置 AUTO_FIXED 时写） */
    private LocalDateTime fixedTime;
    /** 人工处理/复查完成时间 */
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
