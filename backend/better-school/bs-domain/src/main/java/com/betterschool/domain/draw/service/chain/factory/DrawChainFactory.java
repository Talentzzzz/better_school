package com.betterschool.domain.draw.service.chain.factory;

import com.betterschool.domain.draw.adapter.repository.IDrawRepository;
import com.betterschool.domain.draw.model.entity.DrawStrategyEntity;
import com.betterschool.domain.draw.model.valobj.DrawRuleNodeEnum;
import com.betterschool.domain.draw.service.chain.IChainNode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 规则链工厂：按 draw_strategy.rule 的 DB 配置动态组链（节点码序列 → 取对应 prototype bean 串成链表，
 * 链尾恒补默认兜底节点；没配 rule 就只装默认节点）。组好的链按 strategyId 缓存，配置变更后由
 * 预热服务（票 11）/主链路（票 13）调 {@link #evict} 失效重组。
 */
@Service
public class DrawChainFactory {

    private final ApplicationContext applicationContext;
    private final IDrawRepository drawRepository;
    private final Map<String, IChainNode> chainMap = new ConcurrentHashMap<>();

    public DrawChainFactory(ApplicationContext applicationContext, IDrawRepository drawRepository) {
        this.applicationContext = applicationContext;
        this.drawRepository = drawRepository;
    }

    /** 取策略链头（首次按 DB 配置组链，之后走缓存） */
    public IChainNode getFirstChainNode(String strategyId) {
        IChainNode headNode = chainMap.get(strategyId);
        if (headNode != null) {
            return headNode;
        }
        DrawStrategyEntity drawStrategyEntity = drawRepository.queryStrategyEntityByStrategyId(strategyId);
        if (drawStrategyEntity == null) {
            throw new IllegalArgumentException("抽奖策略不存在 strategyId=" + strategyId);
        }
        headNode = buildChain(drawStrategyEntity);
        chainMap.put(strategyId, headNode);
        return headNode;
    }

    /** 配置变更后失效缓存（下次取链头按新配置重组） */
    public void evict(String strategyId) {
        chainMap.remove(strategyId);
    }

    private IChainNode buildChain(DrawStrategyEntity drawStrategyEntity) {
        var ruleModels = drawStrategyEntity.getRuleModels();
        if (ruleModels.isEmpty()) {
            return defaultNode();
        }
        IChainNode headNode = node(ruleModels.get(0));
        IChainNode currentNode = headNode;
        for (int i = 1; i < ruleModels.size(); i++) {
            currentNode = currentNode.appendNext(node(ruleModels.get(i)));
        }
        currentNode.appendNext(defaultNode());
        return headNode;
    }

    private IChainNode node(String ruleModel) {
        // 未知节点码直接炸出来：组链失败好过静默跳过一条规则
        DrawRuleNodeEnum.fromCode(ruleModel);
        return applicationContext.getBean(ruleModel, IChainNode.class);
    }

    private IChainNode defaultNode() {
        return applicationContext.getBean(DrawRuleNodeEnum.RULE_DEFAULT.getCode(), IChainNode.class);
    }

    /**
     * 单抽决策结果：awardId 是命中的奖品位；logicModel 标记决策路径（黑名单拦截 / 权重保底 / 默认兜底）；
     * weightCount 仅权重路径有值 = 命中的权重档阈值（对账与观测用）。
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChainAwardVO {

        private String awardId;
        private DrawRuleNodeEnum logicModel;
        private Integer weightCount;
    }
}
