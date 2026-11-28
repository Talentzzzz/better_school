package com.betterschool.domain.draw.model.valobj;

import com.betterschool.domain.draw.model.entity.DrawStrategyAwardEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次抽奖的「决策完成、库存未预扣」中间态。
 * <p>
 * 抽奖次数属于本域，扣次与责任链出奖都在 domain 完成；库存属于奖品域，预扣由 case 层
 * 在 {@link #getSelectedAward()} / {@link #getFallbackAward()} 之间裁决后执行，最后回到
 * domain 的 {@code finishDraw} 落库。重放命中时 {@link #getReplayResult()} 非空，case 直接返回。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DrawAttemptVO {

    private String recordId;
    private String userId;
    private String activityId;
    private String strategyId;
    /** 责任链选中的奖品位（库存预扣前） */
    private DrawStrategyAwardEntity selectedAward;
    /** 兜底奖品位（无兜底配置时为 null） */
    private DrawStrategyAwardEntity fallbackAward;
    /** 幂等重放：已落库记录的结果；非空时 case 不再做库存预扣 */
    private DrawResultVO replayResult;
}
