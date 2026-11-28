package com.betterschool.domain.draw.service.chain;

import com.betterschool.domain.draw.service.chain.factory.DrawChainFactory;

/**
 * 抽奖规则链节点：按 draw_strategy.rule 的节点码序列动态组链（黑名单 → 权重保底 → 默认兜底），
 * 节点可插拔——命中即返回（短路），未命中传给 {@code next()}，默认节点恒为链尾。
 * 节点是 prototype bean（每个策略一份链实例，节点间无共享状态），bean 名 = 节点码。
 */
public interface IChainNode {

    /**
     * 本节点决策：命中返回本抽结果，未命中透传给下一节点。
     *
     * @param userId     抽奖用户
     * @param strategyId 抽奖策略业务 ID
     * @param activityId 抽奖活动业务 ID（节点目前不读活动配置，入参为票 13 主链路留位）
     */
    DrawChainFactory.ChainAwardVO execute(String userId, String strategyId, String activityId);

    IChainNode next();

    IChainNode appendNext(IChainNode nextNode);
}
