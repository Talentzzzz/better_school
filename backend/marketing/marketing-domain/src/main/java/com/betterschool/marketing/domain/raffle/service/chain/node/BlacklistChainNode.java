package com.betterschool.marketing.domain.raffle.service.chain.node;

import com.betterschool.marketing.domain.raffle.adapter.repository.IRaffleRepository;
import com.betterschool.marketing.domain.raffle.model.valobj.RaffleRuleNodeVO;
import com.betterschool.marketing.domain.raffle.service.chain.AbstractChainNode;
import com.betterschool.marketing.domain.raffle.service.chain.factory.RaffleChainFactory;
import com.betterschool.marketing.types.raffle.Constants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

/**
 * 黑名单拦截节点：命中（风控侧写入的 Redis 黑名单 bucket 存在）即短路，强制出
 * draw_strategy_award(type=blacklist) 的奖品位（拒绝/降级语义由奖品档本身表达）；
 * 配了黑名单用户但没有 blacklist 奖品位时放行下一节点（配置不完整不能把人卡死）。
 */
@Slf4j
@Service(Constants.RULE_NODE_BLACKLIST)
@Scope("prototype")
public class BlacklistChainNode extends AbstractChainNode {

    private final IRaffleRepository raffleRepository;

    public BlacklistChainNode(IRaffleRepository raffleRepository) {
        this.raffleRepository = raffleRepository;
    }

    @Override
    public RaffleChainFactory.ChainAwardVO execute(String userId, Long strategyId, Long activityId) {
        if (raffleRepository.isUserBlacklisted(strategyId, userId)) {
            Long awardId = raffleRepository.queryAwardIdBySlot(strategyId, IRaffleRepository.SLOT_BLACKLIST);
            if (awardId != null) {
                log.info("规则链黑名单命中 userId={} strategyId={} 强制出黑名单奖品位 awardId={}",
                        userId, strategyId, awardId);
                return new RaffleChainFactory.ChainAwardVO(awardId, RaffleRuleNodeVO.RULE_BLACKLIST, null);
            }
            log.warn("规则链黑名单命中但策略未配 blacklist 奖品位，放行下一节点 strategyId={} userId={}",
                    strategyId, userId);
        }
        return next().execute(userId, strategyId, activityId);
    }
}
