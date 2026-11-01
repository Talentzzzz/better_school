package com.betterschool.marketing.domain.raffle.model.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖策略权重档（draw_strategy_weight）：按累计抽数分档的保底奖品池。
 * <p>
 * {@code count} 是阈值（用户在该策略下累计抽数 = draw_record 中该 user_id + strategy_id 的行数），
 * 命中规则 = 满足「count ≤ 累计抽数」的各档中 count 最大的一档；{@code weights} 是该档保底奖品池
 * （award_id 列表），池内按 draw_strategy_award.award_rate 归一化概率出奖（预热建权重磅时定死）。
 */
@Data
public class DrawStrategyWeightEntity {

    private Long id;
    private Long strategyId;
    /** 该档生效的抽数阈值 */
    private Integer count;
    /** 该档保底奖品池（weights 列逗号分隔的 award_id） */
    private List<Long> weights;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
