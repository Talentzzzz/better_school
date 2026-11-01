package com.betterschool.marketing.domain.raffle.service.chain.node;

import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.valobj.RaffleRuleNodeVO;
import com.betterschool.marketing.domain.raffle.service.chain.AbstractChainNode;
import com.betterschool.marketing.domain.raffle.service.chain.factory.RaffleChainFactory;
import com.betterschool.marketing.types.common.AppException;
import com.betterschool.marketing.types.common.ResponseCode;
import com.betterschool.marketing.types.raffle.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 默认兜底节点（恒为链尾）：从默认概率表（预热按 draw_strategy_award(type=normal).award_rate
 * ×10000 建成，恒 10000 槽）随机出奖。概率表缺失 = 未预热（降级态），抛
 * {@link ResponseCode#RAFFLE_WARMUP_MISSING}——DB 直算的降级入口归票 13 接。
 */
@Slf4j
@Service(Constants.RULE_NODE_DEFAULT)
@Scope("prototype")
public class DefaultChainNode extends AbstractChainNode {

    private final IRaffleRepository raffleRepository;

    public DefaultChainNode(IRaffleRepository raffleRepository) {
        this.raffleRepository = raffleRepository;
    }

    @Override
    public RaffleChainFactory.ChainAwardVO execute(String userId, Long strategyId, Long activityId) {
        Map<Integer, Long> raffleTable = raffleRepository
                .getRaffleTable(Constants.defaultTableKey(strategyId));
        if (raffleTable.isEmpty()) {
            log.warn("默认概率表未预热 strategyId={} userId={}", strategyId, userId);
            throw new AppException(ResponseCode.RAFFLE_WARMUP_MISSING.getCode(),
                    ResponseCode.RAFFLE_WARMUP_MISSING.getInfo());
        }
        Long awardId = raffleTable.get(ThreadLocalRandom.current().nextInt(0, raffleTable.size()));
        log.info("规则链默认兜底出奖 userId={} strategyId={} awardId={}", userId, strategyId, awardId);
        return new RaffleChainFactory.ChainAwardVO(awardId, RaffleRuleNodeVO.RULE_DEFAULT, null);
    }
}
