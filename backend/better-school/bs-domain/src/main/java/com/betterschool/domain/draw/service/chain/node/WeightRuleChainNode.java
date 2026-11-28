package com.betterschool.domain.draw.service.chain.node;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.domain.draw.model.entity.DrawStrategyWeightEntity;
import com.betterschool.domain.draw.model.valobj.DrawRuleNodeEnum;
import com.betterschool.domain.draw.service.chain.AbstractChainNode;
import com.betterschool.domain.draw.service.chain.factory.DrawChainFactory;
import com.betterschool.types.draw.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 权重保底节点：用户在该策略下的累计抽数（draw_record 行数）达到某权重档阈值
 * （draw_strategy_weight.count）时，从该档保底奖品池的概率表（预热按 award_rate 归一化建成，
 * key 带 count）出奖并短路；未达任何档、或该档概率表未预热（降级态）则透传默认节点。
 */
@Slf4j
@Service(Constants.RULE_NODE_WEIGHT)
@Scope("prototype")
public class WeightRuleChainNode extends AbstractChainNode {

    private final IDrawRepository drawRepository;

    public WeightRuleChainNode(IDrawRepository drawRepository) {
        this.drawRepository = drawRepository;
    }

    @Override
    public DrawChainFactory.ChainAwardVO execute(String userId, String strategyId, String activityId) {
        int userDrawCount = drawRepository.queryUserDrawCount(strategyId, userId);
        DrawStrategyWeightEntity drawStrategyWeightEntity = matchWeight(userDrawCount,
                drawRepository.queryWeightListByStrategyId(strategyId));
        if (drawStrategyWeightEntity == null) {
            return next().execute(userId, strategyId, activityId);
        }
        Map<Integer, String> weightTable = drawRepository
                .getDrawTable(Constants.weightTableKey(strategyId, drawStrategyWeightEntity.getCount()));
        if (weightTable.isEmpty()) {
            // 该档概率表缺失（未预热/预热被清）：降级走默认节点，DB 直算入口归票 13 接
            log.warn("权重档概率表缺失，降级默认节点 strategyId={} userId={} count={}",
                    strategyId, userId, drawStrategyWeightEntity.getCount());
            return next().execute(userId, strategyId, activityId);
        }
        String awardId = weightTable.get(ThreadLocalRandom.current().nextInt(0, weightTable.size()));
        log.info("规则链权重保底命中 userId={} strategyId={} count={} awardId={}",
                userId, strategyId, drawStrategyWeightEntity.getCount(), awardId);
        return new DrawChainFactory.ChainAwardVO(awardId, DrawRuleNodeEnum.RULE_WEIGHT, drawStrategyWeightEntity.getCount());
    }

    /** 满足「count ≤ 累计抽数」的档里取 count 最大的一档（权重表已按 count 升序） */
    private DrawStrategyWeightEntity matchWeight(int userDrawCount, List<DrawStrategyWeightEntity> weights) {
        DrawStrategyWeightEntity matched = null;
        for (DrawStrategyWeightEntity weight : weights) {
            if (weight.getCount() <= userDrawCount) {
                matched = weight;
            } else {
                break;
            }
        }
        return matched;
    }
}
