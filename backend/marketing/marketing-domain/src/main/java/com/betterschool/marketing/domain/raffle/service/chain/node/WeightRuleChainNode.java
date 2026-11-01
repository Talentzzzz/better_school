package com.betterschool.marketing.domain.raffle.service.chain.node;

import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.entity.DrawStrategyWeightEntity;
import com.betterschool.marketing.domain.raffle.model.valobj.RaffleRuleNodeVO;
import com.betterschool.marketing.domain.raffle.service.chain.AbstractChainNode;
import com.betterschool.marketing.domain.raffle.service.chain.factory.RaffleChainFactory;
import com.betterschool.marketing.types.raffle.Constants;
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

    private final IRaffleRepository raffleRepository;

    public WeightRuleChainNode(IRaffleRepository raffleRepository) {
        this.raffleRepository = raffleRepository;
    }

    @Override
    public RaffleChainFactory.ChainAwardVO execute(String userId, Long strategyId, Long activityId) {
        int userDrawCount = raffleRepository.queryUserDrawCount(strategyId, userId);
        DrawStrategyWeightEntity matched = matchWeight(userDrawCount,
                raffleRepository.queryWeightListByStrategyId(strategyId));
        if (matched == null) {
            return next().execute(userId, strategyId, activityId);
        }
        Map<Integer, Long> weightTable = raffleRepository
                .getRaffleTable(Constants.weightTableKey(strategyId, matched.getCount()));
        if (weightTable.isEmpty()) {
            // 该档概率表缺失（未预热/预热被清）：降级走默认节点，DB 直算入口归票 13 接
            log.warn("权重档概率表缺失，降级默认节点 strategyId={} userId={} count={}",
                    strategyId, userId, matched.getCount());
            return next().execute(userId, strategyId, activityId);
        }
        Long awardId = weightTable.get(ThreadLocalRandom.current().nextInt(0, weightTable.size()));
        log.info("规则链权重保底命中 userId={} strategyId={} count={} awardId={}",
                userId, strategyId, matched.getCount(), awardId);
        return new RaffleChainFactory.ChainAwardVO(awardId, RaffleRuleNodeVO.RULE_WEIGHT, matched.getCount());
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
