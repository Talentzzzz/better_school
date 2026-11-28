package com.betterschool.domain.draw.service.chain.node;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.domain.draw.model.valobj.DrawRuleNodeEnum;
import com.betterschool.domain.draw.service.chain.AbstractChainNode;
import com.betterschool.domain.draw.service.chain.factory.DrawChainFactory;
import com.betterschool.types.common.AppException;
import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.draw.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 默认兜底节点（恒为链尾）：从默认概率表（预热按 draw_strategy_award(type=normal).award_rate
 * ×10000 建成，恒 10000 槽）随机出奖。概率表缺失 = 未预热（降级态），抛
 * {@link ResponseCodeEnum#DRAW_WARMUP_MISSING}——DB 直算的降级入口归票 13 接。
 */
@Slf4j
@Service(Constants.RULE_NODE_DEFAULT)
@Scope("prototype")
public class DefaultChainNode extends AbstractChainNode {

    private final IDrawRepository drawRepository;

    public DefaultChainNode(IDrawRepository drawRepository) {
        this.drawRepository = drawRepository;
    }

    @Override
    public DrawChainFactory.ChainAwardVO execute(String userId, String strategyId, String activityId) {
        Map<Integer, String> drawTable = drawRepository
                .getDrawTable(Constants.defaultTableKey(strategyId));
        if (drawTable.isEmpty()) {
            log.warn("默认概率表未预热 strategyId={} userId={}", strategyId, userId);
            throw new AppException(ResponseCodeEnum.DRAW_WARMUP_MISSING.getCode(),
                    ResponseCodeEnum.DRAW_WARMUP_MISSING.getInfo());
        }
        String awardId = drawTable.get(ThreadLocalRandom.current().nextInt(0, drawTable.size()));
        log.info("规则链默认兜底出奖 userId={} strategyId={} awardId={}", userId, strategyId, awardId);
        return new DrawChainFactory.ChainAwardVO(awardId, DrawRuleNodeEnum.RULE_DEFAULT, null);
    }
}
