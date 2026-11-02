package com.betterschool.marketing.domain.raffle.model.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 抽奖策略（draw_strategy）：责任链的装配配置——{@code ruleModels} 是 rule 列按逗号切开的
 * 节点码序列（如 rule_blacklist,rule_weight），ChainFactory 按此顺序组链、末尾恒补默认兜底节点。
 */
@Data
public class DrawStrategyEntity {

    private Long id;
    /** 业务 ID（对外唯一标识） */
    private String strategyId;
    private String strategyDesc;
    /** 规则链节点码序列（rule 列逗号分隔，可能为空 = 只装默认兜底节点） */
    private List<String> ruleModels;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
