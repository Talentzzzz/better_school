package com.betterschool.domain.raffle.model.valobj;

import com.betterschool.types.raffle.Constants;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 规则链节点码：与 draw_strategy.rule 的逗号分段值、链节点 bean 名（{@code @Service("rule_xxx")}）
 * 三处一致（唯一码表 = {@code types.raffle.Constants}）；同时作为 {@code ChainAwardVO.logicModel}
 * 标记本抽命中的决策路径。
 */
@AllArgsConstructor
@Getter
public enum RaffleRuleNodeVO {

    RULE_BLACKLIST(Constants.RULE_NODE_BLACKLIST),
    RULE_WEIGHT(Constants.RULE_NODE_WEIGHT),
    RULE_DEFAULT(Constants.RULE_NODE_DEFAULT),
    ;

    /** 节点码（rule 列分段值 / 节点 bean 名） */
    private final String code;

    public static RaffleRuleNodeVO fromCode(String code) {
        return Arrays.stream(values()).filter(node -> node.code.equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知规则链节点码: " + code));
    }
}
